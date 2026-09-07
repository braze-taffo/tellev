package app.tellev.feature.chat

import app.tellev.core.provider.ProviderCatalog

/** The same engine identity is used by the dialog, prompt builder and provider dispatch. */
enum class ChatImageEngine(val providerId: String, val label: String, val usesEnglishTags: Boolean) {
    ComfyUi(ProviderCatalog.COMFYUI, "ComfyUI", false),
    NovelAi(ProviderCatalog.NOVELAI_IMAGE, "NovelAI", true);

    companion object {
        fun fromProviderId(id: String): ChatImageEngine? = entries.firstOrNull { it.providerId == id }
    }
}
