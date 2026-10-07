package app.tellev.core.provider

import app.tellev.core.model.Attachment
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.TellevError
import app.tellev.core.prompt.PromptBuildResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

interface ProviderAdapter {
    val id: String
    val displayName: String
    val capabilities: Set<ProviderCapability>

    suspend fun checkStatus(config: ProviderConfig): ProviderStatus
    suspend fun listModels(config: ProviderConfig): List<ProviderModel>
    fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk>

    /**
     * 适配器自家路由的上下文窗口上限（dsh contextWindow：adapter 声明的
     * 容量）。只有握有权威数字的适配器覆写（如 Gemini 系列的固定窗）；
     * 默认 null。调用方叠加：用户档案 > 知识库 > 本声明 > 预设值。
     */
    fun declaredContextWindow(config: ProviderConfig): Long? = null
}

/** OpenAI-shaped bodies exposed to request-time completion hooks. */
interface CompletionSettingsAdapter : ProviderAdapter {
    fun completionPayload(config: ProviderConfig, request: GenerateRequest): JsonObject
}

@Serializable
enum class ProviderCapability {
    Text,
    Chat,
    Streaming,
    Images,
    SpeechToText,
    TextToSpeech,
    Translation,
    Embeddings,
    Vision,
}

/** Whether this adapter can produce a normal assistant message in chat. */
val ProviderAdapter.supportsChatGeneration: Boolean
    get() = ProviderCapability.Chat in capabilities || ProviderCapability.Text in capabilities

@Serializable
data class ProviderConfig(
    val providerType: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val options: JsonObject = buildJsonObject { },
)

@Serializable
data class ProviderStatus(
    val available: Boolean,
    val message: String,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class ProviderModel(
    val id: String,
    val displayName: String = id,
    val capabilities: Set<ProviderCapability> = emptySet(),
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class GenerateRequest(
    val prompt: PromptBuildResult,
    val preset: GenerationPreset,
    val attachments: List<Attachment> = emptyList(),
    val stream: Boolean = true,
    val metadata: JsonObject = buildJsonObject { },
    /** Final per-request OpenAI body after completion events; never persisted in presets. */
    val completionSettings: JsonObject? = null,
)

@Serializable
sealed interface GenerateChunk {
    @Serializable
    data class Delta(val text: String, val reasoning: String = "") : GenerateChunk

    @Serializable
    data class Completed(
        val text: String,
        val finishReason: String? = null,
        // Provider usage/token accounting (e.g. the OpenAI stream
        // include_usage final chunk). Null when the provider did not report it.
        val usage: JsonObject? = null,
        // Normalized provider tool/function calls, when the model requests one.
        val toolCalls: kotlinx.serialization.json.JsonArray? = null,
        val reasoning: String = "",
        /** Opt-in response diagnostics for local troubleshooting; excludes request headers/credentials. */
        val providerDiagnostics: JsonObject? = null,
    ) : GenerateChunk

    @Serializable
    data class Failed(val error: TellevError) : GenerateChunk
}
