package app.tellev.feature.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.WorldBookSummary
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.BatchImportReport
import app.tellev.core.storage.BatchImportSource
import app.tellev.core.storage.CharacterBatchImporter
import app.tellev.core.storage.StDataStore
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tellev.util.decodeImageAsPng
import java.util.UUID

data class CharactersUiState(
    val characters: List<CharacterSummary> = emptyList(),
    val filteredCharacters: List<CharacterSummary> = emptyList(),
    val searchQuery: String = "",
    // 分组（书架分类）：null = 全部；空串 = 未分组；其余为分组 id。
    val groups: List<CharacterGroup> = emptyList(),
    val activeGroupId: String? = null,
    val selectedCharacter: CharacterCard? = null,
    // World books available to bind to the selected character (loaded lazily
    // when a character is opened, for the binding picker in the detail screen).
    // Summaries only: the picker needs names and counts, not entry bodies.
    val worldBooks: List<WorldBookSummary> = emptyList(),
    // Card file per character id: the card PNG itself is the avatar. JSON
    // cards (no embedded image) make the UI fall back to the initials badge.
    val avatarFiles: Map<String, java.io.File> = emptyMap(),
    val isLoading: Boolean = false,
    val selectionError: String? = null,
    val error: String? = null,
    val info: String? = null,
    // Multi-file import outcome; the list screen renders it as a summary
    // dialog. Null after the user dismisses it (clearBatchImportReport).
    val batchImportReport: BatchImportReport? = null,
)

