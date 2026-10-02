package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenRouter is an OpenAI-compatible proxy that routes to multiple providers.
 * Uses the standard OpenAI chat completions endpoint with additional OpenRouter headers.
 */
class OpenRouterAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val resolveAttachmentBytes: ((app.tellev.core.model.Attachment) -> ByteArray?)? = null,
) : CompletionSettingsAdapter {
    override val id: String = ProviderCatalog.OPENROUTER
    override val displayName: String = "OpenRouter"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/v1/models")
            .applyHeaders(config)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                ProviderStatus(
                    available = response.isSuccessful,
                    message = if (response.isSuccessful) "Connected" else "HTTP ${response.code}",
                )
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/v1/models")
            .applyHeaders(config)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ProviderModel>()
                val body = response.body?.string().orEmpty()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@use emptyList()
                data.mapNotNull { item ->
                    val modelId = item.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = modelId, capabilities = capabilities)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun completionPayload(config: ProviderConfig, request: GenerateRequest) = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: "openai/gpt-4o-mini"))
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            // Engine-resolved budget: honor maxCompletionTokens, not just maxTokens.
            // A preset that only sets one of the two must still reach the wire.
            (request.prompt.maxTokens ?: request.preset.maxCompletionTokens ?: request.preset.maxTokens)
                ?.let { put("max_tokens", JsonPrimitive(it)) }
            if (request.preset.stop.isNotEmpty()) {
                put("stop", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
            }
            put("messages", buildJsonArray {
                // G6: declared Vision but never sent images. OpenRouter proxies
                // the OpenAI shape — image_url content parts on the final user
                // turn, mirroring OpenAiCompatibleAdapter.
                val lastUserIndex = request.prompt.messages.indexOfLast { it.role == MessageRole.User }
                request.prompt.messages.forEachIndexed { index, msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(when (msg.role) {
                            MessageRole.System -> "system"
                            MessageRole.User -> "user"
                            MessageRole.Assistant, MessageRole.Character -> "assistant"
                            MessageRole.Tool -> "tool"
                        }))
                        val images = if (index == lastUserIndex) {
                            request.attachments.mapNotNull { attachment ->
                                val base64 = visionBase64(attachment) ?: return@mapNotNull null
                                buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put("image_url", buildJsonObject {
                                        put("url", JsonPrimitive("data:${attachment.mimeType};base64,$base64"))
                                    })
                                }
                            }
                        } else emptyList()
                        if (images.isEmpty()) {
                            put("content", JsonPrimitive(msg.content))
                        } else {
                            put("content", buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(msg.content))
                                    })
                                }
                                images.forEach { add(it) }
                            })
                        }
                        msg.name?.let { put("name", JsonPrimitive(it)) }
                        msg.wireFields?.forEach { (key, value) -> put(key, value) }
                    })
                }
            })
        }


    private fun visionBase64(attachment: app.tellev.core.model.Attachment): String? {
        attachment.metadata["base64"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        if (attachment.relativePath.isBlank()) return null
        val bytes = resolveAttachmentBytes?.invoke(attachment) ?: return null
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val payload = request.completionSettings ?: completionPayload(config, request)

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/v1/chat/completions")
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        val call = client.newCall(httpRequest)
        // guard 子 Job 无协程体可等：取消级联到达的瞬间即终结并断开连接，
        // 让阻塞中的 body 读立即抛错，而不是等到读超时（生产配置 5 分钟）。
        val callGuard = Job(coroutineContext[Job])
        callGuard.invokeOnCompletion { if (!call.isCanceled()) call.cancel() }
        try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                emit(GenerateChunk.Failed(TellevError(
                    code = "openrouter_http_${response.code}",
                    message = response.body?.string().orEmpty().ifBlank { response.message },
                    retryable = response.code in 429..599,
                )))
                return@use
            }

            if (request.stream) {
                val source = response.body?.source()
                var fullText = ""
                while (source != null && !source.exhausted()) {
                    val line = source.readUtf8Line().orEmpty()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        fullText += delta
                        emit(GenerateChunk.Delta(delta))
                    }
                }
                emit(GenerateChunk.Completed(fullText))
            } else {
                val body = response.body?.string().orEmpty()
                val text = runCatching {
                    json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
                emit(GenerateChunk.Completed(text))
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A user stop closes the socket mid-read and surfaces as a generic
            // IOException here. If the coroutine is cancelled this WAS a stop —
            // rethrow as cancellation instead of flashing a bogus error banner.
            coroutineContext.ensureActive()
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
        callGuard.complete()
    }.flowOn(Dispatchers.IO)

    private fun Request.Builder.applyHeaders(config: ProviderConfig): Request.Builder = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        header("Content-Type", "application/json")
        header("HTTP-Referer", "https://tellev.app")
        header("X-Title", "Tellev")
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
