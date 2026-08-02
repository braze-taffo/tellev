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

/**
 * A user-defined, named OpenAI-compatible endpoint. Unlike built-in providers
 * (which have a single fixed slot keyed by providerType), the user may keep an
 * arbitrary number of these and switch between them. Stored as one encrypted
 * JSON list under [ProviderConfigPersistence.CUSTOM_CONFIGS_SECRET_ID].
 */
@Serializable
data class CustomProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val advanced: OpenAiCompatibilitySettings = OpenAiCompatibilitySettings(),
)

object ProviderConfigPersistence {
    private val json = Json { ignoreUnknownKeys = true }

    /** Secret id holding the JSON list of [CustomProviderConfig]. */
    private const val CUSTOM_CONFIGS_SECRET_ID = "custom-provider-configs"

    /** Prefix marking a selected-provider id that points at a custom config. */
    const val CUSTOM_PREFIX = "custom:"

    /** Stable id for the config migrated from the legacy single openai-compatible slot. */
    private const val MIGRATED_LEGACY_ID = "cust_legacy"

    /** Stable id for the starter config created for brand-new users. */
    private const val STARTER_DEFAULT_ID = "cust_default"

    /** True if [selectedId] refers to a user-defined custom OpenAI-compatible config. */
    fun isCustomConfigId(selectedId: String): Boolean = selectedId.startsWith(CUSTOM_PREFIX)

    /** The selected id for a custom config (strips the [CUSTOM_PREFIX]). */
    fun customIdFrom(selectedId: String): String = selectedId.removePrefix(CUSTOM_PREFIX)

    /**
     * The adapter id to use for [selectedId]: [ProviderCatalog.OPENAI_COMPATIBLE]
     * for custom configs, otherwise the id itself. Use this when looking up an
     * adapter for a selected-provider id that may be `custom:...`.
     */
    fun adapterIdFor(selectedId: String): String =
        if (isCustomConfigId(selectedId)) ProviderCatalog.OPENAI_COMPATIBLE else selectedId

    /** Selected-id string for a custom config (adds the [CUSTOM_PREFIX]). */
    fun selectedIdFor(customConfigId: String): String = CUSTOM_PREFIX + customConfigId

    suspend fun listCustomConfigs(secretStore: SecretStore): List<CustomProviderConfig> {
        val stored = secretStore.readSecret(CUSTOM_CONFIGS_SECRET_ID) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CustomProviderConfig>>(stored) }
            .getOrElse { emptyList() }
    }

    suspend fun saveCustomConfigs(secretStore: SecretStore, configs: List<CustomProviderConfig>) {
        secretStore.putSecret(CUSTOM_CONFIGS_SECRET_ID, json.encodeToString(configs))
    }

    /** Only the user-defined OpenAI-compatible provider exposes arbitrary protocol settings. */
    fun hasCustomOpenAiSettings(providerId: String): Boolean =
        providerId == ProviderCatalog.OPENAI_COMPATIBLE

    /**
     * True if the selected id carries editable advanced OpenAI-compatible
     * protocol settings: either a user-defined custom config (`custom:...`) or
     * the legacy built-in `openai-compatible` slot.
     */
    fun hasAdvancedSettings(selectedId: String): Boolean =
        isCustomConfigId(selectedId) || hasCustomOpenAiSettings(selectedId)

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
        // A custom: id selects one of the user's named OpenAI-compatible configs.
        if (isCustomConfigId(providerId)) {
            val config = listCustomConfigs(secretStore)
                .firstOrNull { it.id == customIdFrom(providerId) }
                ?: return ProviderConfig(
                    providerType = ProviderCatalog.OPENAI_COMPATIBLE,
                    baseUrl = ProviderDefaults.baseUrl(ProviderCatalog.OPENAI_COMPATIBLE),
                )
            return ProviderConfig(
                providerType = ProviderCatalog.OPENAI_COMPATIBLE,
                baseUrl = config.baseUrl.ifBlank { ProviderDefaults.baseUrl(ProviderCatalog.OPENAI_COMPATIBLE) },
                apiKey = config.apiKey.ifBlank { null },
                model = config.model.ifBlank { null },
                headers = config.advanced.headers,
                options = config.advanced.toOptions(),
            )
        }
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

    /**
     * One-time migration from the legacy single `openai-compatible` slot to the
     * named-custom-configs model. Idempotent: if custom configs already exist it
     * does nothing. Uses stable ids ([MIGRATED_LEGACY_ID] / [STARTER_DEFAULT_ID])
     * so concurrent callers produce identical writes.
     *
     * - Existing openai-compatible users: their slot is materialised as a
     *   "默认 OpenAI 兼容" custom config and the selected id is repointed at it.
     * - Brand-new users defaulting to openai-compatible: a starter "OpenAI 兼容"
     *   custom config is created and selected.
     * - Users on another provider: left untouched (no clutter).
     *
     * Returns the migration outcome, or null if nothing was done.
     */
    suspend fun migrateLegacyOpenAiCompatible(secretStore: SecretStore): MigrationResult? {
        if (secretStore.readSecret(CUSTOM_CONFIGS_SECRET_ID) != null) return null

        val currentSelected = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
        val wasOpenAiCompatible = currentSelected == null || currentSelected == ProviderCatalog.OPENAI_COMPATIBLE

        val legacyBaseUrl = secretStore.readSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-baseurl")
        val legacyApiKey = secretStore.readSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-apikey")
        val hasLegacy = !legacyBaseUrl.isNullOrBlank() || !legacyApiKey.isNullOrBlank()

        if (!wasOpenAiCompatible && !hasLegacy) return null

        val (id, name) = if (hasLegacy) {
            MIGRATED_LEGACY_ID to "默认 OpenAI 兼容"
        } else {
            STARTER_DEFAULT_ID to "OpenAI 兼容"
        }
        val migrated = CustomProviderConfig(
            id = id,
            name = name,
            baseUrl = legacyBaseUrl ?: "",
            apiKey = legacyApiKey ?: "",
            model = secretStore.readSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-model") ?: "",
            advanced = loadAdvanced(secretStore, ProviderCatalog.OPENAI_COMPATIBLE),
        )
        saveCustomConfigs(secretStore, listOf(migrated))
        val newSelectedId = if (wasOpenAiCompatible) selectedIdFor(id) else null
        if (newSelectedId != null) {
            secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, newSelectedId)
        }
        return MigrationResult(listOf(migrated), newSelectedId)
    }

    /** Outcome of [migrateLegacyOpenAiCompatible]; [newSelectedId] is null when the selection is unchanged. */
    data class MigrationResult(
        val configs: List<CustomProviderConfig>,
        val newSelectedId: String?,
    )

    private fun advancedSecretId(providerId: String): String = "provider-$providerId-advanced"
}
