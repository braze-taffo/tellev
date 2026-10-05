package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ImageResultNormalizer
import app.tellev.core.provider.ImageProviderProfile
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One-shot image generation for the AI cover flow: resolves a configured
 * engine (built-in ComfyUI/NovelAI or a user-defined `imgprof:` OpenAI Images
 * profile), builds the request, normalizes the result and returns PNG bytes
 * ready for [CreationRepository.saveCover]. Chat stays untouched — no gallery
 * records, no session coupling.
 */
internal class CreationCoverGenerator(
    private val secrets: SecretStore,
    private val providers: ProviderRegistry,
    /** Size-capped remote download for engines that return URLs. */
    private val downloadUrl: (suspend (String) -> ByteArray?)? = null,
) {
    suspend fun configuredEngines(): Set<String> =
        ProviderConfigPersistence.configuredImageEngines(secrets)

    suspend fun profiles(): List<ImageProviderProfile> =
        ProviderConfigPersistence.listImageProviderProfiles(secrets)

    suspend fun generatePng(engineId: String, prompt: String, negativePrompt: String): ByteArray {
        val profile = if (ProviderConfigPersistence.isImageProfileEngineId(engineId)) {
            ProviderConfigPersistence
                .findImageProviderProfile(secrets, ProviderConfigPersistence.imageProfileIdFrom(engineId))
                ?.takeIf { it.isConfigured }
                ?: error(UiStrings.get(S.crvm_cover_engine_unavailable))
        } else null
        if (engineId !in configuredEngines()) {
            error(UiStrings.get(S.crvm_cover_engine_unavailable))
        }
        val adapterId = profile?.let { ProviderCatalog.OPENAI_IMAGE } ?: engineId
        val adapter = providers.find(adapterId)
            ?: error(UiStrings.get(S.crvm_cover_engine_unavailable))
        require(ProviderCapability.Images in adapter.capabilities) {
            UiStrings.get(S.crvm_cover_engine_unavailable)
        }
        val config: ProviderConfig = profile?.toProviderConfig()
            ?: ProviderConfigPersistence.loadProviderConfig(secrets, engineId)
        val metadata = profile?.requestMetadata(negativePrompt.trim()) ?: buildJsonObject {
            put("negative_prompt", negativePrompt.trim())
        }
        val request = GenerateRequest(
            prompt = PromptBuildResult(
                messages = listOf(PromptMessage(role = MessageRole.User, content = prompt)),
                stop = emptyList(),
                maxTokens = null,
                providerType = adapterId,
                diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
            ),
            preset = GenerationPreset(id = "image", name = "Image generation", providerType = adapterId),
            stream = false,
            metadata = metadata,
        )

        var result: String? = null
        var failure: String? = null
        adapter.streamGenerate(config, request).collect { chunk ->
            when (chunk) {
                is GenerateChunk.Delta -> Unit
                is GenerateChunk.Completed -> result = chunk.text
                is GenerateChunk.Failed -> failure = UiStrings.get(
                    S.crvm_cover_generate_failed, chunk.error.message.take(200), chunk.error.code,
                )
            }
        }
        failure?.let { error(it) }
        val raw = result?.takeIf(String::isNotBlank)
            ?: error(UiStrings.get(S.crvm_cover_no_image))
        val normalized = ImageResultNormalizer.toBytes(raw, downloadUrl)
            ?: error(UiStrings.get(S.crvm_cover_no_image))
        return if (normalized.mimeType == "image/png") {
            normalized.bytes
        } else {
            withContext(Dispatchers.IO) {
                app.tellev.util.decodeImageAsPng(normalized.bytes)
            } ?: error(UiStrings.get(S.crvm_cover_no_image))
        }
    }
}
