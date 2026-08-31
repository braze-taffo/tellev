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
import app.tellev.core.extension.MessageVariableBackend
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
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.prompt.TavernInitVariables
import app.tellev.core.prompt.PromptTemplateVariableUpdates
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.GenerationRuntimeResolver
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.presetCategoryForProvider
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tellev.util.decodeImageAsPng
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
    // Card file per character id for the character picker list (card = avatar).
    val characterAvatarFiles: Map<String, java.io.File?> = emptyMap(),
    val selectedCharacter: CharacterCard? = null,
    // The selected character's card file (PNG/WebP/JSON): the card image is
    // the avatar. Null when no character is selected or the card file is gone;
    // JSON cards fall back to the initials badge on decode failure.
    val characterAvatarFile: java.io.File? = null,
    val currentSession: ChatSession? = null,
    // Per-session chat background: chat_metadata["background"] resolved to a
    // file under st-data/backgrounds. Null = plain surface color.
    val chatBackgroundFile: java.io.File? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val selectedProvider: String = "openai-compatible",
    val providerConfig: ProviderConfig? = null,
    val personas: List<Persona> = emptyList(),
    val selectedPersona: Persona? = null,
    val worldBooks: List<WorldBook> = emptyList(),
    val disabledWorldIds: Set<String> = emptySet(),
    val presets: List<GenerationPreset> = emptyList(),
    val selectedPreset: GenerationPreset? = null,
    val sessions: List<ChatSession> = emptyList(),
    val error: String? = null,
    val isLoading: Boolean = false,
)

