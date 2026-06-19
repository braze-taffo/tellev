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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

/**
 * Adapter for KoboldAI (the original Kobold backend).
 * Uses /api/v1/generate which is non-streaming by nature.
 * Emits a single Completed chunk with the full response.
 */
class KoboldAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.KOBOLD
    override val displayName: String = "KoboldAI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Text,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/api/v1/model"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val modelName = runCatching {
                        json.parseToJsonElement(body).jsonObject["result"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "Unknown"
                    ProviderStatus(available = true, message = "Connected - $modelName")
                } else {
                    ProviderStatus(available = false, message = "HTTP ${response.code}")
                }
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
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
        val promptText = request.prompt.messages.joinToString("\n") { msg ->
            when (msg.role) {
                MessageRole.System -> msg.content
                MessageRole.User -> "User: ${msg.content}"
                MessageRole.Assistant, MessageRole.Character -> "Assistant: ${msg.content}"
                MessageRole.Tool -> "Tool: ${msg.content}"
            }
        } + "\nAssistant:"

        val payload = buildJsonObject {
            put("prompt", JsonPrimitive(promptText))
            val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens ?: 512
            put("max_length", JsonPrimitive(maxTokens))
            put("max_context_length", JsonPrimitive(request.metadata["max_context_length"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2048))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }

            // Repetition penalty from raw preset or defaults
            val repPen = request.preset.raw["repetition_penalty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 1.1
            put("rep_pen", JsonPrimitive(repPen))
            val repPenRange = request.preset.raw["repetition_penalty_range"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 256
            put("rep_pen_range", JsonPrimitive(repPenRange))

            val stopSeqs = (request.preset.stop + request.prompt.stop).distinct()
            if (stopSeqs.isNotEmpty()) {
                put("stop_sequence", buildJsonArray { stopSeqs.forEach { add(JsonPrimitive(it)) } })
            }
        }

        val httpRequest = Request.Builder()
            .url(config.endpoint("/api/v1/generate"))
            .post(payload.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val call = client.newCall(httpRequest)

        try {
            coroutineContext.ensureActive()

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "kobold_http_${response.code}",
                                message = errorBody.ifBlank { "HTTP ${response.code}" },
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                val body = response.body?.string().orEmpty()
                val text = runCatching {
                    json.parseToJsonElement(body)
                        .jsonObject["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }.getOrElse { "" }

                if (text.isEmpty() && body.isNotBlank()) {
                    val errorMsg = runCatching {
                        json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    if (errorMsg != null) {
                        emit(GenerateChunk.Failed(TellevError(code = "kobold_error", message = errorMsg)))
                        return@use
                    }
                }

                emit(GenerateChunk.Completed(text))
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

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
