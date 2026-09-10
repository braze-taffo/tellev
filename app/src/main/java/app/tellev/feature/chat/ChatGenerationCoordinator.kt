package app.tellev.feature.chat

import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.withGenerationReasoning
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.GenerationRuntimeResolver
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.StDataStore
import app.tellev.feature.chat.ChatSessionInit.generateMessageId
import app.tellev.feature.chat.ChatSessionInit.withTavernInitVariables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
) {
    var generationJob: Job? = null
        private set

    var interruptionJob: Job? = null
        private set

    var activeRegeneration: ActiveRegeneration? = null
        private set

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
        regexIsEdit: Boolean = false,
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
        characterScriptJob: Job?,
    ): Boolean {
        val messageText = text.trim()
        if (regenerationMessageId == null && messageText.isBlank() && attachments.isEmpty()) return false

        val state = uiState.value
        if (state.isGenerating || isGenerating) return false
        val character = state.selectedCharacter
        if (character == null) {
            uiState.update { it.copy(error = "请先选择角色") }
            return false
        }
        val session = state.currentSession
        if (session == null) {
            uiState.update { it.copy(error = "当前没有可用会话") }
            return false
        }
        val regenerationIndex = regenerationMessageId?.let { messageId ->
            state.messages.indexOfFirst { it.id == messageId }
        }
        if (regenerationMessageId != null &&
            (regenerationIndex == null || !canRegenerateResponse(state.messages, regenerationIndex))
        ) {
            uiState.update { it.copy(error = "只能重新生成当前最后一条角色回复") }
            return false
        }

        val regenerationInputIndex = regenerationIndex?.let { targetIndex ->
            state.messages.take(targetIndex).indexOfLast { it.role == MessageRole.User }
        }
        val regenerationInput = regenerationInputIndex
            ?.takeIf { it >= 0 }
            ?.let(state.messages::get)
        if (regenerationMessageId != null && regenerationInput == null) {
            uiState.update { it.copy(error = "找不到这条回复对应的用户消息") }
            return false
        }

        scope.launch {
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
                    worldBooks = runtime.activeWorldBooks,
                )
                if (initializedSession != readySession) {
                    sessionRuntime.persistSessionMutation(readySession, initializedSession) { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
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
                    sessionRuntime.persistSessionMutation(initializedSession, updatedSession) { updated ->
                        uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                    }
                }
                activeRegeneration = regenerationMessageId?.let(::ActiveRegeneration)

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
                            CharacterRegexApplier.isNormalProcessed(inputMessage),
                        )),
                    ),
                )

                val promptResult = ChatPromptBuilder.buildPromptWithSessionScope(promptRequest, promptSession, promptEngine)
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

                ChatTavernAdapter.emitStEvent(
                    extensionHost,
                    StEventCatalog.CHAT_COMPLETION_SETTINGS_READY,
                    buildJsonObject {
                        put("chatId", updatedSession.id)
                        put("characterId", character.id)
                        put("providerType", config.providerType)
                    },
                )

                ChatTavernAdapter.emitStEvent(
                    extensionHost,
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
                            uiState.update { it.copy(streamingText = accumulatedText, streamingReasoning = accumulatedReasoning) }
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

                            sessionRuntime.persistSessionMutation(
                                latestSession?.takeIf { it.id == updatedSession.id } ?: updatedSession,
                                finalSession,
                            ) { updated ->
                                uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                            }
                            activeRegeneration = null

                            uiState.update {
                                it.copy(
                                    isGenerating = true,
                                    streamingText = "",
                                    streamingReasoning = "",
                                )
                            }
                            val assistantMessageIndex = if (regeneratedIndex >= 0) regeneratedIndex else finalMessages.lastIndex
                            val eventType = if (regeneratedIndex >= 0) "swipe" else "normal"
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_RECEIVED, assistantMessageIndex, eventType)
                            if (regeneratedIndex >= 0) {
                                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, assistantMessageIndex)
                            }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, assistantMessageIndex, eventType)
                            sessionRuntime.flushSessionWrites(finalSession.id, extensionHost)
                            uiState.update { it.copy(isGenerating = false) }
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_ENDED, finalMessages.size)
                            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATE_AFTER_DATA, finalMessages.size)
                        }
                        is GenerateChunk.Failed -> {
                            activeRegeneration = null
                            uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    streamingText = "",
                                    streamingReasoning = "",
                                    error = "生成失败：${chunk.error.message}",
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
                uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        streamingReasoning = "",
                        error = "出错了：${e.message}",
                    )
                }
                ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATION_STOPPED)
            }
        }.also { generationJob = it }
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
            uiState.update { it.copy(error = "只能重新生成当前最后一条角色回复") }
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

    fun stopGeneration(
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
    ) {
        generationJob?.cancel()
        generationJob = null

        val state = uiState.value
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
    ): JsonObject {
        val state = uiState.value
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
        uiState.update { current ->
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
        val userInput = stringOption(options, "user_input", "userInput", "prompt").orEmpty()
        val shouldStream = booleanOption(options, "should_stream", "shouldStream", "stream") ?: false
        val generationId = stringOption(options, "generation_id", "generationId")
            ?: UUID.randomUUID().toString()

        return try {
            ChatTavernAdapter.emitStEvent(extensionHost, "js_generation_started", generationId)

            val promptRequest = PromptBuildRequest(
                character = character,
                persona = runtime.persona,
                messages = state.messages,
                worldBooks = runtime.activeWorldBooks,
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
            )
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
                            ChatTavernAdapter.emitStEvent(extensionHost, "js_stream_token_received_fully", accumulatedText, generationId)
                            ChatTavernAdapter.emitStEvent(extensionHost, "js_stream_token_received_incrementally", chunk.text, generationId)
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
            ChatTavernAdapter.emitStEvent(
                extensionHost,
                "js_generation_before_end",
                buildJsonObject { put("message", resultText) },
                generationId,
            )
            ChatTavernAdapter.emitStEvent(extensionHost, "js_generation_ended", resultText, generationId)
            ChatTavernAdapter.emitStEvent(extensionHost, StEventCatalog.GENERATE_AFTER_DATA, generationId)

            buildJsonObject {
                put("text", resultText)
                put("message", resultText)
                put("content", resultText)
                put("generation_id", generationId)
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
