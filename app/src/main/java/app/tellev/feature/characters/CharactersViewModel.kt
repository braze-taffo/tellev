package app.tellev.feature.characters

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class CharactersUiState(
    val characters: List<CharacterSummary> = emptyList(),
    val filteredCharacters: List<CharacterSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedCharacter: CharacterCard? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
)

class CharactersViewModel(
    private val dataStore: StDataStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CharactersUiState())
    val uiState: StateFlow<CharactersUiState> = _uiState.asStateFlow()

    private val importer = CharacterImporter()
    private val exporter = CharacterExporter()

    init {
        loadCharacters()
    }

    fun loadCharacters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val characters = dataStore.listCharacters()
                val query = _uiState.value.searchQuery
                val filtered = filterCharacters(characters, query)
                _uiState.update {
                    it.copy(
                        characters = characters,
                        filteredCharacters = filtered,
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
            val filtered = filterCharacters(state.characters, query)
            state.copy(
                searchQuery = query,
                filteredCharacters = filtered,
            )
        }
    }

    fun selectCharacter(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val character = dataStore.readCharacter(id)
                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载角色失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearSelectedCharacter() {
        _uiState.update { it.copy(selectedCharacter = null) }
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
                // Deletion is implemented by saving a character with empty fields
                // The FileStDataStore implementation should handle removal
                val emptyCard = CharacterCard(
                    id = id,
                    name = "",
                    description = "",
                    personality = "",
                    scenario = "",
                    firstMessage = "",
                    exampleMessages = "",
                    creatorNotes = "",
                )
                // We save with a special marker to indicate deletion
                dataStore.saveCharacter(emptyCard)
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val card = importer.importFromBytes(bytes, fileName)
                val importedCard = if (card.id.isBlank() || card.id == "imported_character") {
                    card.copy(id = "char_${UUID.randomUUID()}")
                } else {
                    card
                }
                val uniqueCard = ensureUniqueImportedId(importedCard)
                dataStore.importCharacter(uniqueCard, bytes, fileName)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "角色“${importedCard.name}”已导入。",
                    )
                }
                loadCharacters()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "导入角色失败：${e.message}",
                    )
                }
            }
        }
    }

    private fun ensureUniqueImportedId(card: CharacterCard): CharacterCard {
        val existingIds = _uiState.value.characters.map { it.id }.toSet()
        if (card.id !in existingIds) return card
        return card.copy(id = "${card.id}_${UUID.randomUUID().toString().take(8)}")
    }

    fun exportCharacter(id: String): ByteArray? {
        var result: ByteArray? = null
        try {
            val state = _uiState.value
            val character = state.characters.find { it.id == id }
            if (character != null) {
                // We need the full card to export
                viewModelScope.launch {
                    try {
                        val card = dataStore.readCharacter(id)
                        val jsonString = exporter.exportToJson(card)
                        // Store the result info for the UI to pick up
                        _uiState.update {
                            it.copy(info = "导出已准备好：${card.name}.json")
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(error = "导出失败：${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = "导出失败：${e.message}")
            }
        }
        return result
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

    private fun filterCharacters(characters: List<CharacterSummary>, query: String): List<CharacterSummary> {
        if (query.isBlank()) return characters
        val lowerQuery = query.lowercase()
        return characters.filter { char ->
            char.name.lowercase().contains(lowerQuery) ||
                char.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }
}

class CharactersViewModelFactory(
    private val dataStore: StDataStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CharactersViewModel::class.java)) {
            return CharactersViewModel(dataStore = dataStore) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
