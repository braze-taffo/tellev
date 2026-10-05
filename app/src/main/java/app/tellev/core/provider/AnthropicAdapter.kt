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
import kotlinx.serialization.json.JsonObject
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

        // Engine-resolved output budget; extended-thinking budgets must stay
        // below it, so resolve the unified reasoning injection up front.
        val outputTokens = request.prompt.maxTokens
            ?: request.preset.maxCompletionTokens
            ?: request.preset.maxTokens
            ?: 8192
        val reasoningInjection = ReasoningSupport.inject(
            family = ReasoningFamily.Anthropic,
            effort = ReasoningSupport.effortFor(request),
            raw = request.preset.raw,
            maxTokens = outputTokens,
        )

        val payload = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: "claude-sonnet-4-20250514"))
            // prompt.maxTokens is the engine-resolved output budget
            // (maxCompletionTokens → maxTokens → default); the preset-only
            // lookup ignored the maxCompletionTokens setting entirely.
            put("max_tokens", JsonPrimitive(outputTokens))
            put("stream", JsonPrimitive(request.stream))
            // Extended thinking requires temperature == 1; the injection's
            // temperature override takes precedence over the preset value.
            val reasoningTemperature = reasoningInjection.fields["temperature"]
            when {
                reasoningTemperature != null -> put("temperature", reasoningTemperature)
                request.preset.temperature != null -> put("temperature", JsonPrimitive(request.preset.temperature))
            }
            if ("top_p" !in reasoningInjection.suppress) {
                request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            }
            reasoningInjection.fields["thinking"]?.let { put("thinking", it) }
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
                var reasoningText = ""
                var usage: JsonObject? = null
                while (source != null && !source.exhausted()) {
                    val line = source.readUtf8Line().orEmpty()
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                        ?: continue
                    // An SSE error frame (overloaded_error etc.) must fail the
                    // generation — the old path swallowed it and emitted
                    // Completed with the partial text, surfacing "空回复".
                    if (obj["type"]?.jsonPrimitive?.contentOrNull == "error") {
                        val error = obj["error"] as? JsonObject
                        val errorType = error?.get("type")?.jsonPrimitive?.contentOrNull
                        emit(GenerateChunk.Failed(TellevError(
                            code = "anthropic_stream_${errorType ?: "error"}",
                            message = error?.get("message")?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?: errorType?.takeIf { it.isNotBlank() }
                                ?: data.take(500),
                            retryable = errorType in RETRYABLE_SSE_ERRORS,
                        )))
                        return@use
                    }
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_start" -> {
                            usage = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                            continue
                        }
                        "message_delta" -> {
                            val deltaUsage = obj["usage"] as? JsonObject
                            if (deltaUsage != null) {
                                val merged = buildJsonObject {
                                    usage?.forEach { (key, value) -> put(key, value) }
                                    deltaUsage.forEach { (key, value) -> put(key, value) }
                                }
                                usage = merged
                            }
                            continue
                        }
                        "content_block_delta" -> Unit
                        else -> continue
                    }
                    val delta = obj["delta"]?.jsonObject
                    val text = delta?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Extended thinking arrives as thinking_delta — route it to
                    // the reasoning channel instead of dropping it on the floor.
                    val thinking = delta?.get("thinking")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        fullText += text
                        reasoningText += thinking
                        emit(GenerateChunk.Delta(text, reasoning = thinking))
                    }
                }
                emit(GenerateChunk.Completed(fullText, usage = usage, reasoning = reasoningText))
            } else {
                val body = response.body?.string().orEmpty()
                val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val text = parsed
                    ?.get("content")?.jsonArray
                    ?.filterIsInstance<JsonObject>()
                    ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                    .orEmpty()
                emit(GenerateChunk.Completed(text, usage = parsed?.get("usage") as? JsonObject))
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
        val RETRYABLE_SSE_ERRORS = setOf("overloaded_error", "rate_limit_error", "timeout_error", "api_error")
    }
}
