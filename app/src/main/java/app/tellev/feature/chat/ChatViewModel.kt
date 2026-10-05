package app.tellev.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellev.core.extension.ExternalChatWritePort
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.CharacterScriptConsentStore
import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.MutableExternalChatWritePort
import app.tellev.core.extension.RuntimeToken
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.Attachment
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSessionSummary
import app.tellev.core.model.toSummary
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.model.WorldBook
import app.tellev.core.memory.MemoryMode
import app.tellev.core.memory.MemoryRecord
import app.tellev.core.memory.MemoryService
import app.tellev.core.memory.withMemoryMode
import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.metrics.GenerationMetricsAggregate
import app.tellev.core.metrics.GenerationMetricsCalculator
import app.tellev.core.metrics.GenerationMetricsStore
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.GenerationRuntimeResolver
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.provider.presetCategoryForProvider
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.GeneratedImage
import app.tellev.core.storage.GeneratedImageStore
import app.tellev.core.storage.StDataStore
import app.tellev.feature.chat.ChatSessionInit.withCharacterGreetingSwipes
import app.tellev.feature.chat.ChatSessionInit.withProcessedGreeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

data class ChatUiState(
    val runtimeGeneration: Long = -1,
    val characters: List<CharacterSummary> = emptyList(),
    // Card file per character id for the character picker list (card = avatar).
    val characterAvatarFiles: Map<String, File?> = emptyMap(),
    val selectedCharacter: CharacterCard? = null,
    /** Available after the selected card's TavernHelper runtime has loaded. */
    val characterUiExtensionId: String? = null,
    // The selected character's card file (PNG/WebP/JSON): the card image is
    // the avatar. Null when no character is selected or the card file is gone;
    // JSON cards fall back to the initials badge on decode failure.
    val characterAvatarFile: File? = null,
    val currentSession: ChatSession? = null,
    // Per-session chat background: chat_metadata["background"] resolved to a
    // file under st-data/backgrounds. Null = plain surface color.
    val chatBackgroundFile: File? = null,
    /** Last main-line generation's assembled context (context viewer). */
    val contextSnapshot: ContextSnapshot? = null,
    val generationMetrics: List<GenerationMetrics> = emptyList(),
    val generationMetricsAggregate: GenerationMetricsAggregate = GenerationMetricsAggregate(),
    val latestGenerationMetrics: GenerationMetrics? = null,
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
    val sessions: List<ChatSessionSummary> = emptyList(),
    val error: String? = null,
    val memoryStatus: String? = null,
    val memoryRecords: List<MemoryRecord> = emptyList(),
    val memoryNeedsRebuild: Boolean = false,
    val memoryPluginEnabled: Boolean = false,
    val memoryVectorEnabled: Boolean = false,
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
    /** Configured custom image endpoints (`imgprof:` engine ids) shown in the generation dialog. */
    val imageProfiles: List<ChatImageProfileOption> = emptyList(),
    // ── A1 卡内脚本确认：首次装载(或脚本集变化后)须用户确认,持久化于 script-consent.json ──
    val pendingScriptConsent: CharacterScriptConsent? = null,
    /** 卡内脚本已停用(拒绝过/本次会话跳过),展示横幅并提供一键重新询问。 */
    val characterScriptsDisabled: CharacterScriptConsent? = null,
    /** 会话抽屉用：按角色分组的会话列表（当前角色排最前）。 */
    val sessionGroups: List<CharacterSessionGroup> = emptyList(),
    /** 置顶的会话 id（pinned-sessions.json）。 */
    val pinnedSessionIds: Set<String> = emptySet(),
)

/** 抽屉里的一个角色分组：角色摘要 + 它名下的会话（按最近消息倒序）。 */
data class CharacterSessionGroup(
    val characterId: String,
    val characterName: String,
    val sessions: List<ChatSessionSummary>,
    val isCurrentCharacter: Boolean = false,
)

/** 卡内脚本确认请求的数据:脚本源指纹 + 脚本名列表(用于弹窗展示)。 */
data class CharacterScriptConsent(
    val characterId: String,
    val fingerprint: String,
    val scriptNames: List<String>,
)

