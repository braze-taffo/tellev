package app.tellev.core.provider

import app.tellev.BuildConfig

/** Build-time-only configuration for the short-lived community beta relay. */
object BetaRelayConfig {
    const val DISPLAY_NAME = "DeepSeek V4 Pro 测试通道"

    val enabled: Boolean
        get() = BuildConfig.TELLEV_BETA_RELAY_ENABLED &&
            BuildConfig.TELLEV_BETA_RELAY_BASE_URL.isNotBlank() &&
            BuildConfig.TELLEV_BETA_RELAY_API_KEY.isNotBlank() &&
            BuildConfig.TELLEV_BETA_RELAY_MODEL.isNotBlank()

    fun providerConfig(): ProviderConfig {
        check(enabled) { "Beta relay is not configured for this build" }
        val compatibility = OpenAiCompatibilitySettings(
            supportsModelListing = false,
            supportsTools = true,
            supportsReasoning = true,
            includeUsage = true,
        )
        return ProviderConfig(
            providerType = ProviderCatalog.BETA_RELAY,
            baseUrl = BuildConfig.TELLEV_BETA_RELAY_BASE_URL,
            apiKey = BuildConfig.TELLEV_BETA_RELAY_API_KEY,
            model = BuildConfig.TELLEV_BETA_RELAY_MODEL,
            options = compatibility.toOptions(),
        )
    }
}