class CharactersViewModel(
    private val dataStore: StDataStore,
    private val importedCardSignal: MutableStateFlow<Long>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CharactersUiState())
    val uiState: StateFlow<CharactersUiState> = _uiState.asStateFlow()

    private val batchImporter = CharacterBatchImporter()
    private val exporter = CharacterExporter()

    init {
        loadCharacters()
        viewModelScope.launch {
            importedCardSignal.drop(1).collect { loadCharacters() }
        }
    }

    fun loadCharacters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, selectionError = null) }
            try {
                val characters = dataStore.listCharacters()
                val groups = withContext(Dispatchers.IO) { CharacterGroups.read(dataStore.layout.root) }
                val avatarFiles = characters.associate { summary ->
                    summary.id to dataStore.layout.root.resolve(summary.avatarRelativePath).toFile()
                }
                _uiState.update { state ->
                    // 分组可能在上一次列表刷新后新增/删除：悬空的筛选回落「全部」。
                    val active = state.activeGroupId?.takeIf { id ->
                        id == UNGROUPED_GROUP_ID || groups.any { it.id == id }
                    }
                    state.copy(
                        characters = characters,
                        groups = groups,
                        activeGroupId = active,
                        filteredCharacters = filterCharacters(characters, state.searchQuery, groups, active),
                        avatarFiles = avatarFiles,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载角色列表失败：${e.message}",
                    )
                }
            }
        }
    }

    fun search(query: String) {
        _uiState.update { state ->
            state.copy(
                searchQuery = query,
                filteredCharacters = filterCharacters(state.characters, query, state.groups, state.activeGroupId),
            )
        }
    }

    /** 书架分组筛选：null=全部、[UNGROUPED_GROUP_ID]=未分组、其余为分组 id。 */
    fun selectGroup(groupId: String?) {
        _uiState.update { state ->
            val active = groupId?.takeIf { it == UNGROUPED_GROUP_ID || state.groups.any { g -> g.id == it } }
            state.copy(
                activeGroupId = active,
                filteredCharacters = filterCharacters(state.characters, state.searchQuery, state.groups, active),
            )
        }
    }

    fun createGroup(label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return false
        val group = CharacterGroup(id = CharacterGroups.newGroupId(), label = trimmed)
        val next = _uiState.value.groups + group
        if (!CharacterGroups.write(dataStore.layout.root, next)) return false
        _uiState.update { state ->
            state.copy(
                groups = next,
                filteredCharacters = filterCharacters(state.characters, state.searchQuery, next, state.activeGroupId),
            )
        }
        return true
    }

    fun renameGroup(groupId: String, label: String): Boolean {
        val trimmed = label.trim()
        if (trimmed.isBlank()) return false
        val next = _uiState.value.groups.map { if (it.id == groupId) it.copy(label = trimmed) else it }
        if (!CharacterGroups.write(dataStore.layout.root, next)) return false
        _uiState.update { it.copy(groups = next) }
        return true
    }

    /** 删除分组只删分类定义，角色卡本身保持不动（成员回落「未分组」）。 */
    fun deleteGroup(groupId: String) {
        val next = _uiState.value.groups.filterNot { it.id == groupId }
        if (!CharacterGroups.write(dataStore.layout.root, next)) return
        _uiState.update { state ->
            val active = state.activeGroupId.takeIf { it != groupId }
            state.copy(
                groups = next,
                activeGroupId = active,
                filteredCharacters = filterCharacters(state.characters, state.searchQuery, next, active),
            )
        }
    }

    /** 角色卡归入/移出分组（一张卡可属多个分组）。 */
    fun setCharacterGroup(characterId: String, groupId: String, member: Boolean) {
        val next = _uiState.value.groups.map { group ->
            if (group.id != groupId) group
            else group.copy(
                members = if (member) (group.members + characterId).distinct()
                else group.members.filterNot { it == characterId },
            )
        }
        if (!CharacterGroups.write(dataStore.layout.root, next)) return
        _uiState.update { state ->
            state.copy(
                groups = next,
                filteredCharacters = filterCharacters(state.characters, state.searchQuery, next, state.activeGroupId),
            )
        }
    }

    fun groupsOf(characterId: String): List<CharacterGroup> =
        _uiState.value.groups.filter { characterId in it.members }

    fun selectCharacter(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, selectionError = null) }
            try {
                val character = dataStore.readCharacter(id)
                // Load the world-book list alongside the card so the detail
                // screen can offer a binding picker without a second round-trip.
                val worldBooks = runCatching { dataStore.listWorldBookSummaries() }.getOrDefault(emptyList())
                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        worldBooks = worldBooks,
                        isLoading = false,
                        selectionError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedCharacter = null,
                        selectionError = "找不到角色“$id”或角色卡无法读取。",
                        error = "加载角色失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearSelectedCharacter() {
        _uiState.update { it.copy(selectedCharacter = null, worldBooks = emptyList()) }
    }

    fun loadWorldBooks() {
        viewModelScope.launch {
            val books = runCatching { dataStore.listWorldBookSummaries() }.getOrDefault(emptyList())
            _uiState.update { it.copy(worldBooks = books) }
        }
    }

    suspend fun createCharacter(card: CharacterCard, avatarPng: ByteArray?): Boolean {
        if (card.name.isBlank()) return false
        _uiState.update { it.copy(isLoading = true, error = null) }
        return try {
            dataStore.saveCharacter(card)
            var avatarError: String? = null
            if (avatarPng != null) {
                runCatching { dataStore.replaceCharacterAvatar(card.id, avatarPng) }
                    .onFailure { avatarError = it.message ?: "未知错误" }
            }
            val saved = dataStore.readCharacter(card.id)
            _uiState.update {
                it.copy(
                    selectedCharacter = saved,
                    isLoading = false,
                    info = if (avatarError == null) "角色已创建。" else "角色已创建，但头像保存失败：$avatarError",
                )
            }
            loadCharacters()
            importedCardSignal.value = importedCardSignal.value + 1L
            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(isLoading = false, error = "创建角色失败：${e.message}")
            }
            false
        }
    }

    /**
     * Replaces the selected character's avatar with the picked image. The
     * image is downsampled and re-encoded as PNG, then the card (unchanged
     * data included) is re-embedded into the new PNG by the data store.
     */
    fun setCharacterAvatar(imageBytes: ByteArray) {
        val current = _uiState.value.selectedCharacter ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val pngBytes = withContext(Dispatchers.IO) {
                    decodeImageAsPng(imageBytes, maxEdge = 1024)
                } ?: error("无法解析所选图片")
                dataStore.replaceCharacterAvatar(current.id, pngBytes)
                val reloaded = dataStore.readCharacter(current.id)
                _uiState.update {
                    it.copy(
                        selectedCharacter = reloaded,
                        isLoading = false,
                        info = "头像已更新。",
                    )
                }
                loadCharacters()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "更换头像失败：${e.message}",
                    )
                }
            }
        }
    }

    /** 附属角色读取（1.7.1.4）：读不到的 id 回退到已保存快照，最后兜底抛出。 */
    suspend fun readCastCharacters(ids: List<String>, fallback: List<CharacterCard>): List<CharacterCard> =
        ids.distinct().map { id ->
            try { dataStore.readCharacter(id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { fallback.firstOrNull { it.id == id } ?: throw error }
        }

    fun saveCharacter(card: CharacterCard) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.saveCharacter(card)
                _uiState.update {
                    it.copy(
                        selectedCharacter = card,
                        isLoading = false,
                        info = "角色已保存。",
                    )
                }
                loadCharacters()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "保存角色失败：${e.message}",
                    )
                }
            }
        }
    }

    fun duplicateCharacter(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val original = dataStore.readCharacter(id)
                val duplicate = original.copy(
                    id = "char_${UUID.randomUUID()}",
                    name = "${original.name}（副本）",
                )
                dataStore.saveCharacter(duplicate)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "角色已复制。",
                    )
                }
                loadCharacters()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "复制角色失败：${e.message}",
                    )
                }
            }
        }
    }

    fun deleteCharacter(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.deleteCharacter(id)
                _uiState.update {
                    it.copy(
                        selectedCharacter = null,
                        isLoading = false,
                        info = "角色已删除。",
                    )
                }
                loadCharacters()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "删除角色失败：${e.message}",
                    )
                }
            }
        }
    }

    fun importCharacter(bytes: ByteArray, fileName: String) {
        importCharacters(listOf(BatchImportSource(fileName, bytes)))
    }

    /**
     * 扫描结果批量导入（导入菜单「扫描角色卡」）：与 [importCharacters] 同一条
     * 隔离管线，只是来源是目录遍历而不是文档选择器。
     */
    fun importScannedCharacters(sources: List<BatchImportSource>) {
        importCharacters(sources)
    }

    /**
     * Batch import: every file is isolated — one malformed card never aborts
     * the rest. A single-file batch reports through the existing info/error
     * snackbars; a multi-file batch surfaces the full per-file report dialog.
     */
    fun importCharacters(sources: List<BatchImportSource>) {
        if (sources.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, batchImportReport = null) }
            try {
                val existingIds = _uiState.value.characters.map { it.id }.toSet()
                val report = batchImporter.importAll(sources, existingIds, dataStore)
                loadCharacters()
                importedCardSignal.value = importedCardSignal.value + 1L
                _uiState.update { state ->
                    when {
                        report.results.size == 1 -> {
                            val result = report.results.first()
                            if (result.isSuccess) {
                                state.copy(
                                    isLoading = false,
                                    info = UiStrings.get(
                                        S.chars_import_one_ok,
                                        result.characterName ?: result.fileName,
                                    ),
                                )
                            } else {
                                state.copy(
                                    isLoading = false,
                                    error = UiStrings.get(S.chars_import_failed, result.error ?: ""),
                                )
                            }
                        }
                        else -> state.copy(isLoading = false, batchImportReport = report)
                    }
                }
            } catch (e: Exception) {
                // Per-item failures are already isolated above; reaching here
                // means the store itself rejected the batch-level flow.
                _uiState.update {
                    it.copy(isLoading = false, error = UiStrings.get(S.chars_import_failed, e.message))
                }
            }
        }
    }

    fun clearBatchImportReport() {
        _uiState.update { it.copy(batchImportReport = null) }
    }

    suspend fun exportCharacterToJson(id: String): String? {
        return try {
            val card = dataStore.readCharacter(id)
            exporter.exportToJson(card)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "导出失败：${e.message}") }
            null
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(info = null) }
    }

    /**
     * 搜索（名称/标签）与分组筛选的交集：分组先收窄集合，再按关键词过滤。
     * [activeGroupId] null=全部、[UNGROUPED_GROUP_ID]=不属于任何分组的卡。
     */
    private fun filterCharacters(
        characters: List<CharacterSummary>,
        query: String,
        groups: List<CharacterGroup> = emptyList(),
        activeGroupId: String? = null,
    ): List<CharacterSummary> = CharactersFilter.filter(characters, query, groups, activeGroupId)

    companion object {
        /** 「未分组」虚拟分组 id：不与真实分组冲突。 */
        const val UNGROUPED_GROUP_ID = "__ungrouped__"
    }
}

