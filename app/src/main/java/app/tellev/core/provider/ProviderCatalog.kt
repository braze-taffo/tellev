package app.tellev.core.provider

object ProviderCatalog {
    const val OPENAI_COMPATIBLE = "openai-compatible"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val OPENROUTER = "openrouter"
    const val DEEPSEEK = "deepseek"
    const val VOLCENGINE_CODING_PLAN = "volcengine-coding-plan"
    const val NOVELAI = "novelai"
    const val KOBOLD = "kobold"
    const val KOBOLDCPP = "koboldcpp"
    const val TEXTGEN_WEBUI = "textgen-webui"
    const val OLLAMA = "ollama"
    const val LLAMA_CPP = "llama-cpp"
    const val HORDE = "horde"
    const val AZURE_OPENAI = "azure-openai"

    const val STABLE_DIFFUSION = "stable-diffusion"
    const val COMFYUI = "comfyui"
    const val OPENAI_IMAGE = "openai-image"

    const val GOOGLE_TTS = "google-tts"
    const val ELEVENLABS = "elevenlabs"
    const val OPENAI_SPEECH = "openai-speech"

    const val GOOGLE_TRANSLATE = "google-translate"
    const val DEEPL = "deepl"
    const val LIBRE_TRANSLATE = "libre-translate"

    val plannedProviderIds: List<String> = listOf(
        OPENAI_COMPATIBLE,
        ANTHROPIC,
        GEMINI,
        OPENROUTER,
        DEEPSEEK,
        VOLCENGINE_CODING_PLAN,
        NOVELAI,
        KOBOLD,
        KOBOLDCPP,
        TEXTGEN_WEBUI,
        OLLAMA,
        LLAMA_CPP,
        HORDE,
        AZURE_OPENAI,
        STABLE_DIFFUSION,
        COMFYUI,
        OPENAI_IMAGE,
        GOOGLE_TTS,
        ELEVENLABS,
        OPENAI_SPEECH,
        GOOGLE_TRANSLATE,
        DEEPL,
        LIBRE_TRANSLATE,
    )
}
