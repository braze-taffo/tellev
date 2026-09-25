package app.tellev.feature.creation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

data class CreationUiState(
    val sessions: List<CreationSession> = emptyList(),
    val current: CreationSession? = null,
    val busy: Boolean = false,
    val extractionProgress: String = "",
    val operationLabel: String = "",
    val modelPhase: String = "",
    val liveReasoning: String = "",
    val liveOutput: String = "",
    val error: String? = null,
    val info: String? = null,
)

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

    fun start(kind: CreationKind) {
        if (_state.value.busy) return
        val session = CreationSession(kind = kind)
        _state.update { it.copy(current = session, error = null, info = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "") }
        persist(session)
    }

    fun open(id: String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.update { it.copy(current = null, error = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "") }
        runCatching { repository.load(id) }
            .onSuccess { session -> _state.update { it.copy(current = session, error = null) } }
            .onFailure { fail(it) }
    }

    fun close() { if (!_state.value.busy) _state.update { it.copy(current = null, extractionProgress = "", operationLabel = "", modelPhase = "", liveReasoning = "", liveOutput = "") } }

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
            ) }
            try {
                write(withUser)
                val reply = engine.converse(session, text.trim(), ::showModelProgress)
                _state.update { it.copy(modelPhase = "校验并保存草稿") }
                val next = CreationReplyParser.apply(withUser, reply).copy(
                    turns = withUser.turns + CreationTurn(
                        "agent", reply.message.ifBlank { "草稿已更新，请检查右侧内容。" },
                    ),
                )
                write(next)
                _state.update { it.copy(current = next, info = null, modelPhase = "本轮完成") }
                refresh()
            } catch (e: CancellationException) {
                _state.update { it.copy(modelPhase = "已停止；本轮草稿未应用") }
                throw e
            } catch (e: Exception) {
                fail(e)
                _state.update { it.copy(modelPhase = "本轮失败，草稿未应用") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun retry() {
        val session = _state.value.current ?: return
        if (_state.value.busy || session.turns.lastOrNull()?.role != "user") return
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
            _state.update { it.copy(busy = true, error = null, extractionProgress = "", operationLabel = "保存原文", modelPhase = "正在保存原文", liveReasoning = "", liveOutput = "") }
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
                modelPhase = "读取已保存原文", liveReasoning = "", liveOutput = "",
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
                        modelPhase = "提炼第 $chunkNumber 段", liveReasoning = "", liveOutput = "",
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
                require(session.lore.all { it.content.isNotBlank() && (it.constant || it.keys.any(String::isNotBlank)) }) {
                    "世界书有空条目或缺少触发词。"
                }
                val prepared = if (session.savedArtifactId.isBlank()) session.copy(
                    savedArtifactId = "${if (session.kind == CreationKind.Character) "char" else "wb"}_${UUID.randomUUID()}",
                ) else session
                write(prepared)
                _state.update { it.copy(current = prepared) }
                val id = if (prepared.kind == CreationKind.Character) {
                    require(prepared.card.name.isNotBlank() && prepared.card.firstMessage.isNotBlank()) {
                        "请至少填写角色名称和开场消息。"
                    }
                    val issues = portableFrontendIssues(prepared.card.frontendHtml)
                    require(issues.isEmpty()) { "前端不符合当前可移植约束：${issues.joinToString()}" }
                    val card = prepared.toCharacterCard()
                    val json = CharacterExporter().exportToJson(card)
                    val imported = CharacterImporter().importFromJson(json)
                    require(imported.name == card.name && imported.firstMessage == card.firstMessage) {
                        "角色卡导出回读失败。"
                    }
                    store.saveCharacter(card)
                    card.id
                } else {
                    require(prepared.worldName.isNotBlank() && prepared.lore.isNotEmpty()) {
                        "请填写世界书名称并至少保留一个条目。"
                    }
                    val book = prepared.toWorldBook()
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
