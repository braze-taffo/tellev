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
)

@Serializable
sealed interface GenerateChunk {
    @Serializable
    data class Delta(val text: String) : GenerateChunk

    @Serializable
    data class Completed(
        val text: String,
        val finishReason: String? = null,
        // Provider usage/token accounting (e.g. the OpenAI stream
        // include_usage final chunk). Null when the provider did not report it.
        val usage: JsonObject? = null,
    ) : GenerateChunk

    @Serializable
    data class Failed(val error: TellevError) : GenerateChunk
}

