package app.tellev.feature.chat

import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.withGenerationReasoning
import app.tellev.core.model.preserveReasoningSwipe
import app.tellev.core.model.selectReasoningSwipe
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
import app.tellev.core.extension.RuntimeToken
import app.tellev.core.extension.RuntimeWriteCoordinator
import app.tellev.core.extension.StorageOwner
import app.tellev.core.extension.MutationRequest
import app.tellev.core.extension.CommitReceipt
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.serialization.json.JsonArray
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
    val runtimeGeneration: Long = -1,
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
    val streamingReasoning: String = "",
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
    private var interruptionJob: Job? = null
    private var characterScriptJob: Job? = null
    private var activeRegeneration: ActiveRegeneration? = null
    @Volatile
    private var loadedCharacterScriptExtensionId: String? = null
    private val sessionWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionWriteLock = Any()
    private val sessionTransitions = Mutex()
    @Volatile private var runtimeToken: RuntimeToken? = null
    private val sessionWrites = RuntimeWriteCoordinator<ChatSession>(sessionWriteScope) { request, desired ->
        val base = Json.decodeFromJsonElement(ChatSession.serializer(), request.payload.getValue("base"))
        val committed = dataStore.commitChatMutation(base, desired, request.baseRevision, request.operationId)
        CommitReceipt(request.operationId, committed.storageRevision, true)
    }

    init {
        extensionHost.setContextProvider(object : ExtensionContextProvider {
            override fun snapshot(): JsonObject = buildTavernContext(_uiState.value)

            override suspend fun setChatMessage(index: Int, field: String, value: String): Boolean =
                setChatMessageFromExtension(index, field, value)

            override suspend fun setChatMessages(messages: JsonArray, options: JsonObject): Boolean {
                val session = _uiState.value.currentSession ?: return false
                val updated = app.tellev.core.extension.applyTavernChatMessages(session.messages, messages)
                val next = session.copy(messages = updated)
                persistSessionMutation(session, next)
                return true
            }

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
                return message.variables.getOrNull(message.swipeIndex) as? JsonObject
            }

            override fun lastIndexWithVariables(): Int {
                val messages = _uiState.value.currentSession?.messages ?: return -1
                return messages.indexOfLast { it.variables.getOrNull(it.swipeIndex) is JsonObject }
            }

            override fun replaceMessageVariables(index: Int, variables: JsonObject) {
                synchronized(sessionWriteLock) {
                    val session = _uiState.value.currentSession ?: error("当前没有会话")
                    val messages = session.messages.toMutableList()
                    val message = messages.getOrNull(index) ?: error("消息不存在：$index")
                    val vars = message.variables.toMutableList()
                    while (vars.size <= message.swipeIndex) vars.add(buildJsonObject { })
                    vars[message.swipeIndex] = variables
                    messages[index] = message.copy(variables = vars)
                    scheduleMetadataSave(session, session.copy(messages = messages))
                }
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
                runCatching {
                        val refreshed = dataStore.readChatSession(sessionId)
                        val visible = synchronized(sessionWriteLock) {
                            if (runtimeToken?.sessionId == sessionId) sessionWrites.observePersisted(StorageOwner("chat", sessionId), refreshed, refreshed.storageRevision).value
                            else refreshed
                        }
                        _uiState.update {
                            if (it.currentSession?.id != sessionId || it.isGenerating) it
                            else it.copy(
                                currentSession = visible,
                                messages = visible.messages,
                                chatBackgroundFile = chatBackgroundFileFor(visible),
                            )
                        }
                    }.onFailure { error ->
                        _uiState.update { it.copy(error = "读取会话提交状态失败：${error.message}") }
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
          sessionTransitions.withLock {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                retireSessionRuntime()
                // 读卡/会话/世界书的 IO 与绑定集合运算搬到 Default，避免与首帧
                // 绘制抢 Main。FileStDataStore 内部虽已是 IO，但 withCharacterGreetingSwipes
                // 与世界书集合运算是纯 CPU，大卡（1.4MB/数百条目）上不可忽略。
                data class Selection(
                    val character: CharacterCard,
                    val session: ChatSession,
                    val allSessions: List<ChatSession>,
                    val disabledWorldIds: Set<String>,
                )
                val selection = withContext(Dispatchers.Default) {
                    val character = dataStore.readCharacter(characterId)
                    val sessions = dataStore.listChatSessions(characterId = characterId)

                    val session = if (sessions.isNotEmpty()) {
                        sessions.first().withCharacterGreetingSwipes(character).let { upgraded ->
                            if (upgraded != sessions.first()) {
                                dataStore.commitChatMutation(sessions.first(), upgraded)
                            } else upgraded
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
                    Selection(character, session, allSessions, disabledWorldIds)
                }
                val character = selection.character
                val session = selection.session
                activateSessionWrites(session)

                _uiState.update {
                    it.copy(
                        selectedCharacter = character,
                        characterAvatarFile = characterCardFile(character.id),
                        currentSession = session,
                        messages = session.messages,
                        chatBackgroundFile = chatBackgroundFileFor(session),
                        sessions = selection.allSessions,
                        disabledWorldIds = selection.disabledWorldIds,
                        isLoading = false,
                    )
                }
                // 角色脚本加载（含 WebView 创建与 ready 等待）不再挡首帧：先让
                // 聊天 UI 可绘制，再后台加载；APP_INITIALIZED/APP_READY 随完成异步发。
                val scriptJob = viewModelScope.launch(Dispatchers.Default) {
                    reloadCharacterTavernHelperScripts(character)
                }
                characterScriptJob = scriptJob
                emitCharacterSelected(character)
                emitChatChanged(session)
                // rendered 事件限流在 emitRenderedEventsForMessages 内部处理。
                emitRenderedEventsForMessages(session.messages)
                // 脚本加载失败时 reload 内部已写 error state；这里只等它完成，
                // 不让异常外泄到 selectCharacter 的 catch（避免误报“加载角色失败”）。
                runCatching { scriptJob.join() }
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
                characterScriptJob?.join()
                flushSessionWrites(session.id)
                check(_uiState.value.currentSession?.id == session.id) { "生成所属会话已经切换" }
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

                val readySession = requireNotNull(_uiState.value.currentSession?.takeIf { it.id == session.id })
                val initializedSession = readySession.withTavernInitVariables(
                    character = character,
                    worldBooks = runtime.activeWorldBooks,
                )
                if (initializedSession != readySession) {
                    persistSessionMutation(readySession, initializedSession)
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
                    persistSessionMutation(initializedSession, updatedSession)
                }
                activeRegeneration = regenerationMessageId?.let(::ActiveRegeneration)

                _uiState.update {
                    it.copy(
                        isGenerating = true,
                        streamingText = "",
                        streamingReasoning = "",
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
                // waits for async handlers and their variable writes to complete.
                emitStEvent(
                    StEventCatalog.GENERATION_AFTER_COMMANDS,
                    "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                    },
                    false,
                )


                flushSessionWrites(updatedSession.id)
                val promptSession = requireNotNull(_uiState.value.currentSession?.takeIf { it.id == updatedSession.id })
                val promptMessages = promptSession.messages
                val promptRequest = PromptBuildRequest(
                    character = character,
                    persona = runtime.persona,
                    messages = if (isRegeneration) {
                        promptMessages.take(regenerationInputIndex!!)
                    } else if (messageRole == MessageRole.User) {
                        promptHistoryBeforeCurrentMessage(promptMessages, inputMessage.id)
                    } else {
                        promptMessages
                    },
                    worldBooks = runtime.activeWorldBooks,
                    preset = preset,
                    userInput = when {
                        isRegeneration -> inputMessage.content
                        messageRole == MessageRole.User -> inputMessage.content
                        else -> ""
                    },
                    providerType = config.providerType,
                    metadata = JsonObject(buildPromptMetadata(runtimeState, config, preset, promptSession) +
                        ("userInputNormalProcessed" to JsonPrimitive(
                            CharacterRegexApplier.isNormalProcessed(inputMessage),
                        ))),
                )

                val promptResult = buildPromptWithSessionScope(promptRequest, promptSession)
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
                var accumulatedReasoning = ""

                flow.collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> {
                            accumulatedText += chunk.text
                            accumulatedReasoning += chunk.reasoning
                            _uiState.update { it.copy(streamingText = accumulatedText, streamingReasoning = accumulatedReasoning) }
                        }
                        is GenerateChunk.Completed -> {
                            val rawFinalText = chunk.text
                            val parts = MessageReasoning.fromResponse(rawFinalText, chunk.reasoning)
                            val finalText = CharacterRegexApplier.applyNormal(
                                text = parts.body,
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
                                        messages[regeneratedIndex].withRegeneratedSwipe(finalText).withGenerationReasoning(
                                            parts, rawFinalText, chunk.reasoning, chunk.finishReason, true,
                                        ),
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
                                ).withGenerationReasoning(parts, rawFinalText, chunk.reasoning, chunk.finishReason, false))
                            }
                            val finalSession = (latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession)
                                .copy(messages = finalMessages)

                            persistSessionMutation(latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession, finalSession)
                            activeRegeneration = null

                            _uiState.update {
                                it.copy(
                                    isGenerating = true,
                                    streamingText = "",
                                    streamingReasoning = "",
                                )
                            }
                            val assistantMessageIndex = if (regeneratedIndex >= 0) regeneratedIndex else finalMessages.lastIndex
                            val eventType = if (regeneratedIndex >= 0) "swipe" else "normal"
                            emitStEvent(StEventCatalog.MESSAGE_RECEIVED, assistantMessageIndex, eventType)
                            if (regeneratedIndex >= 0) {
                                emitStEvent(StEventCatalog.MESSAGE_SWIPED, assistantMessageIndex)
                            }
                            emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, assistantMessageIndex, eventType)
                            flushSessionWrites(finalSession.id)
                            _uiState.update { it.copy(isGenerating = false) }
                            emitStEvent(StEventCatalog.GENERATION_ENDED, finalMessages.size)
                            emitStEvent(StEventCatalog.GENERATE_AFTER_DATA, finalMessages.size)
                        }
                        is GenerateChunk.Failed -> {
                            activeRegeneration = null
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    streamingReasoning = "",
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
                        streamingReasoning = "",
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
        if (state.isLoading) return
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

        val updatedMessage = message.selectReasoningSwipe(newSwipeIndex)
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        val commit = scheduleUiMutation(session, updatedSession) ?: return
        launchAfterCommit(commit) {
            emitStEvent(StEventCatalog.MESSAGE_SWIPED, messageIndex)
            emitRenderedEventForMessage(messageIndex, updatedMessage, "swipe")
        }
    }

    fun editMessage(messageIndex: Int, newContent: String) {
        val state = _uiState.value
        if (state.isLoading) return
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
            scheduleMetadataSave(session, session.copy(messages = trimmedMessages))
            sendMessageWithRole(
                text = newContent,
                attachments = message.attachments,
                messageRole = MessageRole.User,
                regexIsEdit = true,
            )
            return
        }

        val updatedSession = session.copy(messages = messages)
        val commit = scheduleUiMutation(session, updatedSession) ?: return
        launchAfterCommit(commit) {
            emitStEvent(StEventCatalog.MESSAGE_EDITED, messageIndex)
            emitStEvent(StEventCatalog.MESSAGE_UPDATED, messageIndex)
            emitRenderedEventForMessage(messageIndex, updatedMessage, "edit")
        }
    }

    fun deleteMessage(messageIndex: Int) {
        val state = _uiState.value
        if (state.isLoading) return
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        messages.removeAt(messageIndex)

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        val commit = scheduleUiMutation(session, updatedSession) ?: return
        launchAfterCommit(commit) {
            emitStEvent(StEventCatalog.MESSAGE_DELETED, messageIndex)
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null

        val state = _uiState.value
        val regeneration = activeRegeneration
        activeRegeneration = null
        if (state.streamingText.isNotEmpty() || state.streamingReasoning.isNotEmpty()) {
            val parts = MessageReasoning.fromResponse(state.streamingText, state.streamingReasoning)
            val processedPartial = CharacterRegexApplier.applyNormal(
                text = parts.body,
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
                            messages[targetIndex].withRegeneratedSwipe(processedPartial).withGenerationReasoning(
                                parts, state.streamingText, state.streamingReasoning, "interrupted", true,
                            ),
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
                                streamingReasoning = "",
                            )
                        }
                        val commit = scheduleUiMutation(session, updatedSession) ?: return
                        interruptionJob = launchAfterCommit(commit) {
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
            ).withGenerationReasoning(parts, state.streamingText, state.streamingReasoning, "interrupted", false))
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
                        streamingReasoning = "",
                    )
                }
                val commit = scheduleUiMutation(session, updatedSession) ?: return
                interruptionJob = launchAfterCommit(commit) {
                    emitStEvent(StEventCatalog.MESSAGE_RECEIVED, partialMessageIndex, "interrupted")
                    emitStEvent(StEventCatalog.CHARACTER_MESSAGE_RENDERED, partialMessageIndex, "interrupted")
                    emitStEvent(StEventCatalog.GENERATION_STOPPED)
                }
            } else {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        streamingReasoning = "",
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
                    streamingReasoning = "",
                )
            }
            viewModelScope.launch {
                emitStEvent(StEventCatalog.GENERATION_STOPPED)
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
          sessionTransitions.withLock {
            val character = _uiState.value.selectedCharacter ?: return@withLock
            _uiState.update { it.copy(isLoading = true) }
            try {
                retireSessionRuntime()
                val newSession = createSessionForCharacter(character)
                activateSessionWrites(newSession)
                val sessions = dataStore.listChatSessions(characterId = character.id)

                _uiState.update {
                    it.copy(
                        currentSession = newSession,
                        messages = newSession.messages,
                        chatBackgroundFile = null,
                        sessions = sessions,
                    )
                }
                reloadCharacterTavernHelperScripts(character)
                emitChatChanged(newSession)
                emitRenderedEventsForMessages(newSession.messages)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "创建会话失败：${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
          }
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
          sessionTransitions.withLock {
            _uiState.update { it.copy(isLoading = true) }
            try {
                retireSessionRuntime()
                val session = dataStore.readChatSession(sessionId)
                activateSessionWrites(session)
                _uiState.update {
                    it.copy(
                        currentSession = session,
                        messages = session.messages,
                        chatBackgroundFile = chatBackgroundFileFor(session),
                    )
                }
                _uiState.value.selectedCharacter?.let { reloadCharacterTavernHelperScripts(it) }
                emitChatChanged(session)
                emitRenderedEventsForMessages(session.messages)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "切换会话失败：${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
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
                persistSessionMutation(session, updated)
                _uiState.update {
                    if (it.currentSession?.id != updated.id) it
                    else it.copy(chatBackgroundFile = dataStore.layout.root.resolve(rel).toFile())
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
                persistSessionMutation(session, updated)
                _uiState.update {
                    if (it.currentSession?.id != updated.id) it
                    else it.copy(chatBackgroundFile = null)
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
            sessionTransitions.withLock {
                runCatching {
                    val sessionId = _uiState.value.currentSession?.id
                    retireSessionRuntime()
                    dataStore.selectPreset(preset.category, preset.id)
                    val session = sessionId?.let { dataStore.readChatSession(it) }
                    session?.let { activateSessionWrites(it) }
                    _uiState.update { it.copy(selectedPreset = preset, currentSession = session,
                        messages = session?.messages ?: emptyList()) }
                    _uiState.value.selectedCharacter?.let { reloadCharacterTavernHelperScripts(it) }
                    emitStEvent(StEventCatalog.SETTINGS_UPDATED, "preset")
                }.onFailure { error ->
                    _uiState.update { it.copy(error = "加载预设失败：${error.message}") }
                }
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

    fun currentRuntimeToken(sessionId: String?): RuntimeToken? = runtimeToken?.takeIf {
        it.sessionId == sessionId && sessionWrites.isActive(it)
    }

    private fun requireMessageRuntime(token: RuntimeToken?) {
        check(token != null && sessionWrites.isActive(token) && _uiState.value.currentSession?.id == token.sessionId) {
            "消息前端所属会话已失效"
        }
    }

    fun tavernMessageContextJson(token: RuntimeToken?): String {
        requireMessageRuntime(token)
        val state = _uiState.value
        val vars = promptEngine.snapshotPromptTemplateVariables()
        return buildJsonObject {
            buildTavernContext(state).forEach { (key, value) -> put(key, value) }
            putJsonObject("variableScopes") {
                put("global", vars.global)
                put("chat", state.currentSession?.metadata?.get("variables") ?: vars.local)
                put("character", state.selectedCharacter?.let { CharacterTavernHelperScripts.extractCharacterVariables(it) } ?: buildJsonObject {})
            }
        }.toString()
    }

    fun tavernMessageVariablesJson(token: RuntimeToken?): String {
        requireMessageRuntime(token)
        val snapshot = promptEngine.snapshotPromptTemplateVariables()
        val local = (_uiState.value.currentSession?.metadata?.get("variables") as? JsonObject)
            ?: snapshot.local
        val merged = buildJsonObject {
            snapshot.global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
            _uiState.value.messages.forEach { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            }
        }
        return decodeEmbeddedJsonValues(merged).toString()
    }

    fun handleTavernMessageRequest(
        operation: String,
        payloadJson: String,
        onSetInput: (String) -> Unit,
        callback: (Boolean, String) -> Unit,
        token: RuntimeToken?,
    ) {
        viewModelScope.launch {
            runCatching {
                requireMessageRuntime(token)
                val payload = (Json.parseToJsonElement(payloadJson) as? JsonObject) ?: buildJsonObject { }
                when (operation) {
                    "mvuReady", "mvuCall" -> {
                        characterScriptJob?.join()
                        requireMessageRuntime(token)
                        val runtime = extensionHost as? app.tellev.core.extension.WebViewJsExtensionHost ?: error("MVU runtime unavailable")
                        val owner = loadedCharacterScriptExtensionId ?: error("Character scripts are not ready")
                        val expression = if (operation == "mvuReady") "window.__tellevReady().then(()=>{if(!window.Mvu)throw Error('MVU is not initialized');return window.Mvu.events})"
                        else {
                            val method = payload["method"]?.jsonPrimitive?.content ?: error("Missing MVU method")
                            require(method in setOf("parseMessage", "parseMessages"))
                            "window.Mvu[" + JsonPrimitive(method) + "](..." + (payload["args"] ?: JsonArray(emptyList())) + ")"
                        }
                        Json.parseToJsonElement(runtime.evaluateRuntime(owner, expression))
                    }
                    "setChatMessages" -> {
                        val session = _uiState.value.currentSession ?: error("No chat")
                        val updates = payload["messages"] as? JsonArray ?: error("Missing messages")
                        val messages = app.tellev.core.extension.applyTavernChatMessages(session.messages, updates)
                        val next = session.copy(messages = messages)
                        persistSessionMutation(session, next)
                        buildJsonObject { put("ok", true) }
                    }
                    "replaceVariables" -> {
                        val options = payload["options"] as? JsonObject ?: buildJsonObject { put("type", "chat") }
                        val variables = payload["variables"] as? JsonObject ?: error("Invalid variables")
                        when (options["type"]?.jsonPrimitive?.content ?: "chat") {
                            "chat" -> updateChatVariables { it.clear(); it.putAll(variables) }
                            "global" -> promptEngine.persistGlobalPromptTemplateVariables(variables)
                            "message" -> {
                                val session = _uiState.value.currentSession ?: error("No chat")
                                val id = options["message_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                                val updates = buildJsonArray { add(buildJsonObject { put("message_id", id); put("data", variables) }) }
                                val messages = app.tellev.core.extension.applyTavernChatMessages(session.messages, updates)
                                val next = session.copy(messages = messages)
                                        persistSessionMutation(session, next)
                            }
                            else -> error("Unsupported writable variable scope")
                        }
                        buildJsonObject { put("ok", true) }
                    }
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
                        persistSessionMutation(session, updatedSession)
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
                            flushSessionWrites(_uiState.value.currentSession?.id)
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
        scheduleMetadataSave(session, updatedSession)
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
        viewModelScope.launch {
            sessionTransitions.withLock {
                try {
                    retireSessionRuntime()
                    _uiState.update { it.copy(selectedCharacter = null, characterAvatarFile = null,
                        currentSession = null, chatBackgroundFile = null, messages = emptyList(), sessions = emptyList()) }
                    emitStEvent(StEventCatalog.CHAT_CHANGED, "")
                } catch (error: Exception) {
                    _uiState.update { it.copy(error = "关闭会话失败：${error.message}") }
                }
            }
        }
    }

    private suspend fun reloadCharacterTavernHelperScripts(character: CharacterCard) {
        unloadCharacterTavernHelperScripts()

        val scriptSource = CharacterTavernHelperScripts.buildIsolatedScriptSource(character, _uiState.value.selectedPreset)
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

    /**
     * 首启风暴削峰：原来对全量历史逐条 `withContext(Main) + evaluateJavascript`，
     * 存量大号（数百条）直接把主线程消息队列打满。现只发最近
     * [RECENT_RENDERED_EVENT_LIMIT] 条——扩展首屏只需要尾部上下文；
     * 历史补发走按需/分页（getChatMessages API），不走事件风暴。
     * 循环内 yield() 让出协作调度，避免连续 Main 跳霸占帧。
     */
    private suspend fun emitRenderedEventsForMessages(messages: List<ChatMessage>) {
        val start = (messages.size - RECENT_RENDERED_EVENT_LIMIT).coerceAtLeast(0)
        for (index in start until messages.size) {
            emitRenderedEventForMessage(index, messages[index], "load")
            // 每 8 条让一帧，不让事件注入独占主线程。
            if ((index - start) % 8 == 7) kotlinx.coroutines.yield()
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
                        if (shouldStream && chunk.text.isNotEmpty()) {
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
        persistSessionMutation(session, updatedSession)
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
    // enter the ordered commit queue before returning to the caller.

    private fun snapshotLocalVariables(): Map<String, JsonElement> {
        val vars = _uiState.value.currentSession?.metadata?.get("variables") as? JsonObject
            ?: return emptyMap()
        return LinkedHashMap(vars)
    }

    private fun updateChatVariables(
        transform: (MutableMap<String, JsonElement>) -> Unit,
    ): Map<String, JsonElement> = synchronized(sessionWriteLock) {
        val session = requireNotNull(_uiState.value.currentSession) { "当前没有会话" }
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
        scheduleMetadataSave(session, session.copy(metadata = JsonObject(session.metadata + ("variables" to newVars))))
        mutable
    }

    private fun scheduleMetadataSave(base: ChatSession, desired: ChatSession): Deferred<CommitReceipt> {
        val job = synchronized(sessionWriteLock) {
            val token = requireNotNull(runtimeToken) { "会话写入环境尚未初始化" }
            check(token.sessionId == base.id) { "写入所属会话已经切换：${base.id}" }
            val owner = StorageOwner("chat", base.id)
            val previous = sessionWrites.snapshot(owner)
            val request = MutationRequest(UUID.randomUUID().toString(), token, owner, "chat", previous.revision,
                buildJsonObject { put("base", Json.encodeToJsonElement(ChatSession.serializer(), previous.value)) })
            val accepted = sessionWrites.submit(request) { current ->
                app.tellev.core.storage.applyChatSessionMutation(base, desired, current).copy(storageRevision = previous.revision + 1)
            }
            val next = sessionWrites.snapshot(owner).value
            _uiState.update { state ->
                if (state.currentSession?.id == base.id) state.copy(currentSession = next, messages = next.messages) else state
            }
            accepted.committed
        }
        job.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                _uiState.update { state ->
                    if (state.currentSession?.id == base.id) state.copy(error = "变量保存失败，生成已暂停：${failure.message}") else state
                }
            }
        }
        return job
    }

    private fun scheduleUiMutation(base: ChatSession, desired: ChatSession): Deferred<CommitReceipt>? =
        runCatching { scheduleMetadataSave(base, desired) }.onFailure { error ->
            _uiState.update { it.copy(error = "消息修改未提交：${error.message}") }
        }.getOrNull()

    private fun launchAfterCommit(commit: Deferred<CommitReceipt>, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            try { commit.await(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _uiState.update { it.copy(error = "消息更新未完成：${error.message}") } }
        }

    private suspend fun persistSessionMutation(base: ChatSession, desired: ChatSession) {
        scheduleMetadataSave(base, desired).await()
    }

    private fun activateSessionWrites(session: ChatSession) = synchronized(sessionWriteLock) {
        check(runtimeToken == null) { "Previous chat runtime was not retired" }
        sessionWrites.register(StorageOwner("chat", session.id), session, session.storageRevision)
        runtimeToken = sessionWrites.activate(session.id, "native")
        _uiState.update { it.copy(runtimeGeneration = runtimeToken!!.generation) }
    }

    private suspend fun flushSessionWrites(sessionId: String?) {
        if (sessionId == null) return
        runtimeToken?.takeIf { it.sessionId == sessionId }?.let { sessionWrites.flushWrites(it) }
        extensionHost.flushWrites()
    }

    private suspend fun retireSessionRuntime() {
        val previousGeneration = generationJob
        if (previousGeneration?.isActive == true) stopGeneration()
        previousGeneration?.join()
        interruptionJob?.join()
        interruptionJob = null
        characterScriptJob?.cancelAndJoin()
        characterScriptJob = null
        unloadCharacterTavernHelperScripts()
        _uiState.value.selectedCharacter?.let { extensionHost.unload(characterScriptExtensionId(it.id)) }
        extensionHost.flushWrites()
        val token = runtimeToken ?: return
        sessionWrites.release(token)
        synchronized(sessionWriteLock) {
            sessionWrites.unregister(StorageOwner("chat", token.sessionId))
            runtimeToken = null
        }
    }

    override fun onCleared() {
        val token = runtimeToken
        val scriptOwner = loadedCharacterScriptExtensionId
        val scriptCapability = scriptOwner?.let { extensionHost.capabilityToken(it) }
        token?.let { sessionWrites.revoke(it) }
        extensionHost.setContextProvider(null)
        extensionHost.setLocalVariableBackend(null)
        extensionHost.setMessageVariableBackend(null)
        sessionWriteScope.launch {
            try {
                if (scriptOwner != null && scriptCapability != null && extensionHost.capabilityToken(scriptOwner) == scriptCapability) {
                    extensionHost.unload(scriptOwner)
                }
                token?.let { sessionWrites.release(it) }
            }
            catch (error: Exception) { _uiState.update { it.copy(error = "退出时仍有未完成写入：${error.message}") } }
            finally { sessionWriteScope.cancel() }
        }
        super.onCleared()
    }

    private suspend fun buildPromptWithSessionScope(
        request: PromptBuildRequest,
        session: ChatSession?,
    ): PromptBuildResult {
        val initialLocal = (session?.metadata?.get("variables") as? JsonObject)
            ?: JsonObject(emptyMap())
        val backend = TrackingPromptLocalBackend(LinkedHashMap(initialLocal))
        val result = promptEngine.buildWithLocalVariableBackendAsync(request, backend)
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
                val source = requireNotNull(_uiState.value.currentSession?.takeIf { it.id == targetSessionId }) {
                    "Template belongs to an expired chat: $targetSessionId"
                }
                persistSessionMutation(source, source.withPromptTemplateVariables(localVariables))
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
            put("__runtimeGeneration", runtimeToken?.generation ?: -1)
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
            // The upstream chat array uses absolute indices; never truncate its prefix.
            putJsonArray("chat") {
                val start = 0
                for (index in start until state.messages.size) {
                    add(state.messages[index].toTavernJson(index))
                }
            }
            val books = (state.worldBooks + listOfNotNull(character?.characterBook)).distinctBy { it.id }
            putJsonArray("worldBooks") {
                books.forEach { book -> add(buildJsonObject {
                    put("id", book.id); put("name", book.name)
                    putJsonArray("entries") { book.entries.forEach { entry -> add(buildJsonObject {
                        entry.raw.forEach { (key, value) -> put(key, value) }
                        put("uid", entry.id); put("comment", entry.comment); put("content", entry.content)
                        put("disable", !entry.enabled)
                    }) } }
                }) }
            }
            putJsonArray("characterWorldBooks") {
                character?.characterBook?.let { add(JsonPrimitive(it.name)) }
                character?.let { app.tellev.core.model.CharacterWorldBinding.linkedWorldBookNames(it).forEach { name -> add(JsonPrimitive(name)) } }
            }
            putJsonArray("globalWorldBooks") {
                state.worldBooks.filter { it.id !in state.disabledWorldIds }.forEach { add(JsonPrimitive(it.name)) }
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
            raw.forEach { (key, value) -> put(key, value) }
            val user = role == MessageRole.User
            put("id", id)
            put("index", index)
            put("name", name)
            put("mes", content)
            put("is_user", user)
            put("is_system", isHidden)
            put("role", role.name.lowercase())
            put("send_date", createdAtMillis.toString())
            put("send_date_unix", createdAtMillis)
            put("swipe_id", swipeIndex)
            // MVU initializes each greeting swipe independently.
            putJsonArray("swipes") {
                val values = swipes.ifEmpty { listOf(content) }
                values.forEach { add(JsonPrimitive(it)) }
            }
            put("extra", if (role == MessageRole.System && metadata["type"] == null) JsonObject(metadata + ("type" to JsonPrimitive("narrator"))) else metadata)
            if (swipeInfo.isNotEmpty() || raw["swipe_info"] is JsonArray) put("swipe_info", JsonArray(swipeInfo))
            // ST-Prompt-Template per-swipe arrays (only when present)
            if (variables.isNotEmpty()) {
                putJsonArray("variables") { variables.forEach { add(it) } }
            }
            if (isEjsProcessed.isNotEmpty()) {
                putJsonArray("is_ejs_processed") { isEjsProcessed.forEach { add(it) } }
            }
            if (variablesInitialized.isNotEmpty()) {
                putJsonArray("variables_initialized") { variablesInitialized.forEach { add(it) } }
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
            // NOTE: 高频 getContext 快照不再带整卡 raw（1.4MB 卡会存两份：
            // raw 全量 + data 全量，序列化后数 MB，每次读属性都付一次）。
            // 字段名保留但只放 data 子树；整卡按需走 TavernHelper.getCharacter(id)。
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
        return dataStore.readChatSession(initialized.id)
    }

    private fun ChatSession.withTavernInitVariables(
        character: CharacterCard,
        worldBooks: List<WorldBook>,
    ): ChatSession {
        // The real MVU owns initialization, including all greeting swipes and Zod hooks.
        if (CharacterTavernHelperScripts.extract(character).any { it.content.contains("MagVarUpdate/") }) return this
        if (messages.any { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.containsKey("stat_data") == true
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
        val initialized = MutableList(swipeCount) { JsonPrimitive(true) }
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
        /**
         * selectCharacter 首启只向扩展分发最近 N 条消息的 rendered 事件。
         * 全量逐条分发在存量大号上是 O(消息数) 次 Main 跳 + JS 注入，
         * 是 ANR 风暴的主力之一。
         */
        private const val RECENT_RENDERED_EVENT_LIMIT = 20
        /**
         * getContext() chat 快照只带最近 N 条。lastMessageId 仍是全局索引，
         * 需要全量历史的扩展走 getChatMessages 分页 API。
         */
        private const val TAVERN_CONTEXT_CHAT_LIMIT = 100
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
    return preserveReasoningSwipe().copy(
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
