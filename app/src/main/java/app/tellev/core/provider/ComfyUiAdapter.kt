package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** ComfyUI-specific image generation settings, stored encrypted alongside the provider URL/model. */
@Serializable
data class ComfyUiSettings(
    /**
     * API-format workflow JSON containing quoted placeholder literals such as
     * "%prompt%" / "%negative_prompt%" (SillyTavern convention). Blank until
     * the user pastes a workflow in Settings.
     */
    val workflowJson: String = "",
    /** Default negative prompt substituted into "%negative_prompt%"; a per-request value overrides it. */
    val negativePrompt: String = "",
    val steps: Int = 20,
    val cfgScale: Double = 7.0,
    val width: Int = 512,
    val height: Int = 768,
    /** Blank keeps the workflow's own sampler/scheduler values. */
    val sampler: String = "",
    val scheduler: String = "",
    /** -1 rolls a fresh seed on every generation. */
    val seed: Long = -1L,
    val denoise: Double = 1.0,
    val clipSkip: Int = 2,
)

/**
 * Renders an API-format ComfyUI workflow by replacing quoted placeholder
 * literals: the workflow carries "%prompt%" etc. as bare JSON string values
 * and each placeholder is swapped for a properly JSON-encoded value, so the
 * document stays valid for strings and numbers alike. Placeholders whose
 * parameter is blank are left untouched, keeping the workflow's own value.
 */
object ComfyWorkflowTemplate {
    fun parse(workflowJson: String): JsonObject? = runCatching {
        Json.parseToJsonElement(workflowJson).jsonObject
    }.getOrNull()

    fun render(
        workflowJson: String,
        prompt: String,
        negativePrompt: String,
        settings: ComfyUiSettings,
        model: String?,
        seed: Long,
    ): String {
        var workflow = workflowJson
            .replace("\"%prompt%\"", JsonPrimitive(prompt).toString())
            .replace("\"%negative_prompt%\"", JsonPrimitive(negativePrompt).toString())
            .replace("\"%seed%\"", JsonPrimitive(seed).toString())
            .replace("\"%steps%\"", JsonPrimitive(settings.steps).toString())
            .replace("\"%scale%\"", JsonPrimitive(settings.cfgScale).toString())
            .replace("\"%width%\"", JsonPrimitive(settings.width).toString())
            .replace("\"%height%\"", JsonPrimitive(settings.height).toString())
            .replace("\"%denoise%\"", JsonPrimitive(settings.denoise).toString())
        if (settings.clipSkip > 0) {
            // CLIPSetLastLayer stops at a negative layer index (SillyTavern convention).
            workflow = workflow.replace("\"%clip_skip%\"", JsonPrimitive(-settings.clipSkip).toString())
        }
        if (!model.isNullOrBlank()) {
            workflow = workflow.replace("\"%model%\"", JsonPrimitive(model).toString())
        }
        if (settings.sampler.isNotBlank()) {
            workflow = workflow.replace("\"%sampler%\"", JsonPrimitive(settings.sampler).toString())
        }
        if (settings.scheduler.isNotBlank()) {
            workflow = workflow.replace("\"%scheduler%\"", JsonPrimitive(settings.scheduler).toString())
        }
        return workflow
    }
}

/** An image produced by a finished ComfyUI job, addressable via GET /view. */
internal data class ComfyOutputImage(
    val filename: String,
    val subfolder: String,
    val type: String,
)

/**
 * Adapter for a self-hosted ComfyUI server. Generation substitutes the
 * placeholders in the configured workflow, submits it to POST /prompt,
 * polls GET /history/{prompt_id} until the job completes and downloads the
 * first output image via GET /view, emitting it as base64 in a single
 * Completed chunk (same contract as StableDiffusionAdapter).
 */
class ComfyUiAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.COMFYUI
    override val displayName: String = "ComfyUI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Images,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/system_stats"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ProviderStatus(available = true, message = "Connected")
                } else {
                    ProviderStatus(available = false, message = "HTTP ${response.code}")
                }
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed - is the ComfyUI server reachable?")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url(config.endpoint("/object_info"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                // CheckpointLoaderSimple.input.required.ckpt_name[0] lists the installed checkpoints.
                val names = root["CheckpointLoaderSimple"]
                    ?.jsonObject?.get("input")
                    ?.jsonObject?.get("required")
                    ?.jsonObject?.get("ckpt_name")
                    ?.jsonArray?.firstOrNull()
                    ?.jsonArray ?: return@use emptyList()
                names.mapNotNull { entry ->
                    val name = entry.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = name, displayName = name, capabilities = capabilities)
                }
            }
        }.getOrElse { emptyList() }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val settings = request.metadata["comfy_settings"]
            ?.let { element -> runCatching { json.decodeFromJsonElement(ComfyUiSettings.serializer(), element) }.getOrNull() }
            ?: ComfyUiSettings()

        if (settings.workflowJson.isBlank()) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "comfy_workflow_missing",
                        message = "未配置 ComfyUI 工作流：请先在设置中粘贴 API 格式工作流 JSON",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }
        if (ComfyWorkflowTemplate.parse(settings.workflowJson) == null) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "comfy_workflow_invalid",
                        message = "ComfyUI 工作流不是有效的 JSON 对象",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }

        val promptText = request.prompt.messages
            .filter { it.role != MessageRole.System }
            .joinToString(" ") { it.content }
        val negativeOverride = request.metadata["negative_prompt"]?.jsonPrimitive?.contentOrNull
        val negativePrompt = negativeOverride?.takeIf { it.isNotBlank() } ?: settings.negativePrompt
        val seed = if (settings.seed >= 0) settings.seed else Random.nextLong(0, Long.MAX_VALUE)

        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = settings.workflowJson,
            prompt = promptText,
            negativePrompt = negativePrompt,
            settings = settings,
            model = config.model,
            seed = seed,
        )

        val submitBody = buildJsonObject {
            put("prompt", json.parseToJsonElement(rendered))
            put("client_id", JsonPrimitive(UUID.randomUUID().toString()))
        }

        try {
            coroutineContext.ensureActive()

            val promptId = submitWorkflow(config, submitBody.toString())
                ?: run {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "comfy_submit_failed",
                                message = "ComfyUI 未返回任务 ID",
                                retryable = true,
                            ),
                        ),
                    )
                    return@flow
                }

            val image = pollHistory(config, promptId)
            when {
                image == null -> {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "comfy_timeout",
                                message = "ComfyUI 生成超时（10 分钟）",
                                retryable = true,
                            ),
                        ),
                    )
                    return@flow
                }
                image.error != null -> {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "comfy_execution_error",
                                message = image.error,
                                retryable = false,
                            ),
                        ),
                    )
                    return@flow
                }
                image.output == null -> {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "comfy_no_images",
                                message = "ComfyUI 执行完成但没有产出图片",
                                retryable = false,
                            ),
                        ),
                    )
                    return@flow
                }
            }

            val bytes = downloadImage(config, image.output)
            emit(GenerateChunk.Completed(Base64.getEncoder().encodeToString(bytes), "image_generated"))
        } catch (e: ComfyHttpException) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = e.code,
                        message = e.message ?: "ComfyUI 请求失败",
                        retryable = e.retryable,
                    ),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "provider_network",
                        message = e.message ?: "Network error",
                        retryable = true,
                        causeType = e::class.simpleName,
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** Submits the rendered workflow; returns the prompt id, or null when the server did not provide one. */
    private suspend fun submitWorkflow(config: ProviderConfig, body: String): String? {
        val request = Request.Builder()
            .url(config.endpoint("/prompt"))
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ComfyHttpException(
                    code = "comfy_http_${response.code}",
                    message = extractError(text).ifBlank { "HTTP ${response.code}" },
                    retryable = response.code in 429..599,
                )
            }
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw ComfyHttpException("comfy_bad_response", "ComfyUI 响应无法解析：$text", retryable = true)
            val nodeErrors = root["node_errors"]?.jsonObject
            if (!nodeErrors.isNullOrEmpty()) {
                throw ComfyHttpException(
                    code = "comfy_node_errors",
                    message = "工作流节点校验失败：$nodeErrors",
                    retryable = false,
                )
            }
            return root["prompt_id"]?.jsonPrimitive?.contentOrNull
        }
    }

    /** Outcome of polling /history: an output image, an execution error message, or neither (still running). */
    private data class HistoryResult(
        val output: ComfyOutputImage?,
        val error: String?,
        val completed: Boolean,
    )

    private suspend fun pollHistory(config: ProviderConfig, promptId: String): HistoryResult {
        val deadline = System.nanoTime() + POLL_TIMEOUT_NANOS
        while (true) {
            coroutineContext.ensureActive()
            if (System.nanoTime() > deadline) return HistoryResult(output = null, error = null, completed = false)
            delay(POLL_INTERVAL_MS)

            val request = Request.Builder()
                .url(config.endpoint("/history/$promptId"))
                .get()
                .build()
            val entry = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ComfyHttpException(
                        code = "comfy_http_${response.code}",
                        message = "查询生成历史失败：HTTP ${response.code}",
                        retryable = response.code in 429..599,
                    )
                }
                val body = response.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: throw ComfyHttpException("comfy_bad_response", "历史响应无法解析", retryable = true)
                // /history/{id} omits the entry until the job finishes.
                root[promptId]?.jsonObject
            } ?: continue

            val status = entry["status"]?.jsonObject
            if (status?.get("status_str")?.jsonPrimitive?.contentOrNull == "error") {
                return HistoryResult(output = null, error = extractExecutionError(status), completed = true)
            }

            val outputs = entry["outputs"]?.jsonObject
            for (node in outputs?.values.orEmpty()) {
                val images = runCatching { node.jsonObject["images"]?.jsonArray }.getOrNull() ?: continue
                for (img in images) {
                    val obj = runCatching { img.jsonObject }.getOrNull() ?: continue
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
                    if (type != "output") continue
                    return HistoryResult(
                        output = ComfyOutputImage(
                            filename = obj["filename"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            subfolder = obj["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            type = type,
                        ),
                        error = null,
                        completed = true,
                    )
                }
            }
            // Finished without an output image (e.g. only previews produced).
            if (status?.get("completed")?.jsonPrimitive?.contentOrNull == "true") {
                return HistoryResult(output = null, error = null, completed = true)
            }
        }
    }

    private fun downloadImage(config: ProviderConfig, image: ComfyOutputImage): ByteArray {
        val url = config.endpoint("").toHttpUrl().newBuilder()
            .addPathSegment("view")
            .addQueryParameter("filename", image.filename)
            .addQueryParameter("subfolder", image.subfolder)
            .addQueryParameter("type", image.type)
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ComfyHttpException(
                    code = "comfy_http_${response.code}",
                    message = "下载生成图片失败：HTTP ${response.code}",
                    retryable = response.code in 429..599,
                )
            }
            return response.body?.bytes()
                ?: throw ComfyHttpException("comfy_no_image_data", "图片响应为空", retryable = true)
        }
    }

    private fun extractError(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    private fun extractExecutionError(status: JsonObject): String = runCatching {
        val messages = status["messages"]?.jsonArray ?: return "ComfyUI 执行出错"
        for (message in messages) {
            val pair = message.jsonArray
            if (pair.firstOrNull()?.jsonPrimitive?.contentOrNull == "execution_error") {
                val detail = pair.getOrNull(1)?.jsonObject ?: continue
                val exception = detail["exception_message"]?.jsonPrimitive?.contentOrNull
                val nodeType = detail["node_type"]?.jsonPrimitive?.contentOrNull
                return listOfNotNull(nodeType, exception).joinToString(": ").ifBlank { "ComfyUI 执行出错" }
            }
        }
        "ComfyUI 执行出错"
    }.getOrDefault("ComfyUI 执行出错")

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private class ComfyHttpException(
        val code: String,
        message: String,
        val retryable: Boolean,
    ) : Exception(message)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val POLL_INTERVAL_MS = 500L
        const val POLL_TIMEOUT_NANOS = 10L * 60 * 1_000_000_000
    }
}
