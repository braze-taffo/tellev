package app.tellev.core.provider

import app.tellev.BuildConfig

/** Build-time-only credentials for the managed tellevclick beta provider. */
object BetaRelayConfig {
    const val DISPLAY_NAME = "tellevclick（内置测试通道）"
    val knownModels: List<String> = listOf("claude-opus-5", "deepseek-v4-pro")

    val enabled: Boolean
        get() = BuildConfig.TELLEV_BETA_RELAY_ENABLED &&
            BuildConfig.TELLEV_BETA_RELAY_BASE_URL.isNotBlank() &&
            BuildConfig.TELLEV_BETA_RELAY_API_KEY.isNotBlank() &&
            BuildConfig.TELLEV_BETA_RELAY_MODEL.isNotBlank()

    fun providerConfig(model: String? = null): ProviderConfig {
        check(enabled) { "Beta relay is not configured for this build" }
        val compatibility = OpenAiCompatibilitySettings(
            // The base URL already includes /v1.
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
            supportsModelListing = true,
            supportsTools = true,
            supportsReasoning = true,
            includeUsage = true,
        )
        return ProviderConfig(
            providerType = ProviderCatalog.BETA_RELAY,
            baseUrl = BuildConfig.TELLEV_BETA_RELAY_BASE_URL,
            apiKey = BuildConfig.TELLEV_BETA_RELAY_API_KEY,
            model = model?.takeIf { it in knownModels } ?: BuildConfig.TELLEV_BETA_RELAY_MODEL,
            options = compatibility.toOptions(),
        )
    }
}
