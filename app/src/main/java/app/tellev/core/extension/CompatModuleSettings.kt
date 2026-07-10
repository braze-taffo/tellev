package app.tellev.core.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── EJS Prompt-Template settings ──────────────────────────────────────
// Mirrors the 21 user-configurable settings from ST-Prompt-Template's
// `src/modules/ui.ts` DEFAULT_SETTINGS map.  Field names use @SerialName
// to match the snake_case keys that the real extension persists under
// `extension_settings.EjsTemplate` and that tellev injects into the
// WebView as `_ejsFeatures`.

@Serializable
data class EjsTemplateSettings(
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("generate_enabled") val generateEnabled: Boolean = true,
    @SerialName("generate_loader_enabled") val generateLoaderEnabled: Boolean = true,
    @SerialName("render_enabled") val renderEnabled: Boolean = true,
    @SerialName("render_loader_enabled") val renderLoaderEnabled: Boolean = true,
    @SerialName("inject_loader_enabled") val injectLoaderEnabled: Boolean = false,
    @SerialName("code_blocks_enabled") val codeBlocksEnabled: Boolean = false,
    @SerialName("raw_message_evaluation_enabled") val rawMessageEvaluationEnabled: Boolean = true,
    @SerialName("filter_message_enabled") val filterMessageEnabled: Boolean = true,
    @SerialName("cache_enabled") val cacheEnabled: Int = 0,
    @SerialName("cache_size") val cacheSize: Int = 64,
    @SerialName("cache_hasher") val cacheHasher: String = "h32ToString",
    @SerialName("depth_limit") val depthLimit: Int = -1,
    @SerialName("sandbox") val sandbox: Boolean = false,
    @SerialName("debug_enabled") val debugEnabled: Boolean = false,
    @SerialName("autosave_enabled") val autosaveEnabled: Boolean = false,
    @SerialName("preload_worldinfo_enabled") val preloadWorldinfoEnabled: Boolean = true,
    @SerialName("with_context_disabled") val withContextDisabled: Boolean = false,
    @SerialName("invert_enabled") val invertEnabled: Boolean = true,
    @SerialName("compile_workers") val compileWorkers: Boolean = false,
    @SerialName("code_editor") val codeEditor: Boolean = false,
) {
    companion object {
        val DEFAULT = EjsTemplateSettings()
    }
}

// ── TavernHelper (酒馆助手) settings ──────────────────────────────────
// Mirrors the settings structure from js-slash-runner's
// `src/type/settings.ts` GlobalSettings.  Only the settings that have
// practical effect in tellev's compatibility layer are modeled; script
// tree management (which tellev handles via character-card scripts) is
// intentionally omitted.

@Serializable
data class TavernHelperSettings(
    val audio: TavernHelperAudioSettings = TavernHelperAudioSettings(),
    val listener: TavernHelperListenerSettings = TavernHelperListenerSettings(),
    val macro: TavernHelperMacroSettings = TavernHelperMacroSettings(),
    val optimize: TavernHelperOptimizeSettings = TavernHelperOptimizeSettings(),
    val render: TavernHelperRenderSettings = TavernHelperRenderSettings(),
) {
    companion object {
        val DEFAULT = TavernHelperSettings()
    }
}

@Serializable
data class TavernHelperAudioSettings(
    val enabled: Boolean = true,
    val bgm: TavernHelperBgmSettings = TavernHelperBgmSettings(),
    val ambient: TavernHelperAmbientSettings = TavernHelperAmbientSettings(),
)

@Serializable
data class TavernHelperBgmSettings(
    val enabled: Boolean = true,
    val mode: String = "repeat_all",
    val muted: Boolean = false,
    val volume: Int = 50,
)

@Serializable
data class TavernHelperAmbientSettings(
    val enabled: Boolean = true,
    val mode: String = "play_one_and_stop",
    val muted: Boolean = false,
    val volume: Int = 50,
)

@Serializable
data class TavernHelperListenerSettings(
    val enabled: Boolean = false,
    @SerialName("enable_echo") val enableEcho: Boolean = true,
    val url: String = "http://localhost:6621",
    val duration: Int = 1000,
)

@Serializable
data class TavernHelperMacroSettings(
    val enabled: Boolean = true,
)

@Serializable
data class TavernHelperOptimizeSettings(
    @SerialName("disable_incompatible_option") val disableIncompatibleOption: Boolean = true,
    @SerialName("better_message_to_load") val betterMessageToLoad: Boolean = true,
    @SerialName("better_character_update") val betterCharacterUpdate: Boolean = true,
    @SerialName("better_character_export") val betterCharacterExport: Boolean = true,
    @SerialName("better_character_deletion") val betterCharacterDeletion: Boolean = true,
    @SerialName("force_recommended_worldbook_global_settings") val forceRecommendedWorldbookGlobalSettings: Boolean = true,
    @SerialName("save_preset_when_saving_preset_entries") val savePresetWhenSavingPresetEntries: Boolean = true,
    @SerialName("maximize_preset_context_length") val maximizePresetContextLength: Boolean = true,
)

@Serializable
data class TavernHelperRenderSettings(
    val enabled: Boolean = true,
    @SerialName("collapse_code_block") val collapseCodeBlock: String = "frontend_only",
    @SerialName("allow_streaming") val allowStreaming: Boolean = false,
    @SerialName("use_blob_url") val useBlobUrl: Boolean = false,
    @SerialName("optimize_hljs") val optimizeHljs: Boolean = true,
    val depth: Int = 0,
    @SerialName("depth_ignore_hidden") val depthIgnoreHidden: Boolean = false,
)
