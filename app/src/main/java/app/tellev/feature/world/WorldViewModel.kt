package app.tellev.feature.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.model.WorldInfoSettings
import app.tellev.core.model.PromptSettings
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.util.UUID

data class WorldUiState(
    val worldBooks: List<WorldBook> = emptyList(),
    val disabledWorldIds: Set<String> = emptySet(),
    val selectedBook: WorldBook? = null,
    val selectedEntry: WorldBookEntry? = null,
    val searchQuery: String = "",
    val filteredEntries: List<WorldBookEntry> = emptyList(),
    val worldInfoSettings: WorldInfoSettings = WorldInfoSettings(),
    val promptSettings: PromptSettings = PromptSettings(),
    val instructPresets: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val selectionError: String? = null,
    val error: String? = null,
    val info: String? = null,
)

class WorldViewModel(
    private val dataStore: StDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorldUiState())
    val uiState: StateFlow<WorldUiState> = _uiState.asStateFlow()

    private val writeMutex = Mutex()
    private var loadJob: Job? = null
    private var selectionJob: Job? = null
    private var selectionVersion = 0L
    private var pendingSaves = 0

    init {
        loadBooks()
        viewModelScope.launch {
            dataStore.worldBookChanges.collect { loadBooks() }
        }
    }

    fun loadBooks() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val books = dataStore.listWorldBooks()
                val disabledWorldIds = dataStore.readDisabledWorldIds()
                val worldInfoSettings = dataStore.readWorldInfoSettings()
                val promptSettings = dataStore.readPromptSettings()
                val instructPresets = dataStore.listInstructPresets()
                currentCoroutineContext().ensureActive()
                _uiState.update {
                    it.copy(
                        worldBooks = books,
                        disabledWorldIds = disabledWorldIds,
                        worldInfoSettings = worldInfoSettings,
                        promptSettings = promptSettings,
                        instructPresets = instructPresets,
                        isLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载世界书失败：${e.message}",
                    )
                }
            }
        }
    }

    fun saveWorldInfoSettings(settings: WorldInfoSettings) {
        viewModelScope.launch {
            try {
                dataStore.saveWorldInfoSettings(settings)
                _uiState.update { it.copy(worldInfoSettings = settings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "保存世界书设置失败：${e.message}")
                }
            }
        }
    }

    fun savePromptSettings(settings: PromptSettings) {
        viewModelScope.launch {
            try {
                dataStore.savePromptSettings(settings)
                _uiState.update { it.copy(promptSettings = settings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "保存提示词设置失败：${e.message}")
                }
            }
        }
    }

    fun toggleWorldActivation(id: String) {
        viewModelScope.launch {
            val current = _uiState.value.disabledWorldIds
            val updated = if (id in current) current - id else current + id
            try {
                dataStore.saveDisabledWorldIds(updated)
                _uiState.update { it.copy(disabledWorldIds = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "更新世界书开关失败：${e.message}")
                }
            }
        }
    }

    fun selectBook(id: String) {
        selectionVersion++
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedEntry = null, error = null, selectionError = null) }
            try {
                val book = dataStore.readWorldBook(id)
                currentCoroutineContext().ensureActive()
                val query = _uiState.value.searchQuery
                val filtered = filterEntries(book.entries, query)
                _uiState.update {
                    it.copy(
                        selectedBook = book,
                        filteredEntries = filtered,
                        selectedEntry = null,
                        isLoading = false,
                        selectionError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedBook = null,
                        selectedEntry = null,
                        filteredEntries = emptyList(),
                        selectionError = "找不到世界书“$id”或文件无法读取。",
                        error = "加载世界书失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearSelectedBook() {
        selectionVersion++
        selectionJob?.cancel()
        _uiState.update {
            it.copy(
                selectedBook = null,
                selectedEntry = null,
                filteredEntries = emptyList(),
                searchQuery = "",
            )
        }
    }

    fun saveBook(book: WorldBook) {
        if (_uiState.value.isSaving) return
        persistBook(book.id) { book }
    }

    // Build entry mutations after earlier writes finish, so a second tap cannot
    // restore an older snapshot of another entry. Publish only after disk success.
    private fun persistBook(
        bookId: String,
        onSaved: (() -> Unit)? = null,
        transform: (WorldBook) -> WorldBook,
    ) {
        val version = selectionVersion
        pendingSaves++
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                writeMutex.withLock {
                    val updated = transform(dataStore.readWorldBook(bookId))
                    dataStore.saveWorldBook(updated)
                    _uiState.update { current ->
                        val state = current.copy(worldBooks = current.worldBooks.map {
                            if (it.id == bookId) updated else it
                        })
                        if (version == selectionVersion && state.selectedBook?.id == bookId) {
                            state.copy(
                                selectedBook = updated,
                                filteredEntries = filterEntries(updated.entries, state.searchQuery),
                                info = "世界书已保存。",
                            )
                        } else state
                    }
                }
                if (version == selectionVersion && _uiState.value.selectedBook?.id == bookId) {
                    onSaved?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "保存世界书失败：${e.message}") }
            } finally {
                pendingSaves--
                _uiState.update { it.copy(isSaving = pendingSaves > 0) }
            }
        }
    }

    fun createBook(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val book = WorldBook(
                    id = "wb_${UUID.randomUUID()}",
                    name = name,
                    entries = emptyList(),
                )
                dataStore.saveWorldBook(book)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "世界书“$name”已创建。",
                    )
                }
                loadBooks()
                selectBook(book.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "创建世界书失败：${e.message}",
                    )
                }
            }
        }
    }

    fun importBook(jsonBytes: ByteArray, sourceFileName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val book = dataStore.importWorldBook(jsonBytes, sourceFileName)
                val books = dataStore.listWorldBooks()
                val disabledWorldIds = dataStore.readDisabledWorldIds()
                _uiState.update {
                    it.copy(
                        worldBooks = books,
                        disabledWorldIds = disabledWorldIds,
                        isLoading = false,
                        info = "世界书“${book.name}”已导入。",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "导入世界书失败：${e.message}",
                    )
                }
            }
        }
    }

    fun deleteBook(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                writeMutex.withLock { dataStore.deleteWorldBook(id) }
                if (_uiState.value.selectedBook?.id == id) clearSelectedBook()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "世界书已删除。",
                    )
                }
                loadBooks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "删除世界书失败：${e.message}",
                    )
                }
            }
        }
    }

    fun selectEntry(entryId: String) {
        val book = _uiState.value.selectedBook ?: return
        val entry = book.entries.find { it.id == entryId }
        _uiState.update { it.copy(selectedEntry = entry) }
    }

    fun openEntry(bookId: String, entryId: String) {
        selectionVersion++
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedEntry = null, error = null, selectionError = null) }
            try {
                val book = _uiState.value.selectedBook?.takeIf { it.id == bookId }
                    ?: dataStore.readWorldBook(bookId)
                currentCoroutineContext().ensureActive()
                val entry = if (entryId == "new") {
                    WorldBookEntry(
                        id = "entry_${UUID.randomUUID()}",
                        keys = emptyList(),
                        content = "",
                        enabled = true,
                    )
                } else {
                    book.entries.firstOrNull { it.id == entryId }
                        ?: error("Entry not found: $entryId")
                }
                _uiState.update {
                    it.copy(
                        selectedBook = book,
                        selectedEntry = entry,
                        filteredEntries = filterEntries(book.entries, it.searchQuery),
                        isLoading = false,
                        selectionError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        selectedBook = null,
                        selectedEntry = null,
                        filteredEntries = emptyList(),
                        isLoading = false,
                        selectionError = "找不到世界书条目“$entryId”，它可能已被删除。",
                        error = "加载世界书条目失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearSelectedEntry() {
        selectionVersion++
        selectionJob?.cancel()
        _uiState.update { it.copy(selectedEntry = null) }
    }

    fun saveEntry(bookId: String, entry: WorldBookEntry, onSaved: (() -> Unit)? = null) {
        if (_uiState.value.selectedBook?.id != bookId) {
            _uiState.update { it.copy(error = "保存失败：世界书上下文已变化，请返回后重新打开条目。") }
            return
        }
        persistBook(bookId, onSaved) { book ->
            val index = book.entries.indexOfFirst { it.id == entry.id }
            val entries = if (index >= 0) book.entries.toMutableList().apply { this[index] = entry }
                else book.entries + entry
            book.copy(entries = entries)
        }
    }

    fun deleteEntry(bookId: String, entryId: String) {
        if (_uiState.value.selectedBook?.id != bookId) {
            _uiState.update { it.copy(error = "删除失败：世界书上下文已变化，请重新打开条目。") }
            return
        }
        persistBook(bookId) { book -> book.copy(entries = book.entries.filterNot { it.id == entryId }) }
    }

    fun searchEntries(query: String) {
        _uiState.update { state ->
            val book = state.selectedBook
            val filtered = if (book != null) filterEntries(book.entries, query) else emptyList()
            state.copy(
                searchQuery = query,
                filteredEntries = filtered,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(info = null) }
    }

    private fun filterEntries(entries: List<WorldBookEntry>, query: String): List<WorldBookEntry> {
        if (query.isBlank()) return entries
        val lowerQuery = query.lowercase()
        return entries.filter { entry ->
            entry.keys.any { it.lowercase().contains(lowerQuery) } ||
                entry.content.lowercase().contains(lowerQuery) ||
                entry.secondaryKeys.any { it.lowercase().contains(lowerQuery) }
        }
    }
}

class WorldViewModelFactory(
    private val dataStore: StDataStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorldViewModel::class.java)) {
            return WorldViewModel(dataStore = dataStore) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
