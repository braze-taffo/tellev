package app.tellev.core.provider

import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Adapter for OpenAI Image Generation (DALL-E).
 * Uses POST /v1/images/generations.
 * Returns image URLs or base64 as single Completed chunks.
 */
class OpenAiImageAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.OPENAI_IMAGE
    override val displayName: String = "OpenAI Images"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Images,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/v1/models"))
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

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = listOf(
        ProviderModel("dall-e-3", "DALL-E 3", capabilities),
        ProviderModel("dall-e-2", "DALL-E 2", capabilities),
    )

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Extract the prompt from the last user message
        val promptText = request.prompt.messages
            .filter { it.role != app.tellev.core.model.MessageRole.System }
            .joinToString(" ") { it.content }

        val model = config.model ?: "dall-e-3"
        val size = request.metadata["size"]?.jsonPrimitive?.contentOrNull ?: "1024x1024"
        val quality = request.metadata["quality"]?.jsonPrimitive?.contentOrNull ?: "standard"
        val style = request.metadata["style"]?.jsonPrimitive?.contentOrNull
        val responseFormat = request.metadata["response_format"]?.jsonPrimitive?.contentOrNull ?: "url"
        val n = request.metadata["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("prompt", JsonPrimitive(promptText))
            put("n", JsonPrimitive(n))
            put("size", JsonPrimitive(size))
            put("response_format", JsonPrimitive(responseFormat))
            if (model == "dall-e-3") {
                put("quality", JsonPrimitive(quality))
                style?.let { put("style", JsonPrimitive(it)) }
            }
        }

        val httpRequest = Request.Builder()
            .url(config.endpoint("/v1/images/generations"))
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = client.newCall(httpRequest)

        try {
            coroutineContext.ensureActive()

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
                                code = "openai_image_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                val body = response.body?.string().orEmpty()
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray

                if (data.isNullOrEmpty()) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "openai_image_no_data",
                                message = "OpenAI returned no image data",
                                retryable = false,
                            ),
                        ),
                    )
                    return@use
                }

                // Return the first image result
                val firstResult = data.first().jsonObject
                val imageUrl = firstResult["url"]?.jsonPrimitive?.contentOrNull
                val b64Json = firstResult["b64_json"]?.jsonPrimitive?.contentOrNull
                val revisedPrompt = firstResult["revised_prompt"]?.jsonPrimitive?.contentOrNull

                val result = imageUrl ?: b64Json ?: ""
                emit(GenerateChunk.Completed(result, "image_generated"))
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

    // -- Utilities --

    private fun ProviderConfig.endpoint(path: String): String =
        baseUrl.trimEnd('/') + path

    private fun Request.Builder.applyHeaders(config: ProviderConfig): Request.Builder = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        header("Content-Type", "application/json")
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
