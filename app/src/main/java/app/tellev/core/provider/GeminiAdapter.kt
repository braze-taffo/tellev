package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
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
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$model"
        val request = Request.Builder().url(url).applyGeminiHeaders(config).get().build()
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
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models"
        val request = Request.Builder().url(url).applyGeminiHeaders(config).get().build()
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
        val query = if (request.stream) "?alt=sse" else ""
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$model:$endpoint$query"

        val contents = buildContents(request)
        val systemText = request.prompt.messages
            .filter { it.role == MessageRole.System }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        val systemInstruction = systemText?.let {
            buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", JsonPrimitive(it)) })
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
                (request.prompt.maxTokens ?: request.preset.maxCompletionTokens ?: request.preset.maxTokens)
                    ?.let { put("maxOutputTokens", JsonPrimitive(it)) }
                // SillyTavern's -1 means "choose a random seed". Gemini expects
                // a non-negative integer, which is equivalent to omitting it.
                request.preset.seed?.takeIf { it >= 0 }?.let { put("seed", JsonPrimitive(it)) }
                request.preset.presencePenalty?.let { put("presencePenalty", JsonPrimitive(it)) }
                request.preset.frequencyPenalty?.let { put("frequencyPenalty", JsonPrimitive(it)) }
                if (request.preset.stop.isNotEmpty()) {
                    put("stopSequences", buildJsonArray { request.preset.stop.forEach { add(JsonPrimitive(it)) } })
                }
                request.preset.raw["responseMimeType"]?.let { put("responseMimeType", it) }
                request.preset.raw["responseSchema"]?.let { put("responseSchema", it) }
                request.preset.raw["thinkingConfig"]?.let { put("thinkingConfig", it) }
                (request.preset.raw["generationConfigExtra"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            })
            request.preset.raw["safetySettings"]?.let { put("safetySettings", it) }
            request.metadata["safetySettings"]?.let { put("safetySettings", it) }
            request.preset.raw["tools"]?.let { put("tools", it) }
            request.metadata["tools"]?.let { put("tools", it) }
            (request.metadata["toolConfig"] ?: request.preset.raw["toolConfig"])?.let { put("toolConfig", it) }
        }

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .apply { config.headers.forEach { (k, v) -> header(k, v) } }
            .applyGeminiHeaders(config)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val call = client.newCall(httpRequest)
        try {
            call.execute().use { response ->
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
                    var reasoningText = ""
                    var finishReason: String? = null
                    var usage: JsonObject? = null
                    var safetyBlock: String? = null
                    val toolCalls = mutableListOf<JsonObject>()
                    while (source != null && !source.exhausted()) {
                        coroutineContext.ensureActive()
                        val line = source.readUtf8Line().orEmpty()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        val parsed = parseGeminiResponse(data)
                        if (parsed.text.isNotEmpty()) {
                            fullText += parsed.text
                            emit(GenerateChunk.Delta(parsed.text))
                        }
                        reasoningText += parsed.reasoning
                        parsed.finishReason?.let { finishReason = it }
                        parsed.usage?.let { usage = it }
                        parsed.safetyBlock?.let { safetyBlock = it }
                        toolCalls += parsed.toolCalls
                    }
                    val completedText = if (reasoningText.isBlank()) fullText
                        else "<reasoning>\n$reasoningText\n</reasoning>\n$fullText"
                    if (completedText.isEmpty() && safetyBlock != null) {
                        emit(GenerateChunk.Failed(TellevError("gemini_safety", safetyBlock!!, retryable = false)))
                    } else {
                        emit(
                            GenerateChunk.Completed(
                                completedText,
                                finishReason ?: safetyBlock,
                                usage,
                                toolCalls.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                            ),
                        )
                    }
                } else {
                    val parsed = parseGeminiResponse(response.body?.string().orEmpty())
                    if (parsed.completedText.isEmpty() && parsed.safetyBlock != null) {
                        emit(GenerateChunk.Failed(TellevError("gemini_safety", parsed.safetyBlock, retryable = false)))
                    } else {
                        emit(
                            GenerateChunk.Completed(
                                parsed.completedText,
                                parsed.finishReason,
                                parsed.usage,
                                parsed.toolCalls.takeIf { it.isNotEmpty() }?.let(::JsonArray),
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: Exception) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "gemini_network",
                        message = e.message ?: "Network error",
                        retryable = true,
                        causeType = e::class.simpleName,
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun buildContents(request: GenerateRequest): JsonArray {
        val grouped = mutableListOf<GeminiContentBuilder>()
        request.prompt.messages.filter { it.role != MessageRole.System }.forEach { message ->
            val role = if (message.role == MessageRole.User || message.role == MessageRole.Tool) "user" else "model"
            val part = buildJsonObject { put("text", JsonPrimitive(message.content)) }
            val current = grouped.lastOrNull()
            if (current?.role == role) current.parts += part
            else grouped += GeminiContentBuilder(role, mutableListOf(part))
        }

        val imageParts = request.attachments
            .filter { it.mimeType.startsWith("image/") }
            .mapNotNull { attachment ->
                val data = attachment.metadata["base64"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", JsonPrimitive(attachment.mimeType))
                        put("data", JsonPrimitive(data))
                    })
                }
            }
        if (imageParts.isNotEmpty()) {
            val target = grouped.indexOfLast { it.role == "user" }
            if (target >= 0) grouped[target].parts += imageParts
            else grouped += GeminiContentBuilder("user", imageParts.toMutableList())
        }

        return JsonArray(grouped.map { content ->
            buildJsonObject {
                put("role", JsonPrimitive(content.role))
                put("parts", JsonArray(content.parts))
            }
        })
    }

    private fun parseGeminiResponse(data: String): GeminiParsed = runCatching {
        val obj = json.parseToJsonElement(data).jsonObject
        val candidates = obj["candidates"] as? JsonArray ?: JsonArray(emptyList())
        val textParts = mutableListOf<String>()
        val reasoningParts = mutableListOf<String>()
        val functionCalls = mutableListOf<JsonObject>()
        val finishReasons = mutableListOf<String>()
        candidates.forEach { candidateElement ->
            val candidate = candidateElement as? JsonObject ?: return@forEach
            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let(finishReasons::add)
            val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
                ?: return@forEach
            parts.forEach { partElement ->
                val part = partElement as? JsonObject ?: return@forEach
                (part["functionCall"] as? JsonObject)?.let(functionCalls::add)
                val text = part["text"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val isThought = part["thought"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                if (isThought) reasoningParts += text else textParts += text
            }
        }
        val safetyBlock = (obj["promptFeedback"] as? JsonObject)
            ?.get("blockReason")?.jsonPrimitive?.contentOrNull
            ?: finishReasons.firstOrNull { it in blockingFinishReasons }
        GeminiParsed(
            text = textParts.joinToString(""),
            reasoning = reasoningParts.joinToString(""),
            finishReason = finishReasons.distinct().joinToString(",").takeIf { it.isNotBlank() },
            usage = obj["usageMetadata"] as? JsonObject,
            safetyBlock = safetyBlock,
            toolCalls = functionCalls,
        )
    }.getOrDefault(GeminiParsed())

    private fun Request.Builder.applyGeminiHeaders(config: ProviderConfig): Request.Builder = apply {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { header("x-goog-api-key", it) }
        config.headers.forEach { (name, value) -> header(name, value) }
    }

    private data class GeminiContentBuilder(
        val role: String,
        val parts: MutableList<JsonObject>,
    )

    private data class GeminiParsed(
        val text: String = "",
        val reasoning: String = "",
        val finishReason: String? = null,
        val usage: JsonObject? = null,
        val safetyBlock: String? = null,
        val toolCalls: List<JsonObject> = emptyList(),
    ) {
        val completedText: String
            get() = if (reasoning.isBlank()) text else "<reasoning>\n$reasoning\n</reasoning>\n$text"
    }

    private companion object {
        val blockingFinishReasons = setOf("SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION")
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
