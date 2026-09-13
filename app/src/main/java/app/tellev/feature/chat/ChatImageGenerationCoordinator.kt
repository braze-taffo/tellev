package app.tellev.feature.chat

import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.TellevError
import app.tellev.core.model.reasoningParts
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.ComfyUiSettings
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.GeneratedImage
import app.tellev.core.storage.GeneratedImageStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Coordinates image generation requests, scene prompt summarization,
 * engine resolution, and image persistence.
 */
internal class ChatImageGenerationCoordinator(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val generatedImageStore: GeneratedImageStore,
) {
    private var imageGenerationJob: Job? = null

    val isJobActive: Boolean
        get() = imageGenerationJob?.isActive == true

    // 在途生图所属会话：删除任意会话（不限于当前）前据此判定是否需要停掉。
    @Volatile
    private var activeImageSessionId: String? = null

    // 单调令牌区分「当前登记」与「已停/已完结的旧 job」：同会话 stop 后立即重开时，
    // 旧 job 的 finally 按 sessionId 比对会误清新登记，令牌比对不会。
    private val imageGenToken = java.util.concurrent.atomic.AtomicLong(0)

    fun activeImageSessionId(): String? = activeImageSessionId

    suspend fun refreshImageGenAvailability(
        onAvailabilityUpdated: (available: Boolean, configured: Set<String>, engine: String?) -> Unit,
    ) {
        val configured = ProviderConfigPersistence.configuredImageEngines(secretStore)
        val engine = ProviderConfigPersistence.availableImageEngine(secretStore, configured)
        onAvailabilityUpdated(configured.isNotEmpty(), configured, engine)
    }

    suspend fun refreshGeneratedImages(
        sessionId: String,
        onImagesLoaded: (sessionId: String, List<GeneratedImage>) -> Unit,
    ) {
        val images = withContext(Dispatchers.IO) { generatedImageStore.read(sessionId) }
        onImagesLoaded(sessionId, images)
    }

    fun stopImageGeneration(onStopped: () -> Unit) {
        // 先递增令牌：旧 job 的 finally 因令牌不匹配而不再触碰登记。
        imageGenToken.incrementAndGet()
        imageGenerationJob?.cancel()
        imageGenerationJob = null
        activeImageSessionId = null
        onStopped()
    }

    fun generateImage(
        prompt: String,
        negativePrompt: String,
        summarizeScene: Boolean,
        selectedEngine: String? = null,
        state: ChatUiState,
        scope: CoroutineScope,
        isTextGenerating: Boolean,
        onStatusUpdated: (isGenerating: Boolean, status: String?, diagnostic: String?, error: String?) -> Unit,
        onEngineUpdated: (engine: String, configured: Set<String>) -> Unit,
        onImagesLoaded: (sessionId: String, List<GeneratedImage>) -> Unit,
        onDiagnosticRecorded: (String) -> Unit,
    ) {
        if (!state.imageGenAvailable) {
            onStatusUpdated(false, null, null, "生图模型未配置：请先在设置中配置 ComfyUI 工作流或填写 NovelAI 令牌")
            return
        }
        if (state.isGenerating || isTextGenerating) {
            onStatusUpdated(false, null, null, "正在生成回复，请等回复结束后再生图")
            return
        }
        if (state.isGeneratingImage || isJobActive) return

        val character = state.selectedCharacter
        if (character == null) {
            onStatusUpdated(false, null, null, "请先选择角色")
            return
        }
        val session = state.currentSession
        if (session == null) {
            onStatusUpdated(false, null, null, "当前没有可用会话")
            return
        }
        val userPrompt = prompt.trim()
        if (!summarizeScene && userPrompt.isBlank()) {
            onStatusUpdated(false, null, null, "请输入图片提示词")
            return
        }

        // 在 launch 外同步登记（含令牌）：协程体内的赋值可能晚于上一个 job 的
        // finally 清理；令牌保证旧 job 永远不会抹掉新登记。
        val jobSessionId = session.id
        val jobToken = imageGenToken.incrementAndGet()
        activeImageSessionId = jobSessionId
        imageGenerationJob = scope.launch {
            try {
                onStatusUpdated(true, "准备生成图片…", null, null)

                val engine = (selectedEngine ?: state.imageEngine)?.let(ChatImageEngine::fromProviderId)
                    ?: error("请选择生图引擎")
                val engineId = engine.providerId
                val configured = ProviderConfigPersistence.configuredImageEngines(secretStore)
                check(engine.providerId in configured) { "${engine.label} 未配置，请先在设置中完成配置" }
                ProviderConfigPersistence.saveImageEngine(secretStore, engine.providerId)
                onEngineUpdated(engine.providerId, configured)

                val useNovelAiEngine = engine == ChatImageEngine.NovelAi

                val finalPrompt = if (summarizeScene) {
                    onStatusUpdated(true, "正在用对话模型总结画面…", null, null)
                    summarizeScenePrompt(
                        character = character,
                        session = session,
                        persona = state.selectedPersona,
                        selectedProvider = state.selectedProvider,
                        engine = engine,
                        onDiagnosticRecorded = onDiagnosticRecorded,
                    )
                } else {
                    userPrompt
                }

                onStatusUpdated(
                    true,
                    if (useNovelAiEngine) "正在调用 NovelAI 生成图片…" else "正在生成图片…",
                    null,
                    null,
                )

                val adapter: ProviderAdapter = providerRegistry.require(engineId)
                val config: ProviderConfig
                val metadata: kotlinx.serialization.json.JsonObject
                if (useNovelAiEngine) {
                    val novelSettings = ProviderConfigPersistence.loadNovelAiImageSettings(secretStore)
                    config = ProviderConfigPersistence.loadProviderConfig(secretStore, ProviderCatalog.NOVELAI_IMAGE)
                    val personaName = state.selectedPersona?.name
                    metadata = buildJsonObject {
                        put("negative_prompt", negativePrompt.trim())
                        put(
                            "novelai_settings",
                            Json.encodeToJsonElement(NovelAiImageSettings.serializer(), novelSettings),
                        )
                        if (!personaName.isNullOrBlank()) put("macro_user", personaName)
                        if (character.name.isNotBlank()) put("macro_char", character.name)
                    }
                } else {
                    val comfySettings = ProviderConfigPersistence.loadComfySettings(secretStore)
                    config = ProviderConfigPersistence.loadProviderConfig(secretStore, ProviderCatalog.COMFYUI)
                    metadata = buildJsonObject {
                        put("negative_prompt", negativePrompt.trim())
                        put(
                            "comfy_settings",
                            Json.encodeToJsonElement(ComfyUiSettings.serializer(), comfySettings),
                        )
                    }
                }

                val imageRequest = GenerateRequest(
                    prompt = PromptBuildResult(
                        messages = listOf(PromptMessage(role = MessageRole.User, content = finalPrompt)),
                        stop = emptyList(),
                        maxTokens = null,
                        providerType = engineId,
                        diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
                    ),
                    preset = GenerationPreset(id = "image", name = "Image generation", providerType = engineId),
                    stream = false,
                    metadata = metadata,
                )

                var imageBase64: String? = null
                var failure: TellevError? = null
                adapter.streamGenerate(config, imageRequest).collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> Unit
                        is GenerateChunk.Completed -> imageBase64 = chunk.text
                        is GenerateChunk.Failed -> failure = chunk.error
                    }
                }

                val error = failure
                if (error != null) {
                    onStatusUpdated(false, null, null, "生成图片失败：${error.message}（${error.code}）")
                    return@launch
                }
                val base64 = imageBase64
                if (base64.isNullOrBlank()) {
                    onStatusUpdated(false, null, null, "生成图片失败：未返回图片数据")
                    return@launch
                }

                val imageFileId = UUID.randomUUID().toString().substring(0, 8)
                val imageFileName = "img-${System.currentTimeMillis()}-$imageFileId.png"
                val relativePath = "user/images/$imageFileName"
                val bytes = withContext(Dispatchers.IO) {
                    java.util.Base64.getDecoder().decode(base64)
                }
                // 会话可能在生成期间被删除并取消了本 job：取消后不再落盘，
                // 避免为已删除会话重建图片与画廊（级联清理会被撤销）。
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) {
                    val dir = dataStore.layout.userImages.toFile()
                    dir.mkdirs()
                    app.tellev.core.storage.DurableFileOps.write(
                        java.io.File(dir, imageFileName).toPath(),
                        bytes,
                    )
                }

                val record = GeneratedImage(
                    id = imageFileId,
                    createdAtMillis = System.currentTimeMillis(),
                    attachments = listOf(Attachment(
                        id = "img-$imageFileId",
                        name = imageFileName,
                        mimeType = "image/png",
                        relativePath = relativePath,
                        source = AttachmentSource.Chat,
                    )),
                    prompt = finalPrompt,
                    negativePrompt = negativePrompt.trim(),
                    engine = engine.providerId,
                )
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { generatedImageStore.append(session.id, listOf(record)) }
                refreshGeneratedImages(session.id, onImagesLoaded)
                onStatusUpdated(false, null, null, null)
            } catch (e: CancellationException) {
                onStatusUpdated(false, null, null, null)
            } catch (e: Exception) {
                onStatusUpdated(false, null, null, "生成图片失败：${e.message}")
            } finally {
                // 令牌匹配才清理，避免抹掉后继 job 的归属。
                if (imageGenToken.get() == jobToken) activeImageSessionId = null
            }
        }
    }

    suspend fun summarizeScenePrompt(
        character: CharacterCard,
        session: ChatSession,
        persona: Persona?,
        selectedProvider: String,
        engine: ChatImageEngine,
        onDiagnosticRecorded: (String) -> Unit,
    ): String {
        val diagnostic = StringBuilder()
        try {
            val config = ProviderConfigPersistence.loadProviderConfig(secretStore, selectedProvider)
            diagnostic.appendLine("对话服务商：${config.providerType}；模型：${config.model.orEmpty()}")
            val adapter = providerRegistry.find(config.providerType)
                ?: error("找不到当前对话服务商：${config.providerType}")
            check(adapter.supportsChatGeneration) { "当前服务商不支持对话总结" }
            val personaName = persona?.name ?: "User"
            val sceneHistory = ImagePromptTemplates.sceneHistory(session.messages)
            check(sceneHistory.any { it.reasoningParts().body.isNotBlank() }) { "当前会话没有可供总结的剧情正文" }
            var attempt = 0
            var rejectedReason = "模型未返回有效正文"
            val summary = ImagePromptTemplates.summarize(engine, onRejected = { _, reason ->
                rejectedReason = reason
                diagnostic.appendLine("校验结果：$reason")
            }) { instruction ->
                attempt++
                val template = instruction.replace("{{user}}", personaName).replace("{{char}}", character.name)
                val generateRequest = SceneSummaryRequestBuilder.build(
                    character, persona, sceneHistory, config.providerType, template,
                )
                val promptResult = generateRequest.prompt
                diagnostic.appendLine("\n第 $attempt 次请求：独立画面提取，${promptResult.messages.size} 条消息，预计 ${promptResult.diagnostics.estimatedTokenCount} tokens")
                diagnostic.appendLine("回复上限：${promptResult.maxTokens}；停止序列：${generateRequest.preset.stop + promptResult.stop}")
                diagnostic.appendLine("消息角色：${promptResult.messages.joinToString { it.role.name }}")
                val accumulated = StringBuilder()
                var completed: String? = null
                var failed: TellevError? = null
                var finishReason: String? = null
                var reasoningLength = 0
                adapter.streamGenerate(config, generateRequest).collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Delta -> { accumulated.append(chunk.text); reasoningLength += chunk.reasoning.length }
                        is GenerateChunk.Completed -> {
                            if (chunk.text.isNotBlank()) completed = chunk.text
                            finishReason = chunk.finishReason
                            reasoningLength = maxOf(reasoningLength, chunk.reasoning.length)
                            chunk.providerDiagnostics?.let { diagnostic.appendLine("接口原始响应样本：$it") }
                            chunk.usage?.let { diagnostic.appendLine("接口用量：$it") }
                        }
                        is GenerateChunk.Failed -> failed = chunk.error
                    }
                }
                failed?.let { error("总结接口请求失败（${it.code}）：${it.message}") }
                val raw = completed ?: accumulated.toString()
                diagnostic.appendLine("结束原因：${finishReason ?: "未提供"}；正文长度：${raw.length}；推理长度：$reasoningLength")
                diagnostic.appendLine("模型原始回复：\n${raw.take(12000).ifEmpty { "（空）" }}")
                val parts = MessageReasoning.fromResponse(raw, "")
                check(parts.body.isNotBlank()) {
                    if (reasoningLength > 0 || parts.reasoning.isNotBlank()) "总结模型只返回推理，没有正文（结束原因：$finishReason）"
                    else "总结模型没有返回正文（结束原因：$finishReason）"
                }
                parts.body
            }
            return summary ?: error("场景总结校验失败，已停止生图：$rejectedReason")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostic.appendLine("\n失败原因：${e.message}")
            onDiagnosticRecorded(diagnostic.toString())
            throw e
        }
    }
}
