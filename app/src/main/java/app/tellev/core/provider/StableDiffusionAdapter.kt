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
 * Adapter for Stable Diffusion WebUI (AUTOMATIC1111).
 * Uses POST /sdapi/v1/txt2img for image generation.
 * Returns base64-encoded images as single Completed chunks.
 */
class StableDiffusionAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.STABLE_DIFFUSION
    override val displayName: String = "Stable Diffusion WebUI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Images,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val request = Request.Builder()
            .url(config.endpoint("/sdapi/v1/options"))
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
            ProviderStatus(available = false, message = it.message ?: "Connection failed - is SD WebUI running with --api?")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url(config.endpoint("/sdapi/v1/sd-models"))
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val models = json.parseToJsonElement(body).jsonArray
                models.mapNotNull { item ->
                    val obj = item.jsonObject
                    val name = obj["model_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: name
                    ProviderModel(
                        id = name,
                        displayName = title,
                        capabilities = capabilities,
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Extract the prompt from messages
        val promptText = request.prompt.messages
            .filter { it.role != app.tellev.core.model.MessageRole.System }
            .joinToString(" ") { it.content }

        // Extract negative prompt from metadata
        val negativePrompt = request.metadata["negative_prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()

        // Extract generation parameters from metadata/preset
        val steps = request.metadata["steps"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
        val width = request.metadata["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 512
        val height = request.metadata["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 512
        val cfgScale = request.metadata["cfg_scale"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 7.0
        val samplerName = request.metadata["sampler_name"]?.jsonPrimitive?.contentOrNull ?: "Euler a"
        val batchSize = request.metadata["batch_size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1

        val payload = buildJsonObject {
            put("prompt", JsonPrimitive(promptText))
            put("negative_prompt", JsonPrimitive(negativePrompt))
            put("steps", JsonPrimitive(steps))
            put("width", JsonPrimitive(width))
            put("height", JsonPrimitive(height))
            put("cfg_scale", JsonPrimitive(cfgScale))
            put("sampler_name", JsonPrimitive(samplerName))
            put("batch_size", JsonPrimitive(batchSize))
            request.preset.raw["seed"]?.let { put("seed", it) }
        }

        val httpRequest = Request.Builder()
            .url(config.endpoint("/sdapi/v1/txt2img"))
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
                                code = "sd_http_${response.code}",
                                message = errorBody.ifBlank { "HTTP ${response.code}" },
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                val body = response.body?.string().orEmpty()
                val images = json.parseToJsonElement(body).jsonObject["images"]?.jsonArray

                if (images.isNullOrEmpty()) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "sd_no_images",
                                message = "Stable Diffusion returned no images",
                                retryable = false,
                            ),
                        ),
                    )
                    return@use
                }

                // Return the first image as base64
                val base64Image = images.first().jsonPrimitive.content
                emit(GenerateChunk.Completed(base64Image, "image_generated"))
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
