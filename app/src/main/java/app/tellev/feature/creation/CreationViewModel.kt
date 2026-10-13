package app.tellev.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.Attachment
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.provider.ImageProviderProfile
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.codec.WorldBookCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

data class CreationUiState(
    val sessions: List<CreationSessionSummary> = emptyList(),
    val current: CreationSession? = null,
    val coverPreviewPng: ByteArray? = null,
    val busy: Boolean = false,
    val isOptimizingPrompt: Boolean = false,
    val extractionProgress: String = "",
    val operationLabel: String = "",
    val modelPhase: String = "",
    val liveReasoning: String = "",
    val liveOutput: String = "",
    val liveAssistantMessage: String = "",
    val operationStartedAtMillis: Long = 0,
    val modelElapsedMillis: Long = 0,
    val firstDeltaMillis: Long? = null,
    val lastDeltaMillis: Long? = null,
    val deltaCount: Int = 0,
    val providerLabel: String = "",
    val toolEvents: List<CreationToolEvent> = emptyList(),
    val pendingQuestion: CreationAgentQuestion? = null,
    /** Next-step suggestions from the last agent reply; tapping fills the input, never sends. */
    val suggestions: List<String> = emptyList(),
    /** Configured image engines (`imgprof:` ids included) for AI cover generation. */
    val imageEngines: Set<String> = emptySet(),
    val imageProfiles: List<ImageProviderProfile> = emptyList(),
    val isGeneratingCover: Boolean = false,
    /** Shared generation records (chat + creation) for the 今日/累计 sections. */
    val recentMetrics: List<app.tellev.core.metrics.GenerationMetrics> = emptyList(),
    val error: String? = null,
    val info: String? = null,
)

enum class CharacterExportFormat { Json, Png }

/** Serialize a world book to SillyTavern-compatible JSON, verifying it reads back. */
internal fun worldBookExportBytes(book: WorldBook): ByteArray {
    val serialized = WorldBookCodec.serializeWorldBook(book)
    require(WorldBookCodec.parseWorldBookEntries(serialized).size == book.entries.size) {
        UiStrings.get(S.crvm_worldbook_export_roundtrip_failed)
    }
    return FileStDataStore.defaultJson
        .encodeToString(JsonObject.serializer(), serialized)
        .toByteArray(Charsets.UTF_8)
}