/**
 * 「搜索 × 分组」过滤的纯函数（无 Android 依赖，单测直接覆盖）：
 * 分组先收窄集合（全部 / 未分组 / 指定分组），再按关键词过滤名称与标签。
 */
internal object CharactersFilter {
    fun filter(
        characters: List<CharacterSummary>,
        query: String,
        groups: List<CharacterGroup> = emptyList(),
        activeGroupId: String? = null,
    ): List<CharacterSummary> {
        val inGroup = when (activeGroupId) {
            null -> characters
            CharactersViewModel.UNGROUPED_GROUP_ID -> {
                val grouped = groups.flatMap { it.members }.toSet()
                characters.filterNot { it.id in grouped }
            }
            else -> {
                val members = groups.firstOrNull { it.id == activeGroupId }?.members.orEmpty().toSet()
                characters.filter { it.id in members }
            }
        }
        if (query.isBlank()) return inGroup
        val lowerQuery = query.lowercase()
        return inGroup.filter { char ->
            char.name.lowercase().contains(lowerQuery) ||
                char.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }
}

class CharactersViewModelFactory(
    private val dataStore: StDataStore,
    private val importedCardSignal: MutableStateFlow<Long>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CharactersViewModel::class.java)) {
            return CharactersViewModel(dataStore = dataStore, importedCardSignal = importedCardSignal) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
