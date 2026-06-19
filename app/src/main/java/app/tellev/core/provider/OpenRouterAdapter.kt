package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
) : ProviderAdapter {
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

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: request.preset.id))
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.preset.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            if (request.preset.stop.isNotEmpty()) {
                put("stop", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
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

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/v1/chat/completions")
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
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
