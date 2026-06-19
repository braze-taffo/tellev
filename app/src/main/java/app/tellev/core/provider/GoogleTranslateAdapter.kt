package app.tellev.core.provider

import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/**
 * Adapter for Google Cloud Translation API (v2).
 * Uses GET https://translation.googleapis.com/language/translate/v2
 * Simple non-streaming implementation that emits a single Completed chunk.
 */
class GoogleTranslateAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.GOOGLE_TRANSLATE
    override val displayName: String = "Google Translate"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Translation,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        // Try a minimal translation to verify the API key works
        val apiKey = config.apiKey.orEmpty()
        val url = "$GOOGLE_TRANSLATE_URL?q=hello&target=en&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ProviderStatus(available = true, message = "Connected")
                    response.code == 400 -> ProviderStatus(available = false, message = "Invalid API key or parameters")
                    response.code == 403 -> ProviderStatus(available = false, message = "Forbidden - check API key permissions")
                    else -> ProviderStatus(available = false, message = "HTTP ${response.code}")
                }
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        // Google Translate doesn't have "models" per se, but we return the available API versions
        return listOf(
            ProviderModel(
                id = "v2",
                displayName = "Google Translate v2",
                capabilities = capabilities,
            ),
        )
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Extract the text to translate from the prompt
        val inputText = request.prompt.messages
            .filter { it.role != app.tellev.core.model.MessageRole.System }
            .joinToString("\n") { it.content }

        val apiKey = config.apiKey.orEmpty()
        val targetLanguage = request.metadata["target"]?.jsonPrimitive?.contentOrNull
            ?: config.options["target"]?.jsonPrimitive?.contentOrNull
            ?: "en"
        val sourceLanguage = request.metadata["source"]?.jsonPrimitive?.contentOrNull
            ?: config.options["source"]?.jsonPrimitive?.contentOrNull
        val format = request.metadata["format"]?.jsonPrimitive?.contentOrNull ?: "text"

        // Build the URL with query parameters
        val urlBuilder = GOOGLE_TRANSLATE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", inputText)
            .addQueryParameter("target", targetLanguage)
            .addQueryParameter("key", apiKey)
            .addQueryParameter("format", format)

        sourceLanguage?.let { urlBuilder.addQueryParameter("source", it) }

        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .apply { config.headers.forEach { (name, value) -> header(name, value) } }
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
                                code = "google_translate_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                val body = response.body?.string().orEmpty()
                val translations = runCatching {
                    json.parseToJsonElement(body)
                        .jsonObject["data"]
                        ?.jsonObject
                        ?.get("translations")
                        ?.jsonArray
                }.getOrNull()

                if (translations.isNullOrEmpty()) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "google_translate_no_data",
                                message = "Google Translate returned no translations",
                                retryable = false,
                            ),
                        ),
                    )
                    return@use
                }

                // Combine all translations
                val translatedText = translations.joinToString("\n") { translation ->
                    translation.jsonObject["translatedText"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }

                // Include detected source language if available
                val detectedSource = translations.firstOrNull()
                    ?.jsonObject?.get("detectedSourceLanguage")
                    ?.jsonPrimitive?.contentOrNull

                emit(GenerateChunk.Completed(translatedText, detectedSource))
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

    private companion object {
        const val GOOGLE_TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"
    }
}
