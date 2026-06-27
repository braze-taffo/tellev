package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

class OpenAiCompatibleAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val providerId: String = ProviderCatalog.OPENAI_COMPATIBLE,
    private val providerDisplayName: String = "OpenAI-compatible",
    private val defaultModel: String? = null,
    private val modelsPath: String = "/v1/models",
    private val chatCompletionsPath: String = "/v1/chat/completions",
    private val supportsModelListing: Boolean = true,
) : ProviderAdapter {
    override val id: String = providerId
    override val displayName: String = providerDisplayName
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        if (config.baseUrl.isBlank()) {
            return ProviderStatus(available = false, message = "Base URL is required")
        }

        if (supportsModelListing) {
            val modelProbe = probeModelsEndpoint(config)
            if (modelProbe != null) {
                if (modelProbe.status.available || modelProbe.httpCode in setOf(401, 403)) {
                    return modelProbe.status
                }
            }
        }

        return probeChatCompletionsEndpoint(config)
    }

    private fun probeModelsEndpoint(config: ProviderConfig): StatusProbe? {
        val request = Request.Builder()
            .url(config.endpoint(modelsPath))
            .applyHeaders(config)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                StatusProbe(
                    status = ProviderStatus(
                        available = response.isSuccessful,
                        message = if (response.isSuccessful) "Connected" else "HTTP ${response.code}",
                    ),
                    httpCode = response.code,
                )
            }
        }.getOrElse {
            StatusProbe(
                status = ProviderStatus(available = false, message = it.message ?: "Connection failed"),
                httpCode = null,
            )
        }
    }

    private fun probeChatCompletionsEndpoint(config: ProviderConfig): ProviderStatus {
        val model = config.model?.takeIf { it.isNotBlank() }
            ?: defaultModel?.takeIf { it.isNotBlank() }
            ?: return ProviderStatus(
                available = false,
                message = "Please enter a model name or endpoint ID before testing this provider.",
            )

        val request = Request.Builder()
            .url(config.endpoint(chatCompletionsPath))
            .applyHeaders(config)
            .post(buildStatusPayload(model).toString().toRequestBody(JSON))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                ProviderStatus(
                    available = response.isSuccessful,
                    message = if (response.isSuccessful) {
                        "Connected"
                    } else {
                        response.body?.string().orEmpty().ifBlank { "HTTP ${response.code}" }
                    },
                )
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val fallbackModels = fallbackModels(config)
        if (!supportsModelListing) return fallbackModels

        val request = Request.Builder()
            .url(config.endpoint(modelsPath))
            .applyHeaders(config)
            .get()
            .build()

        // Guard the network call + JSON parse the same way probeModelsEndpoint does:
        // a connection failure or a non-JSON 200 body would otherwise propagate as
        // an uncaught exception into the settings screen and crash it.
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching fallbackModels
                val body = response.body?.string().orEmpty()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
                val models = data.mapNotNull { item ->
                    val modelId = item.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = modelId, capabilities = capabilities)
                }
                models.ifEmpty { fallbackModels }
            }
        }.getOrElse { fallbackModels }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val payload = buildPayload(config, request)
        val httpRequest = Request.Builder()
            .url(config.endpoint(chatCompletionsPath))
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = client.newCall(httpRequest)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "provider_http_${response.code}",
                                message = response.body?.string().orEmpty().ifBlank { response.message },
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                if (request.stream) {
                    val source = response.body?.source()
                    var fullText = ""
                    var reasoningText = ""
                    var finishReason: String? = null
                    var lastUsage: JsonObject? = null
                    val toolCallAccumulator = mutableMapOf<Int, ToolCallAccumulator>()

                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()

                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val parsed = parseStreamChunk(data)

                        // Parse reasoning content (DeepSeek, OpenAI o1/o3)
                        val reasoningDelta = parsed.reasoningContent
                        if (reasoningDelta.isNotEmpty()) {
                            reasoningText += reasoningDelta
                        }

                        // Parse regular content delta
                        val delta = parsed.content
                        if (delta.isNotEmpty()) {
                            fullText += delta
                            emit(GenerateChunk.Delta(delta))
                        }

                        // Parse tool calls
                        parsed.toolCalls.forEach { tc ->
                            val existing = toolCallAccumulator.getOrPut(tc.index) {
                                ToolCallAccumulator(id = tc.id.orEmpty(), name = tc.name.orEmpty())
                            }
                            if (tc.id != null) existing.id = tc.id
                            if (tc.name != null) existing.name = tc.name
                            existing.arguments += tc.arguments.orEmpty()
                        }

                        // Parse finish reason
                        if (parsed.finishReason != null) {
                            finishReason = parsed.finishReason
                        }

                        // Capture usage from the final usage-only chunk
                        // (stream_options.include_usage sends it with empty choices).
                        parsed.usage?.let { lastUsage = it }
                    }

                    // Append reasoning before main text in the completed result
                    val completedText = if (reasoningText.isNotEmpty()) {
                        "<reasoning>\n$reasoningText\n</reasoning>\n$fullText"
                    } else {
                        fullText
                    }

                    emit(GenerateChunk.Completed(completedText, finishReason, usage = lastUsage))
                } else {
                    val body = response.body?.string().orEmpty()
                    val parsed = parseNonStreamResponse(body)
                    emit(GenerateChunk.Completed(parsed.text, parsed.finishReason, usage = parsed.usage))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
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

    // -- Payload construction --

    protected fun buildPayload(config: ProviderConfig, request: GenerateRequest): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(config.model ?: defaultModel ?: request.preset.id))
            put("stream", JsonPrimitive(request.stream))
            if (request.stream) {
                put("stream_options", buildJsonObject {
                    put("include_usage", JsonPrimitive(true))
                })
            }
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }
            val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens
            maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            val stopSeqs = request.preset.stop + request.prompt.stop
            if (stopSeqs.isNotEmpty()) {
                put("stop", buildJsonArray { stopSeqs.distinct().forEach { add(JsonPrimitive(it)) } })
            }

            // Messages with vision support
            put("messages", buildMessagesArray(request))

            // Tool calling: add tools from metadata
            val tools = request.metadata["tools"]
            if (tools is JsonArray && tools.isNotEmpty()) {
                put("tools", tools)
                request.metadata["tool_choice"]?.let { put("tool_choice", it) }
            }

            // Logprobs support
            val wantLogprobs = request.metadata["logprobs"]
            if (wantLogprobs is JsonPrimitive && wantLogprobs.content.toBooleanStrictOrNull() == true) {
                put("logprobs", JsonPrimitive(true))
                request.metadata["top_logprobs"]?.let { put("top_logprobs", it) }
            }
        }

    protected fun buildMessagesArray(request: GenerateRequest): JsonArray {
        val imageAttachments = request.attachments.filter { it.mimeType.startsWith("image/") }

        return buildJsonArray {
            request.prompt.messages.forEach { promptMessage ->
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive(mapRole(promptMessage.role)))
                        promptMessage.name?.let { put("name", JsonPrimitive(it)) }

                        // Use multipart content if there are image attachments on user messages
                        if (promptMessage.role == MessageRole.User && imageAttachments.isNotEmpty()) {
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(promptMessage.content))
                                })
                                imageAttachments.forEach { attachment ->
                                    add(buildJsonObject {
                                        put("type", JsonPrimitive("image_url"))
                                        put("image_url", buildJsonObject {
                                            val dataUrl = "data:${attachment.mimeType};base64,${attachment.metadata["base64"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
                                            put("url", JsonPrimitive(dataUrl))
                                            attachment.metadata["detail"]?.let {
                                                put("detail", it)
                                            }
                                        })
                                    })
                                }
                            })
                        } else {
                            put("content", JsonPrimitive(promptMessage.content))
                        }
                    },
                )
            }
        }
    }

    // -- Response parsing --

    private fun parseStreamChunk(data: String): StreamChunkParsed {
        return runCatching {
            val obj = json.parseToJsonElement(data).jsonObject

            // Usage arrives in a final chunk with empty choices (when
            // stream_options.include_usage is set). Capture it before the
            // choice null-check so it isn't discarded.
            val usage = obj["usage"] as? JsonObject

            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@runCatching StreamChunkParsed(usage = usage)
            val delta = choice["delta"]?.jsonObject
                ?: return@runCatching StreamChunkParsed(usage = usage)

            val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()

            // Reasoning content (DeepSeek R1, OpenAI o-series)
            val reasoningContent = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()

            // Finish reason
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull

            // Tool calls
            val toolCalls = parseToolCallDeltas(delta["tool_calls"])

            StreamChunkParsed(
                content = content,
                reasoningContent = reasoningContent,
                finishReason = finishReason,
                toolCalls = toolCalls,
                usage = usage,
            )
        }.getOrDefault(StreamChunkParsed())
    }

    private fun parseToolCallDeltas(element: JsonElement?): List<ToolCallDelta> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            runCatching {
                val obj = item.jsonObject
                val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                val func = obj["function"]?.jsonObject
                val name = func?.get("name")?.jsonPrimitive?.contentOrNull
                val arguments = func?.get("arguments")?.jsonPrimitive?.contentOrNull
                ToolCallDelta(index = index, id = id, name = name, arguments = arguments)
            }.getOrNull()
        }
    }

    private fun parseNonStreamResponse(data: String): NonStreamParsed {
        return runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val message = choice?.get("message")?.jsonObject
            val text = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull

            // Include reasoning if present
            val reasoning = message?.get("reasoning_content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val fullText = if (reasoning.isNotEmpty()) {
                "<reasoning>\n$reasoning\n</reasoning>\n$text"
            } else {
                text
            }

            NonStreamParsed(text = fullText, finishReason = finishReason, usage = obj["usage"] as? JsonObject)
        }.getOrDefault(NonStreamParsed(text = "", finishReason = null))
    }

    // -- Utilities --

    protected fun mapRole(role: MessageRole): String = when (role) {
        MessageRole.System -> "system"
        MessageRole.User -> "user"
        MessageRole.Assistant, MessageRole.Character -> "assistant"
        MessageRole.Tool -> "tool"
    }

    protected fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private fun buildStatusPayload(model: String): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put("stream", JsonPrimitive(false))
            put("max_tokens", JsonPrimitive(1))
            put("messages", buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive("ping"))
                    },
                )
            })
        }

    private fun fallbackModels(config: ProviderConfig): List<ProviderModel> {
        val model = config.model?.takeIf { it.isNotBlank() }
            ?: defaultModel?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return listOf(ProviderModel(id = model, capabilities = capabilities))
    }

    protected fun Request.Builder.applyHeaders(config: ProviderConfig): Request.Builder = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        header("Content-Type", "application/json")
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    // -- Data classes for internal parsing --

    private data class StreamChunkParsed(
        val content: String = "",
        val reasoningContent: String = "",
        val finishReason: String? = null,
        val toolCalls: List<ToolCallDelta> = emptyList(),
        val usage: JsonObject? = null,
    )

    private data class ToolCallDelta(
        val index: Int = 0,
        val id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    )

    private class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        var arguments: String = "",
    )

    private data class NonStreamParsed(
        val text: String,
        val finishReason: String?,
        val usage: JsonObject? = null,
    )

    private data class StatusProbe(
        val status: ProviderStatus,
        val httpCode: Int?,
    )

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
