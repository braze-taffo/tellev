package app.tellev.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.extension.ExtensionContextProvider
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionManifest
import app.tellev.core.extension.ExtensionPermission
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.model.Attachment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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
    val disabledWorldIds: Set<String> = emptySet(),
    // Disabled regex script ids for the selected character (app-level toggle,
    // persisted separately from the card's own `disabled` flag). Applied at
    // display time via CharacterRegexApplier.applyForDisplay.
    val disabledRegexScriptIds: Set<String> = emptySet(),
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
    private val extensionHost: ExtensionHost,
    private val permissionManager: ExtensionPermissionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    @Volatile
    private var loadedCharacterScriptExtensionId: String? = null
    @Volatile
    private var metadataSaveJob: Job? = null

    init {
        extensionHost.setContextProvider(object : ExtensionContextProvider {
            override fun snapshot(): JsonObject = buildTavernContext(_uiState.value)

            override suspend fun setChatMessage(index: Int, field: String, value: String): Boolean =
                setChatMessageFromExtension(index, field, value)

            override suspend fun generateText(options: JsonObject): JsonObject? =
                generateTextFromExtension(options)
        })
        extensionHost.setLocalVariableBackend(object : LocalVariableBackend {
            override fun snapshot(): Map<String, String> = snapshotLocalVariables()

            override fun update(transform: (MutableMap<String, String>) -> Unit): Map<String, String> =
                updateChatVariables(transform)
        })
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
                val disabledWorldIds = dataStore.readDisabledWorldIds()
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
                        disabledWorldIds = disabledWorldIds,
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

                // Exclusive activation of world books only: selecting a character
                // makes its own embedded world book the sole active one and
                // deactivates the rest, persisted to the activation file so the
                // world-book screen reflects the same selection.
                //
                // Regex scripts are NOT subject to exclusive activation — their
                // per-character disable set is left untouched, so manual per-script
                // toggles in the extensions screen survive switching characters.
                val ownWorldBookId = StDataStore.embeddedCharacterBookId(characterId)
                // Read the full world-book list from the store directly rather
                // than relying on uiState.worldBooks being loaded yet, so the
                // exclusive set is correct even if the user selects a character
                // before initial data load finishes.
                val allWorldBookIds = dataStore.listWorldBooks().map { it.id }.toSet()
                val disabledWorldIds = allWorldBookIds - ownWorldBookId
                dataStore.saveDisabledWorldIds(disabledWorldIds)

                val disabledRegexScriptIds = dataStore.readDisabledRegexScriptIds()[characterId]
                    ?: emptySet()

                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        currentSession = session,
                        messages = session.messages,
                        sessions = allSessions,
                        disabledWorldIds = disabledWorldIds,
                        disabledRegexScriptIds = disabledRegexScriptIds,
                        isLoading = false,
                    )
                }
                reloadCharacterTavernHelperScripts(character)
                emitCharacterSelected(character)
                emitChatChanged(session)
                emitRenderedEventsForMessages(session.messages)
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

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()): Boolean {
        val messageText = text.trim()
        if (messageText.isBlank() && attachments.isEmpty()) return false

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
                    attachments = attachments,
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
                val userMessageIndex = updatedMessages.lastIndex
                emitStEvent(StEventCatalog.MESSAGE_SENT, userMessageIndex)
                emitStEvent(StEventCatalog.USER_MESSAGE_RENDERED, userMessageIndex)
                emitStEvent(
                    StEventCatalog.GENERATION_STARTED,
                    "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                    false,
                )

                val promptRequest = PromptBuildRequest(
                    character = character,
                    persona = state.selectedPersona,
                    messages = updatedMessages,
                    worldBooks = worldBooksForCharacter(state, character, dataStore.readDisabledWorldIds()),
                    preset = preset,
                    userInput = messageText,
                    providerType = config.providerType,
                    metadata = buildPromptMetadata(state, config, preset, updatedSession),
                )

                val promptResult = promptEngine.build(promptRequest)

                emitStEvent(
                    StEventCatalog.GENERATION_AFTER_COMMANDS,
                    "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                    },
                    false,
                )

                emitStEvent(
                    StEventCatalog.CHAT_COMPLETION_SETTINGS_READY,
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                )

                emitStEvent(
                    StEventCatalog.CHAT_COMPLETION_PROMPT_READY,
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                )

                val generateRequest = GenerateRequest(
                    prompt = promptResult,
                    preset = preset,
                    attachments = attachments,
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
                            val latestState = _uiState.value
                            val latestSession = latestState.currentSession
                            val baseMessages = if (latestSession?.id == updatedSession.id) {
                                latestState.messages
                            } else {
                                updatedMessages
                            }
                            val finalMessages = baseMessages + assistantMessage
                            val finalSession = (latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession)
                                .copy(messages = finalMessages)

                            dataStore.saveChatSession(finalSession)

                            _uiState.update {
                                it.copy(
                                    messages = finalMessages,
                                    currentSession = finalSession,
                                    isGenerating = false,
                                    streamingText = "",
                                )
                            }
                            val assistantMessageIndex = finalMessages.lastIndex
                            emitStEvent(StEventCatalog.MESSAGE_RECEIVED, assistantMessageIndex, "normal")
                            emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, assistantMessageIndex, "normal")
                            emitStEvent(StEventCatalog.GENERATION_ENDED, finalMessages.size)
                            emitStEvent(StEventCatalog.GENERATE_AFTER_DATA, finalMessages.size)
                        }
                        is GenerateChunk.Failed -> {
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    error = "生成失败：${chunk.error.message}",
                                )
                            }
                            emitStEvent(StEventCatalog.GENERATION_STOPPED)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // stopGeneration owns the interrupted-message state update.
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = "出错了：${e.message}",
                    )
                }
                emitStEvent(StEventCatalog.GENERATION_STOPPED)
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

        sendMessage(lastUserMessage.content, lastUserMessage.attachments)
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
            emitStEvent(StEventCatalog.MESSAGE_SWIPED, messageIndex)
            emitRenderedEventForMessage(messageIndex, updatedMessage, "swipe")
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
            emitStEvent(StEventCatalog.MESSAGE_EDITED, messageIndex)
            emitStEvent(StEventCatalog.MESSAGE_UPDATED, messageIndex)
            emitRenderedEventForMessage(messageIndex, updatedMessage, "edit")
        }

        if (message.role == MessageRole.User) {
            val trimmedMessages = messages.subList(0, messageIndex + 1).toList()
            _uiState.update {
                it.copy(
                    messages = trimmedMessages,
                    currentSession = updatedSession.copy(messages = trimmedMessages),
                )
            }
            sendMessage(newContent, message.attachments)
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
            emitStEvent(StEventCatalog.MESSAGE_DELETED, messageIndex)
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
                val partialMessageIndex = updatedMessages.lastIndex
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
                    emitStEvent(StEventCatalog.MESSAGE_RECEIVED, partialMessageIndex, "interrupted")
                    emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, partialMessageIndex, "interrupted")
                    emitStEvent(StEventCatalog.GENERATION_STOPPED)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                    )
                }
                viewModelScope.launch {
                    emitStEvent(StEventCatalog.GENERATION_STOPPED)
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                )
            }
            viewModelScope.launch {
                emitStEvent(StEventCatalog.GENERATION_STOPPED)
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
                emitChatChanged(newSession)
                emitRenderedEventsForMessages(newSession.messages)
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
                emitChatChanged(session)
                emitRenderedEventsForMessages(session.messages)
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
        viewModelScope.launch {
            emitStEvent(StEventCatalog.SETTINGS_UPDATED, "preset")
        }
    }

    fun selectPersona(personaId: String) {
        val persona = _uiState.value.personas.firstOrNull { it.id == personaId } ?: return
        _uiState.update { it.copy(selectedPersona = persona) }
        viewModelScope.launch {
            emitStEvent(StEventCatalog.PERSONA_CHANGED, persona.name)
        }
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
                disabledRegexScriptIds = emptySet(),
            )
        }
        viewModelScope.launch {
            unloadCharacterTavernHelperScripts()
            emitStEvent(StEventCatalog.CHAT_CHANGED, "")
        }
    }

    private suspend fun reloadCharacterTavernHelperScripts(character: CharacterCard) {
        unloadCharacterTavernHelperScripts()

        val scriptSource = CharacterTavernHelperScripts.buildScriptSource(character)
        if (scriptSource.isBlank()) return

        val extensionId = characterScriptExtensionId(character.id)
        val manifest = ExtensionManifest(
            id = extensionId,
            name = "Character TavernHelper: ${character.name}",
            version = "character-card",
            author = "character-card",
            description = "Scripts embedded in the selected character card.",
            permissions = setOf(
                ExtensionPermission.Storage,
                ExtensionPermission.ProviderRequest,
                ExtensionPermission.Clipboard,
                ExtensionPermission.UiPanel,
            ),
        )

        runCatching {
            // Only grant safe permissions on load; ProviderRequest must be
            // requested at runtime via requestPermissionAsync so the user
            // can approve before any paid API calls happen.
            val safePerms = manifest.permissions.filter {
                it != ExtensionPermission.ProviderRequest && it != ExtensionPermission.Secrets
            }
            permissionManager.grantAll(extensionId, safePerms)
            extensionHost.load(manifest, scriptSource)
            loadedCharacterScriptExtensionId = extensionId
            emitStEvent(StEventCatalog.APP_INITIALIZED)
            emitStEvent(StEventCatalog.APP_READY)
        }.onFailure { e ->
            _uiState.update { it.copy(error = "加载角色卡脚本失败：${e.message}") }
        }
    }

    private suspend fun unloadCharacterTavernHelperScripts() {
        val extensionId = loadedCharacterScriptExtensionId ?: return
        loadedCharacterScriptExtensionId = null
        runCatching { extensionHost.unload(extensionId) }
    }

    private fun characterScriptExtensionId(characterId: String): String =
        "character-tavern-helper-" + characterId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private suspend fun emitCharacterSelected(character: CharacterCard) {
        emitStEvent(
            StEventCatalog.CHARACTER_SELECTED,
            buildJsonObject {
                put("id", character.id)
                put("name", character.name)
            },
        )
    }

    private suspend fun emitChatChanged(session: ChatSession) {
        emitStEvent(StEventCatalog.CHAT_CHANGED, session.id)
        emitStEvent(StEventCatalog.CHAT_LOADED, session.id)
        emitStEvent(StEventCatalog.WORLD_INFO_CHANGED, session.id)
    }

    private suspend fun emitRenderedEventsForMessages(messages: List<ChatMessage>) {
        messages.forEachIndexed { index, message ->
            emitRenderedEventForMessage(index, message, "load")
        }
    }

    private suspend fun emitRenderedEventForMessage(index: Int, message: ChatMessage, type: String = "normal") {
        when (message.role) {
            MessageRole.User -> emitStEvent(StEventCatalog.USER_MESSAGE_RENDERED, index)
            MessageRole.Character,
            MessageRole.Assistant -> emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, index, type)
            else -> Unit
        }
    }

    private suspend fun emitStEvent(name: String, vararg args: Any?) {
        extensionHost.emit(
            ExtensionEvent(
                name = name,
                payload = buildJsonObject {
                    putJsonArray("args") {
                        args.forEach { add(jsonElementOf(it)) }
                    }
                },
            ),
        )
    }

    private suspend fun generateTextFromExtension(options: JsonObject): JsonObject {
        val state = _uiState.value
        val character = state.selectedCharacter
            ?: throw IllegalStateException("No character is selected")
        val preset = state.selectedPreset
            ?: throw IllegalStateException("No generation preset is selected")

        val selectedProvider = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
            ?: state.selectedProvider
        val config = loadProviderConfig(selectedProvider)
        val userInput = options.stringOption("user_input", "userInput", "prompt").orEmpty()
        val shouldStream = options.booleanOption("should_stream", "shouldStream", "stream") ?: false
        val generationId = options.stringOption("generation_id", "generationId")
            ?: UUID.randomUUID().toString()

        return try {
            emitStEvent("js_generation_started", generationId)

            val promptRequest = PromptBuildRequest(
                character = character,
                persona = state.selectedPersona,
                messages = state.messages,
                worldBooks = worldBooksForCharacter(state, character, dataStore.readDisabledWorldIds()),
                preset = preset,
                userInput = userInput,
                providerType = config.providerType,
                metadata = buildPromptMetadata(state, config, preset, state.currentSession),
            )
            val promptResult = promptEngine.build(promptRequest)
            val adapter = providerRegistry.require(config.providerType)

            var accumulatedText = ""
            var finalText = ""
            adapter.streamGenerate(
                config,
                GenerateRequest(
                    prompt = promptResult,
                    preset = preset,
                    stream = shouldStream,
                ),
            ).collect { chunk ->
                when (chunk) {
                    is GenerateChunk.Delta -> {
                        accumulatedText += chunk.text
                        if (shouldStream) {
                            emitStEvent("js_stream_token_received_fully", accumulatedText, generationId)
                            emitStEvent("js_stream_token_received_incrementally", chunk.text, generationId)
                        }
                    }
                    is GenerateChunk.Completed -> {
                        finalText = chunk.text.ifBlank { accumulatedText }
                    }
                    is GenerateChunk.Failed -> {
                        throw IllegalStateException(chunk.error.message)
                    }
                }
            }

            val resultText = finalText.ifBlank { accumulatedText }
            emitStEvent(
                "js_generation_before_end",
                buildJsonObject { put("message", resultText) },
                generationId,
            )
            emitStEvent("js_generation_ended", resultText, generationId)
            emitStEvent(StEventCatalog.GENERATE_AFTER_DATA, generationId)

            buildJsonObject {
                put("text", resultText)
                put("message", resultText)
                put("content", resultText)
                put("generation_id", generationId)
            }
        } catch (e: Exception) {
            emitStEvent(StEventCatalog.GENERATION_STOPPED, generationId)
            throw e
        }
    }

    private suspend fun setChatMessageFromExtension(index: Int, field: String, value: String): Boolean {
        val state = _uiState.value
        val session = state.currentSession ?: return false
        val messages = state.messages.toMutableList()
        if (index !in messages.indices) return false

        val original = messages[index]
        val updated = when (field.lowercase()) {
            "message", "mes" -> original.withContent(value)
            "name" -> original.copy(name = value)
            "role" -> {
                val newRole = when (value.lowercase()) {
                    "user" -> MessageRole.User
                    "assistant" -> MessageRole.Assistant
                    "system" -> MessageRole.System
                    "character" -> MessageRole.Character
                    else -> original.role
                }
                original.copy(role = newRole)
            }
            "is_hidden" -> original.copy(isHidden = value.toBooleanStrictOrNull() ?: original.isHidden)
            "swipe_id" -> {
                val swipes = original.swipes.ifEmpty { listOf(original.content) }
                val swipeIndex = value.toIntOrNull()?.coerceIn(0, swipes.lastIndex) ?: original.swipeIndex
                original.copy(
                    swipeIndex = swipeIndex,
                    swipes = swipes,
                    content = swipes[swipeIndex],
                )
            }
            "extra" -> {
                val parsed = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                original.copy(metadata = parsed ?: original.metadata)
            }
            else -> original.copy(
                metadata = buildJsonObject {
                    original.metadata.forEach { (key, element) -> put(key, element) }
                    put(field, value)
                },
            )
        }

        messages[index] = updated
        val updatedSession = session.copy(messages = messages)
        _uiState.update {
            it.copy(
                messages = messages,
                currentSession = updatedSession,
            )
        }
        dataStore.saveChatSession(updatedSession)
        emitStEvent(StEventCatalog.MESSAGE_UPDATED, index)
        emitRenderedEventForMessage(index, updated, "script")
        return true
    }

    private fun ChatMessage.withContent(value: String): ChatMessage {
        val updatedSwipes = swipes.ifEmpty { listOf(content) }.toMutableList()
        val targetIndex = swipeIndex.coerceIn(0, updatedSwipes.lastIndex)
        updatedSwipes[targetIndex] = value
        return copy(
            content = value,
            swipes = updatedSwipes,
            swipeIndex = targetIndex,
        )
    }

    /**
     * Build the metadata JsonObject handed to DefaultPromptEngine.build. Feeding
     * maxContextTokens is what makes TokenBudget.fitToBudget actually trim long
     * chats; without it (the previous state — only providerType was passed),
     * generation silently overflowed the provider context window.
     *
     * groupMembers is populated for group chats and unblocks
     * applyGroupChatOrdering + the {{group}} macro.
     *
     * instructPreset / contextPreset are intentionally NOT wired here: the app
     * has no storage or UI for them yet, so there is no value to feed (applying a
     * default would silently change message formatting for everyone). Their
     * branches in DefaultPromptEngine stay dormant until preset management lands.
     */
    private suspend fun buildPromptMetadata(
        state: ChatUiState,
        config: ProviderConfig,
        preset: GenerationPreset,
        session: ChatSession?,
    ): JsonObject = buildJsonObject {
        put("providerType", config.providerType)
        preset.maxTokens?.let { put("maxContextTokens", JsonPrimitive(it)) }
        val groupId = session?.groupId
        if (!groupId.isNullOrBlank()) {
            val group = dataStore.listGroups().firstOrNull { it.id == groupId }
            if (group != null && group.memberCharacterIds.isNotEmpty()) {
                val byId = state.characters.associateBy { it.id }
                val names = group.memberCharacterIds.mapNotNull { id -> byId[id]?.name }
                if (names.isNotEmpty()) {
                    putJsonArray("groupMembers") { names.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }
        put("injectedPrompts", extensionHost.collectInjectedPrompts())
    }

    // ── Local-scope variables (chat_metadata.variables) ─────────────────
    // The extension host reaches the active chat's variables through the
    // LocalVariableBackend plugged in at init. Reads are cheap snapshots of
    // the current session metadata; writes update the session in memory and
    // persist through a debounced save so frequent /setvar calls don't rewrite
    // the entire JSONL on every keystroke.

    private fun snapshotLocalVariables(): Map<String, String> {
        val vars = _uiState.value.currentSession?.metadata?.get("variables") as? JsonObject
            ?: return emptyMap()
        return parseVariableMap(vars)
    }

    private fun updateChatVariables(transform: (MutableMap<String, String>) -> Unit): Map<String, String> {
        // Capture the transform result, then apply via _uiState.update so we
        // never clobber concurrent session changes (e.g. streaming message
        // updates) — we only re-read and rewrite the `variables` slot.
        val session = _uiState.value.currentSession
        if (session == null) {
            // Still run the transform against an empty map so callers observe
            // a consistent (empty) snapshot rather than a partial one.
            val empty = mutableMapOf<String, String>()
            transform(empty)
            return empty
        }
        val current = parseVariableMap(session.metadata["variables"] as? JsonObject)
        val mutable = current.toMutableMap()
        transform(mutable)
        val newVars = buildJsonObject {
            mutable.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        _uiState.update { state ->
            val s = state.currentSession ?: return@update state
            val newMetadata = JsonObject(s.metadata.toMutableMap().apply { put("variables", newVars) })
            state.copy(currentSession = s.copy(metadata = newMetadata))
        }
        scheduleMetadataSave()
        return mutable
    }

    private fun scheduleMetadataSave() {
        metadataSaveJob?.cancel()
        metadataSaveJob = viewModelScope.launch {
            delay(400L)
            _uiState.value.currentSession?.let { dataStore.saveChatSession(it) }
        }
    }

    private fun parseVariableMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.forEach { (k, v) ->
            out[k] = when (v) {
                is JsonPrimitive -> v.content
                is JsonObject -> v.toString()
                else -> v.toString()
            }
        }
        return out
    }

    private fun buildTavernContext(state: ChatUiState): JsonObject {
        val character = state.selectedCharacter
        val session = state.currentSession
        val personaName = state.selectedPersona?.name ?: "User"
        val characterName = character?.name ?: "Character"

        return buildJsonObject {
            put("name1", personaName)
            put("name2", characterName)
            put("chatId", session?.id ?: "")
            put("characterId", character?.id ?: "")
            put("this_chid", if (character != null) 0 else -1)
            put("groupId", session?.groupId ?: "")
            put("selected_group", session?.groupId ?: "")
            put("mainApi", state.providerConfig?.providerType ?: state.selectedProvider)
            put("main_api", state.providerConfig?.providerType ?: state.selectedProvider)
            put("onlineStatus", "connected")
            put("maxContext", state.selectedPreset?.maxTokens ?: 8192)
            put("lastMessageId", state.messages.lastIndex)
            put("chatMetadata", session?.metadata ?: buildJsonObject { })
            put("chat_metadata", session?.metadata ?: buildJsonObject { })
            putJsonArray("chat") {
                state.messages.forEachIndexed { index, message ->
                    add(message.toTavernJson(index))
                }
            }
            putJsonArray("characters") {
                character?.let { add(it.toTavernJson()) }
            }
            putJsonArray("groups") { }
            character?.let {
                put("character", it.toTavernJson())
            }
            putJsonObject("extensionPrompts") { }
            put("extensionPrompts", buildJsonObject { })
            // Extension settings map (extensions read their settings from here)
            val extSettings = extensionHost.snapshotExtensionSettings()
            put("extensionSettings", extSettings)
            put("extension_settings", extSettings)
            // Tags
            putJsonArray("tags") { }
            put("tagMap", buildJsonObject { })
            put("tag_map", buildJsonObject { })
            // OAI / text completion settings (empty defaults)
            put("chatCompletionSettings", buildJsonObject { })
            put("oai_settings", buildJsonObject { })
            put("textCompletionSettings", buildJsonObject { })
            put("powerUserSettings", buildJsonObject { })
            put("power_user", buildJsonObject { })
        }
    }

    private fun ChatMessage.toTavernJson(index: Int): JsonObject =
        buildJsonObject {
            val user = role == MessageRole.User
            val system = role == MessageRole.System
            put("id", id)
            put("index", index)
            put("name", name)
            put("mes", content)
            put("is_user", user)
            put("is_system", system)
            put("role", role.name.lowercase())
            put("send_date", createdAtMillis.toString())
            put("send_date_unix", createdAtMillis)
            put("swipe_id", swipeIndex)
            putJsonArray("swipes") {
                val values = swipes.ifEmpty { listOf(content) }
                values.forEach { add(JsonPrimitive(it)) }
            }
            put("extra", metadata)
            // ST-Prompt-Template per-swipe arrays (only when present)
            if (variables.isNotEmpty()) {
                putJsonArray("variables") { variables.forEach { add(it) } }
            }
            if (isEjsProcessed.isNotEmpty()) {
                putJsonArray("is_ejs_processed") { isEjsProcessed.forEach { add(JsonPrimitive(it)) } }
            }
            if (variablesInitialized.isNotEmpty()) {
                putJsonArray("variables_initialized") { variablesInitialized.forEach { add(JsonPrimitive(it)) } }
            }
        }

    private fun CharacterCard.toTavernJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("personality", personality)
            put("scenario", scenario)
            put("first_mes", firstMessage)
            put("mes_example", exampleMessages)
            put("avatar", avatarRelativePath ?: "")
            putJsonArray("tags") {
                tags.forEach { add(JsonPrimitive(it)) }
            }
            put("raw", raw)
            put("data", (raw["data"] as? JsonObject) ?: raw)
        }

    private fun JsonObject.stringOption(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key]
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun JsonObject.booleanOption(vararg keys: String): Boolean? {
        for (key in keys) {
            val value = this[key]
                ?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }
            if (value != null) return value
        }
        return null
    }

    private fun jsonElementOf(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value.toDouble())
            is Double -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toDouble())
            is List<*> -> buildJsonArray {
                value.forEach { add(jsonElementOf(it)) }
            }
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, element) ->
                    if (key != null) put(key.toString(), jsonElementOf(element))
                }
            }
            else -> JsonPrimitive(value.toString())
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

    private fun worldBooksForCharacter(
        state: ChatUiState,
        character: CharacterCard,
        disabledIds: Set<String>,
    ): List<WorldBook> {
        // Embedded character books are written into worlds/ at import time, so
        // state.worldBooks already contains them. Filter to only active books;
        // worlds absent from the disabled set are active (default on).
        return state.worldBooks.filter { it.id !in disabledIds }
    }

    private fun generateMessageId(): String = "msg-${UUID.randomUUID()}"
    private fun generateSessionId(): String = "sess-${UUID.randomUUID()}"
}

class ChatViewModelFactory(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val promptEngine: PromptEngine,
    private val secretStore: SecretStore,
    private val extensionHost: ExtensionHost,
    private val permissionManager: ExtensionPermissionManager,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                promptEngine = promptEngine,
                secretStore = secretStore,
                extensionHost = extensionHost,
                permissionManager = permissionManager,
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
