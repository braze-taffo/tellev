package app.tellev.core.provider

object ProviderDefaults {
    const val SELECTED_PROVIDER_SECRET_ID = "provider-selected-id"

    fun baseUrl(providerType: String): String = when (providerType) {
        ProviderCatalog.OPENAI_COMPATIBLE -> "https://api.openai.com"
        ProviderCatalog.ANTHROPIC -> "https://api.anthropic.com"
        ProviderCatalog.GEMINI -> "https://generativelanguage.googleapis.com"
        ProviderCatalog.OPENROUTER -> "https://openrouter.ai"
        ProviderCatalog.DEEPSEEK -> "https://api.deepseek.com"
        ProviderCatalog.VOLCENGINE_CODING_PLAN -> "https://ark.cn-beijing.volces.com/api/v3"
        ProviderCatalog.OLLAMA -> "http://localhost:11434"
        ProviderCatalog.KOBOLD -> "http://localhost:5000"
        ProviderCatalog.KOBOLDCPP -> "http://localhost:5001"
        ProviderCatalog.NOVELAI -> "https://api.novelai.net"
        ProviderCatalog.TEXTGEN_WEBUI -> "http://localhost:5000"
        ProviderCatalog.AZURE_OPENAI -> "https://your-resource.openai.azure.com"
        ProviderCatalog.HORDE -> "https://aihorde.net"
        ProviderCatalog.LLAMA_CPP -> "http://localhost:8080"
        ProviderCatalog.STABLE_DIFFUSION -> "http://localhost:7860"
        ProviderCatalog.OPENAI_IMAGE -> "https://api.openai.com"
        ProviderCatalog.OPENAI_SPEECH -> "https://api.openai.com"
        ProviderCatalog.GOOGLE_TRANSLATE -> "https://translate.googleapis.com"
        else -> "http://localhost"
    }

    fun model(providerType: String): String = when (providerType) {
        ProviderCatalog.DEEPSEEK -> "deepseek-v4-flash"
        else -> ""
    }
}
