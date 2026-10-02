package app.tellev.core.provider

import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.tellev.core.model.MessageRole

class AnthropicAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val resolveAttachmentBytes: ((app.tellev.core.model.Attachment) -> ByteArray?)? = null,
) : ProviderAdapter {
    override val id: String = ProviderCatalog.ANTHROPIC
    override val displayName: String = "Anthropic"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", config.apiKey.orEmpty())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(JSON))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                ProviderStatus(
                    available = response.code != 401 && response.code != 403,
                    message = if (response.isSuccessful) "Connected" else "HTTP ${response.code}",
                )
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        return listOf(
            ProviderModel(id = "claude-sonnet-4-20250514", displayName = "Claude Sonnet 4", capabilities = capabilities),
            ProviderModel(id = "claude-3-5-sonnet-20241022", displayName = "Claude 3.5 Sonnet", capabilities = capabilities),
            ProviderModel(id = "claude-3-opus-20240229", displayName = "Claude 3 Opus", capabilities = capabilities),
            ProviderModel(id = "claude-3-haiku-20240307", displayName = "Claude 3 Haiku", capabilities = capabilities),
        )
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Tellev's prompt pipeline emits MULTIPLE system messages (core memory
        // injection, character-card jailbreak, at-depth world info, …). Anthropic
        // accepts a single top-level system field, so merge them all — taking
        // only the first silently dropped every later injection (parity with
        // GeminiAdapter, which joins them with a blank line).
        val systemMessage = request.prompt.messages
            .filter { it.role == MessageRole.System }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        val conversationMessages = request.prompt.messages.filter { it.role != MessageRole.System }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: "claude-sonnet-4-20250514"))
            // prompt.maxTokens is the engine-resolved output budget
            // (maxCompletionTokens → maxTokens → default); the preset-only
            // lookup ignored the maxCompletionTokens setting entirely.
            put("max_tokens", JsonPrimitive(request.prompt.maxTokens ?: request.preset.maxCompletionTokens ?: request.preset.maxTokens ?: 8192))
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            if (systemMessage != null) {
                put("system", JsonPrimitive(systemMessage))
            }
            if (request.preset.stop.isNotEmpty()) {
                put("stop_sequences", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
            }
            put("messages", buildJsonArray {
                val lastUserIndex = conversationMessages.indexOfLast { it.role == MessageRole.User }
                conversationMessages.forEachIndexed { index, msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(if (msg.role == MessageRole.User) "user" else "assistant"))
                        // G6: the adapter declared Vision but never sent images —
                        // attachments went silently missing. Encode them into the
                        // final user turn, mirroring the OpenAI/Gemini adapters.
                        val images = if (index == lastUserIndex) {
                            request.attachments.mapNotNull { attachment ->
                                val base64 = visionBase64(attachment) ?: return@mapNotNull null
                                buildJsonObject {
                                    put("type", JsonPrimitive("image"))
                                    put("source", buildJsonObject {
                                        put("type", JsonPrimitive("base64"))
                                        put("media_type", JsonPrimitive(attachment.mimeType))
                                        put("data", JsonPrimitive(base64))
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
                    })
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", config.apiKey.orEmpty())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(JSON))
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
                    code = "anthropic_http_${response.code}",
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
                    val parsed = runCatching {
                        val obj = json.parseToJsonElement(data).jsonObject
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull
                        when (type) {
                            "content_block_delta" -> {
                                obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            }
                            else -> ""
                        }
                    }.getOrDefault("")
                    if (parsed.isNotEmpty()) {
                        fullText += parsed
                        emit(GenerateChunk.Delta(parsed))
                    }
                }
                emit(GenerateChunk.Completed(fullText))
            } else {
                val body = response.body?.string().orEmpty()
                val text = runCatching {
                    val obj = json.parseToJsonElement(body).jsonObject
                    obj["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
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

    private fun visionBase64(attachment: app.tellev.core.model.Attachment): String? {
        attachment.metadata["base64"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        if (attachment.relativePath.isBlank()) return null
        val bytes = resolveAttachmentBytes?.invoke(attachment) ?: return null
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
