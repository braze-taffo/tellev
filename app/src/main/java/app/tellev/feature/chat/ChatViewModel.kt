package app.tellev.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class ChatUiState(
    val characters: List<CharacterSummary> = emptyList(),
    val selectedCharacter: CharacterCard? = null,
    val currentSession: ChatSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val selectedProvider: String = "openai-compatible",
    val providerConfig: ProviderConfig? = null,
    val personas: List<Persona> = emptyList(),
    val selectedPersona: Persona? = null,
    val worldBooks: List<WorldBook> = emptyList(),
    val presets: List<GenerationPreset> = emptyList(),
    val selectedPreset: GenerationPreset? = null,
    val sessions: List<ChatSession> = emptyList(),
    val error: String? = null,
    val isLoading: Boolean = false,
)

class ChatViewModel(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val promptEngine: PromptEngine,
    private val secretStore: SecretStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                dataStore.bootstrap()

                val characters = dataStore.listCharacters()
                val personas = dataStore.listPersonas()
                val worldBooks = dataStore.listWorldBooks()
                val presets = dataStore.listPresets()

                val defaultPreset = presets.firstOrNull()
                val defaultPersona = personas.firstOrNull()

                val selectedProvider = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: ProviderCatalog.OPENAI_COMPATIBLE
                val providerConfig = loadProviderConfig(selectedProvider)

                _uiState.update {
                    it.copy(
                        characters = characters,
                        personas = personas,
                        worldBooks = worldBooks,
                        presets = presets,
                        selectedPreset = defaultPreset,
                        selectedPersona = defaultPersona,
                        selectedProvider = selectedProvider,
                        providerConfig = providerConfig,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载数据失败：${e.message}",
                    )
                }
            }
        }
    }

    fun selectCharacter(characterId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val character = dataStore.readCharacter(characterId)
                val sessions = dataStore.listChatSessions(characterId = characterId)

                val session = if (sessions.isNotEmpty()) {
                    sessions.first().withCharacterGreetingSwipes(character).also { upgraded ->
                        if (upgraded != sessions.first()) {
                            dataStore.saveChatSession(upgraded)
                        }
                    }
                } else {
                    createSessionForCharacter(character)
                }
                val allSessions = dataStore.listChatSessions(characterId = characterId)

                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        currentSession = session,
                        messages = session.messages,
                        sessions = allSessions,
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

    fun sendMessage(text: String): Boolean {
        val messageText = text.trim()
        if (messageText.isBlank()) return false

        val state = _uiState.value
        val character = state.selectedCharacter
        if (character == null) {
            _uiState.update { it.copy(error = "请先选择角色") }
            return false
        }
        val session = state.currentSession
        if (session == null) {
            _uiState.update { it.copy(error = "当前没有可用会话") }
            return false
        }
        val preset = state.selectedPreset
        if (preset == null) {
            _uiState.update { it.copy(error = "没有可用预设，请在设置中创建或重新启动应用") }
            return false
        }

        viewModelScope.launch {
            try {
                val selectedProvider = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: state.selectedProvider
                val config = loadProviderConfig(selectedProvider)
                _uiState.update {
                    it.copy(
                        selectedProvider = selectedProvider,
                        providerConfig = config,
                    )
                }

                val userMessage = ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.User,
                    name = state.selectedPersona?.name ?: "你",
                    content = messageText,
                    createdAtMillis = System.currentTimeMillis(),
                )

                val updatedMessages = state.messages + userMessage
                val updatedSession = session.copy(messages = updatedMessages)

                dataStore.saveChatSession(updatedSession)

                _uiState.update {
                    it.copy(
                        messages = updatedMessages,
                        currentSession = updatedSession,
                        isGenerating = true,
                        streamingText = "",
                        error = null,
                    )
                }

                val promptRequest = PromptBuildRequest(
                    character = character,
                    persona = state.selectedPersona,
                    messages = updatedMessages,
                    worldBooks = worldBooksForCharacter(state, character),
                    preset = preset,
                    userInput = messageText,
                    providerType = config.providerType,
                    metadata = buildJsonObject {
                        put("providerType", config.providerType)
                    },
                )

                val promptResult = promptEngine.build(promptRequest)

                val generateRequest = GenerateRequest(
                    prompt = promptResult,
                    preset = preset,
                    stream = true,
                )

                val adapter = providerRegistry.require(config.providerType)
                val flow = adapter.streamGenerate(config, generateRequest)

                var accumulatedText = ""

                flow.collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> {
                            accumulatedText += chunk.text
                            _uiState.update { it.copy(streamingText = accumulatedText) }
                        }
                        is GenerateChunk.Completed -> {
                            val finalText = chunk.text.ifBlank { accumulatedText }
                            val assistantMessage = ChatMessage(
                                id = generateMessageId(),
                                role = MessageRole.Character,
                                name = character.name,
                                content = finalText,
                                createdAtMillis = System.currentTimeMillis(),
                                swipes = listOf(finalText),
                                swipeIndex = 0,
                            )
                            val finalMessages = updatedMessages + assistantMessage
                            val finalSession = updatedSession.copy(messages = finalMessages)

                            dataStore.saveChatSession(finalSession)

                            _uiState.update {
                                it.copy(
                                    messages = finalMessages,
                                    currentSession = finalSession,
                                    isGenerating = false,
                                    streamingText = "",
                                )
                            }
                        }
                        is GenerateChunk.Failed -> {
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    error = "生成失败：${chunk.error.message}",
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = "出错了：${e.message}",
                    )
                }
            }
        }.also { generationJob = it }
        return true
    }

    fun regenerateLastMessage() {
        val state = _uiState.value
        val session = state.currentSession ?: return
        val messages = state.messages

        val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.Character || it.role == MessageRole.Assistant }
        if (lastAssistantIndex < 0) return

        val lastUserIndex = messages.subList(0, lastAssistantIndex).indexOfLast { it.role == MessageRole.User }
        if (lastUserIndex < 0) return

        val lastUserMessage = messages[lastUserIndex]
        val trimmedMessages = messages.subList(0, lastUserIndex)

        val updatedSession = session.copy(messages = trimmedMessages)

        _uiState.update {
            it.copy(
                messages = trimmedMessages,
                currentSession = updatedSession,
            )
        }

        viewModelScope.launch {
            dataStore.saveChatSession(updatedSession)
        }

        sendMessage(lastUserMessage.content)
    }

    fun swipeMessage(messageIndex: Int, direction: Int) {
        val state = _uiState.value
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        val message = messages[messageIndex]

        if (message.swipes.isEmpty()) return

        val newSwipeIndex = when (direction) {
            -1 -> {
                if (message.swipeIndex > 0) message.swipeIndex - 1 else message.swipes.size - 1
            }
            1 -> {
                if (message.swipeIndex < message.swipes.size - 1) message.swipeIndex + 1 else 0
            }
            else -> return
        }

        val updatedMessage = message.copy(
            swipeIndex = newSwipeIndex,
            content = message.swipes[newSwipeIndex],
        )
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        _uiState.update {
            it.copy(
                messages = messages,
                currentSession = updatedSession,
            )
        }

        viewModelScope.launch {
            dataStore.saveChatSession(updatedSession)
        }
    }

    fun editMessage(messageIndex: Int, newContent: String) {
        val state = _uiState.value
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        val message = messages[messageIndex]

        val updatedSwipes = if (message.swipes.isNotEmpty()) {
            message.swipes.toMutableList().also {
                if (message.swipeIndex in it.indices) {
                    it[message.swipeIndex] = newContent
                } else {
                    it.add(newContent)
                }
            }
        } else {
            listOf(newContent)
        }

        val updatedMessage = message.copy(
            content = newContent,
            swipes = updatedSwipes,
            swipeIndex = if (message.swipes.isEmpty()) 0 else message.swipeIndex,
        )
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        _uiState.update {
            it.copy(
                messages = messages,
                currentSession = updatedSession,
            )
        }

        viewModelScope.launch {
            dataStore.saveChatSession(updatedSession)
        }

        if (message.role == MessageRole.User) {
            val trimmedMessages = messages.subList(0, messageIndex + 1).toList()
            _uiState.update {
                it.copy(
                    messages = trimmedMessages,
                    currentSession = updatedSession.copy(messages = trimmedMessages),
                )
            }
            sendMessage(newContent)
        }
    }

    fun deleteMessage(messageIndex: Int) {
        val state = _uiState.value
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        messages.removeAt(messageIndex)

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        _uiState.update {
            it.copy(
                messages = messages,
                currentSession = updatedSession,
            )
        }

        viewModelScope.launch {
            dataStore.saveChatSession(updatedSession)
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null

        val state = _uiState.value
        if (state.streamingText.isNotEmpty()) {
            val character = state.selectedCharacter
            val partialMessage = ChatMessage(
                id = generateMessageId(),
                role = MessageRole.Character,
                name = character?.name ?: "助手",
                content = state.streamingText,
                createdAtMillis = System.currentTimeMillis(),
                swipes = listOf(state.streamingText),
                swipeIndex = 0,
                metadata = buildJsonObject { put("interrupted", true) },
            )
            val updatedMessages = state.messages + partialMessage
            val session = state.currentSession

            if (session != null) {
                val updatedSession = session.copy(messages = updatedMessages)
                _uiState.update {
                    it.copy(
                        messages = updatedMessages,
                        currentSession = updatedSession,
                        isGenerating = false,
                        streamingText = "",
                    )
                }
                viewModelScope.launch {
                    dataStore.saveChatSession(updatedSession)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                    )
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                )
            }
        }
    }

    fun createNewSession() {
        val state = _uiState.value
        val character = state.selectedCharacter ?: return

        viewModelScope.launch {
            try {
                val newSession = createSessionForCharacter(character)
                val sessions = dataStore.listChatSessions(characterId = character.id)

                _uiState.update {
                    it.copy(
                        currentSession = newSession,
                        messages = newSession.messages,
                        sessions = sessions,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "创建会话失败：${e.message}")
                }
            }
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val session = dataStore.readChatSession(sessionId)
                _uiState.update {
                    it.copy(
                        currentSession = session,
                        messages = session.messages,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "切换会话失败：${e.message}")
                }
            }
        }
    }

    fun updateProviderConfig(config: ProviderConfig) {
        _uiState.update {
            it.copy(
                providerConfig = config,
                selectedProvider = config.providerType,
            )
        }
    }

    fun selectPreset(presetId: String) {
        val preset = _uiState.value.presets.firstOrNull { it.id == presetId } ?: return
        _uiState.update { it.copy(selectedPreset = preset) }
    }

    fun selectPersona(personaId: String) {
        val persona = _uiState.value.personas.firstOrNull { it.id == personaId } ?: return
        _uiState.update { it.copy(selectedPersona = persona) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun deselectCharacter() {
        _uiState.update {
            it.copy(
                selectedCharacter = null,
                currentSession = null,
                messages = emptyList(),
                sessions = emptyList(),
            )
        }
    }

    private suspend fun createSessionForCharacter(character: CharacterCard): ChatSession {
        val sessionId = generateSessionId()
        val greetings = character.initialGreetings()
        val firstMessage = if (greetings.isNotEmpty()) {
            listOf(
                ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.Character,
                    name = character.name,
                    content = greetings.first(),
                    createdAtMillis = System.currentTimeMillis(),
                    swipes = greetings,
                    swipeIndex = 0,
                ),
            )
        } else {
            emptyList()
        }

        val session = ChatSession(
            id = sessionId,
            title = "和 ${character.name} 的聊天",
            characterId = character.id,
            groupId = null,
            messages = firstMessage,
        )

        dataStore.saveChatSession(session)
        return session
    }

    private fun CharacterCard.initialGreetings(): List<String> =
        (listOf(firstMessage) + alternateGreetings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun ChatSession.withCharacterGreetingSwipes(character: CharacterCard): ChatSession {
        val greetings = character.initialGreetings()
        if (greetings.size <= 1 || messages.isEmpty()) return this

        val first = messages.first()
        if (first.role != MessageRole.Character && first.role != MessageRole.Assistant) return this

        val mergedSwipes = (first.swipes + greetings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (mergedSwipes == first.swipes) return this

        val currentIndex = mergedSwipes.indexOf(first.content).takeIf { it >= 0 } ?: first.swipeIndex.coerceIn(0, mergedSwipes.lastIndex)
        val upgradedFirst = first.copy(
            swipes = mergedSwipes,
            swipeIndex = currentIndex,
            content = mergedSwipes[currentIndex],
        )
        return copy(messages = listOf(upgradedFirst) + messages.drop(1))
    }

    private suspend fun loadProviderConfig(providerType: String): ProviderConfig {
        val apiKey = secretStore.readSecret("provider-$providerType-apikey")
        val baseUrl = secretStore.readSecret("provider-$providerType-baseurl")

        val defaultModel = ProviderDefaults.model(providerType).takeIf { it.isNotBlank() }
        val model = secretStore.readSecret("provider-$providerType-model") ?: defaultModel

        return ProviderConfig(
            providerType = providerType,
            baseUrl = baseUrl ?: ProviderDefaults.baseUrl(providerType),
            apiKey = apiKey,
            model = model,
        )
    }

    private fun worldBooksForCharacter(state: ChatUiState, character: CharacterCard): List<WorldBook> {
        val embeddedBook = character.characterBook
            ?.takeIf { it.entries.isNotEmpty() }
            ?.copy(id = "${character.id}_character_book")
            ?: return state.worldBooks

        return if (state.worldBooks.any { it.id == embeddedBook.id }) {
            state.worldBooks
        } else {
            state.worldBooks + embeddedBook
        }
    }

    private fun generateMessageId(): String = "msg-${UUID.randomUUID()}"
    private fun generateSessionId(): String = "sess-${UUID.randomUUID()}"
}

class ChatViewModelFactory(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val promptEngine: PromptEngine,
    private val secretStore: SecretStore,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                promptEngine = promptEngine,
                secretStore = secretStore,
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
