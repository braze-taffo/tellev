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
import kotlinx.serialization.json.JsonObject
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
import kotlin.coroutines.coroutineContext

/**
 * Adapter for Oobabooga Text Generation WebUI.
 * Uses the OpenAI-compatible endpoint at /v1/chat/completions for simplicity.
 */
class TextGenAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.TEXTGEN_WEBUI
    override val displayName: String = "TextGen WebUI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/v1/models"))
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
            ProviderStatus(available = false, message = it.message ?: "Connection failed - is TextGen WebUI running with --api?")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url(config.endpoint("/v1/models"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@use emptyList()
                data.mapNotNull { item ->
                    val modelId = item.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = modelId, displayName = modelId, capabilities = capabilities)
                }
            }
        }.getOrElse { emptyList() }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val payload = buildPayload(config, request)
        val httpRequest = Request.Builder()
            .url(config.endpoint("/v1/chat/completions"))
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val call = client.newCall(httpRequest)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val errorMessage = runCatching {
                        json.parseToJsonElement(errorBody).jsonObject["error"]
                            ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: errorBody.ifBlank { "HTTP ${response.code}" }

                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "textgen_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                if (request.stream) {
                    val source = response.body?.source()
                    var fullText = ""
                    var finishReason: String? = null

                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()

                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val parsed = parseDelta(data)
                        if (parsed.content.isNotEmpty()) {
                            fullText += parsed.content
                            emit(GenerateChunk.Delta(parsed.content))
                        }
                        if (parsed.finishReason != null) {
                            finishReason = parsed.finishReason
                        }
                    }

                    emit(GenerateChunk.Completed(fullText, finishReason))
                } else {
                    val body = response.body?.string().orEmpty()
                    val parsed = parseNonStream(body)
                    emit(GenerateChunk.Completed(parsed.text, parsed.finishReason))
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

    private fun buildPayload(config: ProviderConfig, request: GenerateRequest): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(config.model ?: request.preset.id))
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens
            maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }

            val stopSeqs = (request.preset.stop + request.prompt.stop).distinct()
            if (stopSeqs.isNotEmpty()) {
                put("stop", buildJsonArray { stopSeqs.forEach { add(JsonPrimitive(it)) } })
            }

            put("messages", buildJsonArray {
                request.prompt.messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(when (msg.role) {
                            MessageRole.System -> "system"
                            MessageRole.User -> "user"
                            MessageRole.Assistant, MessageRole.Character -> "assistant"
                            MessageRole.Tool -> "tool"
                        }))
                        put("content", JsonPrimitive(msg.content))
                        msg.name?.let { put("name", JsonPrimitive(it)) }
                    })
                }
            })

            // Pass through TextGen-specific parameters from preset raw
            val passthrough = listOf(
                "top_k", "min_p", "repetition_penalty", "repetition_penalty_range",
                "encoder_repetition_penalty", "typical_p", "epsilon_cutoff", "eta_cutoff",
                "tfs", "guidance_scale", "negative_prompt", "seed", "skip_special_tokens",
            )
            passthrough.forEach { key ->
                request.preset.raw[key]?.let { put(key, it) }
            }
        }

    // -- Response parsing --

    private fun parseDelta(data: String): DeltaParsed =
        runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching DeltaParsed()
            val delta = choice["delta"]?.jsonObject ?: return@runCatching DeltaParsed()
            val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            DeltaParsed(content = content, finishReason = finishReason)
        }.getOrDefault(DeltaParsed())

    private fun parseNonStream(data: String): NonStreamParsed =
        runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            NonStreamParsed(text = text, finishReason = finishReason)
        }.getOrDefault(NonStreamParsed(text = "", finishReason = null))

    // -- Utilities --

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private data class DeltaParsed(val content: String = "", val finishReason: String? = null)
    private data class NonStreamParsed(val text: String, val finishReason: String?)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
