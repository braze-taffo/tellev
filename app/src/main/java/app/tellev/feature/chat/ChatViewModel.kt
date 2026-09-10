package app.tellev.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.RuntimeToken
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
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.GenerationRuntimeResolver
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.presetCategoryForProvider
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.GeneratedImage
import app.tellev.core.storage.GeneratedImageStore
import app.tellev.core.storage.StDataStore
import app.tellev.feature.chat.ChatSessionInit.withCharacterGreetingSwipes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ChatUiState(
    val runtimeGeneration: Long = -1,
    val characters: List<CharacterSummary> = emptyList(),
    // Card file per character id for the character picker list (card = avatar).
    val characterAvatarFiles: Map<String, File?> = emptyMap(),
    val selectedCharacter: CharacterCard? = null,
    // The selected character's card file (PNG/WebP/JSON): the card image is
    // the avatar. Null when no character is selected or the card file is gone;
    // JSON cards fall back to the initials badge on decode failure.
    val characterAvatarFile: File? = null,
    val currentSession: ChatSession? = null,
    // Per-session chat background: chat_metadata["background"] resolved to a
    // file under st-data/backgrounds. Null = plain surface color.
    val chatBackgroundFile: File? = null,
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
    // ── 生图：仅在至少一个引擎已配置时对 UI 可见 ──
    val imageGenAvailable: Boolean = false,
    val configuredImageEngines: Set<String> = emptySet(),
    val imageEngine: String? = null,
    val isGeneratingImage: Boolean = false,
    val imageGenStatus: String? = null,
    val imageGenError: String? = null,
    val generatedImages: List<GeneratedImage> = emptyList(),
    /** Last failed scene summary, kept in memory for user inspection; never written to logcat. */
    val imageGenDiagnostic: String? = null,
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

    private val sessionRuntime = ChatSessionRuntime(
        dataStore = dataStore,
        onSessionError = { sessionId, error ->
            _uiState.update { state ->
                if (state.currentSession?.id == sessionId) state.copy(error = error) else state
            }
        },
    )
    private val messageActions = ChatMessageActions(sessionRuntime)
    private val runtimeResolver = GenerationRuntimeResolver(dataStore, providerRegistry, secretStore)
    private val generatedImageStore = GeneratedImageStore(dataStore.layout)
    private val imageGenCoordinator = ChatImageGenerationCoordinator(
        dataStore, providerRegistry, secretStore, generatedImageStore,
    )
    private val generationCoordinator = ChatGenerationCoordinator(
        dataStore, providerRegistry, promptEngine, extensionHost, sessionRuntime, runtimeResolver,
    )

    private var characterScriptJob: Job? = null
    @Volatile
    private var loadedCharacterScriptExtensionId: String? = null

    init {
        extensionHost.setContextProvider(
            ChatTavernAdapter.createExtensionContextProvider(
                getCurrentState = { _uiState.value },
                extensionHost = extensionHost,
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                },
                onSetChatMessage = { index, field, value ->
                    messageActions.setChatMessageFromExtension(index, field, value, _uiState.value) { updated ->
                        _uiState.update {
                            if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                            else it
                        }
                    }
                },
                onGenerateText = { options ->
                    generationCoordinator.generateTextFromExtension(options, _uiState)
                },
            ),
        )

        extensionHost.setLocalVariableBackend(
            ChatTavernAdapter.createLocalVariableBackend(
                getCurrentSession = { _uiState.value.currentSession },
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                },
            ),
        )

        extensionHost.setMessageVariableBackend(
            ChatTavernAdapter.createMessageVariableBackend(
                getCurrentSession = { _uiState.value.currentSession },
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                },
            ),
        )

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
                                characterAvatarFiles = ChatSessionAssets.avatarFilesFor(characters, dataStore.layout),
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
                                characterAvatarFile = ChatSessionAssets.characterCardFile(refreshed.id, dataStore.layout),
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
                refreshImageGenAvailability()
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
                    val visible = sessionRuntime.observePersisted(sessionId, refreshed)
                    _uiState.update {
                        if (it.currentSession?.id != sessionId || it.isGenerating) it
                        else it.copy(
                            currentSession = visible,
                            messages = visible.messages,
                            chatBackgroundFile = ChatSessionAssets.chatBackgroundFileFor(visible, dataStore.layout),
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
                refreshImageGenAvailability()

                _uiState.update {
                    it.copy(
                        characters = characters,
                        characterAvatarFiles = ChatSessionAssets.avatarFilesFor(characters, dataStore.layout),
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
            sessionRuntime.sessionTransitions.withLock {
                _uiState.update { it.copy(isLoading = true, error = null) }
                try {
                    retireSessionRuntime()
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
                            ChatSessionInit.createSessionForCharacter(
                                character,
                                _uiState.value.selectedPersona?.name ?: "User",
                                dataStore,
                            )
                        }
                        val allSessions = dataStore.listChatSessions(characterId = characterId)

                        val allWorldBooks = dataStore.listWorldBooks()
                        val embeddedId = StDataStore.embeddedCharacterBookId(characterId)
                        val ownWorldBookIds = buildSet {
                            add(embeddedId)
                            app.tellev.core.model.CharacterWorldBinding.linkedWorldBookNames(character).forEach { name ->
                                allWorldBooks
                                    .firstOrNull { it.name.equals(name, ignoreCase = true) || it.id == name }
                                    ?.let { add(it.id) }
                            }
                        }
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
                    val token = sessionRuntime.activateSessionWrites(session)

                    _uiState.update {
                        it.copy(
                            runtimeGeneration = token.generation,
                            selectedCharacter = character,
                            characterAvatarFile = ChatSessionAssets.characterCardFile(character.id, dataStore.layout),
                            currentSession = session,
                            messages = session.messages,
                            chatBackgroundFile = ChatSessionAssets.chatBackgroundFileFor(session, dataStore.layout),
                            sessions = selection.allSessions,
                            disabledWorldIds = selection.disabledWorldIds,
                            isLoading = false,
                        )
                    }

                    val scriptJob = viewModelScope.launch(Dispatchers.Default) {
                        reloadCharacterTavernHelperScripts(character)
                    }
                    characterScriptJob = scriptJob
                    ChatTavernAdapter.emitCharacterSelected(extensionHost, character)
                    imageGenCoordinator.refreshGeneratedImages(session.id) { targetSessionId, images ->
                        _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                    }
                    ChatTavernAdapter.emitChatChanged(extensionHost, session)
                    ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, session.messages)
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

    fun deselectCharacter() {
        viewModelScope.launch {
            sessionRuntime.sessionTransitions.withLock {
                try {
                    retireSessionRuntime()
                    _uiState.update {
                        it.copy(
                            selectedCharacter = null,
                            characterAvatarFile = null,
                            currentSession = null,
                            chatBackgroundFile = null,
                            messages = emptyList(),
                            generatedImages = emptyList(),
                            sessions = emptyList(),
                        )
                    }
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHAT_CHANGED, "")
                } catch (error: Exception) {
                    _uiState.update { it.copy(error = "关闭会话失败：${error.message}") }
                }
            }
        }
    }

    fun createNewSession() {
        viewModelScope.launch {
            sessionRuntime.sessionTransitions.withLock {
                val character = _uiState.value.selectedCharacter ?: return@withLock
                _uiState.update { it.copy(isLoading = true) }
                try {
                    retireSessionRuntime()
                    val newSession = ChatSessionInit.createSessionForCharacter(
                        character,
                        _uiState.value.selectedPersona?.name ?: "User",
                        dataStore,
                    )
                    val token = sessionRuntime.activateSessionWrites(newSession)
                    val sessions = dataStore.listChatSessions(characterId = character.id)

                    _uiState.update {
                        it.copy(
                            runtimeGeneration = token.generation,
                            currentSession = newSession,
                            messages = newSession.messages,
                            generatedImages = emptyList(),
                            chatBackgroundFile = null,
                            sessions = sessions,
                        )
                    }
                    reloadCharacterTavernHelperScripts(character)
                    ChatTavernAdapter.emitChatChanged(extensionHost, newSession)
                    ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, newSession.messages)
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
            sessionRuntime.sessionTransitions.withLock {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    retireSessionRuntime()
                    val session = dataStore.readChatSession(sessionId)
                    val token = sessionRuntime.activateSessionWrites(session)
                    _uiState.update {
                        it.copy(
                            runtimeGeneration = token.generation,
                            currentSession = session,
                            messages = session.messages,
                            chatBackgroundFile = ChatSessionAssets.chatBackgroundFileFor(session, dataStore.layout),
                        )
                    }
                    _uiState.value.selectedCharacter?.let { reloadCharacterTavernHelperScripts(it) }
                    imageGenCoordinator.refreshGeneratedImages(session.id) { targetSessionId, images ->
                        _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                    }
                    ChatTavernAdapter.emitChatChanged(extensionHost, session)
                    ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, session.messages)
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

    private suspend fun retireSessionRuntime() {
        val previousGeneration = generationCoordinator.generationJob
        if (previousGeneration?.isActive == true) generationCoordinator.stopGeneration(_uiState, viewModelScope)
        previousGeneration?.join()
        generationCoordinator.interruptionJob?.join()
        generationCoordinator.clearInterruptionJob()
        characterScriptJob?.cancelAndJoin()
        characterScriptJob = null
        ChatTavernAdapter.unloadCharacterTavernHelperScripts(
            extensionHost,
            loadedCharacterScriptExtensionId,
        ) { loadedCharacterScriptExtensionId = it }
        _uiState.value.selectedCharacter?.let {
            extensionHost.unload(ChatTavernAdapter.characterScriptExtensionId(it.id))
        }
        sessionRuntime.retireSessionRuntime(extensionHost)
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
        return generationCoordinator.sendMessageWithRole(
            text = text,
            attachments = attachments,
            messageRole = messageRole,
            regenerationMessageId = regenerationMessageId,
            regexIsEdit = regexIsEdit,
            uiState = _uiState,
            scope = viewModelScope,
            characterScriptJob = characterScriptJob,
        )
    }

    fun regenerateResponse(messageId: String): Boolean {
        return generationCoordinator.regenerateResponse(
            messageId = messageId,
            uiState = _uiState,
            scope = viewModelScope,
            characterScriptJob = characterScriptJob,
        )
    }

    fun regenerateLastMessage(): Boolean {
        val lastResponse = _uiState.value.messages.lastOrNull() ?: return false
        return regenerateResponse(lastResponse.id)
    }

    fun stopGeneration() {
        generationCoordinator.stopGeneration(_uiState, viewModelScope)
    }

    fun swipeMessage(messageIndex: Int, direction: Int) {
        messageActions.swipeMessage(
            messageIndex = messageIndex,
            direction = direction,
            state = _uiState.value,
            scope = viewModelScope,
            onSessionUpdated = { updated ->
                _uiState.update {
                    if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                    else it
                }
            },
            onError = { err -> _uiState.update { it.copy(error = err) } },
            onSwipeCommitted = { updated ->
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, messageIndex)
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_UPDATED, messageIndex)
                ChatTavernAdapter.emitRenderedEventForMessage(extensionHost, messageIndex, updated, "swipe")
            },
        )
    }

    fun editMessage(messageIndex: Int, newContent: String) {
        messageActions.editMessage(
            messageIndex = messageIndex,
            newContent = newContent,
            state = _uiState.value,
            scope = viewModelScope,
            onSessionUpdated = { updated ->
                _uiState.update {
                    if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                    else it
                }
            },
            onError = { err -> _uiState.update { it.copy(error = err) } },
            onEditCommitted = { updated ->
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_EDITED, messageIndex)
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_UPDATED, messageIndex)
                ChatTavernAdapter.emitRenderedEventForMessage(extensionHost, messageIndex, updated, "edit")
            },
            onUserMessageEdit = { baseSession, trimmedSession, trimmedMessages, rawContent, attachments, updatedMessage ->
                _uiState.update {
                    it.copy(
                        currentSession = trimmedSession,
                        messages = trimmedMessages,
                    )
                }
                viewModelScope.launch {
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_EDITED, messageIndex)
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_UPDATED, messageIndex)
                    ChatTavernAdapter.emitRenderedEventForMessage(extensionHost, messageIndex, updatedMessage, "edit")
                }
                sessionRuntime.scheduleMetadataSave(baseSession, trimmedSession) { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                }
                sendMessageWithRole(rawContent, attachments, MessageRole.User, regexIsEdit = true)
            },
        )
    }

    fun deleteMessage(messageIndex: Int) {
        messageActions.deleteMessage(
            messageIndex = messageIndex,
            state = _uiState.value,
            scope = viewModelScope,
            onSessionUpdated = { updated ->
                _uiState.update {
                    if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                    else it
                }
            },
            onError = { err -> _uiState.update { it.copy(error = err) } },
            onDeleteCommitted = {
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_DELETED, messageIndex)
            },
        )
    }

    fun setChatBackground(imageBytes: ByteArray) {
        val session = _uiState.value.currentSession ?: return
        viewModelScope.launch {
            ChatSessionAssets.setChatBackground(
                imageBytes = imageBytes,
                session = session,
                layout = dataStore.layout,
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                },
                onBackgroundFileResolved = { file ->
                    _uiState.update { it.copy(chatBackgroundFile = file) }
                },
                onError = { err -> _uiState.update { it.copy(error = err) } },
            )
        }
    }

    fun clearChatBackground() {
        val session = _uiState.value.currentSession ?: return
        viewModelScope.launch {
            ChatSessionAssets.clearChatBackground(
                session = session,
                layout = dataStore.layout,
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    _uiState.update {
                        if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                        else it
                    }
                },
                onBackgroundCleared = {
                    _uiState.update { it.copy(chatBackgroundFile = null) }
                },
                onError = { err -> _uiState.update { it.copy(error = err) } },
            )
        }
    }

    fun generateImage(prompt: String, negativePrompt: String, summarizeScene: Boolean, selectedEngine: String? = null) {
        imageGenCoordinator.generateImage(
            prompt = prompt,
            negativePrompt = negativePrompt,
            summarizeScene = summarizeScene,
            selectedEngine = selectedEngine,
            state = _uiState.value,
            scope = viewModelScope,
            isTextGenerating = generationCoordinator.isGenerating,
            onStatusUpdated = { isGenerating, status, diagnostic, error ->
                _uiState.update {
                    it.copy(
                        isGeneratingImage = isGenerating,
                        imageGenStatus = status,
                        imageGenDiagnostic = diagnostic ?: it.imageGenDiagnostic,
                        imageGenError = error,
                    )
                }
            },
            onEngineUpdated = { engine, configured ->
                _uiState.update { it.copy(imageEngine = engine, configuredImageEngines = configured) }
            },
            onImagesLoaded = { targetSessionId, images ->
                _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
            },
            onDiagnosticRecorded = { diagnostic ->
                _uiState.update { it.copy(imageGenDiagnostic = diagnostic) }
            },
        )
    }

    fun stopImageGeneration() {
        imageGenCoordinator.stopImageGeneration {
            _uiState.update { it.copy(isGeneratingImage = false, imageGenStatus = null) }
        }
    }

    fun clearImageError() {
        _uiState.update { it.copy(imageGenError = null) }
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
            sessionRuntime.sessionTransitions.withLock {
                runCatching {
                    val sessionId = _uiState.value.currentSession?.id
                    retireSessionRuntime()
                    dataStore.selectPreset(preset.category, preset.id)
                    val session = sessionId?.let { dataStore.readChatSession(it) }
                    session?.let { sessionRuntime.activateSessionWrites(it) }
                    _uiState.update {
                        it.copy(
                            selectedPreset = preset,
                            currentSession = session,
                            messages = session?.messages ?: emptyList(),
                            runtimeGeneration = sessionRuntime.runtimeToken?.generation ?: -1,
                        )
                    }
                    _uiState.value.selectedCharacter?.let { reloadCharacterTavernHelperScripts(it) }
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.SETTINGS_UPDATED, "preset")
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
            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.PERSONA_CHANGED, persona.name)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun refreshImageGenAvailability() {
        imageGenCoordinator.refreshImageGenAvailability { available, configured, engine ->
            _uiState.update {
                it.copy(
                    imageGenAvailable = available,
                    configuredImageEngines = configured,
                    imageEngine = engine,
                )
            }
        }
    }

    private suspend fun reloadCharacterTavernHelperScripts(character: CharacterCard) {
        ChatTavernAdapter.reloadCharacterTavernHelperScripts(
            character = character,
            preset = _uiState.value.selectedPreset,
            extensionHost = extensionHost,
            permissionManager = permissionManager,
            currentLoadedId = loadedCharacterScriptExtensionId,
            onLoadedIdChanged = { loadedCharacterScriptExtensionId = it },
            onError = { err -> _uiState.update { it.copy(error = err) } },
        )
    }

    fun currentRuntimeToken(sessionId: String?): RuntimeToken? =
        sessionRuntime.currentRuntimeToken(sessionId)

    fun tavernMessageContextJson(token: RuntimeToken?): String =
        ChatTavernAdapter.tavernMessageContextJson(token, _uiState.value, promptEngine, sessionRuntime, extensionHost)

    fun tavernMessageVariablesJson(token: RuntimeToken?): String =
        ChatTavernAdapter.tavernMessageVariablesJson(token, _uiState.value, promptEngine, sessionRuntime)

    fun handleTavernMessageRequest(
        operation: String,
        payloadJson: String,
        onSetInput: (String) -> Unit,
        callback: (Boolean, String) -> Unit,
        token: RuntimeToken?,
    ) {
        ChatTavernAdapter.handleTavernMessageRequest(
            operation = operation,
            payloadJson = payloadJson,
            onSetInput = onSetInput,
            callback = callback,
            token = token,
            uiState = _uiState,
            scope = viewModelScope,
            sessionRuntime = sessionRuntime,
            extensionHost = extensionHost,
            promptEngine = promptEngine,
            dataStore = dataStore,
            characterScriptJob = characterScriptJob,
            loadedCharacterScriptExtensionId = loadedCharacterScriptExtensionId,
            messageActions = messageActions,
            onDeleteMessage = ::deleteMessage,
            onSendMessage = ::sendMessage,
            onSendMessageWithRole = { text, attachments, role ->
                sendMessageWithRole(text, attachments, role)
            },
        )
    }

    override fun onCleared() {
        generationCoordinator.clearInterruptionJob()
        extensionHost.setContextProvider(null)
        extensionHost.setLocalVariableBackend(null)
        extensionHost.setMessageVariableBackend(null)
        sessionRuntime.onCleared(
            extensionHost = extensionHost,
            loadedCharacterScriptExtensionId = loadedCharacterScriptExtensionId,
            onError = { err -> _uiState.update { it.copy(error = err) } },
        )
        super.onCleared()
    }
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
