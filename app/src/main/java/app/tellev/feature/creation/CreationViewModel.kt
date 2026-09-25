package app.tellev.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.codec.WorldBookCodec
import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.util.UUID

data class CreationUiState(
    val sessions: List<CreationSession> = emptyList(),
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
    val error: String? = null,
    val info: String? = null,
)

enum class CharacterExportFormat { Json, Png }

/** Serialize a world book to SillyTavern-compatible JSON, verifying it reads back. */
internal fun worldBookExportBytes(book: WorldBook): ByteArray {
    val serialized = WorldBookCodec.serializeWorldBook(book)
    require(WorldBookCodec.parseWorldBookEntries(serialized).size == book.entries.size) {
        "世界书导出回读失败。"
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
                    info = "创作草稿已删除。",
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
        _state.update { it.copy(current = session, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
        persist(session)
    }

    fun open(id: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(current = null, coverPreviewPng = null, error = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
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
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "读取角色卡", modelPhase = "正在读取角色卡", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
        try {
            val session = CreationSession.fromCharacter(store.readCharacter(cardId))
            _state.update { it.copy(current = session, modelPhase = "已载入角色卡") }
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
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "读取角色卡", modelPhase = "正在读取角色卡", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
        try {
            val session = CreationSession.worldBookFromCharacter(store.readCharacter(cardId))
            _state.update { it.copy(current = session, modelPhase = "已载入来源角色卡") }
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
        _state.update { it.copy(busy = true, current = null, coverPreviewPng = null, error = null, info = null, extractionProgress = "", operationLabel = "读取世界书", modelPhase = "正在读取世界书", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
        try {
            val session = CreationSession.fromWorldBook(store.readWorldBook(bookId))
            _state.update { it.copy(current = session, modelPhase = "已载入世界书") }
            persist(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun close() { if (!_state.value.busy) _state.update { it.copy(current = null, coverPreviewPng = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = 0, modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") } }

    fun send(text: String) {
        val session = _state.value.current ?: return
        if (_state.value.busy || text.isBlank()) return
        generationJob = viewModelScope.launch {
            val withUser = session.copy(
                turns = session.turns + CreationTurn("user", text.trim()),
                updatedAt = System.currentTimeMillis(),
            )
            _state.update { it.copy(
                current = withUser, busy = true, error = null,
                extractionProgress = "", operationLabel = "创作对话", modelPhase = "准备请求", liveReasoning = "", liveOutput = "",
                liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(),
                modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            ) }
            try {
                write(withUser)
                val reply = engine.converse(session, text.trim(), ::showModelProgress) { working ->
                    val checkpoint = working.copy(
                        turns = withUser.turns,
                        partialTurnSaved = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                    // Finish the atomic checkpoint even if Stop arrives during disk I/O.
                    withContext(NonCancellable) { write(checkpoint) }
                    _state.update { it.copy(current = checkpoint) }
                }
                _state.update { it.copy(modelPhase = "校验并保存草稿") }
                val next = reply.session.copy(
                    turns = withUser.turns + CreationTurn(
                        "agent", reply.message.ifBlank { "草稿已更新，请检查右侧内容。" },
                    ),
                    partialTurnSaved = false,
                    updatedAt = System.currentTimeMillis(),
                )
                write(next)
                _state.update { it.copy(current = next, info = null, modelPhase = "本轮完成") }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    "已停止；已完成的草稿修改已保存，可继续处理" else "已停止；本轮草稿未应用") }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = if (it.current?.partialTurnSaved == true)
                    "请求中断；已完成的草稿修改已保存，可继续处理" else "本轮失败，草稿未应用") }
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
            _state.update { it.copy(error = "原文超过 400 万字符，请先拆为多个文件；没有截断或处理任何内容。") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, extractionProgress = "", operationLabel = "保存原文", modelPhase = "正在保存原文", liveReasoning = "", liveOutput = "", liveAssistantMessage = "", operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "") }
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
                _state.update { it.copy(current = next, info = "原文已保存，可开始逐段提炼。", modelPhase = "原文已保存") }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = "保存原文已停止") }
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
                busy = true, error = null, operationLabel = "世界书长文提炼",
                modelPhase = "读取已保存原文", liveReasoning = "", liveOutput = "", liveAssistantMessage = "",
                operationStartedAtMillis = System.currentTimeMillis(), modelElapsedMillis = 0,
                firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0, providerLabel = "",
            ) }
            try {
                val source = repository.readSource(starting.id, starting.sourceSha256)
                require(source.length == starting.sourceLength) { "原文长度与草稿记录不一致，请重新导入。" }
                var current = starting
                val chunkCount = countSourceChunks(source, starting.sourceCursor)
                var chunkNumber = chunkCount.completed
                while (true) {
                    val chunk = nextSourceChunk(source, current.sourceCursor) ?: break
                    chunkNumber++
                    _state.update { it.copy(
                        extractionProgress = "第 $chunkNumber/${chunkCount.total} 段：正在处理 ${chunk.start}–${chunk.end} / ${source.length} 字符；已保存至 ${current.sourceCursor}",
                        modelPhase = "提炼第 $chunkNumber 段", liveReasoning = "", liveOutput = "", liveAssistantMessage = "",
                        modelElapsedMillis = 0, firstDeltaMillis = null, lastDeltaMillis = null, deltaCount = 0,
                    ) }
                    val reply = engine.extractChunk(chunk, starting.sourceName, ::showModelProgress)
                    _state.update { it.copy(modelPhase = "核对第 $chunkNumber 段的原文证据") }
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
                        extractionProgress = "已保存 ${current.sourceCursor}/${source.length} 字符，共 ${current.lore.size} 条世界书内容",
                        modelPhase = "第 $chunkNumber 段已保存",
                    ) }
                }
                _state.update { it.copy(info = "原文提炼完成，请逐条核对后保存世界书。", modelPhase = "提炼完成") }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = "已停止，可从已保存位置继续提炼") }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = "提炼中断，可从已保存位置继续") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelGeneration() { generationJob?.cancel() }

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
                _state.update { it.copy(current = next, coverPreviewPng = pngBytes, info = "封面已保存到创作草稿。") }
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
        val session = _state.value.current ?: error("请先打开角色卡草稿。")
        require(session.kind == CreationKind.Character && !_state.value.busy) { "当前无法导出角色卡。" }
        val card = checkedCharacterCard(session)
        val exporter = CharacterExporter()
        when (format) {
            CharacterExportFormat.Json -> exporter.exportToJson(card).toByteArray(Charsets.UTF_8)
            CharacterExportFormat.Png -> {
                require(session.coverSha256.isNotBlank()) { "请先在角色卡页设置封面，再导出 PNG。" }
                val cover = repository.readCover(session.id, session.coverSha256)
                exporter.exportToPng(card, cover).also { bytes ->
                    val imported = CharacterImporter().importFromBytes(bytes, "character.png")
                    require(imported.name == card.name && imported.firstMessage == card.firstMessage) {
                        "PNG 角色卡导出回读失败。"
                    }
                }
            }
        }
    }

    /** Export the draft as a standalone SillyTavern world book JSON file. */
    suspend fun exportWorldBook(): ByteArray = withContext(Dispatchers.IO) {
        val session = _state.value.current ?: error("请先打开世界书草稿。")
        require(session.kind == CreationKind.WorldBook && !_state.value.busy) { "当前无法导出世界书。" }
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
                        "角色卡导出回读失败。"
                    }
                    if (prepared.coverSha256.isNotBlank()) {
                        val cover = repository.readCover(prepared.id, prepared.coverSha256)
                        val png = CharacterExporter().exportToPng(card, cover)
                        val pngCard = CharacterImporter().importFromBytes(png, "character.png")
                        require(pngCard.name == card.name && pngCard.firstMessage == card.firstMessage) {
                            "封面角色卡回读失败。"
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
                _state.update { it.copy(current = next, info = "已保存到本机。双端运行仍需实际验证。") }
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
        require(session.card.name.isNotBlank()) { "请至少填写角色名称。" }
        require(session.lore.all { it.content.isNotBlank() && (it.constant || it.keys.any(String::isNotBlank)) }) {
            "世界书有空条目或缺少触发词。"
        }
        val issues = portableFrontendIssues(session.card.frontendHtml)
        require(issues.isEmpty()) { "前端不符合当前可移植约束：${issues.joinToString()}" }
        return session.toCharacterCard()
    }

    private fun checkedWorldBook(session: CreationSession): WorldBook {
        require(session.worldName.isNotBlank() && session.lore.isNotEmpty()) {
            "请填写世界书名称并至少保留一个条目。"
        }
        require(session.lore.all { it.content.isNotBlank() && (it.constant || it.keys.any(String::isNotBlank)) }) {
            "世界书有空条目或缺少触发词。"
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
        _state.update { it.copy(error = e.message ?: "创作失败") }
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
