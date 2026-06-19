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
 * Adapter for KoboldCpp, which uses an OpenAI-compatible API at /v1/chat/completions.
 * Supports SSE streaming.
 */
class KoboldCppAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.KOBOLDCPP
    override val displayName: String = "KoboldCpp"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/api/v1/model"))
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
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        // Try OpenAI-compatible models endpoint first
        val modelsRequest = Request.Builder()
            .url(config.endpoint("/v1/models"))
            .get()
            .build()

        return runCatching {
            client.newCall(modelsRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use fallbackModel(config)
                }
                val body = response.body?.string().orEmpty()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@use fallbackModel(config)
                data.mapNotNull { item ->
                    val modelId = item.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = modelId, displayName = modelId, capabilities = capabilities)
                }
            }
        }.getOrElse { fallbackModel(config) }
    }

    private fun fallbackModel(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url(config.endpoint("/api/v1/model"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val modelName = json.parseToJsonElement(body)
                    .jsonObject["result"]?.jsonPrimitive?.contentOrNull ?: "default"
                listOf(ProviderModel(id = modelName, displayName = modelName, capabilities = capabilities))
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
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "koboldcpp_http_${response.code}",
                                message = errorBody.ifBlank { "HTTP ${response.code}" },
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                if (request.stream) {
                    val source = response.body?.source()
                    var fullText = ""

                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()

                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val delta = parseDelta(data)
                        if (delta.isNotEmpty()) {
                            fullText += delta
                            emit(GenerateChunk.Delta(delta))
                        }
                    }

                    emit(GenerateChunk.Completed(fullText))
                } else {
                    val body = response.body?.string().orEmpty()
                    val text = parseMessage(body)
                    emit(GenerateChunk.Completed(text))
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

    private fun buildPayload(config: ProviderConfig, request: GenerateRequest): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(config.model ?: request.preset.id))
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }
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
        }

    private fun parseDelta(data: String): String =
        runCatching {
            json.parseToJsonElement(data)
                .jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")

    private fun parseMessage(data: String): String =
        runCatching {
            json.parseToJsonElement(data)
                .jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
