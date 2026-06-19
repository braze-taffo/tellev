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

class GeminiAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.GEMINI
    override val displayName: String = "Google Gemini"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val model = config.model ?: "gemini-2.0-flash"
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$model?key=${config.apiKey.orEmpty()}"
        val request = Request.Builder().url(url).get().build()
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
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models?key=${config.apiKey.orEmpty()}"
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ProviderModel>()
                val body = response.body?.string().orEmpty()
                val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray ?: return@use emptyList()
                models.mapNotNull { item ->
                    val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val shortName = name.removePrefix("models/")
                    ProviderModel(id = shortName, displayName = shortName, capabilities = capabilities)
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val model = config.model ?: "gemini-2.0-flash"
        val endpoint = if (request.stream) "streamGenerateContent" else "generateContent"
        val apiKeyParam = if (config.apiKey != null) "&key=${config.apiKey}" else ""
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$model:$endpoint?alt=sse$apiKeyParam"

        val contents = buildJsonArray {
            request.prompt.messages.filter { it.role != MessageRole.System }.forEach { msg ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(if (msg.role == MessageRole.User) "user" else "model"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(msg.content)) })
                    })
                })
            }
        }

        val systemInstruction = request.prompt.messages.firstOrNull { it.role == MessageRole.System }?.let { sys ->
            buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(sys.content)) })
                })
            }
        }

        val payload = buildJsonObject {
            put("contents", contents)
            systemInstruction?.let { put("systemInstruction", it) }
            put("generationConfig", buildJsonObject {
                request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
                request.preset.topP?.let { put("topP", JsonPrimitive(it)) }
                request.preset.topK?.let { put("topK", JsonPrimitive(it)) }
                request.preset.maxTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
                if (request.preset.stop.isNotEmpty()) {
                    put("stopSequences", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                emit(GenerateChunk.Failed(TellevError(
                    code = "gemini_http_${response.code}",
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
                    if (data.isEmpty()) continue
                    val parsed = runCatching {
                        val obj = json.parseToJsonElement(data).jsonObject
                        obj["candidates"]?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("content")?.jsonObject
                            ?.get("parts")?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
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
                    obj["candidates"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
                emit(GenerateChunk.Completed(text))
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
