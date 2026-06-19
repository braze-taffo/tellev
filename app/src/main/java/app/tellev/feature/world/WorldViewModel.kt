package app.tellev.feature.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class WorldUiState(
    val worldBooks: List<WorldBook> = emptyList(),
    val selectedBook: WorldBook? = null,
    val selectedEntry: WorldBookEntry? = null,
    val searchQuery: String = "",
    val filteredEntries: List<WorldBookEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class WorldViewModel(
    private val dataStore: StDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorldUiState())
    val uiState: StateFlow<WorldUiState> = _uiState.asStateFlow()

    init {
        loadBooks()
    }

    fun loadBooks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val books = dataStore.listWorldBooks()
                _uiState.update {
                    it.copy(
                        worldBooks = books,
                        isLoading = false,
                    )
                }
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

    fun selectBook(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val book = dataStore.readWorldBook(id)
                val query = _uiState.value.searchQuery
                val filtered = filterEntries(book.entries, query)
                _uiState.update {
                    it.copy(
                        selectedBook = book,
                        filteredEntries = filtered,
                        selectedEntry = null,
                        isLoading = false,
                    )
                }
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

    fun clearSelectedBook() {
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.saveWorldBook(book)
                _uiState.update {
                    it.copy(
                        selectedBook = book,
                        isLoading = false,
                        info = "世界书已保存。",
                    )
                }
                loadBooks()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "保存世界书失败：${e.message}",
                    )
                }
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

    fun deleteBook(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // Save with empty state to signal deletion
                val emptyBook = WorldBook(
                    id = id,
                    name = "",
                    entries = emptyList(),
                )
                dataStore.saveWorldBook(emptyBook)
                _uiState.update {
                    it.copy(
                        selectedBook = null,
                        selectedEntry = null,
                        isLoading = false,
                        info = "世界书已删除。",
                    )
                }
                loadBooks()
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

    fun clearSelectedEntry() {
        _uiState.update { it.copy(selectedEntry = null) }
    }

    fun saveEntry(bookId: String, entry: WorldBookEntry) {
        val book = _uiState.value.selectedBook ?: return
        val existingIndex = book.entries.indexOfFirst { it.id == entry.id }
        val updatedEntries = if (existingIndex >= 0) {
            book.entries.toMutableList().apply {
                this[existingIndex] = entry
            }
        } else {
            book.entries + entry
        }
        val updatedBook = book.copy(entries = updatedEntries)
        saveBook(updatedBook)
        _uiState.update { it.copy(selectedEntry = null) }
    }

    fun addEntry(bookId: String) {
        val newEntry = WorldBookEntry(
            id = "entry_${UUID.randomUUID()}",
            keys = emptyList(),
            content = "",
            enabled = true,
        )
        _uiState.update { it.copy(selectedEntry = newEntry) }
    }

    fun deleteEntry(bookId: String, entryId: String) {
        val book = _uiState.value.selectedBook ?: return
        val updatedEntries = book.entries.filter { it.id != entryId }
        val updatedBook = book.copy(entries = updatedEntries)
        saveBook(updatedBook)
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
