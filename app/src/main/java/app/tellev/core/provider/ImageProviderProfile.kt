package app.tellev.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A user-defined, named custom image endpoint speaking the OpenAI Images
 * protocol (POST {baseUrl}{path}). Unlike the two built-in engines (ComfyUI,
 * NovelAI) which each have one fixed slot, the user may keep any number of
 * these and pick one as the active image engine. Stored as one encrypted JSON
 * list; the active engine references a profile by the `imgprof:` engine id.
 *
 * Blank [size]/[quality]/[style]/[responseFormat] are omitted from the request
 * body so the upstream default applies (some compatible endpoints reject
 * unknown or DALL·E-only fields).
 */
@Serializable
data class ImageProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Model id sent as `model`; blank falls back to the adapter default. */
    val model: String = "",
    /** Generations path appended to [baseUrl]; override for relays without /v1. */
    val path: String = DEFAULT_PATH,
    /** GET path used by the settings-page connectivity test. */
    val modelsPath: String = DEFAULT_MODELS_PATH,
    /** e.g. 1024x1024; blank = omit. */
    val size: String = "1024x1024",
    /** standard/hd on DALL·E 3, low/medium/high on gpt-image-1; blank = omit. */
    val quality: String = "",
    /** vivid/natural on DALL·E 3; blank = omit. */
    val style: String = "",
    /** url/b64_json; blank = omit (upstream default). */
    val responseFormat: String = "",
    /**
     * The OpenAI Images spec has no negative-prompt field; many compatible
     * relays accept one. Off by default so strict upstreams are not sent an
     * unrecognized argument.
     */
    val sendNegativePrompt: Boolean = false,
    /** Extra fields merged into the request body (power-user escape hatch). */
    val extraBody: JsonObject = JsonObject(emptyMap()),
) {
    /** A profile is selectable as an image engine once it names an endpoint. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        providerType = ProviderCatalog.OPENAI_IMAGE,
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim().takeIf(String::isNotBlank),
        model = model.trim().takeIf(String::isNotBlank),
        options = buildJsonObject { put("models_path", modelsPath.trim().ifBlank { DEFAULT_MODELS_PATH }) },
    )

    /** Metadata consumed by [OpenAiImageAdapter] when building the request body. */
    fun requestMetadata(negativePrompt: String): JsonObject = buildJsonObject {
        size.trim().takeIf(String::isNotBlank)?.let { put("size", it) }
        quality.trim().takeIf(String::isNotBlank)?.let { put("quality", it) }
        style.trim().takeIf(String::isNotBlank)?.let { put("style", it) }
        responseFormat.trim().takeIf(String::isNotBlank)?.let { put("response_format", it) }
        path.trim().takeIf(String::isNotBlank)?.let { put("images_path", it) }
        if (sendNegativePrompt) put("negative_prompt", negativePrompt.trim())
        if (extraBody.isNotEmpty()) put("extra_body", extraBody)
    }

    /** One-line settings-page summary: endpoint + model. */
    fun summary(): String = listOf(
        baseUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/'),
        model.trim(),
    ).filter(String::isNotBlank).joinToString(" · ")

    companion object {
        const val DEFAULT_PATH = "/v1/images/generations"
        const val DEFAULT_MODELS_PATH = "/v1/models"

        private val json = Json { ignoreUnknownKeys = true }

        /** Lenient decode for edited JSON (e.g. pasted exports): null when unusable. */
        fun fromJsonOrNull(text: String): ImageProviderProfile? = runCatching {
            json.decodeFromString<ImageProviderProfile>(text.trim())
        }.getOrNull()

        fun parseExtraBody(text: String): JsonObject? = runCatching {
            val trimmed = text.trim().ifBlank { return@runCatching JsonObject(emptyMap()) }
            json.parseToJsonElement(trimmed) as? JsonObject
        }.getOrNull()
    }
}