class ChatViewModel(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val promptEngine: PromptEngine,
    private val secretStore: SecretStore,
    private val extensionHost: ExtensionHost,
    private val permissionManager: ExtensionPermissionManager,
    private val externalChatWritePort: MutableExternalChatWritePort? = null,
    imageDownloader: (suspend (String) -> ByteArray?)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val sessionRuntime = ChatSessionRuntime(
        dataStore = dataStore,
        onSessionError = { sessionId, error ->
            _uiState.update { state ->
                // Empty id marks a report from a transition without a live token; show it
                // only when there is no current session to anchor it to.
                val relevant = state.currentSession?.id == sessionId ||
                    (sessionId.isEmpty() && state.currentSession == null)
                if (relevant) state.copy(error = error) else state
            }
        },
        onSessionRecovered = { sessionId, truth ->
            _uiState.update { state ->
                if (state.currentSession?.id != sessionId) {
                    state
                } else {
                    // A failed write never persisted; roll the optimistic view back to
                    // what is actually stored so the next action builds on disk truth.
                    state.copy(
                        currentSession = truth,
                        messages = truth.messages,
                        error = state.error ?: UiStrings.get(S.chatvm_recover_unsaved),
                    )
                }
            }
        },
    )
    private val messageActions = ChatMessageActions(sessionRuntime, promptEngine)
    private val runtimeResolver = GenerationRuntimeResolver(dataStore, providerRegistry, secretStore)
    private val memoryService = MemoryService(dataStore, providerRegistry, secretStore)
    private val generationMetricsStore = GenerationMetricsStore(dataStore.layout.root)
    private val generatedImageStore = GeneratedImageStore(dataStore.layout)
    private val imageGenCoordinator = ChatImageGenerationCoordinator(
        dataStore, providerRegistry, secretStore, generatedImageStore, imageDownloader,
    )
    private val generationCoordinator = ChatGenerationCoordinator(
        dataStore, providerRegistry, promptEngine, extensionHost, sessionRuntime, runtimeResolver, memoryService,
        generationMetricsStore,
    )
    private val promptOptimizer = app.tellev.core.prompt.PromptOptimizer()
    private var promptOptimizationJob: Job? = null

    private var characterScriptJob: Job? = null
    @Volatile
    private var loadedCharacterScriptExtensionId: String? = null
    @Volatile
    private var loadedCharacterScriptSource: String? = null
    private val scriptConsentStore = CharacterScriptConsentStore(dataStore.layout.root)

    /** 本次进程内已答复「暂不启用」的记录：characterId → fingerprint，避免每次 reload 重复弹窗。 */
    private val sessionDismissedScripts = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        // The extension virtual API saves/appends chats straight to storage; these hooks
        // keep those writes out of the coordinator's in-flight tail and re-adopt the
        // disk revision afterwards, so a script write can no longer poison the session.
        externalChatWritePort?.register(object : ExternalChatWritePort {
            override suspend fun quiesce(sessionId: String) {
                val token = sessionRuntime.runtimeToken?.takeIf { it.sessionId == sessionId } ?: return
                // A poisoned chain must not block the script write either; recovery on the
                // next coordinated action re-arms from whatever this write leaves on disk.
                runCatching { sessionRuntime.sessionWrites.flushWrites(token) }
            }

            override suspend fun notifyWritten(sessionId: String) {
                if (sessionRuntime.runtimeToken?.sessionId != sessionId) return
                runCatching {
                    val adopted = sessionRuntime.observePersisted(sessionId, dataStore.readChatSession(sessionId))
                    if (!_uiState.value.isGenerating) {
                        _uiState.update {
                            if (it.currentSession?.id == sessionId) it.copy(currentSession = adopted, messages = adopted.messages) else it
                        }
                    }
                }
            }
        })

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
                onGenerateText = { options ->
                    generationCoordinator.generateTextFromExtension(options, _uiState)
                },
                onCompatibilityStorage = { operation, payload ->
                    ChatTavernStorage.call(operation, payload, dataStore, _uiState, sessionRuntime)
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
        observeGenerationMetrics()
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
                // A refresh can suspend on disk or script loading. Serialize it with
                // selection so the old card cannot replace the destination's state.
                sessionRuntime.sessionTransitions.withLock {
                    if (_uiState.value.selectedCharacter?.id != characterId) return@withLock
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
                            if (error is CancellationException) throw error
                            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_reread_character_failed, error.message)) }
                        }
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
                    _uiState.update { it.copy(error = UiStrings.get(S.chatvm_reread_preset_failed, error.message)) }
                }
            }
        }
    }

    private fun observeWorldBookChanges() {
        viewModelScope.launch {
            dataStore.worldBookChanges.collect {
                refreshRuntimeState(S.chatvm_reread_worldbook_failed)
            }
        }
    }

    private fun observePersonaChanges() {
        viewModelScope.launch {
            dataStore.personaChanges.collect {
                refreshRuntimeState(S.chatvm_reread_persona_failed)
            }
        }
    }

    private fun observeProviderChanges() {
        viewModelScope.launch {
            secretStore.changes.collect {
                refreshRuntimeState(S.chatvm_reread_provider_failed)
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
                    // 已删除的会话（删除级联会发出 change 事件）不算读取失败。
                    if (!error.message.orEmpty().contains("Chat session not found")) {
                        _uiState.update { it.copy(error = UiStrings.get(S.chatvm_read_session_state_failed, error.message)) }
                    }
                }
            }
        }
    }

    private fun observeGenerationMetrics() {
        viewModelScope.launch {
            generationMetricsStore.load()
            generationMetricsStore.entries.collect { entries ->
                _uiState.update { state ->
                    state.copy(
                        generationMetrics = entries,
                        latestGenerationMetrics = entries.lastOrNull(),
                        generationMetricsAggregate = GenerationMetricsCalculator.aggregate(entries),
                    )
                }
            }
        }
    }

    private suspend fun refreshRuntimeState(errorKey: String) {
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
            .onFailure { error -> _uiState.update { it.copy(error = UiStrings.get(errorKey, error.message)) } }
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
                        error = UiStrings.get(S.chatvm_load_data_failed, e.message),
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
                        val allSessions: List<ChatSessionSummary>,
                        val disabledWorldIds: Set<String>,
                    )
                    val selection = withContext(Dispatchers.Default) {
                        val character = dataStore.readCharacter(characterId)
                        val sessions = dataStore.listChatSessionSummaries(characterId = characterId)

                        val session = if (sessions.isNotEmpty()) {
                            val loaded = dataStore.readChatSession(sessions.first().id)
                            loaded.withCharacterGreetingSwipes(character).withProcessedGreeting(
                                character, _uiState.value.selectedPersona?.name ?: "User", promptEngine, _uiState.value.selectedPreset,
                            ).let { upgraded ->
                                if (upgraded != loaded) dataStore.commitChatMutation(loaded, upgraded) else upgraded
                            }
                        } else {
                            ChatSessionInit.createSessionForCharacter(
                                character,
                                _uiState.value.selectedPersona?.name ?: "User",
                                dataStore,
                                promptEngine, _uiState.value.selectedPreset,
                            )
                        }
                        val allSessions = (listOf(session.toSummary()) + sessions.filterNot { it.id == session.id })
                            .sortedByDescending { it.lastMessageAtMillis }

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
                            memoryStatus = null,
                            memoryRecords = emptyList(),
                        )
                    }
                    refreshMemory(session.id)
                    resumePendingMemory(session.id)

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
                            error = UiStrings.get(S.chatvm_load_character_failed, e.message),
                        )
                    }
                }
            }
        }
    }

    fun messageMacroContext(state: ChatUiState): app.tellev.core.prompt.MacroContext? {
        val character = state.selectedCharacter ?: return null
        val session = state.currentSession ?: return null
        return app.tellev.core.prompt.ChatTextProcessing.context(character, session,
            state.selectedPersona?.name ?: "User", state.selectedPersona).copy(
            globalVariables = promptEngine.snapshotPromptTemplateVariables().global,
            modelName = state.providerConfig?.model.orEmpty(),
            maxContextTokens = state.selectedPreset?.maxContextTokens ?: 0,
            maxResponseTokens = state.selectedPreset?.maxCompletionTokens ?: state.selectedPreset?.maxTokens ?: 0,
        )
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
                    _uiState.update { it.copy(error = UiStrings.get(S.chatvm_close_session_failed, error.message)) }
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
                        promptEngine, _uiState.value.selectedPreset,
                    )
                    val token = sessionRuntime.activateSessionWrites(newSession)
                    val sessions = dataStore.listChatSessionSummaries(characterId = character.id)

                    _uiState.update {
                        it.copy(
                            runtimeGeneration = token.generation,
                            currentSession = newSession,
                            messages = newSession.messages,
                            generatedImages = emptyList(),
                            chatBackgroundFile = null,
                            sessions = sessions,
                            memoryStatus = null,
                            memoryRecords = emptyList(),
                        )
                    }
                    refreshMemory(newSession.id)
                    reloadCharacterTavernHelperScripts(character)
                    ChatTavernAdapter.emitChatChanged(extensionHost, newSession)
                    ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, newSession.messages)
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(error = UiStrings.get(S.chatvm_create_session_failed, e.message))
                    }
                } finally {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /** First explicit choice wins; existing and new chats use the same path. */
    fun selectMemoryMode(mode: MemoryMode) {
        viewModelScope.launch {
            sessionRuntime.sessionTransitions.withLock {
                val session = _uiState.value.currentSession ?: return@withLock
                if (MemoryMode.of(session) != null) return@withLock
                try {
                    val selected = session.withMemoryMode(mode)
                    sessionRuntime.persistSessionMutation(session, selected) { updated ->
                        _uiState.update { state ->
                            if (state.currentSession?.id == updated.id) state.copy(currentSession = updated) else state
                        }
                    }
                    if (mode != MemoryMode.NONE) memoryService.initialize(_uiState.value.currentSession ?: selected, mode)
                    refreshMemory(session.id)
                    resumePendingMemory(session.id)
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = UiStrings.get(S.chatvm_save_memory_mode_failed, e.message)) }
                }
            }
        }
    }

    fun refreshMemory(sessionId: String? = null) {
        val id = sessionId ?: _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            val document = runCatching { memoryService.store.read(id) }.getOrNull()
            val settings = runCatching { memoryService.settings.read() }.getOrNull()
            _uiState.update { state ->
                if (state.currentSession?.id != id) state else state.copy(
                    memoryRecords = document?.records.orEmpty(),
                    memoryNeedsRebuild = document?.needsRebuild == true,
                    memoryStatus = document?.error ?: document?.stage,
                    memoryPluginEnabled = settings?.enabled == true,
                    memoryVectorEnabled = settings?.vectorEnabled == true,
                )
            }
        }
    }

    fun rebuildMemory() {
        val id = _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            try {
                memoryService.processPending(id, flushIdle = true, rebuild = true) { stage ->
                    _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = stage) else it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = UiStrings.get(S.chatvm_memory_rebuild_failed, e.message)) else it }
            } finally {
                refreshMemory(id)
            }
        }
    }

    fun retryMemory() {
        val id = _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            try {
                memoryService.processPending(id, force = true) { stage ->
                    _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = stage) else it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = UiStrings.get(S.chatvm_memory_retry_failed, e.message)) else it }
            }
            refreshMemory(id)
        }
    }

    /** Card script manager: every TavernHelper script in the selected character's card. */
    fun characterScriptEntries(): List<CharacterTavernHelperScripts.ScriptEntry> {
        val character = _uiState.value.selectedCharacter ?: return emptyList()
        return CharacterTavernHelperScripts.listScriptEntries(character)
    }

    /**
     * Flip one card script's `enabled` flag, persist the card losslessly and
     * hot-reload its script runtime. Changing the enabled set changes the
     * isolated source fingerprint, so the consent flow re-asks by design.
     */
    fun toggleCharacterScript(path: String) {
        val character = _uiState.value.selectedCharacter ?: return
        viewModelScope.launch {
            try {
                val entry = CharacterTavernHelperScripts.listScriptEntries(character)
                    .firstOrNull { it.path == path } ?: return@launch
                val patched = CharacterTavernHelperScripts.withScriptEnabledAt(character, path, !entry.enabled)
                    ?: return@launch
                dataStore.saveCharacter(patched)
                _uiState.update {
                    it.copy(
                        selectedCharacter = patched,
                        characterAvatarFile = it.characterAvatarFile,
                    )
                }
                reloadCharacterTavernHelperScripts(patched)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chatvm_script_toggle_failed, e.message)) }
            }
        }
    }

    fun correctMemory(recordId: String, newText: String?) {
        val id = _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            try {
                memoryService.correct(id, recordId, newText)
                refreshMemory(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chatvm_memory_correct_failed, e.message)) }
            }
        }
    }

    /** Restore a corrected record to one of its previous versions (0 = newest). */
    fun rollbackMemoryCorrection(recordId: String, historyIndex: Int) {
        val id = _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            try {
                memoryService.rollbackCorrection(id, recordId, historyIndex)
                refreshMemory(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chatvm_memory_correct_failed, e.message)) }
            }
        }
    }

    fun rebuildMemoryVectors() {
        val id = _uiState.value.currentSession?.id ?: return
        viewModelScope.launch {
            try {
                memoryService.rebuildVectors(id) { stage ->
                    _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = stage) else it }
                }
                refreshMemory(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { if (it.currentSession?.id == id) it.copy(memoryStatus = UiStrings.get(S.chatvm_memory_vectors_failed, e.message)) else it }
            }
        }
    }

    private fun resumePendingMemory(sessionId: String) {
        viewModelScope.launch {
            try {
                memoryService.processPending(sessionId, flushIdle = true) { stage ->
                    _uiState.update { if (it.currentSession?.id == sessionId) it.copy(memoryStatus = stage) else it }
                }
                refreshMemory(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _uiState.update { if (it.currentSession?.id == sessionId) it.copy(memoryStatus = UiStrings.get(S.chatvm_memory_resume_failed, e.message)) else it }
            }
        }
    }

    fun switchSession(sessionId: String) {
        viewModelScope.launch {
            sessionRuntime.sessionTransitions.withLock {
                _uiState.update { it.copy(isLoading = true) }
                try {
                    retireSessionRuntime()
                    val loaded = dataStore.readChatSession(sessionId)
                    val upgraded = _uiState.value.selectedCharacter?.let { character ->
                        loaded.withCharacterGreetingSwipes(character).withProcessedGreeting(
                            character, _uiState.value.selectedPersona?.name ?: "User", promptEngine, _uiState.value.selectedPreset,
                        )
                    } ?: loaded
                    val session = if (upgraded != loaded) dataStore.commitChatMutation(loaded, upgraded) else loaded
                    val token = sessionRuntime.activateSessionWrites(session)
                    _uiState.update {
                        it.copy(
                            runtimeGeneration = token.generation,
                            currentSession = session,
                            messages = session.messages,
                            chatBackgroundFile = ChatSessionAssets.chatBackgroundFileFor(session, dataStore.layout),
                            memoryStatus = null,
                            memoryRecords = emptyList(),
                        )
                    }
                    refreshMemory(session.id)
                    _uiState.value.selectedCharacter?.let { reloadCharacterTavernHelperScripts(it) }
                    imageGenCoordinator.refreshGeneratedImages(session.id) { targetSessionId, images ->
                        _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                    }
                    ChatTavernAdapter.emitChatChanged(extensionHost, session)
                    ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, session.messages)
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(error = UiStrings.get(S.chatvm_switch_session_failed, e.message))
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
        ) {
            loadedCharacterScriptExtensionId = it
            loadedCharacterScriptSource = null
            _uiState.update { state -> state.copy(characterUiExtensionId = it) }
        }
        _uiState.value.selectedCharacter?.let {
            extensionHost.unload(ChatTavernAdapter.characterScriptExtensionId(it.id))
        }
        sessionRuntime.retireSessionRuntime(extensionHost)
    }

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList()): Boolean {
        // 与消息编辑/滑动删除一致的门禁：会话切换/删除进行中不接受发送。
        if (_uiState.value.isLoading) {
            // 输入栏已经把发送控件置灰，这道门禁是第二道防线（脚本触发的发送、切会话瞬间的
            // 竞态点击都会走到这里）。拒绝不再静默：走既有的 error → Snackbar 通道给出
            // 本地化提示，草稿与附件由调用方保留，不在这里丢弃。
            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_send_blocked_loading)) }
            return false
        }
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

    /** 继续生成：最后一条角色/助手回复续写。没有可续写的回复时给出提示。 */
    fun continueGeneration(): Boolean {
        val last = _uiState.value.messages.lastOrNull() ?: run {
            _uiState.update { it.copy(error = UiStrings.get(S.chat_continue_empty)) }
            return false
        }
        return generationCoordinator.continueGeneration(
            messageId = last.id,
            uiState = _uiState,
            scope = viewModelScope,
            characterScriptJob = characterScriptJob,
        )
    }

    /**
     * 绑定/解绑本会话的世界书（ST 的 chat-level world_info）。
     * name 为空表示解除绑定；不存在的世界书给出错误提示。
     */
    fun setChatWorldBook(name: String?) {
        val session = _uiState.value.currentSession ?: return
        viewModelScope.launch {
            if (name != null && _uiState.value.worldBooks.none { it.name == name || it.id == name }) {
                _uiState.update { it.copy(error = UiStrings.get(S.chat_world_book_not_found, name)) }
                return@launch
            }
            val updated = session.copy(
                metadata = if (name == null) {
                    JsonObject(session.metadata - "world_info")
                } else {
                    JsonObject(session.metadata + ("world_info" to JsonPrimitive(name)))
                },
            )
            if (updated == session) return@launch
            sessionRuntime.persistSessionMutation(session, updated) { saved ->
                _uiState.update {
                    if (it.currentSession?.id == saved.id) {
                        it.copy(currentSession = saved, messages = saved.messages)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** 导出当前会话为 JSON（消息 + 角色/预设/用户设定元数据），供分享与备份。 */
    fun exportChatLog(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val state = _uiState.value
        val session = state.currentSession ?: return
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val messages = JsonArray(session.messages.map { message ->
                        JsonObject(
                            mapOf(
                                "role" to JsonPrimitive(message.role.name.lowercase()),
                                "name" to JsonPrimitive(message.name),
                                "content" to JsonPrimitive(message.content),
                                "createdAt" to JsonPrimitive(message.createdAtMillis),
                            ),
                        )
                    })
                    val payload = JsonObject(
                        mapOf(
                            "character" to JsonPrimitive(state.selectedCharacter?.name ?: ""),
                            "preset" to JsonPrimitive(state.selectedPreset?.name ?: ""),
                            "persona" to JsonPrimitive(state.selectedPersona?.name ?: ""),
                            "messages" to messages,
                        ),
                    ).toString()
                    requireNotNull(resolver.openOutputStream(uri))
                        .use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
            }
            _uiState.update {
                it.copy(
                    error = if (result.isSuccess) UiStrings.get(S.chat_export_log_saved)
                    else UiStrings.get(S.chat_export_log_failed, result.exceptionOrNull()?.message),
                )
            }
        }
    }

    /** 抽屉用：装载按角色分组的会话列表。当前角色的分组排最前。 */
    fun loadSessionGroups() {
        viewModelScope.launch {
            val groups = withContext(Dispatchers.Default) {
                val characters = _uiState.value.characters
                val currentId = _uiState.value.selectedCharacter?.id
                characters.map { character ->
                    val sessions = dataStore.listChatSessionSummaries(characterId = character.id)
                    CharacterSessionGroup(
                        characterId = character.id,
                        characterName = character.name,
                        sessions = sessions,
                        isCurrentCharacter = character.id == currentId,
                    )
                }.filter { it.sessions.isNotEmpty() }
                    .sortedWith(compareByDescending<CharacterSessionGroup> { it.isCurrentCharacter }
                        .thenByDescending { it.sessions.firstOrNull()?.lastMessageAtMillis ?: 0L })
            }
            val pinned = withContext(Dispatchers.IO) { ChatPinnedSessions.read(dataStore.layout.root) }
            _uiState.update { it.copy(sessionGroups = groups, pinnedSessionIds = pinned) }
        }
    }

    /** 抽屉里的 📌：切换置顶并落盘。 */
    fun togglePinnedSession(sessionId: String) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                ChatPinnedSessions.toggle(dataStore.layout.root, sessionId)
            }
            _uiState.update { it.copy(pinnedSessionIds = updated) }
        }
    }

    /**
     * 消息反馈（参考图一的 👍/👎）：写到消息 metadata 的 feedback 字段
     * （"up"/"down"，再次点击同一个清除）。不改消息内容、不触发重渲染管线。
     */
    fun setMessageFeedback(messageIndex: Int, feedback: String?) {
        val state = _uiState.value
        val session = state.currentSession ?: return
        if (messageIndex !in state.messages.indices) return
        viewModelScope.launch {
            val messages = state.messages.toMutableList()
            val message = messages[messageIndex]
            val current = (message.metadata["feedback"] as? JsonPrimitive)?.contentOrNull
            val nextMetadata = if (feedback == null || feedback == current) {
                JsonObject(message.metadata - "feedback")
            } else {
                JsonObject(message.metadata + ("feedback" to JsonPrimitive(feedback)))
            }
            messages[messageIndex] = message.copy(metadata = nextMetadata)
            val updated = session.copy(messages = messages)
            sessionRuntime.persistSessionMutation(session, updated) { saved ->
                _uiState.update {
                    if (it.currentSession?.id == saved.id) it.copy(currentSession = saved, messages = saved.messages)
                    else it
                }
            }
        }
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
                val reportError: (String) -> Unit = { error -> _uiState.update { it.copy(error = error) } }
                val commit = sessionRuntime.scheduleUiMutation(
                    baseSession, trimmedSession,
                    onSessionUpdated = { updated ->
                        _uiState.update {
                            if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages)
                            else it
                        }
                    },
                    onError = reportError,
                )
                if (commit != null) sessionRuntime.launchAfterCommit(viewModelScope, commit, reportError) {
                    if (_uiState.value.currentSession?.id != baseSession.id) return@launchAfterCommit
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_EDITED, messageIndex)
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_UPDATED, messageIndex)
                    ChatTavernAdapter.emitRenderedEventForMessage(extensionHost, messageIndex, updatedMessage, "edit")
                    sendMessageWithRole(rawContent, attachments, MessageRole.User, regexIsEdit = true)
                }
            },
        )
    }

    fun deleteMessage(messageIndex: Int) {
        val target = _uiState.value.messages.getOrNull(messageIndex) ?: return
        val session = _uiState.value.currentSession
        // 消息删除后的图片级联：删掉消息引用的 user/images 文件与对应画廊记录。
        val imageRelatives = target.attachments
            .mapNotNull { it.relativePath.takeIf { rel -> rel.startsWith("user/images/") } }
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
                if (imageRelatives.isNotEmpty() && session != null) {
                    runCatching { deleteImageFilesAndGalleryRecords(session.id, imageRelatives) }
                        .onFailure { err -> _uiState.update { it.copy(error = UiStrings.get(S.chatvm_cleanup_images_failed, err.message)) } }
                    imageGenCoordinator.refreshGeneratedImages(session.id) { targetSessionId, images ->
                        _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                    }
                }
            },
        )
    }

    /** Permanently delete image files under user/images and the gallery records referencing them. */
    private suspend fun deleteImageFilesAndGalleryRecords(sessionId: String, relativePaths: List<String>) {
        withContext(Dispatchers.IO) {
            val store = GeneratedImageStore(dataStore.layout)
            relativePaths.forEach { relative ->
                val file = dataStore.layout.root.resolve(relative).normalize()
                if (file.startsWith(dataStore.layout.userImages)) {
                    runCatching { java.nio.file.Files.deleteIfExists(file) }
                }
            }
            store.read(sessionId)
                .filter { record -> record.attachments.any { it.relativePath in relativePaths } }
                .forEach { store.remove(sessionId, it.id) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRuntime.sessionTransitions.withLock {
                _uiState.update { it.copy(isLoading = true) }
                val character = _uiState.value.selectedCharacter
                val deletingCurrent = _uiState.value.currentSession?.id == sessionId
                // 只停「归属于被删会话」的在途生图：当前会话删除时归属即当前；
                // 无条件停会误杀属于其他会话（切换前启动）的生成。
                val imageGenBelongsToSession = imageGenCoordinator.activeImageSessionId() == sessionId
                // 区分「删除本身失败」与「删除成功后的后续步骤失败」：前者才需要恢复现场。
                var deleteSucceeded = false
                try {
                    if (imageGenBelongsToSession) {
                        // 停掉归属于被删会话的在途生图：否则完成后会给已删除的
                        // 会话重建画廊与图片文件，撤销级联清理。
                        stopImageGeneration()
                    }
                    if (deletingCurrent) {
                        retireSessionRuntime()
                    }
                    dataStore.deleteChatSession(sessionId)
                    deleteSucceeded = true
                    val remaining = if (character != null) {
                        dataStore.listChatSessionSummaries(characterId = character.id)
                    } else {
                        emptyList()
                    }
                    if (!deletingCurrent) {
                        // 删除的是后台会话：当前会话与其脚本/生成状态保持不动，仅刷新列表。
                        _uiState.update { it.copy(sessions = remaining) }
                        return@withLock
                    }
                    if (remaining.isEmpty()) {
                        clearToNoSession()
                        ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHAT_CHANGED, "")
                    } else {
                        val loadedNext = dataStore.readChatSession(remaining.first().id)
                        val upgradedNext = character?.let {
                            loadedNext.withCharacterGreetingSwipes(it).withProcessedGreeting(it,
                                _uiState.value.selectedPersona?.name ?: "User", promptEngine, _uiState.value.selectedPreset)
                        } ?: loadedNext
                        val next = if (upgradedNext != loadedNext) dataStore.commitChatMutation(loadedNext, upgradedNext) else loadedNext
                        val token = sessionRuntime.activateSessionWrites(next)
                        _uiState.update {
                            it.copy(
                                runtimeGeneration = token.generation,
                                currentSession = next,
                                messages = next.messages,
                                chatBackgroundFile = ChatSessionAssets.chatBackgroundFileFor(next, dataStore.layout),
                                sessions = remaining,
                                generatedImages = emptyList(),
                            )
                        }
                        character?.let { reloadCharacterTavernHelperScripts(it) }
                        runCatching {
                            imageGenCoordinator.refreshGeneratedImages(next.id) { targetSessionId, images ->
                                _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                            }
                        }
                        ChatTavernAdapter.emitChatChanged(extensionHost, next)
                        ChatTavernAdapter.emitRenderedEventsForMessages(extensionHost, next.messages)
                    }
                } catch (e: Exception) {
                    if (!deleteSucceeded) {
                        // journal 半提交可能令删除抛错但文件已消失：以文件存在性为准，
                        // 绝不为已删会话重建写入环境（会制造注定失败的存储 owner 卡死切换）。
                        val stillExists = runCatching { dataStore.chatSessionExists(sessionId) }.getOrDefault(true)
                        if (stillExists && deletingCurrent) {
                            runCatching {
                                val current = _uiState.value.currentSession
                                if (current != null && sessionRuntime.currentRuntimeToken(sessionId) == null) {
                                    val token = sessionRuntime.activateSessionWrites(current)
                                    _uiState.update { it.copy(runtimeGeneration = token.generation) }
                                }
                                // retire 时角色脚本已被卸载，失败恢复需要重新加载。
                                character?.let { reloadCharacterTavernHelperScripts(it) }
                            }
                            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_delete_session_failed, e.message)) }
                        } else if (stillExists) {
                            // 后台会话删除失败：只报错，当前会话状态原封不动。
                            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_delete_session_failed, e.message)) }
                        } else if (!deletingCurrent) {
                            // 后台会话实际已被半提交删除：只报错并尽力刷新列表，
                            // 当前会话视图绝不动。
                            runCatching {
                                val remaining = character?.let { dataStore.listChatSessionSummaries(characterId = it.id) }.orEmpty()
                                _uiState.update { it.copy(sessions = remaining) }
                            }
                            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_deleted_cleanup_failed, e.message)) }
                        } else {
                            clearToNoSession()
                            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_deleted_cleanup_failed, e.message)) }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHAT_CHANGED, "")
                        }
                    } else if (!deletingCurrent) {
                        // 后台会话已删除、仅列表刷新失败：绝不动当前会话视图。
                        _uiState.update { it.copy(error = UiStrings.get(S.chatvm_deleted_refresh_failed, e.message)) }
                    } else {
                        clearToNoSession()
                        _uiState.update { it.copy(error = UiStrings.get(S.chatvm_deleted_refresh_failed, e.message)) }
                        ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHAT_CHANGED, "")
                    }
                } finally {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun clearToNoSession() {
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
    }

    fun deleteGeneratedImage(imageId: String) {
        val session = _uiState.value.currentSession ?: return
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    val store = GeneratedImageStore(dataStore.layout)
                    val record = store.remove(session.id, imageId)
                    record?.attachments
                        ?.mapNotNull { it.relativePath.takeIf { rel -> rel.startsWith("user/images/") } }
                        ?.forEach { relative ->
                            val file = dataStore.layout.root.resolve(relative).normalize()
                            if (file.startsWith(dataStore.layout.userImages)) {
                                runCatching { java.nio.file.Files.deleteIfExists(file) }
                            }
                        }
                    record
                }
                if (removed != null) {
                    imageGenCoordinator.refreshGeneratedImages(session.id) { targetSessionId, images ->
                        _uiState.update { if (it.currentSession?.id == targetSessionId) it.copy(generatedImages = images) else it }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chatvm_delete_image_failed, e.message)) }
            }
        }
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

    /**
     * Per-session reasoning effort override, persisted in the session's
     * metadata and honored by every main-line generation of this chat.
     * [ReasoningEffort.Auto] (or null) clears the override so the preset's
     * own field applies again.
     */
    fun setSessionReasoningEffort(effort: ReasoningEffort?) {
        val session = _uiState.value.currentSession ?: return
        val updated = session.copy(metadata = ReasoningSupport.withSessionOverride(session.metadata, effort))
        if (updated.metadata == session.metadata) return
        viewModelScope.launch {
            try {
                sessionRuntime.persistSessionMutation(session, updated) { saved ->
                    _uiState.update {
                        if (it.currentSession?.id == saved.id) it.copy(currentSession = saved, messages = saved.messages)
                        else it
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chat_reasoning_set_failed, e.message)) }
            }
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

    /**
     * Switch the chat model without leaving the conversation.
     *
     * Built-in providers keep their model in the `provider-<id>-model` secret,
     * which is exactly what [GenerationRuntimeResolver] reads back, so a write
     * here propagates through [observeProviderChanges] on the next send.
     * Custom (`custom:<id>`) endpoints keep their model inside the custom
     * config JSON — that one is edited in Settings, so it is reported as
     * unsupported here instead of silently doing nothing.
     */
    fun selectModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_model_blank)) }
            return
        }
        val providerId = _uiState.value.selectedProvider
        if (app.tellev.core.provider.ProviderConfigPersistence.isCustomConfigId(providerId)) {
            _uiState.update { it.copy(error = UiStrings.get(S.chatvm_model_custom_unsupported)) }
            return
        }
        viewModelScope.launch {
            try {
                secretStore.putSecret("provider-$providerId-model", trimmed)
                // 写密钥已触发 secretStore.changes -> refreshRuntimeState；这里再显式
                // 刷新一次，保证读取失败时用户能看到错误而不是静默回退。
                refreshRuntimeState(S.chatvm_reread_provider_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiStrings.get(S.chatvm_model_set_failed, e.message)) }
            }
        }
    }

    /**
     * Side-effect-free draft optimization for the input bar: resolves the same
     * runtime as a chat send (provider + preset) but never writes messages,
     * variables or extension events. Returns false when it cannot start
     * (no provider / adapter without chat capability).
     */
    fun optimizeDraft(
        text: String,
        options: app.tellev.core.prompt.PromptOptimizationOptions,
        onPreview: (String) -> Unit,
        onDone: (app.tellev.core.prompt.PromptOptimizationResult) -> Unit,
    ): Boolean {
        promptOptimizationJob?.cancel()
        promptOptimizationJob = viewModelScope.launch {
            try {
                val runtime = runtimeResolver.resolve(_uiState.value.selectedPersona?.id)
                val adapter = providerRegistry.find(runtime.providerConfig.providerType)
                    ?.takeIf { it.supportsChatGeneration }
                if (adapter == null) {
                    onDone(app.tellev.core.prompt.PromptOptimizationResult.failed(
                        listOf(UiStrings.get(S.chat_optimize_unavailable)),
                    ))
                    return@launch
                }
                val result = promptOptimizer.optimize(
                    input = text,
                    options = options,
                    config = runtime.providerConfig,
                    adapter = adapter,
                    preset = runtime.preset,
                    onPreview = onPreview,
                )
                onDone(result)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onDone(app.tellev.core.prompt.PromptOptimizationResult.failed(
                    listOf(error.message ?: UiStrings.get(S.chat_optimize_failed_generic)),
                ))
            }
        }
        return true
    }

    fun cancelPromptOptimization() {
        promptOptimizationJob?.cancel()
        promptOptimizationJob = null
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
                    _uiState.update { it.copy(error = UiStrings.get(S.chatvm_load_preset_failed, error.message)) }
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
        imageGenCoordinator.refreshImageGenAvailability { available, configured, engine, profiles ->
            _uiState.update {
                it.copy(
                    imageGenAvailable = available,
                    configuredImageEngines = configured,
                    imageEngine = engine,
                    imageProfiles = profiles,
                )
            }
        }
    }

    /**
     * Serializes [reloadCharacterTavernHelperScripts]. Concurrent triggers
     * (session open, preset switch, settings change) used to interleave the
     * adapter's unload+load: both captured the same currentLoadedId, so the
     * first load's runtime survived while the second was installed and the
     * `loadedCharacterScriptExtensionId` callback order was arbitrary — a
     * stale script runtime could keep running. Queued calls re-check inside
     * the lock, so a reload that another call already covered becomes a no-op.
     */
    private val scriptReloadMutex = Mutex()

    private suspend fun reloadCharacterTavernHelperScripts(character: CharacterCard) =
        scriptReloadMutex.withLock {
            reloadCharacterTavernHelperScriptsLocked(character)
        }

    private suspend fun reloadCharacterTavernHelperScriptsLocked(character: CharacterCard) {
        val scriptSource = CharacterTavernHelperScripts.buildIsolatedScriptSource(character, _uiState.value.selectedPreset)
        if (loadedCharacterScriptExtensionId == ChatTavernAdapter.characterScriptExtensionId(character.id) &&
            loadedCharacterScriptSource == scriptSource) return

        if (scriptSource.isBlank()) {
            // 无脚本的卡片：清掉确认弹窗/停用横幅；已装载的旧脚本仍要经适配器卸载。
            _uiState.update { it.copy(pendingScriptConsent = null, characterScriptsDisabled = null) }
        } else if (!isScriptLoadAllowed(character, scriptSource)) {
            // A1：卡内脚本不再随聊天打开自动执行——首次装载（或脚本集变化后）必须先经确认。
            return
        }

        ChatTavernAdapter.reloadCharacterTavernHelperScripts(
            character = character,
            preset = _uiState.value.selectedPreset,
            extensionHost = extensionHost,
            permissionManager = permissionManager,
            currentLoadedId = loadedCharacterScriptExtensionId,
            onLoadedIdChanged = { id ->
                loadedCharacterScriptExtensionId = id
                loadedCharacterScriptSource = scriptSource.takeIf { id != null }
                _uiState.update { it.copy(characterUiExtensionId = id) }
            },
            onError = { err -> _uiState.update { it.copy(error = err) } },
        )
    }

    /** 同意判定。未获同意时在 uiState 上挂出确认弹窗或停用横幅并返回 false。 */
    private suspend fun isScriptLoadAllowed(character: CharacterCard, scriptSource: String): Boolean {
        val fingerprint = CharacterScriptConsentStore.fingerprint(scriptSource)
        val consent = runCatching { scriptConsentStore.read(character.id) }.getOrNull()
        if (consent?.approved == true && consent.fingerprint == fingerprint) {
            _uiState.update {
                it.copy(
                    pendingScriptConsent = it.pendingScriptConsent?.takeIf { p -> p.characterId != character.id },
                    characterScriptsDisabled = it.characterScriptsDisabled?.takeIf { d -> d.characterId != character.id },
                )
            }
            return true
        }
        val prompt = CharacterScriptConsent(
            characterId = character.id,
            fingerprint = fingerprint,
            scriptNames = CharacterTavernHelperScripts.scriptNames(character, _uiState.value.selectedPreset),
        )
        val persistedDeny = consent != null && !consent.approved && consent.fingerprint == fingerprint
        if (persistedDeny || sessionDismissedScripts[character.id] == fingerprint) {
            _uiState.update { it.copy(pendingScriptConsent = null, characterScriptsDisabled = prompt) }
        } else {
            _uiState.update { it.copy(pendingScriptConsent = prompt, characterScriptsDisabled = null) }
        }
        return false
    }

    /** 弹窗「启用并记住」：持久化同意并按当前指纹装载脚本。 */
    fun approveCharacterScripts() {
        val prompt = _uiState.value.pendingScriptConsent ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { scriptConsentStore.write(prompt.characterId, approved = true, fingerprint = prompt.fingerprint) }
                .onFailure { e ->
                    _uiState.update { it.copy(error = UiStrings.get(S.chatvm_script_consent_save_failed, e.message)) }
                }
            sessionDismissedScripts.remove(prompt.characterId)
            _uiState.update { it.copy(pendingScriptConsent = null, characterScriptsDisabled = null) }
            // 只在用户仍停留在该角色时立即装载；否则下次打开聊天时按已记住的同意自动装载。
            if (_uiState.value.selectedCharacter?.id != prompt.characterId) return@launch
            val card = runCatching { dataStore.readCharacter(prompt.characterId) }.getOrNull() ?: return@launch
            characterScriptJob = viewModelScope.launch(Dispatchers.Default) {
                reloadCharacterTavernHelperScripts(card)
            }
        }
    }

    /** 弹窗「禁用并记住」(persist=true)或「暂不启用」(persist=false)。 */
    fun denyCharacterScripts(persist: Boolean) {
        val prompt = _uiState.value.pendingScriptConsent ?: return
        sessionDismissedScripts[prompt.characterId] = prompt.fingerprint
        _uiState.update { it.copy(pendingScriptConsent = null, characterScriptsDisabled = prompt) }
        if (!persist) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { scriptConsentStore.write(prompt.characterId, approved = false, fingerprint = prompt.fingerprint) }
        }
    }

    /** 停用横幅上的「启用」：重新唤起确认弹窗。 */
    fun requestScriptConsentPrompt() {
        val disabled = _uiState.value.characterScriptsDisabled ?: return
        sessionDismissedScripts.remove(disabled.characterId)
        _uiState.update { it.copy(pendingScriptConsent = disabled, characterScriptsDisabled = null) }
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
        promptOptimizationJob?.cancel()
        generationCoordinator.clearInterruptionJob()
        externalChatWritePort?.register(null)
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
    private val externalChatWritePort: MutableExternalChatWritePort? = null,
    private val imageDownloader: (suspend (String) -> ByteArray?)? = null,
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
                externalChatWritePort = externalChatWritePort,
                imageDownloader = imageDownloader,
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
