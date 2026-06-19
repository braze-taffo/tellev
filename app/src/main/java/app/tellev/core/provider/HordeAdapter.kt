package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * Adapter for AI Horde (stablehorde.net) text generation.
 * Uses an asynchronous request/poll pattern:
 * 1. POST /generate/text (async, returns request ID)
 * 2. GET /generate/text/status/{id} (poll for completion)
 */
class HordeAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.HORDE
    override val displayName: String = "AI Horde"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Text,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        // Check the heartbeat endpoint
        val request = Request.Builder()
            .url("$HORDE_BASE_URL/status/heartbeat")
            .applyHeaders(config)
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
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val request = Request.Builder()
            .url("$HORDE_BASE_URL/status/models?type=text")
            .applyHeaders(config)
            .get()
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use knownModels()
                val body = response.body?.string().orEmpty()
                val models = json.parseToJsonElement(body).jsonArray
                models.mapNotNull { item ->
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val count = obj["count"]?.jsonPrimitive?.contentOrNull
                    ProviderModel(
                        id = name,
                        displayName = name,
                        capabilities = capabilities,
                        metadata = buildJsonObject {
                            count?.let { put("workers", JsonPrimitive(it)) }
                        },
                    )
                }
            }
        }.getOrElse { knownModels() }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        // Build the prompt text from messages
        val promptText = buildPromptText(request)

        // Step 1: Submit the generation request
        val submitPayload = buildSubmitPayload(config, request, promptText)
        val submitRequest = Request.Builder()
            .url("$HORDE_BASE_URL/generate/text/async")
            .applyHeaders(config)
            .post(submitPayload.toString().toRequestBody(JSON))
            .build()

        var requestId: String = ""
        val submitCall = client.newCall(submitRequest)

        try {
            submitCall.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val errorMessage = runCatching {
                        json.parseToJsonElement(errorBody).jsonObject["message"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: errorBody.ifBlank { "HTTP ${response.code}" }

                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "horde_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                val body = response.body?.string().orEmpty()
                requestId = json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()

                if (requestId.isBlank()) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "horde_no_id",
                                message = "No request ID returned from Horde",
                                retryable = false,
                            ),
                        ),
                    )
                    return@use
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            submitCall.cancel()
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
            return@flow
        }

        // Step 2: Poll for completion
        val maxAttempts = 120 // 10 minutes at 5-second intervals
        var attempts = 0

        while (attempts < maxAttempts) {
            coroutineContext.ensureActive()

            delay(POLL_INTERVAL_MS)
            attempts++

            val statusRequest = Request.Builder()
                .url("$HORDE_BASE_URL/generate/text/status/$requestId")
                .applyHeaders(config)
                .get()
                .build()

            val statusCall = client.newCall(statusRequest)
            try {
                statusCall.execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 404) {
                            emit(
                                GenerateChunk.Failed(
                                    TellevError(
                                        code = "horde_not_found",
                                        message = "Request not found - may have expired",
                                        retryable = true,
                                    ),
                                ),
                            )
                            return@flow
                        }
                        return@use // Retry on transient errors
                    }

                    val body = response.body?.string().orEmpty()
                    val statusObj = json.parseToJsonElement(body).jsonObject

                    val done = statusObj["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    val faulted = statusObj["faulted"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

                    if (faulted) {
                        emit(
                            GenerateChunk.Failed(
                                TellevError(
                                    code = "horde_faulted",
                                    message = "Request faulted on Horde",
                                    retryable = true,
                                ),
                            ),
                        )
                        return@flow
                    }

                    if (done) {
                        val generations = statusObj["generations"]?.jsonArray
                        val text = generations?.firstOrNull()?.jsonObject
                            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

                        if (text.isEmpty()) {
                            emit(
                                GenerateChunk.Failed(
                                    TellevError(
                                        code = "horde_empty",
                                        message = "Horde returned an empty response",
                                        retryable = true,
                                    ),
                                ),
                            )
                        } else {
                            emit(GenerateChunk.Completed(text))
                        }
                        return@flow
                    }

                    // Still processing - emit a status update as empty delta (optional)
                    val waitTime = statusObj["wait_time"]?.jsonPrimitive?.contentOrNull
                    val queuePosition = statusObj["queue_position"]?.jsonPrimitive?.contentOrNull
                    // Could emit a progress indicator here if needed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                statusCall.cancel()
                // Try to cancel the remote request
                cancelRemoteRequest(config, requestId)
                throw e
            } catch (_: Exception) {
                // Network error during polling, retry
            }
        }

        // Timeout
        emit(
            GenerateChunk.Failed(
                TellevError(
                    code = "horde_timeout",
                    message = "Horde request timed out after ${maxAttempts * POLL_INTERVAL_MS / 1000}s",
                    retryable = true,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    // -- Payload construction --

    private fun buildPromptText(request: GenerateRequest): String {
        return request.prompt.messages.joinToString("\n") { msg ->
            when (msg.role) {
                MessageRole.System -> msg.content
                MessageRole.User -> "User: ${msg.content}"
                MessageRole.Assistant, MessageRole.Character -> msg.content
                MessageRole.Tool -> "System: ${msg.content}"
            }
        }
    }

    private fun buildSubmitPayload(config: ProviderConfig, request: GenerateRequest, promptText: String): JsonObject {
        val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens ?: 512

        return buildJsonObject {
            put("prompt", JsonPrimitive(promptText))

            put("params", buildJsonObject {
                put("max_length", JsonPrimitive(maxTokens))
                put("max_context_length", JsonPrimitive(2048))
                request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
                request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
                request.preset.topK?.let { put("top_k", JsonPrimitive(it)) }

                val repPen = request.preset.raw["repetition_penalty"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                repPen?.let { put("rep_pen", JsonPrimitive(it)) }
                val repPenRange = request.preset.raw["repetition_penalty_range"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                repPenRange?.let { put("rep_pen_range", JsonPrimitive(it)) }

                val stopSeqs = (request.preset.stop + request.prompt.stop).distinct()
                if (stopSeqs.isNotEmpty()) {
                    put("stop_sequence", buildJsonArray { stopSeqs.forEach { add(JsonPrimitive(it)) } })
                }
            })

            // Model selection
            val model = config.model
            if (model != null && model.isNotBlank()) {
                put("models", buildJsonArray { add(JsonPrimitive(model)) })
            }

            // Soft prompt from metadata
            request.metadata["softprompt"]?.let { put("softprompt", it) }
        }
    }

    private fun cancelRemoteRequest(config: ProviderConfig, requestId: String) {
        runCatching {
            val cancelRequest = Request.Builder()
                .url("$HORDE_BASE_URL/generate/text/status/$requestId")
                .applyHeaders(config)
                .delete()
                .build()
            client.newCall(cancelRequest).execute().close()
        }
    }

    private fun knownModels(): List<ProviderModel> = listOf(
        ProviderModel("aphrodite/metharme-12b", "Metharme 12B", capabilities),
        ProviderModel("alpaca", "Alpaca", capabilities),
    )

    // -- Utilities --

    private fun Request.Builder.applyHeaders(config: ProviderConfig): Request.Builder = apply {
        val apiKey = config.apiKey?.takeIf { it.isNotBlank() } ?: ANONYMOUS_KEY
        header("apikey", apiKey)
        header("Content-Type", "application/json")
        header("Client-Agent", "tellev:1.0.0:anonymous")
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private companion object {
        const val HORDE_BASE_URL = "https://stablehorde.net/api/v2"
        const val ANONYMOUS_KEY = "0000000000"
        const val POLL_INTERVAL_MS = 5000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
