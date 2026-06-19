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
 * NovelAI adapter - uses the NovelAI text generation API.
 * Supports both their curated models and user fine-tuned models.
 */
class NovelAiAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.NOVELAI
    override val displayName: String = "NovelAI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Text,
        ProviderCapability.Streaming,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/user/subscription")
            .header("Authorization", "Bearer ${config.apiKey.orEmpty()}")
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
        return listOf(
            ProviderModel(id = "clio-v1", displayName = "Clio", capabilities = capabilities),
            ProviderModel(id = "kayra-v1", displayName = "Kayra", capabilities = capabilities),
            ProviderModel(id = "llama-3-erato-v1", displayName = "Erato (LLaMA 3)", capabilities = capabilities),
        )
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val promptText = request.prompt.messages.joinToString("\n") { it.content }
        val model = config.model ?: "kayra-v1"

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(promptText))
            put("stream", JsonPrimitive(request.stream))
            put("parameters", buildJsonObject {
                request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
                request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
                request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }
                request.preset.maxTokens?.let { put("max_length", JsonPrimitive(it)) }
                if (request.preset.stop.isNotEmpty()) {
                    put("stop_sequences", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/ai/generate")
            .header("Authorization", "Bearer ${config.apiKey.orEmpty()}")
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                emit(GenerateChunk.Failed(TellevError(
                    code = "novelai_http_${response.code}",
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
                        obj["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
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
                    json.parseToJsonElement(body).jsonObject["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }.getOrDefault("")
                emit(GenerateChunk.Completed(text))
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
