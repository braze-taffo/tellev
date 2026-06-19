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

class OllamaAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.OLLAMA
    override val displayName: String = "Ollama"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/tags")
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
            .url(config.baseUrl.trimEnd('/') + "/api/tags")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ProviderModel>()
                val body = response.body?.string().orEmpty()
                val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: return@use emptyList()
                models.mapNotNull { item ->
                    val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ProviderModel(id = name, displayName = name, capabilities = capabilities)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val payload = buildJsonObject {
            put("model", JsonPrimitive(config.model ?: "llama3"))
            put("stream", JsonPrimitive(request.stream))
            put("options", buildJsonObject {
                request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
                request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
                request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }
                request.preset.maxTokens?.let { put("num_predict", JsonPrimitive(it)) }
                if (request.preset.stop.isNotEmpty()) {
                    put("stop", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
                }
            })
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
                    })
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/api/chat")
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                emit(GenerateChunk.Failed(TellevError(
                    code = "ollama_http_${response.code}",
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
                    if (line.isBlank()) continue
                    val parsed = runCatching {
                        val obj = json.parseToJsonElement(line).jsonObject
                        val done = obj["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        val text = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (done) null else text
                    }.getOrNull()
                    if (parsed == null) break
                    if (parsed.isNotEmpty()) {
                        fullText += parsed
                        emit(GenerateChunk.Delta(parsed))
                    }
                }
                emit(GenerateChunk.Completed(fullText))
            } else {
                val body = response.body?.string().orEmpty()
                val text = runCatching {
                    json.parseToJsonElement(body).jsonObject["message"]?.jsonObject
                        ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
                emit(GenerateChunk.Completed(text))
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
