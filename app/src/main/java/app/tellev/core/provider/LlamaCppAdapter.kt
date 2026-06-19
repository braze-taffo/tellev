package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

/**
 * Adapter for llama.cpp server.
 * Uses POST /completion with SSE streaming.
 */
class LlamaCppAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.LLAMA_CPP
    override val displayName: String = "llama.cpp"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Text,
        ProviderCapability.Streaming,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/"))
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
            ProviderStatus(available = false, message = it.message ?: "Connection failed - is llama.cpp server running?")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        // llama.cpp server doesn't have a model listing endpoint.
        // Return a single "default" model.
        return listOf(
            ProviderModel(
                id = "default",
                displayName = "llama.cpp (loaded model)",
                capabilities = capabilities,
            ),
        )
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Build the prompt text (completion-style, not chat)
        val promptText = buildPromptText(request)

        val payload = buildPayload(config, request, promptText)
        val httpRequest = Request.Builder()
            .url(config.endpoint("/completion"))
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val call = client.newCall(httpRequest)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "llamacpp_http_${response.code}",
                                message = errorBody.ifBlank { "HTTP ${response.code}" },
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                if (request.stream) {
                    // llama.cpp uses SSE format: data: {"content":"...","stop":false}
                    val source = response.body?.source()
                    var fullText = ""

                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()

                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue

                        val parsed = parseChunk(data)

                        if (parsed.error != null) {
                            emit(
                                GenerateChunk.Failed(
                                    TellevError(
                                        code = "llamacpp_error",
                                        message = parsed.error,
                                        retryable = false,
                                    ),
                                ),
                            )
                            return@use
                        }

                        if (parsed.content.isNotEmpty()) {
                            fullText += parsed.content
                            emit(GenerateChunk.Delta(parsed.content))
                        }

                        if (parsed.stop) {
                            break
                        }
                    }

                    emit(GenerateChunk.Completed(fullText))
                } else {
                    // Non-streaming: read the entire response body
                    val body = response.body?.string().orEmpty()
                    val parsed = parseChunk(body)
                    if (parsed.error != null) {
                        emit(
                            GenerateChunk.Failed(
                                TellevError(
                                    code = "llamacpp_error",
                                    message = parsed.error,
                                    retryable = false,
                                ),
                            ),
                        )
                    } else {
                        emit(GenerateChunk.Completed(parsed.content))
                    }
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

    private fun buildPromptText(request: GenerateRequest): String {
        return request.prompt.messages.joinToString("\n") { msg ->
            when (msg.role) {
                MessageRole.System -> "${msg.content}\n"
                MessageRole.User -> "### User: ${msg.content}"
                MessageRole.Assistant, MessageRole.Character -> "### Assistant: ${msg.content}"
                MessageRole.Tool -> "### System: ${msg.content}"
            }
        } + "\n### Assistant:"
    }

    private fun buildPayload(config: ProviderConfig, request: GenerateRequest, promptText: String): JsonObject =
        buildJsonObject {
            put("prompt", JsonPrimitive(promptText))
            put("stream", JsonPrimitive(request.stream))

            val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens ?: 512
            put("n_predict", JsonPrimitive(maxTokens))

            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }

            val stopSeqs = (request.preset.stop + request.prompt.stop).distinct()
            if (stopSeqs.isNotEmpty()) {
                put("stop", buildJsonArray { stopSeqs.forEach { add(JsonPrimitive(it)) } })
            }

            // Additional llama.cpp parameters from raw preset
            request.preset.raw["repeat_penalty"]?.let { put("repeat_penalty", it) }
            request.preset.raw["repeat_last_n"]?.let { put("repeat_last_n", it) }
            request.preset.raw["n_keep"]?.let { put("n_keep", it) }
            request.preset.raw["typical_p"]?.let { put("typical_p", it) }
            request.preset.raw["tfs_z"]?.let { put("tfs_z", it) }
            request.preset.raw["mirostat"]?.let { put("mirostat", it) }
            request.preset.raw["mirostat_tau"]?.let { put("mirostat_tau", it) }
            request.preset.raw["mirostat_eta"]?.let { put("mirostat_eta", it) }
        }

    // -- Response parsing --

    private fun parseChunk(data: String): LlamaCppChunk {
        return runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val error = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
            if (error != null) return@runCatching LlamaCppChunk(error = error)

            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val stop = obj["stop"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

            LlamaCppChunk(content = content, stop = stop)
        }.getOrDefault(LlamaCppChunk())
    }

    // -- Utilities --

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private data class LlamaCppChunk(
        val content: String = "",
        val stop: Boolean = false,
        val error: String? = null,
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
