package app.tellev.core.provider

import app.tellev.core.security.SecretStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Advanced OpenAI-compatible settings, stored encrypted alongside API keys. */
@Serializable
data class OpenAiCompatibilitySettings(
    val modelsPath: String = "/v1/models",
    val chatCompletionsPath: String = "/v1/chat/completions",
    val authHeader: String = "Authorization",
    val authScheme: String = "Bearer",
    val headers: Map<String, String> = emptyMap(),
    val includeUsage: Boolean = false,
    val maxTokensField: String = "max_tokens",
    val supportsModelListing: Boolean = true,
    val supportsTopK: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
    val supportsVision: Boolean = false,
    val extraBody: JsonObject = JsonObject(emptyMap()),
) {
    fun toOptions(): JsonObject = buildJsonObject {
        put("modelsPath", JsonPrimitive(modelsPath))
        put("chatCompletionsPath", JsonPrimitive(chatCompletionsPath))
        put("authHeader", JsonPrimitive(authHeader))
        put("authScheme", JsonPrimitive(authScheme))
        put("includeUsage", JsonPrimitive(includeUsage))
        put("maxTokensField", JsonPrimitive(maxTokensField))
        put("supportsModelListing", JsonPrimitive(supportsModelListing))
        put("supportsTopK", JsonPrimitive(supportsTopK))
        put("supportsTools", JsonPrimitive(supportsTools))
        put("supportsReasoning", JsonPrimitive(supportsReasoning))
        put("supportsVision", JsonPrimitive(supportsVision))
        if (extraBody.isNotEmpty()) put("extraBody", extraBody)
    }

    companion object {
        fun defaults(providerId: String): OpenAiCompatibilitySettings =
            if (providerId == ProviderCatalog.DEEPSEEK) {
                OpenAiCompatibilitySettings(
                    modelsPath = "/models",
                    chatCompletionsPath = "/chat/completions",
                    supportsTools = true,
                    supportsReasoning = true,
                )
            } else {
                OpenAiCompatibilitySettings()
            }
    }
}

object ProviderConfigPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    /** Only the user-defined OpenAI-compatible provider exposes arbitrary protocol settings. */
    fun hasCustomOpenAiSettings(providerId: String): Boolean =
        providerId == ProviderCatalog.OPENAI_COMPATIBLE

    suspend fun loadAdvanced(secretStore: SecretStore, providerId: String): OpenAiCompatibilitySettings {
        // DeepSeek has a fixed profile. Never let an older saved compatibility
        // document change its paths, authentication headers or request body.
        if (!hasCustomOpenAiSettings(providerId)) return OpenAiCompatibilitySettings.defaults(providerId)
        val stored = secretStore.readSecret(advancedSecretId(providerId))
            ?: return OpenAiCompatibilitySettings.defaults(providerId)
        return runCatching { json.decodeFromString<OpenAiCompatibilitySettings>(stored) }
            .getOrElse { OpenAiCompatibilitySettings.defaults(providerId) }
    }

    suspend fun saveAdvanced(
        secretStore: SecretStore,
        providerId: String,
        settings: OpenAiCompatibilitySettings,
    ) {
        if (!hasCustomOpenAiSettings(providerId)) return
        secretStore.putSecret(advancedSecretId(providerId), json.encodeToString(settings))
    }

    suspend fun loadProviderConfig(secretStore: SecretStore, providerId: String): ProviderConfig {
        val advanced = loadAdvanced(secretStore, providerId)
        val customOpenAi = hasCustomOpenAiSettings(providerId)
        return ProviderConfig(
            providerType = providerId,
            baseUrl = secretStore.readSecret("provider-$providerId-baseurl")
                ?: ProviderDefaults.baseUrl(providerId),
            apiKey = secretStore.readSecret("provider-$providerId-apikey"),
            model = secretStore.readSecret("provider-$providerId-model")
                ?: ProviderDefaults.model(providerId).takeIf { it.isNotBlank() },
            headers = if (customOpenAi) advanced.headers else emptyMap(),
            options = if (customOpenAi) advanced.toOptions() else JsonObject(emptyMap()),
        )
    }

    private fun advancedSecretId(providerId: String): String = "provider-$providerId-advanced"
}
