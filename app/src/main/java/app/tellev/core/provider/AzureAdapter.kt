package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
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
 * Adapter for Azure OpenAI Service.
 * Extends the OpenAI-compatible pattern with Azure-specific URL format and authentication.
 *
 * URL format: {baseUrl}/openai/deployments/{deployment}/chat/completions?api-version={version}
 * Auth: api-key header instead of Bearer token.
 */
class AzureAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.AZURE_OPENAI
    override val displayName: String = "Azure OpenAI"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.Chat,
        ProviderCapability.Streaming,
        ProviderCapability.Vision,
    )

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val url = buildChatUrl(config, stream = false)
        val payload = buildJsonObject {
            put("max_tokens", JsonPrimitive(1))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive("ping"))
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> ProviderStatus(available = true, message = "Connected")
                    401 -> ProviderStatus(available = false, message = "Invalid API key")
                    403 -> ProviderStatus(available = false, message = "Forbidden - check resource and deployment")
                    404 -> ProviderStatus(available = false, message = "Not found - check deployment name and API version")
                    else -> ProviderStatus(
                        available = false,
                        message = "HTTP ${response.code}: ${response.body?.string().orEmpty().take(200)}",
                    )
                }
            }
        }.getOrElse {
            ProviderStatus(available = false, message = it.message ?: "Connection failed")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        // Azure doesn't have a public models list endpoint in the same way.
        // Return the deployment as a model.
        val deployment = config.options["deployment"]?.jsonPrimitive?.contentOrNull
            ?: config.model
            ?: "gpt-4"
        return listOf(
            ProviderModel(
                id = deployment,
                displayName = deployment,
                capabilities = capabilities,
            ),
        )
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val url = buildChatUrl(config, stream = request.stream)
        val payload = buildPayload(config, request)

        val httpRequest = Request.Builder()
            .url(url)
            .applyHeaders(config)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = client.newCall(httpRequest)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val errorMessage = runCatching {
                        val errorObj = json.parseToJsonElement(errorBody).jsonObject["error"]?.jsonObject
                        val msg = errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                        val code = errorObj?.get("code")?.jsonPrimitive?.contentOrNull
                        "$code: ${msg.orEmpty()}"
                    }.getOrNull() ?: errorBody.ifBlank { "HTTP ${response.code}" }

                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "azure_http_${response.code}",
                                message = errorMessage,
                                retryable = response.code in 429..599,
                            ),
                        ),
                    )
                    return@use
                }

                if (request.stream) {
                    val source = response.body?.source()
                    var fullText = ""
                    var finishReason: String? = null

                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()

                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        val parsed = parseDelta(data)
                        if (parsed.content.isNotEmpty()) {
                            fullText += parsed.content
                            emit(GenerateChunk.Delta(parsed.content))
                        }
                        if (parsed.finishReason != null) {
                            finishReason = parsed.finishReason
                        }
                    }

                    emit(GenerateChunk.Completed(fullText, finishReason))
                } else {
                    val body = response.body?.string().orEmpty()
                    val parsed = parseNonStream(body)
                    emit(GenerateChunk.Completed(parsed.text, parsed.finishReason))
                }
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

    // -- URL and payload construction --

    private fun buildChatUrl(config: ProviderConfig, stream: Boolean): String {
        val deployment = config.options["deployment"]?.jsonPrimitive?.contentOrNull
            ?: config.model
            ?: "gpt-4"
        val apiVersion = config.options["api_version"]?.jsonPrimitive?.contentOrNull
            ?: "2024-02-01"
        val base = config.baseUrl.trimEnd('/')
        return "$base/openai/deployments/$deployment/chat/completions?api-version=$apiVersion"
    }

    private fun buildPayload(config: ProviderConfig, request: GenerateRequest): JsonObject =
        buildJsonObject {
            put("stream", JsonPrimitive(request.stream))
            request.preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.preset.topP?.let { put("top_p", JsonPrimitive(it)) }
            val maxTokens = request.prompt.maxTokens ?: request.preset.maxTokens
            maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            val stopSeqs = (request.preset.stop + request.prompt.stop).distinct()
            if (stopSeqs.isNotEmpty()) {
                put("stop", buildJsonArray { stopSeqs.forEach { add(JsonPrimitive(it)) } })
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

            // Tools from metadata
            val tools = request.metadata["tools"]
            if (tools is JsonArray && tools.isNotEmpty()) {
                put("tools", tools)
                request.metadata["tool_choice"]?.let { put("tool_choice", it) }
            }
        }

    // -- Response parsing --

    private fun parseDelta(data: String): DeltaParsed =
        runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching DeltaParsed()
            val delta = choice["delta"]?.jsonObject ?: return@runCatching DeltaParsed()
            val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            DeltaParsed(content = content, finishReason = finishReason)
        }.getOrDefault(DeltaParsed())

    private fun parseNonStream(data: String): NonStreamParsed =
        runCatching {
            val obj = json.parseToJsonElement(data).jsonObject
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val text = choice?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val finishReason = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            NonStreamParsed(text = text, finishReason = finishReason)
        }.getOrDefault(NonStreamParsed(text = "", finishReason = null))

    // -- Utilities --

    private fun Request.Builder.applyHeaders(config: ProviderConfig): Request.Builder = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("api-key", it) }
        header("Content-Type", "application/json")
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private data class DeltaParsed(val content: String = "", val finishReason: String? = null)
    private data class NonStreamParsed(val text: String, val finishReason: String?)

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
