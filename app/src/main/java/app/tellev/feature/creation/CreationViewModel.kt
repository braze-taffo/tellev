package app.tellev.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterCastBinding
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.WorldBook
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.codec.WorldBookCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class CreationUiState(
    val sessions: List<CreationSessionSummary> = emptyList(),
    val current: CreationSession? = null,
    val coverPreviewPng: ByteArray? = null,
    val busy: Boolean = false,
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
    secrets: SecretStore,
    providers: ProviderRegistry,
) : ViewModel() {
    private val engine = CreationEngine(secrets, providers)
    private val writeMutex = Mutex()
    private val _state = MutableStateFlow(CreationUiState())
    val state: StateFlow<CreationUiState> = _state.asStateFlow()
    private var generationJob: Job? = null
    /** Answer channel for a pending ask_user; completed by [answerAgentQuestion]. */
    private var askAnswer: CompletableDeferred<String>? = null

    init { refresh() }

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
        val session = CreationSession(kind = kind, allowAgentLoreEdits = kind == CreationKind.WorldBook)
        _state.update { it.copy(current = session, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        persist(session)
    }

    fun open(id: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(current = null, coverPreviewPng = null, error = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "", toolEvents = emptyList(), pendingQuestion = null) }
        runCatching { writeMutex.withLock { repository.load(id).withAssignedLoreIds() } }
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

    /** Continue with a separate card/book draft while preserving the current draft and cover. */
    fun startRelatedDraft(target: CreationKind) = viewModelScope.launch {
        val source = _state.value.current ?: return@launch
        if (_state.value.busy || target == source.kind) return@launch
        _state.update { it.copy(busy = true, error = null) }
        try {
            if (target == CreationKind.Character) checkedWorldBook(source)
            else require(source.card.name.isNotBlank()) { UiStrings.get(S.crvm_error_name_required) }
            var next = source.relatedDraft(target)
            val cover = source.coverSha256.takeIf(String::isNotBlank)?.let {
                repository.readCover(source.id, it)
            }
            if (cover != null) next = next.copy(coverSha256 = repository.saveCover(next.id, cover))
            write(source)
            write(next)
            _state.update { CreationUiState(sessions = it.sessions, current = next,
                coverPreviewPng = cover.takeIf { target == CreationKind.Character }, busy = true) }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    suspend fun worldBookSources(): List<CreationWorldBookSource> = withContext(Dispatchers.IO) {
        val drafts = repository.list().filter { it.kind == CreationKind.WorldBook && it.id != _state.value.current?.id }.map {
            CreationWorldBookSource(it.id, it.worldName, it.loreCount, fromDraft = true)
        }
        val saved = store.listWorldBookSummaries().map {
            CreationWorldBookSource(it.id, it.name, it.entryCount, fromDraft = false)
        }
        val available = drafts + saved
        val current = _state.value.current
        val retained = current?.referenceBooks().orEmpty().mapIndexedNotNull { index, book ->
            val key = current?.referenceSourceIds?.getOrNull(index)
            val source = if (key != null) CreationWorldBookSource(key.substringAfter(':'), book.name, book.entries.size,
                fromDraft = key.substringBefore(':') == "true") else CreationWorldBookSource(book.id, book.name, book.entries.size, false)
            source.takeUnless { retained -> available.any { "${it.fromDraft}:${it.id}" == "${retained.fromDraft}:${retained.id}" ||
                (key == null && (it.id == book.id || it.name == book.name)) } }
        }
        available + retained
    }

    /** Associate a read-only book. Existing embedded entries stay in the card until an explicit merge. */
    fun associateWorldBook(source: CreationWorldBookSource) = associateWorldBooks(listOf(source))

    fun associateWorldBooks(sources: List<CreationWorldBookSource>) = viewModelScope.launch {
        val current = _state.value.current ?: return@launch
        if (_state.value.busy) return@launch
        _state.update { it.copy(busy = true, error = null) }
        try {
            val loaded = sources.distinctBy { "${it.fromDraft}:${it.id}" }.map { source ->
                val key = "${source.fromDraft}:${source.id}"
                val book = try { if (source.fromDraft) {
                // Wait for earlier editor writes before reading the chosen draft.
                val draft = writeMutex.withLock { repository.load(source.id) }
                require(draft.kind == CreationKind.WorldBook)
                checkedWorldBook(draft)
            } else store.readWorldBook(source.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val index = current.referenceSourceIds.indexOf(key)
                    current.referenceBooks().getOrNull(index) ?: current.referenceBooks().firstOrNull { it.id == source.id } ?: throw error
                }
                source to book
            }.distinctBy { it.second.id }
            val books = loaded.map { it.second }
            val next = current.copy(referenceBook = books.firstOrNull(), additionalReferenceBooks = books.drop(1),
                referenceSourceIds = loaded.map { "${it.first.fromDraft}:${it.first.id}" },
                allowAgentLoreEdits = current.kind == CreationKind.WorldBook,
                updatedAt = System.currentTimeMillis())
            write(next)
            _state.update { it.copy(current = next) }
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun startFactionWorldBook() = viewModelScope.launch {
        val source = _state.value.current ?: return@launch
        if (_state.value.busy || source.referenceBook == null) return@launch
        _state.update { it.copy(busy = true, error = null) }
        var created = false
        try {
            write(source)
            val next = source.factionDraft()
            write(next)
            _state.update { CreationUiState(sessions = it.sessions, current = next, busy = true) }
            refresh().join()
            created = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail(e) }
        finally { _state.update { it.copy(busy = false) } }
        if (created) send("【创作起点】依据已选主世界书的世界观，以及辅助参考世界书，创作独立、可复用的通用势力世界书。" +
            "先分页读取各参考书的设定，遵守主世界书的时代、力量体系、历史与地理；辅助书有冲突时先向用户提问。" +
            "写出有差异的主要势力：起源、目标、组织与权力结构、代表人物、资源与势力范围、对外关系、内部矛盾、行动方式、加入条件及剧情钩子。" +
            "各条目独立可理解，配置可触发的关键词，区分既有事实和新创作。避免依赖某一玩家、单一角色或开场剧情，不改写参考书。" +
            "信息不足先询问用户，完成后说明哪些设定来自参考书、哪些是新增设计。")
    }

    fun embedReferenceWorldBook() {
        val current = _state.value.current ?: return
        if (_state.value.busy || current.kind != CreationKind.Character || current.referenceBook == null) return
        val next = current.embedReferenceBooks()
        _state.update { it.copy(current = next) }
        persist(next)
    }

    fun setAgentLoreEdits(allowed: Boolean) {
        val current = _state.value.current ?: return
        if (_state.value.busy || current.kind != CreationKind.Character) return
        val next = current.copy(allowAgentLoreEdits = allowed, updatedAt = System.currentTimeMillis())
        _state.update { it.copy(current = next) }
        persist(next)
    }

    suspend fun castSources(): List<CharacterSummary> = store.listCharacters()

    fun associateCast(ids: List<String>) = viewModelScope.launch {
        val current = _state.value.current ?: return@launch
        if (_state.value.busy || current.kind != CreationKind.Character) return@launch
        _state.update { it.copy(busy = true, error = null) }
        try {
            val fallback = current.castMembers()
            val members = ids.distinct().filterNot { it == current.savedArtifactId }.map { id ->
                try { store.readCharacter(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { fallback.firstOrNull { it.id == id } ?: throw error }
            }
            val originalData = current.originalCard?.raw?.get("data") as? JsonObject ?: current.originalCard?.raw
            val extensions = current.advancedExtensions.takeIf { it.isNotEmpty() }
                ?: originalData?.get("extensions") as? JsonObject ?: JsonObject(emptyMap())
            val proxy = CharacterCard(current.savedArtifactId.ifBlank { current.id }, current.card.name,
                raw = buildJsonObject { put("data", buildJsonObject { put("extensions", extensions) }) })
            val raw = CharacterCastBinding.withMembers(proxy, members).raw["data"] as JsonObject
            val next = current.copy(advancedExtensions = raw["extensions"] as JsonObject, updatedAt = System.currentTimeMillis())
            write(next)
            _state.update { it.copy(current = next) }
            refresh().join()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { fail(error) }
        finally { _state.update { it.copy(busy = false) } }
    }

    fun send(text: String) {
        // A typed reply resumes the waiting ask_user call inside this turn.
        if (_state.value.pendingQuestion != null) {
            answerAgentQuestion(text)
            return
        }
        val session = _state.value.current ?: return
        if (_state.value.busy || text.isBlank()) return
        generationJob = viewModelScope.launch {
            val withUser = session.copy(
                turns = session.turns + CreationTurn("user", text.trim()),
                updatedAt = System.currentTimeMillis(),
            )
            _state.update { it.copy(
                current = withUser, busy = true, error = null,
                extractionProgress = "", operationLabel = UiStrings.get(S.crvm_op_converse), modelPhase = UiStrings.get(S.crvm_phase_preparing), liveReasoning = "", liveOutput = "",
                liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(),
                modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            ) }
            try {
                write(withUser)
                val reply = engine.converse(
                    session, text.trim(), ::showModelProgress,
                    onCheckpoint = { working ->
                        val checkpoint = working.copy(
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
                val next = reply.session.copy(
                    turns = withUser.turns + CreationTurn(
                        "agent", reply.message.ifBlank { "草稿已更新，请检查右侧内容。" },
                    ),
                    partialTurnSaved = false,
                    updatedAt = System.currentTimeMillis(),
                )
                write(next)
                _state.update { it.copy(current = next, info = null, modelPhase = UiStrings.get(S.crvm_phase_turn_done)) }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    UiStrings.get(S.crvm_phase_stopped_saved) else UiStrings.get(S.crvm_phase_stopped_not_applied)) }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    UiStrings.get(S.crvm_phase_interrupted_saved) else UiStrings.get(S.crvm_phase_turn_failed)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun retry() {
        val session = _state.value.current ?: return
        if (_state.value.busy || session.partialTurnSaved || session.turns.lastOrNull()?.role != "user") return
        val last = session.turns.last().text
        val before = session.copy(turns = session.turns.dropLast(1))
        _state.update { it.copy(current = before) }
        send(last)
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
                    sourceName = name.ifBlank { "粘贴原文" },
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
        if (_state.value.busy || starting.sourceLength == 0) return
        generationJob = viewModelScope.launch {
            _state.update { it.copy(
                busy = true, error = null, operationLabel = UiStrings.get(S.crvm_op_extract),
                modelPhase = UiStrings.get(S.crvm_phase_reading_source), liveReasoning = "", liveOutput = "", liveAssistantMessage = "",
                operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0,
                firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            ) }
            try {
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
                    val reply = engine.extractChunk(chunk, starting.sourceName, ::showModelProgress)
                    _state.update { it.copy(modelPhase = UiStrings.get(S.crvm_phase_verify_chunk, chunkNumber)) }
                    val proposed = reply.lore.orEmpty()
                    val verified = verifiedLoreFromChunk(chunk, proposed, starting.sourceName, starting.sourceSha256)
                    val rejected = proposed.size - verified.size
                    current = current.copy(
                        lore = mergeExtractedLore(current.lore, verified),
                        worldName = current.worldName.ifBlank { reply.worldName.orEmpty() },
                        sourceCursor = chunk.end,
                        turns = current.turns + CreationTurn(
                            "agent",
                            "已提炼 ${chunk.end}/${source.length} 字符，新增 ${verified.size} 条；${rejected} 条因缺少可定位原文证据而未加入。",
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
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelGeneration() { generationJob?.cancel() }

    /** Resolve a pending ask_user with a selected option or a free-form reply. */
    fun answerAgentQuestion(choice: String) {
        val answer = choice.trim()
        if (answer.isBlank()) return
        if (askAnswer?.complete(answer) == true) {
            _state.update { it.copy(pendingQuestion = null) }
        }
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
                val hash = repository.saveCover(session.id, pngBytes)
                val next = session.copy(coverSha256 = hash, updatedAt = System.currentTimeMillis())
                write(next)
                repository.pruneCovers(session.id, hash)
                _state.update { it.copy(current = next, coverPreviewPng = pngBytes, info = UiStrings.get(S.crvm_cover_saved)) }
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
            state.copy(current = session.copy(lore = items, updatedAt = System.currentTimeMillis()), info = null)
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
                        store.importCharacter(card, cover, "character.png")
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

    private suspend fun write(session: CreationSession) = writeMutex.withLock { repository.save(session) }

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

    private fun fail(e: Throwable) {
        _state.update { it.copy(error = e.message ?: UiStrings.get(S.crvm_failed_default)) }
    }
}

class CreationViewModelFactory(
    private val root: File,
    private val store: StDataStore,
    private val secrets: SecretStore,
    private val providers: ProviderRegistry,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CreationViewModel(
        repository = CreationRepository(root),
        store = store,
        secrets = secrets,
        providers = providers,
    ) as T
}
