package app.tellev.core.provider

import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        val systemMessage = request.prompt.messages.firstOrNull { it.role == MessageRole.System }?.content
        val conversationMessages = request.prompt.messages.filter { it.role != MessageRole.System }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: "claude-sonnet-4-20250514"))
            put("max_tokens", JsonPrimitive(request.preset.maxTokens ?: 8192))
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
                conversationMessages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", JsonPrimitive(if (msg.role == MessageRole.User) "user" else "assistant"))
                        put("content", JsonPrimitive(msg.content))
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

        client.newCall(httpRequest).execute().use { response ->
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
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