class CreationViewModel(
    private val repository: CreationRepository,
    private val store: StDataStore,
    private val secrets: SecretStore,
    private val providers: ProviderRegistry,
    /** Size-capped remote download for engines that return URLs (OpenAI Images). */
    private val imageDownloader: (suspend (String) -> ByteArray?)? = null,
    /**
     * Shared with ChatViewModel: one writer instance for the metrics files, so
     * creation records cannot clobber chat history (and the chat flow sees
     * creation records immediately).
     */
    private val metricsStore: app.tellev.core.metrics.GenerationMetricsStore? = null,
) : ViewModel() {
    private val engine = CreationEngine(secrets, providers, store)
    private val coverGenerator = CreationCoverGenerator(secrets, providers, imageDownloader)
    private val promptOptimizer = app.tellev.core.prompt.PromptOptimizer()
    private var promptOptimizationJob: Job? = null
    @Volatile private var promptOptimizationToken = 0L
    private var coverJob: Job? = null
    private val writeMutex = Mutex()
    private val _state = MutableStateFlow(CreationUiState())
    val state: StateFlow<CreationUiState> = _state.asStateFlow()
    private var generationJob: Job? = null
    /** Answer channel for a pending ask_user; completed by [answerAgentQuestion]. */
    private var askAnswer: CompletableDeferred<String>? = null

    init { refresh() }

    init {
        // 今日/累计分区复用共享明细缓冲；只读订阅，写入仍由唯一 writer 负责。
        metricsStore?.let { store ->
            viewModelScope.launch {
                store.load()
                store.entries.collect { entries -> _state.update { it.copy(recentMetrics = entries) } }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { repository.list() }
            .onSuccess { sessions -> _state.update { it.copy(sessions = sessions) } }
            .onFailure { fail(it) }
    }

    fun deleteDraft(id: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                writeMutex.withLock { repository.delete(id) }
                _state.update { state -> state.copy(
                    sessions = state.sessions.filterNot { it.id == id },
                    current = state.current?.takeUnless { it.id == id },
                    coverPreviewPng = if (state.current?.id == id) null else state.coverPreviewPng,
                    info = UiStrings.get(S.crvm_draft_deleted),
                ) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun start(kind: CreationKind) {
        if (_state.value.busy) return
        val session = CreationSession(kind = kind)
        _state.update { it.copy(current = session, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        persist(session)
    }

    fun open(id: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(current = null, coverPreviewPng = null, error = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        runCatching { repository.load(id).withAssignedLoreIds() }
            .onSuccess { session ->
                val coverResult = runCatching {
                    session.coverSha256.takeIf(String::isNotBlank)?.let { repository.readCover(id, it) }
                }
                _state.update { it.copy(
                    current = session,
                    coverPreviewPng = coverResult.getOrNull(),
                    error = coverResult.exceptionOrNull()?.message,
                ) }
            }
            .onFailure { fail(it) }
    }

    /** Load a stored character card into a new creation session for AI editing. */
    fun startFromCharacter(cardId: String) = viewModelScope.launch {
        if (_state.value.busy || cardId.isBlank()) return@launch
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_read_card), modelPhase = UiStrings.get(S.crvm_phase_reading_card), liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        try {
            val session = CreationSession.fromCharacter(store.readCharacter(cardId))
            _state.update { it.copy(current = session, modelPhase = UiStrings.get(S.crvm_phase_card_loaded)) }
            persist(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Make a separate world book from a stored card and its embedded entries. */
    fun startWorldBookFromCharacter(cardId: String) = viewModelScope.launch {
        if (_state.value.busy || cardId.isBlank()) return@launch
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_read_card), modelPhase = UiStrings.get(S.crvm_phase_reading_card), liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        try {
            val session = CreationSession.worldBookFromCharacter(store.readCharacter(cardId))
            _state.update { it.copy(current = session, modelPhase = UiStrings.get(S.crvm_phase_source_card_loaded)) }
            persist(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Load a stored world book into a new creation session for AI editing. */
    fun startFromWorldBook(bookId: String) = viewModelScope.launch {
        if (_state.value.busy || bookId.isBlank()) return@launch
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_read_worldbook), modelPhase = UiStrings.get(S.crvm_phase_reading_worldbook), liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        try {
            val session = CreationSession.fromWorldBook(store.readWorldBook(bookId))
            _state.update { it.copy(current = session, modelPhase = UiStrings.get(S.crvm_phase_worldbook_loaded)) }
            persist(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun close() { if (!_state.value.busy) _state.update { it.copy(current = null, coverPreviewPng = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) } }

    fun send(text: String, attachments: List<Attachment> = emptyList()): Boolean {
        val state = _state.value
        val session = state.current ?: return false
        val trimmed = text.trim()
        if (state.isOptimizingPrompt || (trimmed.isBlank() && attachments.isEmpty())) return false
        if (state.pendingQuestion != null) {
            if (attachments.isNotEmpty()) {
                showError(UiStrings.get(S.crs_attachment_pending_question))
                return false
            }
            val accepted = askAnswer?.complete(trimmed) == true
            if (accepted) _state.update { it.copy(error = null, pendingQuestion = null) }
            return accepted
        }
        if (state.busy) return false
        val userTurn = CreationTurn("user", trimmed, attachments.toList())
        if (!validateSendAttachments(userTurn.attachments)) return false
        val withUser = session.copy(
            turns = session.turns + userTurn,
            updatedAt = System.currentTimeMillis(),
        )
        launchConversation(session, withUser, userTurn)
        return true
    }

    private fun validateSendAttachments(attachments: List<Attachment>): Boolean {
        return try {
            validateCreationAttachments(attachments, store.layout.root)
            true
        } catch (error: Exception) {
            fail(error)
            false
        }
    }

    private fun launchConversation(session: CreationSession, withUser: CreationSession, userTurn: CreationTurn) {
        _state.update { it.copy(
            current = withUser, busy = true, error = null, suggestions = emptyList(),
            extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_converse), modelPhase = UiStrings.get(S.crvm_phase_preparing), liveReasoning = "", liveOutput = "",
            liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(),
            modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            toolEvents = emptyList(), pendingQuestion = null,
        ) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                write(withUser)
                val sessionId = withUser.id
                val reply = engine.converse(
                    session, userTurn.text, ::showModelProgress,
                    attachments = userTurn.attachments,
                    snapshot = engine.resolveSessionSnapshot(session.presetId),
                    onUsage = { metric -> recordUsage(sessionId, metric) },
                    onCheckpoint = { working ->
                        val checkpoint = mergeUsage(working).copy(
                            turns = withUser.turns,
                            partialTurnSaved = true,
                            updatedAt = System.currentTimeMillis(),
                        )
                        // Finish the atomic checkpoint even if Stop arrives during disk I/O.
                        withContext(NonCancellable) { write(checkpoint) }
                        _state.update { it.copy(current = checkpoint) }
                    },
                    onToolEvent = { event ->
                        _state.update { it.copy(toolEvents = it.toolEvents + event) }
                    },
                    onAskUser = { question ->
                        val deferred = CompletableDeferred<String>()
                        askAnswer = deferred
                        _state.update { it.copy(pendingQuestion = question) }
                        try {
                            deferred.await()
                        } finally {
                            askAnswer = null
                            _state.update { it.copy(pendingQuestion = null) }
                        }
                    },
                )
                _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_verify_save)) }
                val next = mergeUsage(reply.session).copy(
                    turns = withUser.turns + CreationTurn(
                        "agent", reply.message.ifBlank { UiStrings.get(S.creng_turn_draft_updated) },
                    ),
                    partialTurnSaved = false,
                    updatedAt = System.currentTimeMillis(),
                )
                write(next)
                _state.update { it.copy(current = next, info = null, modelPhase = UiStrings.get(S.crvm_phase_turn_done), suggestions = reply.suggestions) }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    UiStrings.get(S.crvm_phase_stopped_saved) else UiStrings.get(S.crvm_phase_stopped_not_applied)) }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    UiStrings.get(S.crvm_phase_interrupted_saved) else UiStrings.get(S.crvm_phase_turn_failed)) }
            }
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob === job) {
                generationJob = null
                _state.update { it.copy(busy = false, pendingQuestion = null) }
            }
        }
        job.start()
    }

    fun retry() {
        val state = _state.value
        val session = state.current ?: return
        if (state.busy || state.isOptimizingPrompt || session.partialTurnSaved || session.turns.lastOrNull()?.role != "user") return
        val userTurn = session.turns.last()
        if (!validateSendAttachments(userTurn.attachments)) return
        // The persisted failed turn stays visible until replaced by its retry result.
        launchConversation(session.copy(turns = session.turns.dropLast(1)), session, userTurn)
    }

    fun setSource(name: String, text: String) {
        val session = _state.value.current ?: return
        if (_state.value.busy || text.isBlank()) return
        if (text.length > 4_000_000) {
            _state.update { it.copy(error = UiStrings.get(S.crvm_error_source_too_large)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_save_source), modelPhase = UiStrings.get(S.crvm_phase_saving_source), liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
            try {
                val (hash, length) = repository.saveSource(session.id, text)
                val next = session.copy(
                    sourceName = name.ifBlank { UiStrings.get(S.crs_paste_source_name) },
                    sourceSha256 = hash,
                    sourceCursor = 0,
                    sourceLength = length,
                    updatedAt = System.currentTimeMillis(),
                )
                write(next)
                _state.update { it.copy(current = next, info = UiStrings.get(S.crvm_source_saved), modelPhase = UiStrings.get(S.crvm_phase_source_saved)) }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_save_source_stopped)) }
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun extractSource() {
        val starting = _state.value.current ?: return
        if (_state.value.busy || _state.value.isOptimizingPrompt || starting.sourceLength == 0) return
        _state.update { it.copy(
            busy = true, error = null, operationLabel = UiStrings.get(S.crvm_op_extract),
            modelPhase = UiStrings.get(S.crvm_phase_reading_source), liveReasoning = "", liveOutput = "", liveAssistantMessage = "",
            operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0,
            firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            toolEvents = emptyList(), pendingQuestion = null,
        ) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = engine.resolveSessionSnapshot(starting.presetId)
                val source = repository.readSource(starting.id, starting.sourceSha256)
                require(source.length == starting.sourceLength) { UiStrings.get(S.crvm_error_source_length_mismatch) }
                var current = starting
                val chunkCount = countSourceChunks(source, starting.sourceCursor)
                var chunkNumber = chunkCount.completed
                while (true) {
                    val chunk = nextSourceChunk(source, current.sourceCursor) ?: break
                    chunkNumber++
                    _state.update { it.copy(
                        extractionProgress = UiStrings.get(S.crvm_extract_progress_chunk,
                            chunkNumber, chunkCount.total, chunk.start, chunk.end, source.length, current.sourceCursor),
                        modelPhase = UiStrings.get(S.crvm_phase_extract_chunk, chunkNumber),
                        liveReasoning = "", liveOutput = "", liveAssistantMessage = "",
                        modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0,
                    ) }
                    val reply = engine.extractChunk(
                        chunk, starting.sourceName, ::showModelProgress, snapshot = snapshot,
                        effortOverride = starting.reasoningEffort,
                        onUsage = { metric -> recordUsage(starting.id, metric) },
                    )
                    _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_verify_chunk, chunkNumber)) }
                    val proposed = reply.lore.orEmpty()
                    val verified = verifiedLoreFromChunk(chunk, proposed, starting.sourceName, starting.sourceSha256)
                    val rejected = proposed.size - verified.size
                    current = mergeUsage(current).copy(
                        lore = mergeExtractedLore(current.lore, verified),
                        worldName = current.worldName.ifBlank { reply.worldName.orEmpty() },
                        sourceCursor = chunk.end,
                        turns = current.turns + CreationTurn(
                            "agent",
                            UiStrings.get(S.creng_turn_extract_progress, chunk.end, source.length, verified.size, rejected),
                        ),
                        updatedAt = System.currentTimeMillis(),
                    )
                    write(current)
                    _state.update { it.copy(
                        current = current,
                        extractionProgress = UiStrings.get(S.crvm_extract_progress_saved,
                            current.sourceCursor, source.length, current.lore.size),
                        modelPhase = UiStrings.get(S.crvm_phase_chunk_saved, chunkNumber),
                    ) }
                }
                _state.update { it.copy(info = UiStrings.get(S.crvm_extract_done), modelPhase = UiStrings.get(S.crvm_phase_extract_done)) }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_extract_stopped)) }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_extract_interrupted)) }
            }
        }
        generationJob = job
        job.invokeOnCompletion {
            if (generationJob === job) {
                generationJob = null
                _state.update { it.copy(busy = false) }
            }
        }
        job.start()
    }

    fun cancelGeneration() { generationJob?.cancel() }

    /** Resolve a pending ask_user with the option the user tapped. */
    fun answerAgentQuestion(choice: String) {
        askAnswer?.complete(choice)
    }

    fun editCard(edit: (CharacterDraft) -> CharacterDraft) {
        if (_state.value.busy) return
        _state.update { state -> state.copy(
            current = state.current?.let { it.copy(card = edit(it.card), updatedAt = System.currentTimeMillis()) },
            info = null,
        ) }
        persist(_state.value.current)
    }

    fun setCoverPng(pngBytes: ByteArray) {
        val session = _state.value.current ?: return
        if (session.kind != CreationKind.Character || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                persistCover(session, pngBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Loads configured image engines for the AI cover dialog. */
    fun refreshCoverEngines() {
        viewModelScope.launch {
            runCatching {
                val engines = coverGenerator.configuredEngines()
                val profiles = coverGenerator.profiles()
                _state.update { it.copy(imageEngines = engines, imageProfiles = profiles) }
            }
        }
    }

    /**
     * AI-generated cover: generates one image through the selected engine and
     * stores it as the draft cover. Independent of the conversation busy state
     * (own job and flag), but the write itself goes through the same mutex.
     */
    fun generateCover(prompt: String, negativePrompt: String, engineId: String) {
        val session = _state.value.current ?: return
        if (session.kind != CreationKind.Character || _state.value.isGeneratingCover) return
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        coverJob = viewModelScope.launch {
            _state.update { it.copy(isGeneratingCover = true, error = null) }
            try {
                val bytes = coverGenerator.generatePng(engineId, trimmed, negativePrompt)
                _state.update { it.copy(info = UiStrings.get(S.crvm_cover_generated)) }
                persistCover(_state.value.current ?: return@launch, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(isGeneratingCover = false) }
            }
        }
    }

    fun cancelCoverGeneration() {
        coverJob?.cancel()
        coverJob = null
    }

    private suspend fun persistCover(session: CreationSession, pngBytes: ByteArray) {
        val hash = repository.saveCover(session.id, pngBytes)
        val next = session.copy(coverSha256 = hash, updatedAt = System.currentTimeMillis())
        write(next)
        repository.pruneCovers(session.id, hash)
        _state.update { it.copy(current = next, coverPreviewPng = pngBytes, info = UiStrings.get(S.crvm_cover_saved)) }
        refresh()
    }

    suspend fun exportCharacter(format: CharacterExportFormat): ByteArray = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: error(UiStrings.get(S.crvm_error_open_card_draft_first))
        require(session.kind == CreationKind.Character && !_state.value.busy) { UiStrings.get(S.crvm_error_cannot_export_card) }
        val card = checkedCharacterCard(session)
        val exporter = CharacterExporter()
        when (format) {
            CharacterExportFormat.Json -> exporter.exportToJson(card).toByteArray(Charsets.UTF_8)
            CharacterExportFormat.Png -> {
                require(session.coverSha256.isNotBlank()) { UiStrings.get(S.crvm_error_cover_required_for_png) }
                val cover = repository.readCover(session.id, session.coverSha256)
                exporter.exportToPng(card, cover).also { bytes ->
                    val imported = CharacterImporter().importFromBytes(bytes, "character.png")
                    require(imported.name == card.name && imported.firstMessage == card.firstMessage) {
                        UiStrings.get(S.crvm_error_png_export_roundtrip)
                    }
                }
            }
        }
    }

    /** Export the draft as a standalone SillyTavern world book JSON file. */
    suspend fun exportWorldBook(): ByteArray = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: error(UiStrings.get(S.crvm_error_open_worldbook_draft_first))
        // Character drafts expose the same world-book editor for their embedded
        // lore. Both draft kinds can export those entries as a standalone file.
        require(!_state.value.busy) { UiStrings.get(S.crvm_error_cannot_export_worldbook) }
        worldBookExportBytes(checkedWorldBook(session))
    }

    fun editWorldName(name: String) {
        if (_state.value.busy) return
        _state.update { it.copy(current = it.current?.copy(worldName = name, updatedAt = System.currentTimeMillis()), info = null) }
        persist(_state.value.current)
    }

    fun editLore(index: Int, entry: LoreDraft?) {
        if (_state.value.busy) return
        _state.update { state ->
            val session = state.current ?: return@update state
            val items = session.lore.toMutableList()
            if (entry == null) {
                if (index in items.indices) items.removeAt(index)
            } else if (index in items.indices) items[index] = entry
            else if (index == items.size) items.add(entry)
            // Entries added by hand arrive with a blank id (the "add entry" button).
            // Assign ids here too, not just on open/import: the model locates and
            // edits entries by id, and a blank id is treated as "new" by upsert_lore
            // while remove_lore(ids:[""]) would sweep every blank-id entry at once.
            val normalized = session.copy(
                lore = items,
                updatedAt = System.currentTimeMillis(),
            ).withAssignedLoreIds()
            state.copy(current = normalized, info = null)
        }
        persist(_state.value.current)
    }

    fun saveArtifact(onSaved: (CreationKind, String) -> Unit) {
        val session = _state.value.current ?: return
        if (_state.value.busy) return
        generationJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val prepared = if (session.savedArtifactId.isBlank()) session.copy(
                    savedArtifactId = "${if (session.kind == CreationKind.Character) "char" else "wb"}_${UUID.randomUUID()}",
                ) else session
                write(prepared)
                _state.update { it.copy(current = prepared) }
                val id = if (prepared.kind == CreationKind.Character) {
                    val card = checkedCharacterCard(prepared)
                    val json = CharacterExporter().exportToJson(card)
                    val imported = CharacterImporter().importFromJson(json)
                    require(imported.name == card.name && imported.firstMessage == card.firstMessage) {
                        UiStrings.get(S.crvm_error_card_export_roundtrip)
                    }
                    if (prepared.coverSha256.isNotBlank()) {
                        val cover = repository.readCover(prepared.id, prepared.coverSha256)
                        val png = CharacterExporter().exportToPng(card, cover)
                        val pngCard = CharacterImporter().importFromBytes(png, "character.png")
                        require(pngCard.name == card.name && pngCard.firstMessage == card.firstMessage) {
                            UiStrings.get(S.crvm_error_cover_card_roundtrip)
                        }
                        // The card and its generated lorebook are one artifact here,
                        // so the book is bound straight away instead of waiting for
                        // the user to switch it on in the world screen.
                        store.importCharacter(card, cover, "character.png", bindEmbeddedWorldBook = true)
                    } else store.saveCharacter(card)
                    card.id
                } else {
                    val book = checkedWorldBook(prepared)
                    store.saveWorldBook(book)
                    book.id
                }
                val next = prepared.copy(savedArtifactId = id, updatedAt = System.currentTimeMillis())
                write(next)
                _state.update { it.copy(current = next, info = UiStrings.get(S.crvm_saved_to_device)) }
                refresh()
                onSaved(session.kind, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun clearNotice() { _state.update { it.copy(error = null, info = null) } }

    /**
     * Draft optimization for the creation conversation input. Uses the same
     * selected provider as the creation agent but is side-effect-free: it
     * never writes drafts, turns or tool results. The caller keeps the
     * original text unless the user applies the result.
     */
    fun optimizeDraft(
        text: String,
        options: app.tellev.core.prompt.PromptOptimizationOptions,
        onPreview: (String) -> Unit,
        onDone: (app.tellev.core.prompt.PromptOptimizationResult) -> Unit,
    ): Boolean {
        if (_state.value.busy || text.isBlank()) return false
        val token = ++promptOptimizationToken
        promptOptimizationJob?.cancel()
        _state.update { it.copy(isOptimizingPrompt = true) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val activeJob = currentCoroutineContext()[Job]
            fun isCurrent() = promptOptimizationToken == token && activeJob?.isActive == true
            var result: app.tellev.core.prompt.PromptOptimizationResult
            try {
                val selectedId = secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: ProviderCatalog.OPENAI_COMPATIBLE
                val adapterId = ProviderConfigPersistence.adapterIdFor(selectedId)
                val adapter = providers.find(adapterId)?.takeIf { it.supportsChatGeneration }
                result = if (adapter == null) {
                    app.tellev.core.prompt.PromptOptimizationResult.failed(
                        listOf(UiStrings.get(S.chat_optimize_unavailable)),
                    )
                } else {
                    val config = ProviderConfigPersistence.loadProviderConfig(secrets, selectedId)
                    currentCoroutineContext().ensureActive()
                    promptOptimizer.optimize(text, options, config, adapter,
                        onPreview = { preview -> if (isCurrent()) onPreview(preview) })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result = app.tellev.core.prompt.PromptOptimizationResult.failed(
                    listOf(error.message ?: UiStrings.get(S.chat_optimize_failed_generic)),
                )
            }
            if (isCurrent()) onDone(result)
        }
        promptOptimizationJob = job
        job.invokeOnCompletion {
            if (promptOptimizationToken == token && promptOptimizationJob === job) {
                promptOptimizationJob = null
                _state.update { it.copy(isOptimizingPrompt = false) }
            }
        }
        job.start()
        return true
    }

    fun cancelPromptOptimization() {
        ++promptOptimizationToken
        val job = promptOptimizationJob
        promptOptimizationJob = null
        _state.update { it.copy(isOptimizingPrompt = false) }
        job?.cancel()
    }

    fun showError(message: String) { _state.update { it.copy(error = message) } }

    fun showInfo(message: String) { _state.update { it.copy(info = message, error = null) } }

    private fun checkedCharacterCard(session: CreationSession): CharacterCard {
        require(session.card.name.isNotBlank()) { UiStrings.get(S.crvm_error_name_required) }
        require(session.lore.all { it.content.isNotBlank() && (it.constant || it.keys.any(String::isNotBlank)) }) {
            UiStrings.get(S.crvm_error_lore_empty)
        }
        val issues = portableFrontendIssues(session.card.frontendHtml)
        require(issues.isEmpty()) { UiStrings.get(S.crvm_error_frontend_portable, issues.joinToString()) }
        return session.toCharacterCard()
    }

    private fun checkedWorldBook(session: CreationSession): WorldBook {
        require(session.worldName.isNotBlank() && session.lore.isNotEmpty()) {
            UiStrings.get(S.crvm_error_worldbook_required)
        }
        require(session.lore.all { it.content.isNotBlank() && (it.constant || it.keys.any(String::isNotBlank)) }) {
            UiStrings.get(S.crvm_error_lore_empty)
        }
        return session.toWorldBook()
    }

    private fun persist(session: CreationSession?) {
        if (session == null) return
        viewModelScope.launch {
            try {
                write(session)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun write(session: CreationSession) = writeMutex.withLock {
        repository.save(mergeUsage(session))
    }

    /**
     * Engine snapshots predate the usage accumulated during their own turn, so
     * assigning one verbatim to the state (or writing it to disk) would erase
     * freshly counted requests. The in-memory draft always holds the newest
     * aggregate for its id; merge it whenever it is further along. Callers that
     * put an engine snapshot into the state must run it through here first.
     */
    private fun mergeUsage(session: CreationSession): CreationSession {
        val live = _state.value.current?.takeIf { it.id == session.id } ?: return session
        if (live.usage.completedRequests <= session.usage.completedRequests) return session
        return session.copy(usage = live.usage)
    }

    private fun showModelProgress(progress: CreationStreamUpdate) {
        _state.update { it.copy(
            modelPhase = progress.phase,
            liveReasoning = progress.reasoning,
            liveOutput = progress.output,
            liveAssistantMessage = progress.assistantMessage,
            modelElapsedMillis = progress.elapsedMillis,
            firstDeltaMillis = progress.firstDeltaMillis,
            lastDeltaMillis = progress.lastDeltaMillis,
            deltaCount = progress.deltaCount,
            providerLabel = progress.providerLabel.ifBlank { it.providerLabel },
        ) }
    }

    /**
     * Per-draft usage accumulation. The engine calls this once per completed
     * provider request (before parsing/tool execution), so retries and repair
     * rounds are all counted. The draft copy is the source of truth and is
     * merged into every later save through [write].
     */
    private suspend fun recordUsage(sessionId: String, metric: app.tellev.core.metrics.GenerationMetrics) {
        val current = _state.value.current?.takeIf { it.id == sessionId } ?: return
        val attributed = metric.copy(sessionId = sessionId)
        val next = current.copy(
            usage = current.usage.plus(attributed),
            updatedAt = System.currentTimeMillis(),
        )
        _state.update { it.copy(current = next) }
        runCatching { metricsStore?.record(attributed) }
    }

    /**
     * The draft's own reasoning effort, applied to authoring requests through
     * the shared override metadata (same wire path as chat).
     */
    fun setReasoningEffort(effort: app.tellev.core.model.ReasoningEffort) {
        val session = _state.value.current ?: return
        if (_state.value.busy) return
        _state.update { it.copy(current = it.current?.copy(reasoningEffort = effort), info = null) }
        persist(_state.value.current)
    }

    /**
     * 本草案指定的预设 id（空 = 跟随聊天当前所选）。随草案持久化，
     * 与档位同一存储位；请求时经 resolveSessionSnapshot 落线。
     */
    fun setPreset(presetId: String) {
        val session = _state.value.current ?: return
        if (_state.value.busy) return
        _state.update { it.copy(current = it.current?.copy(presetId = presetId), info = null) }
        persist(_state.value.current)
    }

    /**
     * 统计面板的「清除统计」：清空共享明细与本草稿的累计。本草稿只重置聚合，
     * 不影响草稿内容本身；共享明细的清除会同时清掉聊天侧记录（同一份文件）。
     */
    fun clearUsage() {
        val session = _state.value.current ?: return
        viewModelScope.launch {
            runCatching { metricsStore?.clearAll() }
            _state.update { it.copy(current = it.current?.copy(usage = CreationUsage())) }
            runCatching { write(_state.value.current ?: session) }
        }
    }

    private fun fail(e: Throwable) {
        _state.update { it.copy(error = e.message ?: UiStrings.get(S.crvm_failed_default)) }
    }
}

class CreationViewModelFactory(
    private val root: File,
    private val store: StDataStore,
    private val secrets: SecretStore,
    private val providers: ProviderRegistry,
    private val imageDownloader: (suspend (String) -> ByteArray?)? = null,
    private val metricsStore: app.tellev.core.metrics.GenerationMetricsStore? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CreationViewModel(
        repository = CreationRepository(root),
        store = store,
        secrets = secrets,
        providers = providers,
        imageDownloader = imageDownloader,
        metricsStore = metricsStore,
    ) as T
}
