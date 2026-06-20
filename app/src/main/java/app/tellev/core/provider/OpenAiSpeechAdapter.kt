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
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Adapter for OpenAI Text-to-Speech (TTS).
 * Uses POST /v1/audio/speech.
 * Returns audio bytes as base64-encoded string in a Completed chunk.
 */
class OpenAiSpeechAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.OPENAI_SPEECH
    override val displayName: String = "OpenAI Speech"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.TextToSpeech,
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
        ProviderModel("tts-1", "TTS-1 (Fast)", capabilities),
        ProviderModel("tts-1-hd", "TTS-1 HD (Quality)", capabilities),
    )

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Extract the text to synthesize from the prompt messages
        val inputText = request.prompt.messages
            .filter { it.role != app.tellev.core.model.MessageRole.System }
            .joinToString(" ") { it.content }

        val model = config.model ?: "tts-1"
        val voice = request.metadata["voice"]?.jsonPrimitive?.contentOrNull ?: "alloy"
        val responseFormat = request.metadata["response_format"]?.jsonPrimitive?.contentOrNull ?: "mp3"
        val speed = request.metadata["speed"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(inputText))
            put("voice", JsonPrimitive(voice))
            put("response_format", JsonPrimitive(responseFormat))
            speed?.let { put("speed", JsonPrimitive(it)) }
        }

        val httpRequest = Request.Builder()
            .url(config.endpoint("/v1/audio/speech"))
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
                                code = "openai_speech_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                // Read the audio bytes and encode as base64
                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "openai_speech_empty",
                                message = "OpenAI returned empty audio response",
                                retryable = false,
                            ),
                        ),
                    )
                    return@use
                }

                val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
                emit(GenerateChunk.Completed(base64Audio, "audio_generated"))
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
