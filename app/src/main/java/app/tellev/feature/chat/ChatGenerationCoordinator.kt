package app.tellev.feature.chat

import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.reasoningParts
import app.tellev.core.model.withGenerationReasoning
import app.tellev.core.memory.MemoryService
import app.tellev.core.metrics.GenerationMetricsCalculator
import app.tellev.core.metrics.GenerationMetricsStore
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.prompt.TokenBudget
import app.tellev.core.prompt.DEFAULT_MAX_CONTEXT_TOKENS
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.GenerationRuntimeResolver
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ModelReasoningProfile
import app.tellev.core.provider.ModelReasoningProfiles
import app.tellev.core.provider.ReasoningKnowledgeBase
import app.tellev.core.provider.resolveCharacterCast
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.provider.json
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.prompt.ChatTextProcessing
import app.tellev.core.storage.StDataStore
import app.tellev.feature.chat.ChatSessionInit.generateMessageId
import app.tellev.feature.chat.ChatSessionInit.withTavernInitVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class ActiveRegeneration(
    val messageId: String,
)

/**
 * Coordinates LLM text generation: user messaging, regeneration,
 * streaming response handling, graceful cancellation/interruption, and
 * extension generation calls.
 */
internal class ChatGenerationCoordinator(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val promptEngine: PromptEngine,
    private val extensionHost: ExtensionHost,
    private val sessionRuntime: ChatSessionRuntime,
    private val runtimeResolver: GenerationRuntimeResolver,
    private val memoryService: MemoryService,
    private val metricsStore: GenerationMetricsStore? = null,
) {
    var generationJob: Job? = null
        private set

    var interruptionJob: Job? = null
        private set

    /**
     * 上下文上限分层（与 ChatViewModel.resolvedContextWindow 同一口径）：
     * 用户档案 > 知识库 > adapter 声明 > 预设值。均无 → null（未知，UI 不算比率）。
     */
    private fun resolveContextWindow(
        config: app.tellev.core.provider.ProviderConfig,
        preset: app.tellev.core.model.GenerationPreset,
    ): Int? {
        val model = config.model?.takeIf(String::isNotBlank)
        if (model != null) {
            ModelReasoningProfiles.read(dataStore.layout.root).profiles[model]?.contextWindow?.let { return it.toInt() }
            ReasoningKnowledgeBase.suggest(model).contextWindow?.let { return it.toInt() }
        }
        providerRegistry.find(config.providerType)?.declaredContextWindow(config)?.let { return it.toInt() }
        return preset.maxContextTokens
    }

    var activeRegeneration: ActiveRegeneration? = null
        private set

    /** 继续生成中的目标消息 id：流式结果按「追加」写回该消息，而不是新开一条。 */
    var activeContinue: String? = null
        private set

    /**
     * True while an extension-triggered generation ([generateTextFromExtension])
     * is streaming. Unlike [generationJob] this is not user-cancellable and does
     * not flip the chat UI into its generating state, but it gates new main-line
     * sends the same way so the two streams cannot interleave their variable
     * and message writes.
     */
    @Volatile
    var extensionGenerationActive = false
        private set

    /** Serializes extension-triggered generations among themselves. */
    private val extensionGenerationMutex = Mutex()

    val isGenerating: Boolean
        get() = generationJob?.isActive == true

    fun clearInterruptionJob() {
        interruptionJob = null
    }

    fun sendMessageWithRole(
        text: String,
        attachments: List<Attachment>,
        messageRole: MessageRole,
        regenerationMessageId: String? = null,
        continueMessageId: String? = null,
        regexIsEdit: Boolean = false,
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
        characterScriptJob: Job?,
    ): Boolean {
        val messageText = text.trim()
        if (regenerationMessageId == null && continueMessageId == null && messageText.isBlank() && attachments.isEmpty()) return false

        val state = uiState.value
        if (state.isGenerating || isGenerating || extensionGenerationActive) return false
        val character = state.selectedCharacter
        if (character == null) {
            uiState.update { it.copy(error = UiStrings.get(S.chatgenco_select_character_first)) }
            return false
        }
        val session = state.currentSession
        if (session == null) {
            uiState.update { it.copy(error = UiStrings.get(S.chatgenco_no_session)) }
            return false
        }
        val regenerationIndex = regenerationMessageId?.let { messageId ->
            state.messages.indexOfFirst { it.id == messageId }
        }
        if (regenerationMessageId != null &&
            (regenerationIndex == null || !canRegenerateResponse(state.messages, regenerationIndex))
        ) {
            uiState.update { it.copy(error = UiStrings.get(S.chatgenco_regen_last_only)) }
            return false
        }
        // 继续生成：目标必须是最后一条角色/助手回复（开场白之后也能继续）；
        // 不像重新生成那样强制前文有用户消息。
        val continueIndex = continueMessageId?.let { messageId ->
            state.messages.indexOfFirst { it.id == messageId }.takeIf { index ->
                index >= 0 && index == state.messages.lastIndex &&
                    (state.messages[index].role == MessageRole.Character ||
                        state.messages[index].role == MessageRole.Assistant)
            }
        }
        if (continueMessageId != null && continueIndex == null) {
            uiState.update { it.copy(error = UiStrings.get(S.chat_continue_empty)) }
            return false
        }

        val regenerationInputIndex = regenerationIndex?.let { targetIndex ->
            state.messages.take(targetIndex).indexOfLast { it.role == MessageRole.User }
        }
        val regenerationInput = regenerationInputIndex
            ?.takeIf { it >= 0 }
            ?.let(state.messages::get)
        if (regenerationMessageId != null && regenerationInput == null) {
            uiState.update { it.copy(error = UiStrings.get(S.chatgenco_regen_input_missing)) }
            return false
        }
        // Stable-id anchor for the post-flush re-resolution below (G17).
        val regenerationInputId = regenerationInput?.id

        scope.launch(start = CoroutineStart.LAZY) {
            try {
                characterScriptJob?.join()
                sessionRuntime.flushSessionWrites(session.id, extensionHost)
                check(uiState.value.currentSession?.id == session.id) { "生成所属会话已经切换" }
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
                uiState.update {
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

                val readySession = requireNotNull(uiState.value.currentSession?.takeIf { it.id == session.id })
                val initializedSession = readySession.withTavernInitVariables(
                    character = character,
                    worldBooks = ChatTavernStorage.activeWorldBooks(runtime.activeWorldBooks, runtime.worldBooks, character, state.currentSession),
                )
                if (initializedSession != readySession) {
                    sessionRuntime.persistSessionMutation(readySession, initializedSession) { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                    }
                }

                val isContinue = continueMessageId != null
                // 继续生成不产生新消息：目标回复留在列表末尾，即模型的续写上文。
                val processedInput = if (regenerationInput == null && !isContinue) promptEngine.processChatText(
                    messageText, messageRole, character, preset,
                    ChatTextProcessing.context(character, initializedSession, runtime.persona?.name ?: "User", runtime.persona),
                    isEdit = regexIsEdit,
                ) else null
                // 文本附件（选取时提取的 textContent）拼进本轮 userInput：模型能直接
                // 读到文档内容；气泡里的消息正文保持原样，不受拼接影响。
                val attachmentContext = attachments.mapNotNull { attachment ->
                    val content = attachment.metadata["textContent"]
                        ?.let { it as? JsonPrimitive }?.content
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    StringBuilder().apply {
                        append("[附件 ").append(attachment.name).append(']')
                        append('\n')
                        append(content)
                    }.toString()
                }.joinToString("\n\n")
                val promptUserInputSuffix = attachmentContext.take(16_000)
                val inputMessage = when {
                    isContinue -> null
                    regenerationInput != null -> regenerationInput
                    else -> CharacterRegexApplier.markNormalProcessed(ChatMessage(
                        id = generateMessageId(),
                        role = messageRole,
                        name = if (messageRole == MessageRole.System) "System" else runtime.persona?.name ?: "你",
                        content = requireNotNull(processedInput).text,
                        createdAtMillis = System.currentTimeMillis(),
                        attachments = attachments,
                    ))
                }

                val isRegeneration = regenerationMessageId != null
                val baseSessionMessages = initializedSession.messages
                val updatedMessages = when {
                    isRegeneration -> baseSessionMessages
                    isContinue -> baseSessionMessages
                    // requireNotNull 把列表元素类型收窄成 ChatMessage：可空元素会顺着
                    // baseMessages 一路污染后续的消息写入路径。
                    else -> baseSessionMessages + requireNotNull(inputMessage)
                }
                val updatedSession = initializedSession.copy(messages = updatedMessages,
                    metadata = processedInput?.let { JsonObject(initializedSession.metadata + ("variables" to it.localVariables)) } ?: initializedSession.metadata)

                if (!isRegeneration && !isContinue) {
                    sessionRuntime.persistSessionMutation(initializedSession, updatedSession) { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                    }
                }
                activeRegeneration = regenerationMessageId?.let(::ActiveRegeneration)
                activeContinue = continueMessageId

                uiState.update {
                    it.copy(
                        isGenerating = true,
                        streamingText = "",
                        streamingReasoning = "",
                        error = null,
                    )
                }
                if (!isRegeneration) {
                    val inputMessageIndex = updatedMessages.lastIndex
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SENT, inputMessageIndex)
                    if (messageRole == MessageRole.User) {
                        ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.USER_MESSAGE_RENDERED, inputMessageIndex)
                    }
                }
                ChatTavernAdapter.emitStEvent(
                    extensionHost,
                    StEventCatalog.GENERATION_STARTED,
                    if (isRegeneration) "swipe" else "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                    false,
                )

                ChatTavernAdapter.emitStEvent(
                    extensionHost,
                    StEventCatalog.GENERATION_AFTER_COMMANDS,
                    "normal",
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                    },
                    false,
                )

                sessionRuntime.flushSessionWrites(updatedSession.id, extensionHost)
                val promptSession = requireNotNull(uiState.value.currentSession?.takeIf { it.id == updatedSession.id })
                val promptMessages = promptSession.messages
                // Hooks may edit or insert floors. Resolve the accepted input by
                // stable identity after flushing, never by its pre-hook index/text.
                val promptInputIndex = inputMessage
                    ?.let { promptMessages.indexOfFirst { m -> m.id == it.id } } ?: -1
                val promptInput = if (promptInputIndex >= 0) {
                    check(promptMessages.count { it.id == inputMessage!!.id } == 1) {
                        "输入消息标识重复，无法确定生成上下文，请重新发送"
                    }
                    promptMessages[promptInputIndex].also { resolved ->
                        if (isRegeneration || messageRole == MessageRole.User) {
                            check(resolved.role == MessageRole.User && !resolved.isHidden) {
                                "生成前输入消息已被隐藏或改变角色，请重新发送"
                            }
                            check(isRegeneration || promptInputIndex == promptMessages.lastIndex) {
                                "生成前输入消息已不在会话末尾，请重新发送"
                            }
                        }
                    }
                } else {
                    // 继续生成：没有新的输入楼层，宏上下文沿用既有历史。
                    check(isContinue) { "生成前输入消息已被删除，请重新发送" }
                    promptMessages.lastOrNull()
                }
                val promptInputText = promptInput?.reasoningParts()?.body.orEmpty()
                val possibleRawIds = mutableSetOf<String>()
                val rawBudget = ((preset.maxContextTokens ?: DEFAULT_MAX_CONTEXT_TOKENS) -
                    (preset.maxCompletionTokens ?: preset.maxTokens ?: 0)).coerceAtLeast(0)
                var rawTokens = 0
                for (message in promptMessages.asReversed()) {
                    possibleRawIds.add(message.id)
                    rawTokens += TokenBudget.estimateTokens(message.content) + 4
                    if (rawTokens > rawBudget) break
                }
                val memoryDetail = try {
                    memoryService.contextDetail(
                        promptSession, promptInputText,
                        possibleRawIds,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    app.tellev.core.memory.MemoryContextDetail("", emptyList()) // Memory retrieval cannot prevent a normal chat reply.
                }
                val memoryContext = memoryDetail.text
                // G17: regenerationInputIndex was computed against the
                // pre-launch UI snapshot; the two flushSessionWrites above can
                // replay extension chat writes that insert or remove messages.
                // Re-resolve the input message by stable id in the flushed
                // view; only fall back to the stale index when the id vanished.
                val regenerationHistoryCut = if (isRegeneration) {
                    val byId = regenerationInputId
                        ?.let { id -> promptMessages.indexOfFirst { it.id == id } }
                        ?.takeIf { it >= 0 }
                    byId ?: regenerationInputIndex!!.coerceIn(0, promptMessages.size)
                } else 0
                // 群像（1.7.1.4）：附属角色设定进主请求；其世界书一并激活。
                val supportingCast = resolveCharacterCast(character, dataStore)
                val supportingBooks = supportingCast.flatMap { member ->
                    ChatTavernStorage.activeWorldBooks(emptyList(), runtime.worldBooks, member, null)
                }
                val promptRequest = PromptBuildRequest(
                    character = character,
                    supportingCharacters = supportingCast,
                    persona = runtime.persona,
                    messages = when {
                        isRegeneration -> promptMessages.take(regenerationHistoryCut)
                        // 继续生成：目标回复留在提示词末尾，模型据此续写。
                        isContinue -> promptMessages
                        messageRole == MessageRole.User ->
                            promptHistoryBeforeCurrentMessage(promptMessages, promptInput?.id.orEmpty())
                        else -> promptMessages
                    },
                    worldBooks = (ChatTavernStorage.activeWorldBooks(runtime.activeWorldBooks, runtime.worldBooks, character, state.currentSession) + supportingBooks).distinctBy { it.id },
                    preset = preset,
                    userInput = when {
                        isRegeneration -> promptInputText
                        // 继续生成没有新输入：续写上文由目标回复承担。
                        isContinue -> ""
                        messageRole == MessageRole.User ->
                            promptInputText + promptUserInputSuffix
                        else -> ""
                    },
                    // ST macros see the saved current input even though wire history
                    // excludes it. A swipe must not see the reply it is replacing.
                    macroMessages = if (isRegeneration) {
                        promptMessages.take(promptInputIndex + 1)
                    } else {
                        promptMessages
                    },
                    providerType = config.providerType,
                    metadata = JsonObject(
                        ChatPromptBuilder.buildPromptMetadata(
                            state = runtimeState,
                            config = config,
                            preset = preset,
                            session = promptSession,
                            extensionHost = extensionHost,
                            dataStore = dataStore,
                            promptEngine = promptEngine,
                        ) + ("userInputNormalProcessed" to JsonPrimitive(
                            promptInput?.let { CharacterRegexApplier.isNormalProcessed(it) } ?: true,
                        )) + ("tellevMemoryContext" to JsonPrimitive(memoryContext)),
                    ),
                )

                val promptResult = ChatPromptBuilder.buildPromptWithSessionScope(promptRequest, promptSession, promptEngine)
                // Context viewer snapshot: what this turn actually sends. In-memory
                // only; the viewer ignores it once another session is opened.
                uiState.update {
                    it.copy(
                        contextSnapshot = ContextSnapshot(
                            sessionId = promptSession.id,
                            capturedAtMillis = System.currentTimeMillis(),
                            messages = promptResult.messages.map { message ->
                                ContextMessageSnapshot(
                                    role = message.role.name.lowercase(),
                                    name = message.name,
                                    content = message.content,
                                )
                            },
                            estimatedTokenCount = promptResult.diagnostics.estimatedTokenCount,
                            // dsh contextWindow 语义：路由容量分层（档案>知识库>
                            // adapter>预设），预设兜底时不再编造 1M 内部值——null 即未知。
                            contextTokenLimit = resolveContextWindow(config, preset),
                            warnings = promptResult.diagnostics.warnings,
                            worldBookHits = promptResult.diagnostics.worldBookHits,
                            rejectedWorldEntries = promptResult.diagnostics.rejectedWorldEntries,
                            memoryInjections = memoryDetail.injected.map { injected ->
                                ContextMemoryInjection(
                                    recordId = injected.recordId,
                                    kind = injected.kind,
                                    text = injected.text,
                                    score = injected.score,
                                    sourceMessageIds = injected.sourceIds,
                                )
                            },
                        ),
                    )
                }
                ChatPromptBuilder.persistPromptTemplateVariableUpdates(
                    updates = promptResult.promptTemplateVariableUpdates,
                    targetSessionId = updatedSession.id,
                    getCurrentSession = { uiState.value.currentSession },
                    sessionRuntime = sessionRuntime,
                    onSessionUpdated = { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                    },
                    promptEngine = promptEngine,
                )
                ChatPromptBuilder.emitPromptDiagnostics(promptResult, extensionHost)

                val adapter = providerRegistry.require(config.providerType)
                val prepared = ChatCompletionEvents.prepare(extensionHost, config, GenerateRequest(
                    prompt = promptResult,
                    preset = preset,
                    attachments = if (isRegeneration || messageRole == MessageRole.User) {
                        promptInput?.attachments.orEmpty()
                    } else {
                        attachments
                    },
                    stream = true,
                    // Per-session reasoning effort override rides on request
                    // metadata; adapters resolve it ahead of the preset field.
                    metadata = run {
                        // 会话档位 override + 该模型的思考档案（bre 每模型声明）一起
                        // 挂上请求元数据；适配器按档案拼写落 body。
                        val base = ReasoningSupport.sessionOverrideMetadata(promptSession)
                        val profile = ModelReasoningProfiles.read(dataStore.layout.root)
                            .profiles[config.model]
                        if (profile == null) base
                        else JsonObject(base + ("tellev_model_profile" to json.encodeToJsonElement(
                            ModelReasoningProfile.serializer(), profile,
                        )))
                    },
                ), adapter)
                val flow = adapter.streamGenerate(prepared.config, prepared.request)
                val generationStartedAtMs = System.currentTimeMillis()
                var firstDeltaAtMs: Long? = null
                var accumulatedText = ""
                var accumulatedReasoning = ""
                // 本次生成的会话账本桶（Completed 时填充；默认零桶=不入账）。
                var metricsLedgerBuckets: ChatTokenUsageLedger.Buckets? = null
                var metricsLedgerReasoning = 0L

                flow.collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> {
                            if (firstDeltaAtMs == null && (chunk.text.isNotEmpty() || chunk.reasoning.isNotEmpty())) {
                                firstDeltaAtMs = System.currentTimeMillis()
                            }
                            accumulatedText += chunk.text
                            accumulatedReasoning += chunk.reasoning
                            uiState.update { it.copy(streamingText = accumulatedText, streamingReasoning = accumulatedReasoning) }
                        }
                        is GenerateChunk.Completed -> {
                            metricsStore?.let { store ->
                                val completedAtMs = System.currentTimeMillis()
                                val metrics = GenerationMetricsCalculator.fromCompletion(
                                    providerType = config.providerType,
                                    model = config.model,
                                    usage = chunk.usage,
                                    estimatedPromptTokens = promptResult.diagnostics.estimatedTokenCount,
                                    deltaText = accumulatedText.ifEmpty { chunk.text },
                                    startedAtMs = generationStartedAtMs,
                                    firstDeltaAtMs = firstDeltaAtMs,
                                    completedAtMs = completedAtMs,
                                    sessionId = updatedSession.id,
                                )
                                // 账本只收 provider 上报的精确样本；估算样本给零桶。
                                metricsLedgerBuckets = ChatTokenUsageLedger.bucketsOf(metrics)
                                metricsLedgerReasoning = (metrics.reasoningTokens ?: 0).toLong()
                                scope.launch { runCatching { store.record(metrics) } }
                            }
                            // 服务商 HTTP 200 但响应体为空（网关/中转站错误帧被适配器吞掉、
                            // 内容拦截等）：显式报错，不再把空气泡持久化进会话。
                            val rawFinalText = chunk.text
                            val parts = MessageReasoning.fromResponse(rawFinalText, chunk.reasoning)
                            val finalBase = uiState.value.currentSession?.takeIf { it.id == updatedSession.id } ?: updatedSession
                            val processedFinal = promptEngine.processChatText(parts.body, MessageRole.Character, character, preset,
                                ChatTextProcessing.context(character, finalBase, runtime.persona?.name ?: "User", runtime.persona),
                                expandMacros = false)
                            val finalText = processedFinal.text
                            if (finalText.isBlank()) {
                                activeRegeneration = null
                                activeContinue = null
                                uiState.update {
                                    it.copy(
                                        isGenerating = false,
                                        streamingText = "",
                                        streamingReasoning = "",
                                        error = UiStrings.get(S.chatgenco_empty_reply),
                                    )
                                }
                                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                                return@collect
                            }
                            val latestState = uiState.value
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
                            val continueTarget = activeContinue
                            val continueIndex = continueTarget?.let { id ->
                                baseMessages.indexOfFirst { it.id == id }
                            } ?: -1
                            val finalMessages = when {
                                regeneration != null && regeneratedIndex >= 0 -> {
                                    baseMessages.toMutableList().also { messages ->
                                        messages[regeneratedIndex] = CharacterRegexApplier.markNormalProcessed(
                                            messages[regeneratedIndex].withRegeneratedSwipe(finalText).withGenerationReasoning(
                                                parts, rawFinalText, chunk.reasoning, chunk.finishReason, true,
                                            ),
                                        )
                                    }
                                }
                                // 继续生成：把新文本追加到目标回复当前 swipe 的内容后面，
                                // 不新开消息、不产生新 swipe。
                                continueTarget != null && continueIndex >= 0 -> {
                                    baseMessages.toMutableList().also { messages ->
                                        messages[continueIndex] = CharacterRegexApplier.markNormalProcessed(
                                            messages[continueIndex].withContinuedText(finalText).withGenerationReasoning(
                                                parts, rawFinalText, chunk.reasoning, chunk.finishReason, true,
                                            ),
                                        )
                                    }
                                }
                                else -> baseMessages + CharacterRegexApplier.markNormalProcessed(ChatMessage(
                                    id = generateMessageId(),
                                    role = MessageRole.Character,
                                    name = character.name,
                                    content = finalText,
                                    createdAtMillis = System.currentTimeMillis(),
                                    swipes = listOf(finalText),
                                    swipeIndex = 0,
                                    // ST clones the generated floor's variables from the
                                    // previous message; message-scope writes performed by
                                    // the generate-phase templates land on the new floor.
                                    variables = listOfNotNull(promptResult.promptTemplateVariableUpdates.message),
                                ).withGenerationReasoning(parts, rawFinalText, chunk.reasoning, chunk.finishReason, false))
                            }
                            // ── 会话级 token 账本（dsh tokenUsage 投影）──
                            // 本 swipe 的桶 = 本次生成的 provider 上报；重新生成/继续时
                            // 先减去该消息旧 swipe 的桶（addReplacing，重刷不重复计数）。
                            val ledgerBuckets = metricsLedgerBuckets
                            val ledgerTargetIndex = when {
                                regeneratedIndex >= 0 -> regeneratedIndex
                                continueIndex >= 0 -> continueIndex
                                else -> finalMessages.lastIndex
                            }
                            val ledgerPrev = if ((regeneratedIndex >= 0 || continueIndex >= 0) && ledgerTargetIndex in finalMessages.indices) {
                                ChatTokenUsageLedger.messageBuckets(baseMessages.getOrNull(ledgerTargetIndex)?.metadata)
                            } else ChatTokenUsageLedger.Buckets()
                            val ledgerMessages = finalMessages.mapIndexed { idx, msg ->
                                if (idx == ledgerTargetIndex && ledgerBuckets != null) {
                                    msg.copy(metadata = ChatTokenUsageLedger.messageMetadataWith(
                                        msg.metadata, ledgerBuckets, metricsLedgerReasoning,
                                    ))
                                } else {
                                    msg
                                }
                            }
                            val ledgerMetadata = ChatTokenUsageLedger.sessionMetadataWith(
                                (latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession).metadata,
                                previous = ledgerPrev,
                                next = ledgerBuckets ?: ChatTokenUsageLedger.Buckets(),
                            ) ?: finalBase.metadata
                            val finalSession = (latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession)
                                .copy(messages = ledgerMessages,
                                    metadata = JsonObject(ledgerMetadata + ("variables" to processedFinal.localVariables)))

                            sessionRuntime.persistSessionMutation(
                                latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession,
                                finalSession,
                            ) { updated ->
                                uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                            }
                            activeRegeneration = null
                            activeContinue = null
                            activeContinue = null

                            uiState.update {
                                it.copy(
                                    isGenerating = true,
                                    streamingText = "",
                                    streamingReasoning = "",
                                )
                            }
                            val assistantMessageIndex = when {
                                regeneratedIndex >= 0 -> regeneratedIndex
                                continueIndex >= 0 -> continueIndex
                                else -> finalMessages.lastIndex
                            }
                            val eventType = if (regeneratedIndex >= 0 || continueIndex >= 0) "swipe" else "normal"
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_RECEIVED, assistantMessageIndex, eventType)
                            if (regeneratedIndex >= 0 || continueIndex >= 0) {
                                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, assistantMessageIndex)
                            }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, assistantMessageIndex, eventType)
                            sessionRuntime.flushSessionWrites(finalSession.id, extensionHost)
                            scope.launch {
                                try {
                                    memoryService.processPending(finalSession.id) { stage ->
                                        uiState.update { if (it.currentSession?.id == finalSession.id) it.copy(memoryStatus = stage) else it }
                                    }
                                    val document = memoryService.store.read(finalSession.id)
                                    uiState.update { state -> if (state.currentSession?.id == finalSession.id) state.copy(
                                        memoryRecords = document?.records.orEmpty(),
                                        memoryNeedsRebuild = document?.needsRebuild == true,
                                    ) else state }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    uiState.update { if (it.currentSession?.id == finalSession.id) it.copy(memoryStatus = UiStrings.get(S.chatgenco_memory_process_failed, error.message)) else it }
                                }
                            }
                            scope.launch {
                                delay(60_000)
                                try {
                                    memoryService.processPending(finalSession.id, flushIdle = true) { stage ->
                                        uiState.update { if (it.currentSession?.id == finalSession.id) it.copy(memoryStatus = stage) else it }
                                    }
                                    val document = memoryService.store.read(finalSession.id)
                                    uiState.update { state -> if (state.currentSession?.id == finalSession.id) state.copy(
                                        memoryRecords = document?.records.orEmpty(),
                                        memoryNeedsRebuild = document?.needsRebuild == true,
                                    ) else state }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    uiState.update { if (it.currentSession?.id == finalSession.id) it.copy(memoryStatus = UiStrings.get(S.chatgenco_memory_process_failed, error.message)) else it }
                                }
                            }
                            uiState.update { it.copy(isGenerating = false) }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_ENDED, finalMessages.size)
                        }
                        is GenerateChunk.Failed -> {
                            activeRegeneration = null
                            activeContinue = null
                            uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    streamingReasoning = "",
                                    error = UiStrings.get(S.chatgenco_generate_failed, chunk.error.message),
                                )
                            }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                        }
                    }
                }
            } catch (_: CancellationException) {
                // stopGeneration owns the interrupted-message state update.
            } catch (e: Exception) {
                activeRegeneration = null
                activeContinue = null
                uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        streamingReasoning = "",
                        error = UiStrings.get(S.chatgenco_error_generic, e.message),
                    )
                }
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
            } finally {
                // A cancelled request may finish after a new request has started.
                // Only the job that still owns generation may clear its state.
                if (generationJob === coroutineContext[Job]) {
                    generationJob = null
                    activeRegeneration = null
                    activeContinue = null
                    uiState.update {
                        it.copy(isGenerating = false, streamingText = "", streamingReasoning = "")
                    }
                }
            }
        }.also { generationJob = it; it.start() }
        return true
    }

    fun regenerateResponse(
        messageId: String,
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
        characterScriptJob: Job?,
    ): Boolean {
        val state = uiState.value
        val targetIndex = state.messages.indexOfFirst { it.id == messageId }
        if (!canRegenerateResponse(state.messages, targetIndex)) {
            uiState.update { it.copy(error = UiStrings.get(S.chatgenco_regen_last_only)) }
            return false
        }
        return sendMessageWithRole(
            text = "",
            attachments = emptyList(),
            messageRole = MessageRole.User,
            regenerationMessageId = messageId,
            uiState = uiState,
            scope = scope,
            characterScriptJob = characterScriptJob,
        )
    }

    /**
     * 继续生成：以最后一条角色/助手回复为上文续写，流式结果按追加写回该消息。
     */
    fun continueGeneration(
        messageId: String,
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
        characterScriptJob: Job?,
    ): Boolean {
        val state = uiState.value
        if (state.isGenerating || isGenerating || extensionGenerationActive) return false
        val last = state.messages.lastOrNull()
        if (last == null || last.id != messageId ||
            (last.role != MessageRole.Character && last.role != MessageRole.Assistant)
        ) {
            uiState.update { it.copy(error = UiStrings.get(S.chat_continue_empty)) }
            return false
        }
        return sendMessageWithRole(
            text = "",
            attachments = emptyList(),
            messageRole = MessageRole.User,
            continueMessageId = messageId,
            uiState = uiState,
            scope = scope,
            characterScriptJob = characterScriptJob,
        )
    }

    fun stopGeneration(
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
    ) {
        generationJob?.cancel()
        generationJob = null

        val state = uiState.value
        val regeneration = activeRegeneration
        val continueTarget = activeContinue
        activeRegeneration = null
        activeContinue = null
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
                        uiState.update {
                            it.copy(
                                messages = updatedMessages,
                                currentSession = updatedSession,
                                isGenerating = false,
                                streamingText = "",
                                streamingReasoning = "",
                            )
                        }
                        val commit = sessionRuntime.scheduleUiMutation(
                            base = session,
                            desired = updatedSession,
                            onSessionUpdated = { updated ->
                                uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                            },
                            onError = { err -> uiState.update { it.copy(error = err) } },
                        ) ?: return
                        interruptionJob = sessionRuntime.launchAfterCommit(
                            scope = scope,
                            commit = commit,
                            onError = { err -> uiState.update { it.copy(error = err) } },
                        ) {
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_RECEIVED, targetIndex, "interrupted")
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, targetIndex)
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, targetIndex, "interrupted")
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                        }
                        return
                    }
                }
            }
            // 继续生成被中断：已流出的部分文本同样按追加写回目标回复。
            if (continueTarget != null) {
                val targetIndex = state.messages.indexOfFirst { it.id == continueTarget }
                if (targetIndex >= 0) {
                    val updatedMessages = state.messages.toMutableList().also { messages ->
                        messages[targetIndex] = CharacterRegexApplier.markNormalProcessed(
                            messages[targetIndex].withContinuedText(processedPartial).withGenerationReasoning(
                                parts, state.streamingText, state.streamingReasoning, "interrupted", true,
                            ),
                        )
                    }
                    val session = state.currentSession
                    if (session != null) {
                        val updatedSession = session.copy(messages = updatedMessages)
                        uiState.update {
                            it.copy(
                                messages = updatedMessages,
                                currentSession = updatedSession,
                                isGenerating = false,
                                streamingText = "",
                                streamingReasoning = "",
                            )
                        }
                        val commit = sessionRuntime.scheduleUiMutation(
                            base = session,
                            desired = updatedSession,
                            onSessionUpdated = { updated ->
                                uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                            },
                            onError = { err -> uiState.update { it.copy(error = err) } },
                        ) ?: return
                        interruptionJob = sessionRuntime.launchAfterCommit(
                            scope = scope,
                            commit = commit,
                            onError = { err -> uiState.update { it.copy(error = err) } },
                        ) {
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_RECEIVED, targetIndex, "interrupted")
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, targetIndex)
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, targetIndex, "interrupted")
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                        }
                        return
                    }
                }
            }
            val character = state.selectedCharacter
            val partialMessage = CharacterRegexApplier.markNormalProcessed(ChatMessage(
                id = generateMessageId(),
                role = MessageRole.Character,
                name = character?.name ?: UiStrings.get(S.chat_default_assistant_name),
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
                uiState.update {
                    it.copy(
                        messages = updatedMessages,
                        currentSession = updatedSession,
                        isGenerating = false,
                        streamingText = "",
                        streamingReasoning = "",
                    )
                }
                val commit = sessionRuntime.scheduleUiMutation(
                    base = session,
                    desired = updatedSession,
                    onSessionUpdated = { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                    },
                    onError = { err -> uiState.update { it.copy(error = err) } },
                ) ?: return
                interruptionJob = sessionRuntime.launchAfterCommit(
                    scope = scope,
                    commit = commit,
                    onError = { err -> uiState.update { it.copy(error = err) } },
                ) {
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_RECEIVED, partialMessageIndex, "interrupted")
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, partialMessageIndex, "interrupted")
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                }
            } else {
                uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        streamingReasoning = "",
                    )
                }
                scope.launch {
                    ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
                }
            }
        } else {
            uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                    streamingReasoning = "",
                )
            }
            scope.launch {
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
            }
        }
    }

    suspend fun generateTextFromExtension(
        options: JsonObject,
        uiState: MutableStateFlow<ChatUiState>,
    ): JsonObject = extensionGenerationMutex.withLock {
        // Queue behind an in-flight main-line generation instead of streaming
        // concurrently: both paths write variables and messages, and interleaved
        // writes were the race the audit flagged. The await happens in the
        // script's async promise, so nothing deadlocks.
        generationJob?.join()
        extensionGenerationActive = true
        try {
            generateTextFromExtensionLocked(options, uiState)
        } finally {
            extensionGenerationActive = false
        }
    }

    private suspend fun generateTextFromExtensionLocked(
        options: JsonObject,
        uiState: MutableStateFlow<ChatUiState>,
    ): JsonObject {
        val state = uiState.value
        val character = state.selectedCharacter
            ?: throw IllegalStateException("No character is selected")
        val runtime = runtimeResolver.resolve(state.selectedPersona?.id)
        val preset = ExtensionGenerationOptions.preset(options, runtime.preset, dataStore)
        val config = ExtensionGenerationOptions.config(options, runtime.providerConfig)
        val runtimeState = state.copy(
            selectedProvider = runtime.selectedProviderId,
            providerConfig = runtime.providerConfig,
            presets = runtime.presets,
            selectedPreset = runtime.preset,
            personas = runtime.personas,
            selectedPersona = runtime.persona,
            worldBooks = runtime.worldBooks,
            disabledWorldIds = runtime.disabledWorldIds,
        )
        uiState.update { current ->
            current.copy(
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
        val userInput = stringOption(options, "user_input", "userInput", "prompt").orEmpty()
        val shouldStream = booleanOption(options, "should_stream", "shouldStream", "stream") ?: false
        val generationId = stringOption(options, "generation_id", "generationId")
            ?: UUID.randomUUID().toString()

        return try {
            ChatTavernAdapter.emitStEvent(extensionHost, "js_generation_started", generationId)

            val supportingCast = resolveCharacterCast(character, dataStore)
            val supportingBooks = supportingCast.flatMap { member ->
                ChatTavernStorage.activeWorldBooks(emptyList(), runtime.worldBooks, member, null)
            }
            val promptRequest = ExtensionGenerationOptions.promptRequest(options, PromptBuildRequest(
                character = character,
                supportingCharacters = supportingCast,
                persona = runtime.persona,
                messages = state.messages,
                worldBooks = (ChatTavernStorage.activeWorldBooks(runtime.activeWorldBooks, runtime.worldBooks, character, state.currentSession) + supportingBooks).distinctBy { it.id },
                preset = preset,
                userInput = userInput,
                providerType = config.providerType,
                metadata = ChatPromptBuilder.buildPromptMetadata(
                    state = runtimeState,
                    config = config,
                    preset = preset,
                    session = state.currentSession,
                    extensionHost = extensionHost,
                    dataStore = dataStore,
                    promptEngine = promptEngine,
                ),
            ))
            val promptResult = ChatPromptBuilder.buildPromptWithSessionScope(promptRequest, state.currentSession, promptEngine)
            ChatPromptBuilder.persistPromptTemplateVariableUpdates(
                updates = promptResult.promptTemplateVariableUpdates,
                targetSessionId = state.currentSession?.id,
                getCurrentSession = { uiState.value.currentSession },
                sessionRuntime = sessionRuntime,
                onSessionUpdated = { updated ->
                    uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                },
                promptEngine = promptEngine,
            )
            ChatPromptBuilder.emitPromptDiagnostics(promptResult, extensionHost)
            val adapter = providerRegistry.require(config.providerType)
            if (adapter !is app.tellev.core.provider.CompletionSettingsAdapter) {
                require(options["tools"] == null && options["json_schema"] == null) {
                    "Provider ${adapter.id} does not support helper tools or JSON schema requests"
                }
            }

            var accumulatedText = ""
            var finalText = ""
            var toolCalls: kotlinx.serialization.json.JsonArray? = null
            val generationRequest = ExtensionGenerationOptions.request(options, promptResult, preset, shouldStream)
            ExtensionGenerationOptions.validateCapabilities(options, config, generationRequest, adapter)
            val prepared = ChatCompletionEvents.prepare(extensionHost, config, generationRequest, adapter)
            adapter.streamGenerate(prepared.config, prepared.request).collect { chunk ->
                when (chunk) {
                    is GenerateChunk.Delta -> {
                        accumulatedText += chunk.text
                        if (shouldStream && chunk.text.isNotEmpty()) {
                            ChatTavernAdapter.emitStEvent(extensionHost, "js_stream_token_received_fully", accumulatedText, generationId)
                            ChatTavernAdapter.emitStEvent(extensionHost, "js_stream_token_received_incrementally", chunk.text, generationId)
                        }
                    }
                    is GenerateChunk.Completed -> {
                        finalText = chunk.text.ifBlank { accumulatedText }
                        toolCalls = chunk.toolCalls
                    }
                    is GenerateChunk.Failed -> {
                        throw IllegalStateException(chunk.error.message)
                    }
                }
            }

            val resultText = finalText.ifBlank { accumulatedText }
            ChatTavernAdapter.emitStEvent(
                extensionHost,
                "js_generation_before_end",
                buildJsonObject { put("message", resultText) },
                generationId,
            )
            ChatTavernAdapter.emitStEvent(extensionHost, "js_generation_ended", resultText, generationId)

            buildJsonObject {
                put("text", resultText)
                put("message", resultText)
                put("content", resultText)
                put("generation_id", generationId)
                toolCalls?.let { put("tool_calls", it) }
            }
        } catch (e: Exception) {
            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED, generationId)
            throw e
        }
    }

    private fun stringOption(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj[key]
                ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun booleanOption(obj: JsonObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val value = obj[key]
                ?.let { runCatching { it.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull() }
            if (value != null) return value
        }
        return null
    }
}