private data class ActiveRegeneration(
    val messageId: String,
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
    private val runtimeResolver = GenerationRuntimeResolver(dataStore, providerRegistry, secretStore)

    private var generationJob: Job? = null
    private var activeRegeneration: ActiveRegeneration? = null
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
            override fun snapshot(): Map<String, JsonElement> = snapshotLocalVariables()

            override fun update(
                transform: (MutableMap<String, JsonElement>) -> Unit,
            ): Map<String, JsonElement> = updateChatVariables(transform)
        })
        extensionHost.setMessageVariableBackend(object : MessageVariableBackend {
            override fun messageCount(): Int =
                _uiState.value.currentSession?.messages?.size ?: 0

            override fun messageVariables(index: Int): JsonObject? {
                val message = _uiState.value.currentSession?.messages?.getOrNull(index) ?: return null
                return message.variables.getOrNull(message.swipeIndex)
            }

            override fun lastIndexWithVariables(): Int {
                val messages = _uiState.value.currentSession?.messages ?: return -1
                return messages.indexOfLast { it.variables.getOrNull(it.swipeIndex) != null }
            }

            override fun replaceMessageVariables(index: Int, variables: JsonObject) {
                // Apply via _uiState.update so concurrent session changes (e.g.
                // streaming) are never clobbered — same discipline as
                // updateChatVariables. Persist through the debounced save.
                _uiState.update { state ->
                    val session = state.currentSession ?: return@update state
                    val messages = session.messages.toMutableList()
                    val message = messages.getOrNull(index) ?: return@update state
                    val vars = message.variables.toMutableList()
                    while (vars.size <= message.swipeIndex) vars.add(buildJsonObject { })
                    vars[message.swipeIndex] = variables
                    messages[index] = message.copy(variables = vars)
                    state.copy(currentSession = session.copy(messages = messages))
                }
                scheduleMetadataSave()
            }
        })
        observeCharacterChanges()
        observePresetChanges()
        observeWorldBookChanges()
        observePersonaChanges()
        observeProviderChanges()
        observeChatChanges()
        loadInitialData()
    }
    private fun observeCharacterChanges() {
        viewModelScope.launch {
            dataStore.characterChanges.collect { characterId ->
                runCatching { dataStore.listCharacters() }
                    .onSuccess { characters ->
                        _uiState.update {
                            it.copy(
                                characters = characters,
                                characterAvatarFiles = avatarFilesFor(characters),
                            )
                        }
                    }
                val selected = _uiState.value.selectedCharacter
                if (selected?.id != characterId) return@collect
                runCatching { dataStore.readCharacter(characterId) }
                    .onSuccess { refreshed ->
                        _uiState.update {
                            it.copy(
                                selectedCharacter = refreshed,
                                characterAvatarFile = characterCardFile(refreshed.id),
                            )
                        }
                        reloadCharacterTavernHelperScripts(refreshed)
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(error = "重新读取角色卡失败：${error.message}") }
                    }
            }
        }
    }

    private fun observePresetChanges() {
        viewModelScope.launch {
            dataStore.presetChanges.collect { category ->
                val state = _uiState.value
                if (presetCategoryForProvider(state.selectedProvider) != category) return@collect
                runCatching {
                    val presets = dataStore.listPresets().filter { it.category == category }
                    val selectedName = dataStore.readSelectedPresetName(category)
                    val selectedNamed = presets.firstOrNull { it.id == selectedName } ?: presets.firstOrNull()
                    val working = selectedNamed?.let { dataStore.readPreset(category, "in_use") }
                    presets to (working?.copy(id = selectedNamed!!.id, name = selectedNamed.name) ?: selectedNamed)
                }.onSuccess { (presets, selected) ->
                    _uiState.update { it.copy(presets = presets, selectedPreset = selected) }
                }.onFailure { error ->
                    _uiState.update { it.copy(error = "重新读取预设失败：${error.message}") }
                }
            }
        }
    }

    private fun observeWorldBookChanges() {
        viewModelScope.launch {
            dataStore.worldBookChanges.collect {
                refreshRuntimeState("重新读取世界书失败")
            }
        }
    }

    private fun observePersonaChanges() {
        viewModelScope.launch {
            dataStore.personaChanges.collect {
                refreshRuntimeState("重新读取用户设定失败")
            }
        }
    }

    private fun observeProviderChanges() {
        viewModelScope.launch {
            secretStore.changes.collect {
                refreshRuntimeState("重新读取服务商配置失败")
            }
        }
    }

    private fun observeChatChanges() {
        viewModelScope.launch {
            dataStore.chatChanges.collect { sessionId ->
                val state = _uiState.value
                if (state.currentSession?.id != sessionId || state.isGenerating) return@collect
                runCatching { dataStore.readChatSession(sessionId) }
                    .onSuccess { refreshed ->
                        _uiState.update {
                            if (it.currentSession?.id != sessionId || it.isGenerating) it
                            else it.copy(
                                currentSession = refreshed,
                                messages = refreshed.messages,
                                chatBackgroundFile = chatBackgroundFileFor(refreshed),
                            )
                        }
                    }
            }
        }
    }

    private suspend fun refreshRuntimeState(errorPrefix: String) {
        val selectedPersonaId = _uiState.value.selectedPersona?.id
        runCatching { runtimeResolver.resolve(selectedPersonaId) }
            .onSuccess { runtime ->
                _uiState.update {
                    it.copy(
                        selectedProvider = runtime.selectedProviderId,
                        providerConfig = runtime.providerConfig,
                        presets = runtime.presets,
                        selectedPreset = runtime.preset,
                        personas = runtime.personas,
                        selectedPersona = runtime.persona,
                        worldBooks = runtime.worldBooks,
                        disabledWorldIds = runtime.disabledWorldIds,
                    )
                }
            }
            .onFailure { error -> _uiState.update { it.copy(error = "$errorPrefix：${error.message}") } }
    }


    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                dataStore.bootstrap()

                val characters = dataStore.listCharacters()
                val runtime = runtimeResolver.resolve()

                _uiState.update {
                    it.copy(
                        characters = characters,
                        characterAvatarFiles = avatarFilesFor(characters),
                        personas = runtime.personas,
                        worldBooks = runtime.worldBooks,
                        disabledWorldIds = runtime.disabledWorldIds,
                        presets = runtime.presets,
                        selectedPreset = runtime.preset,
                        selectedPersona = runtime.persona,
                        selectedProvider = runtime.selectedProviderId,
                        providerConfig = runtime.providerConfig,
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

                // Selecting a character activates the books bound to it. That is
                // its embedded character_book *and* any external book named by
                // data.extensions.world — the way most real cards bind a
                // lorebook. Only ever force-disabling everything else meant such
                // a card activated nothing at all, and it also switched off
                // standalone books the user had enabled globally (in ST a global
                // lorebook stays on regardless of the selected character).
                //
                // Regex switches live only in the character card's
                // extensions.regex_scripts[].disabled fields.
                //
                // Read the full world-book list from the store directly rather
                // than relying on uiState.worldBooks being loaded yet, so the
                // set is correct even if the user selects a character before
                // initial data load finishes.
                val allWorldBooks = dataStore.listWorldBooks()
                val embeddedId = StDataStore.embeddedCharacterBookId(characterId)
                val ownWorldBookIds = buildSet {
                    add(embeddedId)
                    characterWorldBookNames(character).forEach { name ->
                        allWorldBooks
                            .firstOrNull { it.name.equals(name, ignoreCase = true) || it.id == name }
                            ?.let { add(it.id) }
                    }
                }
                // Another character's embedded book must not stay active across
                // a switch; standalone books keep whatever the user chose.
                val otherEmbeddedIds = allWorldBooks
                    .map { it.id }
                    .filter {
                        it.endsWith(StDataStore.EMBEDDED_CHARACTER_BOOK_SUFFIX) &&
                            it !in ownWorldBookIds
                    }
                val disabledWorldIds =
                    (dataStore.readDisabledWorldIds() + otherEmbeddedIds) - ownWorldBookIds
                dataStore.saveDisabledWorldIds(disabledWorldIds)

                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        characterAvatarFile = characterCardFile(character.id),
                        currentSession = session,
                        messages = session.messages,
                        chatBackgroundFile = chatBackgroundFileFor(session),
                        sessions = allSessions,
                        disabledWorldIds = disabledWorldIds,
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
        return sendMessageWithRole(text, attachments, MessageRole.User)
    }

    private fun sendMessageWithRole(
        text: String,
        attachments: List<Attachment>,
        messageRole: MessageRole,
        regenerationMessageId: String? = null,
        regexIsEdit: Boolean = false,
    ): Boolean {
        val messageText = text.trim()
        if (regenerationMessageId == null && messageText.isBlank() && attachments.isEmpty()) return false

        val state = _uiState.value
        if (state.isGenerating || generationJob?.isActive == true) return false
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
        val regenerationIndex = regenerationMessageId?.let { messageId ->
            state.messages.indexOfFirst { it.id == messageId }
        }
        if (regenerationMessageId != null &&
            (regenerationIndex == null || !canRegenerateResponse(state.messages, regenerationIndex))
        ) {
            _uiState.update { it.copy(error = "只能重新生成当前最后一条角色回复") }
            return false
        }

        val regenerationInputIndex = regenerationIndex?.let { targetIndex ->
            state.messages.take(targetIndex).indexOfLast { it.role == MessageRole.User }
        }
        val regenerationInput = regenerationInputIndex
            ?.takeIf { it >= 0 }
            ?.let(state.messages::get)
        if (regenerationMessageId != null && regenerationInput == null) {
            _uiState.update { it.copy(error = "找不到这条回复对应的用户消息") }
            return false
        }

        viewModelScope.launch {
            try {
                val runtime = runtimeResolver.resolve(state.selectedPersona?.id)
                val config = runtime.providerConfig
                val preset = runtime.preset
                val runtimeState = state.copy(
                    selectedProvider = runtime.selectedProviderId,
                    providerConfig = config,
                    presets = runtime.presets,
                    selectedPreset = preset,
                    personas = runtime.personas,
                    selectedPersona = runtime.persona,
                    worldBooks = runtime.worldBooks,
                    disabledWorldIds = runtime.disabledWorldIds,
                )
                _uiState.update {
                    it.copy(
                        selectedProvider = runtime.selectedProviderId,
                        providerConfig = config,
                        presets = runtime.presets,
                        selectedPreset = preset,
                        personas = runtime.personas,
                        selectedPersona = runtime.persona,
                        worldBooks = runtime.worldBooks,
                        disabledWorldIds = runtime.disabledWorldIds,
                    )
                }

                val initializedSession = session.withTavernInitVariables(
                    character = character,
                    worldBooks = runtime.activeWorldBooks,
                )
                if (initializedSession != session) {
                    dataStore.saveChatSession(initializedSession)
                    _uiState.update {
                        it.copy(
                            currentSession = initializedSession,
                            messages = initializedSession.messages,
                        )
                    }
                }

                val inputMessage = regenerationInput ?: CharacterRegexApplier.markNormalProcessed(ChatMessage(
                    id = generateMessageId(),
                    role = messageRole,
                    name = if (messageRole == MessageRole.System) "System" else runtime.persona?.name ?: "你",
                    content = CharacterRegexApplier.applyNormal(
                        text = messageText,
                        role = messageRole,
                        character = character,
                        preset = preset,
                        userName = runtime.persona?.name ?: "User",
                        depth = 0,
                        isEdit = regexIsEdit,
                    ),
                    createdAtMillis = System.currentTimeMillis(),
                    attachments = attachments,
                ))

                val isRegeneration = regenerationMessageId != null
                val baseSessionMessages = initializedSession.messages
                val updatedMessages = if (isRegeneration) baseSessionMessages else baseSessionMessages + inputMessage
                val updatedSession = initializedSession.copy(messages = updatedMessages)

                if (!isRegeneration) {
                    dataStore.saveChatSession(updatedSession)
                }
                activeRegeneration = regenerationMessageId?.let(::ActiveRegeneration)

                _uiState.update {
                    it.copy(
                        messages = updatedMessages,
                        currentSession = updatedSession,
                        isGenerating = true,
                        streamingText = "",
                        error = null,
                    )
                }
                if (!isRegeneration) {
                    val inputMessageIndex = updatedMessages.lastIndex
                    emitStEvent(StEventCatalog.MESSAGE_SENT, inputMessageIndex)
                    if (messageRole == MessageRole.User) {
                        emitStEvent(StEventCatalog.USER_MESSAGE_RENDERED, inputMessageIndex)
                    }
                }
                emitStEvent(
                    StEventCatalog.GENERATION_STARTED,
                    if (isRegeneration) "swipe" else "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                    false,
                )

                // Fire GENERATION_AFTER_COMMANDS BEFORE building the prompt so
                // extensions (e.g. MVU) can call injectPrompts and have their
                // injections picked up by buildPromptMetadata.  A short delay
                // gives the WebView's async JS event handlers time to execute
                // (evaluateJavascript dispatches but does not wait for JS).
                emitStEvent(
                    StEventCatalog.GENERATION_AFTER_COMMANDS,
                    "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                    },
                    false,
                )
                // Allow WebView JS event handlers to process the event and
                // register any injectPrompts calls before we collect them.
                delay(300)

                val promptRequest = PromptBuildRequest(
                    character = character,
                    persona = runtime.persona,
                    messages = if (isRegeneration) {
                        updatedMessages.take(regenerationInputIndex!!)
                    } else if (messageRole == MessageRole.User) {
                        promptHistoryBeforeCurrentMessage(updatedMessages, inputMessage.id)
                    } else {
                        updatedMessages
                    },
                    worldBooks = runtime.activeWorldBooks,
                    preset = preset,
                    userInput = when {
                        isRegeneration -> inputMessage.content
                        messageRole == MessageRole.User -> inputMessage.content
                        else -> ""
                    },
                    providerType = config.providerType,
                    metadata = JsonObject(buildPromptMetadata(runtimeState, config, preset, updatedSession) +
                        ("userInputNormalProcessed" to JsonPrimitive(
                            CharacterRegexApplier.isNormalProcessed(inputMessage),
                        ))),
                )

                val promptResult = buildPromptWithSessionScope(promptRequest, updatedSession)
                persistPromptTemplateVariableUpdates(
                    promptResult.promptTemplateVariableUpdates,
                    targetSessionId = updatedSession.id,
                )
                emitPromptDiagnostics(promptResult)

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
                    attachments = if (isRegeneration) inputMessage.attachments else attachments,
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
                            val rawFinalText = chunk.text.ifBlank { accumulatedText }
                            val finalText = CharacterRegexApplier.applyNormal(
                                text = rawFinalText,
                                role = MessageRole.Character,
                                character = character,
                                preset = preset,
                                userName = runtime.persona?.name ?: "User",
                                depth = 0,
                            )
                            val latestState = _uiState.value
                            val latestSession = latestState.currentSession
                            val baseMessages = if (latestSession?.id == updatedSession.id) {
                                latestState.messages
                            } else {
                                updatedMessages
                            }
                            val regeneration = activeRegeneration
                            val regeneratedIndex = regeneration?.let { active ->
                                baseMessages.indexOfFirst { it.id == active.messageId }
                            } ?: -1
                            val finalMessages = if (regeneration != null && regeneratedIndex >= 0) {
                                baseMessages.toMutableList().also { messages ->
                                    messages[regeneratedIndex] = CharacterRegexApplier.markNormalProcessed(
                                        messages[regeneratedIndex].withRegeneratedSwipe(finalText),
                                    )
                                }
                            } else {
                                baseMessages + CharacterRegexApplier.markNormalProcessed(ChatMessage(
                                    id = generateMessageId(),
                                    role = MessageRole.Character,
                                    name = character.name,
                                    content = finalText,
                                    createdAtMillis = System.currentTimeMillis(),
                                    swipes = listOf(finalText),
                                    swipeIndex = 0,
                                ))
                            }
                            val finalSession = (latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession)
                                .copy(messages = finalMessages)

                            dataStore.saveChatSession(finalSession)
                            activeRegeneration = null

                            _uiState.update {
                                it.copy(
                                    messages = finalMessages,
                                    currentSession = finalSession,
                                    isGenerating = false,
                                    streamingText = "",
                                )
                            }
                            val assistantMessageIndex = if (regeneratedIndex >= 0) regeneratedIndex else finalMessages.lastIndex
                            val eventType = if (regeneratedIndex >= 0) "swipe" else "normal"
                            emitStEvent(StEventCatalog.MESSAGE_RECEIVED, assistantMessageIndex, eventType)
                            if (regeneratedIndex >= 0) {
                                emitStEvent(StEventCatalog.MESSAGE_SWIPED, assistantMessageIndex)
                            }
                            emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, assistantMessageIndex, eventType)
                            emitStEvent(StEventCatalog.GENERATION_ENDED, finalMessages.size)
                            emitStEvent(StEventCatalog.GENERATE_AFTER_DATA, finalMessages.size)
                        }
                        is GenerateChunk.Failed -> {
                            activeRegeneration = null
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
                activeRegeneration = null
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

    fun regenerateResponse(messageId: String): Boolean {
        val state = _uiState.value
        val targetIndex = state.messages.indexOfFirst { it.id == messageId }
        if (!canRegenerateResponse(state.messages, targetIndex)) {
            _uiState.update { it.copy(error = "只能重新生成当前最后一条角色回复") }
            return false
        }
        return sendMessageWithRole(
            text = "",
            attachments = emptyList(),
            messageRole = MessageRole.User,
            regenerationMessageId = messageId,
        )
    }

    fun regenerateLastMessage(): Boolean {
        val lastResponse = _uiState.value.messages.lastOrNull() ?: return false
        return regenerateResponse(lastResponse.id)
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

        val processedContent = CharacterRegexApplier.applyNormal(
            text = newContent,
            role = message.role,
            character = state.selectedCharacter,
            preset = state.selectedPreset,
            userName = state.selectedPersona?.name ?: "User",
            depth = visibleRegexDepth(state.messages, messageIndex),
            isEdit = true,
        )
        val updatedSwipes = if (message.swipes.isNotEmpty()) {
            message.swipes.toMutableList().also {
                if (message.swipeIndex in it.indices) {
                    it[message.swipeIndex] = processedContent
                } else {
                    it.add(processedContent)
                }
            }
        } else {
            listOf(processedContent)
        }

        val updatedMessage = CharacterRegexApplier.markNormalProcessed(message.copy(
            content = processedContent,
            swipes = updatedSwipes,
            swipeIndex = if (message.swipes.isEmpty()) 0 else message.swipeIndex,
        ))
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return

        if (message.role == MessageRole.User) {
            // sendMessage owns the new user-input slot. Keep only the history
            // before the edited user message so the replacement is appended
            // exactly once and all later replies are discarded.
            val trimmedMessages = messages.take(messageIndex)
            _uiState.update {
                it.copy(
                    messages = trimmedMessages,
                    currentSession = session.copy(messages = trimmedMessages),
                )
            }
            viewModelScope.launch {
                emitStEvent(StEventCatalog.MESSAGE_EDITED, messageIndex)
                emitStEvent(StEventCatalog.MESSAGE_UPDATED, messageIndex)
                emitRenderedEventForMessage(messageIndex, updatedMessage, "edit")
            }
            sendMessageWithRole(
                text = newContent,
                attachments = message.attachments,
                messageRole = MessageRole.User,
                regexIsEdit = true,
            )
            return
        }

        val updatedSession = session.copy(messages = messages)
        _uiState.update { it.copy(messages = messages, currentSession = updatedSession) }
        viewModelScope.launch {
            dataStore.saveChatSession(updatedSession)
            emitStEvent(StEventCatalog.MESSAGE_EDITED, messageIndex)
            emitStEvent(StEventCatalog.MESSAGE_UPDATED, messageIndex)
            emitRenderedEventForMessage(messageIndex, updatedMessage, "edit")
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
        val regeneration = activeRegeneration
        activeRegeneration = null
        if (state.streamingText.isNotEmpty()) {
            val processedPartial = CharacterRegexApplier.applyNormal(
                text = state.streamingText,
                role = MessageRole.Character,
                character = state.selectedCharacter,
                preset = state.selectedPreset,
                userName = state.selectedPersona?.name ?: "User",
                depth = 0,
            )
            if (regeneration != null) {
                val targetIndex = state.messages.indexOfFirst { it.id == regeneration.messageId }
                if (targetIndex >= 0) {
                    val updatedMessages = state.messages.toMutableList().also { messages ->
                        messages[targetIndex] = CharacterRegexApplier.markNormalProcessed(
                            messages[targetIndex].withRegeneratedSwipe(processedPartial),
                        )
                    }
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
                            emitStEvent(StEventCatalog.MESSAGE_RECEIVED, targetIndex, "interrupted")
                            emitStEvent(StEventCatalog.MESSAGE_SWIPED, targetIndex)
                            emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, targetIndex, "interrupted")
                            emitStEvent(StEventCatalog.GENERATION_STOPPED)
                        }
                        return
                    }
                }
            }
            val character = state.selectedCharacter
            val partialMessage = CharacterRegexApplier.markNormalProcessed(ChatMessage(
                id = generateMessageId(),
                role = MessageRole.Character,
                name = character?.name ?: "助手",
                content = processedPartial,
                createdAtMillis = System.currentTimeMillis(),
                swipes = listOf(processedPartial),
                swipeIndex = 0,
                metadata = buildJsonObject { put("interrupted", true) },
            ))
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
                        chatBackgroundFile = null,
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
                        chatBackgroundFile = chatBackgroundFileFor(session),
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

    /**
     * Sets a per-session chat background (SillyTavern chat_metadata.background
     * semantics). The picked image is copied into st-data/backgrounds as a
     * downsampled PNG — content URIs from the photo picker are not reliably
     * readable after a restart, an owned file is.
     */
    fun setChatBackground(imageBytes: ByteArray) {
        viewModelScope.launch {
            try {
                val pngBytes = withContext(Dispatchers.IO) { decodeImageAsPng(imageBytes) }
                    ?: error("无法解析图片")
                val session = _uiState.value.currentSession ?: error("当前没有可用会话")
                val rel = "backgrounds/${session.id}.png"
                withContext(Dispatchers.IO) {
                    val dir = dataStore.layout.backgrounds.toFile()
                    dir.mkdirs()
                    java.io.File(dir, "${session.id}.png").writeBytes(pngBytes)
                }
                val updated = session.copy(
                    metadata = buildJsonObject {
                        session.metadata.forEach { (key, value) -> put(key, value) }
                        put("background", rel)
                    },
                )
                dataStore.saveChatSession(updated)
                _uiState.update {
                    if (it.currentSession?.id != updated.id) it
                    else it.copy(currentSession = updated, chatBackgroundFile = dataStore.layout.root.resolve(rel).toFile())
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "设置聊天背景失败：${e.message}") }
            }
        }
    }

    fun clearChatBackground() {
        viewModelScope.launch {
            try {
                val session = _uiState.value.currentSession ?: return@launch
                val rel = session.metadata.stringOption("background")
                val updated = session.copy(
                    metadata = buildJsonObject {
                        session.metadata.forEach { (key, value) ->
                            if (key != "background") put(key, value)
                        }
                    },
                )
                dataStore.saveChatSession(updated)
                _uiState.update {
                    if (it.currentSession?.id != updated.id) it
                    else it.copy(currentSession = updated, chatBackgroundFile = null)
                }
                if (rel != null) {
                    withContext(Dispatchers.IO) {
                        dataStore.layout.root.resolve(rel).toFile().takeIf { it.exists() }?.delete()
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "清除聊天背景失败：${e.message}") }
            }
        }
    }

    /** Resolves chat_metadata["background"] (stored relative to the st-data
     *  root) into an existing file, or null when the session has none. */
    private fun chatBackgroundFileFor(session: ChatSession?): java.io.File? {
        val rel = session?.metadata?.stringOption("background") ?: return null
        return dataStore.layout.root.resolve(rel).toFile().takeIf { it.exists() }
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
        viewModelScope.launch {
            runCatching {
                dataStore.selectPreset(preset.category, preset.id)
                _uiState.update { it.copy(selectedPreset = preset) }
                emitStEvent(StEventCatalog.SETTINGS_UPDATED, "preset")
            }.onFailure { error ->
                _uiState.update { it.copy(error = "加载预设失败：${error.message}") }
            }
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

    fun tavernMessageVariablesJson(): String {
        val snapshot = promptEngine.snapshotPromptTemplateVariables()
        val local = (_uiState.value.currentSession?.metadata?.get("variables") as? JsonObject)
            ?: snapshot.local
        val merged = buildJsonObject {
            snapshot.global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
        }
        return decodeEmbeddedJsonValues(merged).toString()
    }

    fun handleTavernMessageRequest(
        operation: String,
        payloadJson: String,
        onSetInput: (String) -> Unit,
        callback: (Boolean, String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val payload = (Json.parseToJsonElement(payloadJson) as? JsonObject) ?: buildJsonObject { }
                when (operation) {
                    "getChatMessages" -> {
                        val state = _uiState.value
                        val requested = payload["messageId"]?.jsonPrimitive?.content?.toIntOrNull()
                        buildJsonArray {
                            state.messages.forEachIndexed { index, message ->
                                if (requested == null || requested == index) add(message.toTavernJson(index))
                            }
                        }
                    }
                    "setChatMessage" -> {
                        val index = payload["messageId"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: error("消息索引无效")
                        val content = payload["message"]?.jsonPrimitive?.content.orEmpty()
                        val options = payload["options"] as? JsonObject ?: buildJsonObject { }
                        val state = _uiState.value
                        val session = state.currentSession ?: error("当前没有会话")
                        require(index in state.messages.indices) { "消息不存在：$index" }
                        val messages = state.messages.toMutableList()
                        val original = messages[index]
                        val requestedSwipe = options["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull()
                        val updated = setTavernMessageSwipe(original, content, requestedSwipe)
                        messages[index] = updated
                        val updatedSession = session.copy(messages = messages)
                        // Update state first, then persist the freshest session
                        // snapshot: saving from a pre-suspension snapshot could
                        // clobber concurrent writes during the IO gap.
                        _uiState.update { it.copy(messages = messages, currentSession = updatedSession) }
                        dataStore.saveChatSession(_uiState.value.currentSession ?: updatedSession)
                        emitStEvent(StEventCatalog.MESSAGE_SWIPED, index)
                        emitStEvent(StEventCatalog.MESSAGE_UPDATED, index)
                        emitRenderedEventForMessage(index, updated, "swipe")
                        buildJsonObject { put("ok", true); put("message_id", index); put("swipe_id", updated.swipeIndex) }
                    }
                    "getLorebooks" -> kotlinx.serialization.json.buildJsonArray {
                        dataStore.listWorldBooks().forEach { add(JsonPrimitive(it.name)) }
                    }
                    "createLorebook" -> {
                        val name = payload["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        require(name.isNotEmpty()) { "世界书名称不能为空" }
                        val existing = dataStore.listWorldBooks().firstOrNull { it.name == name || it.id == name }
                        val book = existing ?: WorldBook(
                            id = "wb_${UUID.randomUUID()}",
                            name = name,
                            entries = emptyList(),
                        ).also { dataStore.saveWorldBook(it) }
                        buildJsonObject {
                            put("id", book.id)
                            put("name", book.name)
                        }
                    }
                    "createLorebookEntry" -> {
                        val name = payload["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val entryJson = payload["entry"] as? JsonObject
                            ?: error("世界书条目格式无效")
                        val book = dataStore.listWorldBooks().firstOrNull { it.name == name || it.id == name }
                            ?: error("世界书不存在：$name")
                        val entry = tavernWorldBookEntry(entryJson, book.entries.size.toString())
                        val updated = book.copy(entries = book.entries + entry)
                        dataStore.saveWorldBook(updated)
                        buildJsonObject {
                            put("id", entry.id)
                            put("ok", true)
                        }
                    }
                    "triggerSlash" -> {
                        val script = payload["script"]?.jsonPrimitive?.content.orEmpty()
                        val action = parseTavernMessageSlashCommand(script)
                        action.setInputText?.let(onSetInput)
                        if (action.systemText != null) {
                            refreshTavernMessageWorldBooks()
                            action.deleteMessageIndex?.let { index ->
                                if (removeTavernMessageWithoutSaving(index)) {
                                    emitStEvent(StEventCatalog.MESSAGE_DELETED, index)
                                }
                            }
                        } else {
                            action.deleteMessageIndex?.let(::deleteMessage)
                        }
                        val handled = when {
                            action.systemText != null -> sendMessageWithRole(
                                action.systemText,
                                emptyList(),
                                MessageRole.System,
                            )
                            action.sendText != null -> sendMessage(action.sendText)
                            action.setInputText != null -> true
                            action.deleteMessageIndex != null -> true
                            else -> false
                        }
                        if (action.systemText != null && !handled) {
                            _uiState.value.currentSession?.let { dataStore.saveChatSession(it) }
                        }
                        if (handled) {
                            buildJsonObject {
                                put("handled", true)
                                put("pipe", "")
                            }
                        } else {
                            // The regex matcher above only recognises the four
                            // chat-mutating shapes it has to run on the UI
                            // thread. Everything else — /setvar, /run, closures,
                            // pipes, the whole rest of STScript — goes to the
                            // real engine instead of silently doing nothing.
                            val engineResult = extensionHost.executeStScript(script)
                            buildJsonObject {
                                put("handled", engineResult.handled)
                                put("pipe", engineResult.output)
                                engineResult.metadata["isError"]?.let { put("isError", it) }
                                engineResult.metadata["errorMessage"]?.let { put("errorMessage", it) }
                            }
                        }
                    }
                    else -> error("不支持的消息渲染操作：$operation")
                }
            }.onSuccess { result ->
                callback(true, result.toString())
            }.onFailure { error ->
                callback(
                    false,
                    buildJsonObject { put("error", error.message ?: "消息渲染操作失败") }.toString(),
                )
            }
        }
    }

    private fun removeTavernMessageWithoutSaving(messageIndex: Int): Boolean {
        val state = _uiState.value
        val session = state.currentSession ?: return false
        if (messageIndex !in state.messages.indices) return false
        val messages = state.messages.toMutableList().also { it.removeAt(messageIndex) }
        val updatedSession = session.copy(messages = messages)
        _uiState.update {
            it.copy(
                messages = messages,
                currentSession = updatedSession,
            )
        }
        return true
    }

    private suspend fun refreshTavernMessageWorldBooks() {
        val worlds = dataStore.listWorldBooks()
        _uiState.update { it.copy(worldBooks = worlds) }
    }

    /** Resolves the character's card file in the store, mirroring the
     *  png/webp/json variant lookup FileStDataStore uses. */
    private fun characterCardFile(characterId: String): java.io.File? =
        listOf("png", "webp", "json").firstNotNullOfOrNull { extension ->
            dataStore.layout.characters.resolve("$characterId.$extension").toFile()
                .takeIf { it.exists() }
        }

    private fun avatarFilesFor(characters: List<CharacterSummary>): Map<String, java.io.File?> =
        characters.associate { it.id to characterCardFile(it.id) }

    fun deselectCharacter() {
        _uiState.update {
            it.copy(
                selectedCharacter = null,
                characterAvatarFile = null,
                currentSession = null,
                chatBackgroundFile = null,
                messages = emptyList(),
                sessions = emptyList(),
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
        val runtime = runtimeResolver.resolve(state.selectedPersona?.id)
        val preset = runtime.preset
        val config = runtime.providerConfig
        val runtimeState = state.copy(
            selectedProvider = runtime.selectedProviderId,
            providerConfig = config,
            presets = runtime.presets,
            selectedPreset = preset,
            personas = runtime.personas,
            selectedPersona = runtime.persona,
            worldBooks = runtime.worldBooks,
            disabledWorldIds = runtime.disabledWorldIds,
        )
        _uiState.update { current ->
            current.copy(
                selectedProvider = runtime.selectedProviderId,
                providerConfig = config,
                presets = runtime.presets,
                selectedPreset = preset,
                personas = runtime.personas,
                selectedPersona = runtime.persona,
                worldBooks = runtime.worldBooks,
                disabledWorldIds = runtime.disabledWorldIds,
            )
        }
        val userInput = options.stringOption("user_input", "userInput", "prompt").orEmpty()
        val shouldStream = options.booleanOption("should_stream", "shouldStream", "stream") ?: false
        val generationId = options.stringOption("generation_id", "generationId")
            ?: UUID.randomUUID().toString()

        return try {
            emitStEvent("js_generation_started", generationId)

            val promptRequest = PromptBuildRequest(
                character = character,
                persona = runtime.persona,
                messages = state.messages,
                worldBooks = runtime.activeWorldBooks,
                preset = preset,
                userInput = userInput,
                providerType = config.providerType,
                metadata = buildPromptMetadata(runtimeState, config, preset, state.currentSession),
            )
            val promptResult = buildPromptWithSessionScope(promptRequest, state.currentSession)
            persistPromptTemplateVariableUpdates(
                promptResult.promptTemplateVariableUpdates,
                targetSessionId = state.currentSession?.id,
            )
            emitPromptDiagnostics(promptResult)
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
            "data" -> {
                // js-slash-runner setChatMessage semantics: fv.data replaces the
                // message's variables at the current swipe (variables[swipe_id]).
                val parsed = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                    ?: return false
                val vars = original.variables.toMutableList()
                while (vars.size <= original.swipeIndex) vars.add(buildJsonObject { })
                vars[original.swipeIndex] = parsed
                original.copy(variables = vars)
            }
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
     * instructPreset IS wired (see below) and reads the instruct/ directory.
     *
     * contextPreset is still NOT wired: there is no storage or UI for context
     * templates yet, so there is no value to feed and applying a default would
     * silently change the system prompt for everyone. That means
     * [app.tellev.core.prompt.ContextTemplate] and the `contextPreset` branch
     * of DefaultPromptEngine are unreachable in production today — do not read
     * the presence of that code as "story strings are supported".
     */
    private suspend fun buildPromptMetadata(
        state: ChatUiState,
        config: ProviderConfig,
        preset: GenerationPreset,
        session: ChatSession?,
    ): JsonObject {
        val variableSnapshot = promptEngine.snapshotPromptTemplateVariables()
        val localVariables = (session?.metadata?.get("variables") as? JsonObject)
            ?: variableSnapshot.local
        val globalVariables = variableSnapshot.global
        val mergedVariables = mergePromptTemplateVariables(globalVariables, localVariables)
        val worldInfoSettings = dataStore.readWorldInfoSettings()
        val promptSettings = dataStore.readPromptSettings()

        return buildJsonObject {
        put("providerType", config.providerType)
        put("worldInfoRecursive", JsonPrimitive(worldInfoSettings.recursive))
        put("worldInfoMaxRecursionSteps", JsonPrimitive(worldInfoSettings.maxRecursionSteps))
        put("worldInfoScanDepth", JsonPrimitive(worldInfoSettings.scanDepth))
        put("preferCharacterPrompt", JsonPrimitive(promptSettings.preferCharacterPrompt))
        put("preferCharacterJailbreak", JsonPrimitive(promptSettings.preferCharacterJailbreak))
        // Instruct mode: load preset and pass to PromptEngine when enabled.
        if (promptSettings.instructEnabled) {
            val instructPreset = if (promptSettings.instructPresetName.isNotBlank()) {
                dataStore.readInstructPreset(promptSettings.instructPresetName)
            } else {
                DEFAULT_CHATML_INSTRUCT
            }
            instructPreset?.let { put("instructPreset", it) }
        }
        put("maxContextTokens", JsonPrimitive(
            preset.maxContextTokens ?: defaultContextTokens(),
        ))
        config.model?.takeIf { it.isNotBlank() }?.let { put("modelName", JsonPrimitive(it)) }
        put(
            "maxResponseTokens",
            JsonPrimitive(
                preset.maxCompletionTokens
                    ?: preset.maxTokens
                    ?: defaultResponseTokens(),
            ),
        )
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
        put("promptTemplateLocalVariables", localVariables)
        put("promptTemplateGlobalVariables", globalVariables)
        // Keep the legacy merged key for processors/extensions that still
        // consume the pre-v1.2 metadata shape. LOCAL wins over GLOBAL.
        put("promptTemplateVariables", mergedVariables)
        }
    }

    // ── Local-scope variables (chat_metadata.variables) ─────────────────
    // The extension host reaches the active chat's variables through the
    // LocalVariableBackend plugged in at init. Reads are cheap snapshots of
    // the current session metadata; writes update the session in memory and
    // persist through a debounced save so frequent /setvar calls don't rewrite
    // the entire JSONL on every keystroke.

    private fun snapshotLocalVariables(): Map<String, JsonElement> {
        val vars = _uiState.value.currentSession?.metadata?.get("variables") as? JsonObject
            ?: return emptyMap()
        return LinkedHashMap(vars)
    }

    private fun updateChatVariables(
        transform: (MutableMap<String, JsonElement>) -> Unit,
    ): Map<String, JsonElement> {
        // Capture the transform result, then apply via _uiState.update so we
        // never clobber concurrent session changes (e.g. streaming message
        // updates) — we only re-read and rewrite the `variables` slot.
        val session = _uiState.value.currentSession
        if (session == null) {
            // Still run the transform against an empty map so callers observe
            // a consistent (empty) snapshot rather than a partial one.
            val empty = mutableMapOf<String, JsonElement>()
            transform(empty)
            return empty
        }
        // Values stay JsonElement end to end. Round-tripping them through
        // String collapsed every nested object a variable card had written
        // (and any EJS-authored structure) into an opaque JSON string.
        val mutable = LinkedHashMap<String, JsonElement>(
            (session.metadata["variables"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
        transform(mutable)
        val newVars = buildJsonObject {
            mutable.forEach { (k, v) -> put(k, v) }
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

    private fun buildPromptWithSessionScope(
        request: PromptBuildRequest,
        session: ChatSession?,
    ): PromptBuildResult {
        val initialLocal = (session?.metadata?.get("variables") as? JsonObject)
            ?: JsonObject(emptyMap())
        val backend = TrackingPromptLocalBackend(LinkedHashMap(initialLocal))
        val result = promptEngine.buildWithLocalVariableBackend(request, backend)
        val templateLocal = result.promptTemplateVariableUpdates.local
        val combinedLocal = when {
            templateLocal != null -> applyTopLevelVariableDiff(
                base = backend.applyChanges(initialLocal),
                before = initialLocal,
                after = templateLocal,
            )
            backend.hasChanges() -> backend.applyChanges(initialLocal)
            else -> null
        }
        return result.copy(
            promptTemplateVariableUpdates = result.promptTemplateVariableUpdates.copy(
                local = combinedLocal,
            ),
        )
    }

    private fun applyTopLevelVariableDiff(
        base: JsonObject,
        before: JsonObject,
        after: JsonObject,
    ): JsonObject = JsonObject(base.toMutableMap().apply {
        (before.keys + after.keys).forEach { key ->
            if (before[key] != after[key]) {
                if (key in after) put(key, after.getValue(key)) else remove(key)
            }
        }
    })

    private class TrackingPromptLocalBackend(initial: Map<String, JsonElement>) : LocalVariableBackend {
        private val initialValues = LinkedHashMap(initial)
        private val values = LinkedHashMap(initial)

        override fun snapshot(): Map<String, JsonElement> = values.toMap()

        override fun update(
            transform: (MutableMap<String, JsonElement>) -> Unit,
        ): Map<String, JsonElement> {
            transform(values)
            return snapshot()
        }

        fun hasChanges(): Boolean = values != initialValues

        fun applyChanges(base: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
            val currentValues = this@TrackingPromptLocalBackend.values
            (initialValues.keys + currentValues.keys).forEach { key ->
                if (initialValues[key] != currentValues[key]) {
                    currentValues[key]?.let { put(key, it) } ?: remove(key)
                }
            }
        })
    }

    private suspend fun persistPromptTemplateVariableUpdates(
        updates: PromptTemplateVariableUpdates,
        targetSessionId: String?,
    ) {
        updates.local?.let { localVariables ->
            if (targetSessionId != null) {
                var activeSessionUpdate: ChatSession? = null
                _uiState.update { state ->
                    val current = state.currentSession
                    if (current?.id != targetSessionId) state
                    else {
                        val updated = current.withPromptTemplateVariables(localVariables)
                        activeSessionUpdate = updated
                        state.copy(currentSession = updated)
                    }
                }
                // Persist the exact originating session even if a chat switch
                // races with the state update above.
                val source = activeSessionUpdate
                    ?: runCatching { dataStore.readChatSession(targetSessionId) }.getOrNull()
                source?.let {
                    dataStore.saveChatSession(it.withPromptTemplateVariables(localVariables))
                }
            }
        }
        updates.global?.let(promptEngine::persistGlobalPromptTemplateVariables)
    }

    private fun mergePromptTemplateVariables(global: JsonObject, local: JsonObject): JsonObject =
        buildJsonObject {
            global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
        }

    private suspend fun emitPromptDiagnostics(result: PromptBuildResult) {
        extensionHost.reportHostEvent(
            ExtensionEvent(
                name = "prompt_diagnostics",
                payload = buildJsonObject {
                    val estimated = result.diagnostics.estimatedTokenCount
                    if (estimated == null) {
                        put("estimatedTokenCount", JsonNull)
                    } else {
                        put("estimatedTokenCount", estimated)
                    }
                    putJsonArray("activatedWorldEntryIds") {
                        result.diagnostics.activatedWorldEntryIds.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("warnings") {
                        result.diagnostics.warnings.forEach { add(JsonPrimitive(it)) }
                    }
                    putJsonArray("messages") {
                        result.messages.forEach { message ->
                            add(
                                buildJsonObject {
                                    put("role", message.role.name.lowercase())
                                    if (message.name == null) {
                                        put("name", JsonNull)
                                    } else {
                                        put("name", message.name)
                                    }
                                    put("content", message.content)
                                },
                            )
                        }
                    }
                },
            ),
        )
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

    /**
     * External lorebooks a card binds by name: `data.extensions.world` is the
     * field SillyTavern writes when you pick a lorebook for a character, and a
     * bare top-level `world` shows up on older exports.
     */
    private fun characterWorldBookNames(card: CharacterCard): List<String> =
        app.tellev.core.model.CharacterWorldBinding.linkedWorldBookNames(card)

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
            put("mainApi", state.providerConfig?.providerType ?: ProviderConfigPersistence.adapterIdFor(state.selectedProvider))
            put("main_api", state.providerConfig?.providerType ?: ProviderConfigPersistence.adapterIdFor(state.selectedProvider))
            put("onlineStatus", "connected")
            put(
                "maxContext",
                state.selectedPreset?.maxContextTokens
                    ?: defaultContextTokens(),
            )
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
            rawHeader = buildJsonObject {
                put("user_name", _uiState.value.selectedPersona?.name ?: "User")
                put("character_name", character.name)
            },
        )

        val initialized = session.withTavernInitVariables(
            character = character,
            worldBooks = listOfNotNull(character.characterBook),
        )
        dataStore.saveChatSession(initialized)
        return initialized
    }

    private fun ChatSession.withTavernInitVariables(
        character: CharacterCard,
        worldBooks: List<WorldBook>,
    ): ChatSession {
        if (messages.any { message ->
                message.variables.getOrNull(message.swipeIndex)?.containsKey("stat_data") == true
            }
        ) {
            return this
        }
        val initial = TavernInitVariables.extractMessageVariables(
            if (worldBooks.isNotEmpty()) worldBooks else listOfNotNull(character.characterBook),
        ) ?: return this
        if (messages.isEmpty()) return this

        val targetIndex = messages.indexOfFirst {
            it.role == MessageRole.Character || it.role == MessageRole.Assistant
        }.takeIf { it >= 0 } ?: 0
        val updatedMessages = messages.toMutableList()
        val message = updatedMessages[targetIndex]
        val swipeCount = message.swipes.ifEmpty { listOf(message.content) }.size
            .coerceAtLeast(message.swipeIndex + 1)
        val variables = MutableList(swipeCount) { initial }
        val initialized = MutableList(swipeCount) { true }
        updatedMessages[targetIndex] = message.copy(
            variables = variables,
            variablesInitialized = initialized,
        )
        return copy(messages = updatedMessages)
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

    private fun defaultContextTokens(): Int = 1_000_000

    private fun defaultResponseTokens(): Int = 128 * 1_024

    private fun generateMessageId(): String = "msg-${UUID.randomUUID()}"
    private fun generateSessionId(): String = "sess-${UUID.randomUUID()}"

    companion object {
        /** Built-in ChatML instruct preset used when no preset file is selected. */
        private val DEFAULT_CHATML_INSTRUCT: JsonObject = buildJsonObject {
            put("name", "ChatML")
            put("input_sequence", "<|im_start|>user\n")
            put("output_sequence", "<|im_start|>assistant\n")
            put("last_output_sequence", "")
            put("first_output_sequence", "")
            put("first_input_sequence", "")
            put("last_input_sequence", "")
            put("system_sequence", "<|im_start|>system\n")
            put("system_suffix", "<|im_end|>\n")
            put("input_suffix", "<|im_end|>\n")
            put("output_suffix", "<|im_end|>\n")
            put("stop_sequence", "<|im_end|>")
            put("wrap", true)
            put("macro", true)
            put("names", false)
            put("names_force_groups", false)
            put("activation_regex", "")
            put("system_same_as_user", false)
            put("skip_examples", false)
        }
    }
}

/**
 * The prompt engine owns the current user-input slot. Once the message has
 * been saved to the chat, remove that exact message from the history handed
 * to the engine so it is not sent twice. Matching by id avoids dropping a
 * legitimate earlier user message that happens to have identical text.
 */
internal fun promptHistoryBeforeCurrentMessage(messages: List<ChatMessage>, currentMessageId: String): List<ChatMessage> =
    if (messages.lastOrNull()?.id == currentMessageId) messages.dropLast(1) else messages

internal fun canRegenerateResponse(messages: List<ChatMessage>, messageIndex: Int): Boolean {
    if (messageIndex !in messages.indices || messageIndex != messages.lastIndex) return false
    val message = messages[messageIndex]
    if (message.role != MessageRole.Character && message.role != MessageRole.Assistant) return false
    return messages.take(messageIndex).any { it.role == MessageRole.User }
}

internal fun visibleRegexDepth(messages: List<ChatMessage>, messageIndex: Int): Int =
    messages.drop(messageIndex + 1).count {
        !it.isHidden && it.role != MessageRole.System && it.role != MessageRole.Tool
    }

internal fun ChatMessage.withRegeneratedSwipe(newContent: String): ChatMessage {
    val previousSwipes = swipes.ifEmpty { listOf(content) }
    return copy(
        content = newContent,
        swipes = previousSwipes + newContent,
        swipeIndex = previousSwipes.size,
    )
}

internal fun ChatSession.withPromptTemplateVariables(variables: JsonObject): ChatSession =
    copy(
        metadata = JsonObject(metadata.toMutableMap().apply {
            put("variables", variables)
        }),
    )

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
