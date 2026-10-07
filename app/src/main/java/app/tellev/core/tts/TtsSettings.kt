package app.tellev.core.tts

import android.content.Context

/**
 * User-selectable OpenAI-compatible speech settings.
 *
 * baseUrl/apiKey 留空时沿用聊天服务商的配置；一旦 baseUrl 非空即走独立端点，
 * 不再要求聊天侧必须是 OpenAI 兼容服务商。customVoice/customModel 非空时
 * 覆盖预设下拉里的 voice/model（自定义端点常只认自己的音色名）。
 */
data class TtsSettingsValues(
    val enabled: Boolean = false,
    val voice: String = DEFAULT_VOICE,
    val speed: Float = DEFAULT_SPEED,
    val model: String = DEFAULT_MODEL,
    val format: String = DEFAULT_FORMAT,
    val baseUrl: String = "",
    val apiKey: String = "",
    val customVoice: String = "",
    val customModel: String = "",
) {
    fun validated(): TtsSettingsValues = copy(
        voice = voice.takeIf { it in VOICES } ?: DEFAULT_VOICE,
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        model = model.takeIf { it in MODELS } ?: DEFAULT_MODEL,
        format = format.takeIf { it in FORMATS } ?: DEFAULT_FORMAT,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        customVoice = customVoice.trim(),
        customModel = customModel.trim(),
    )

    /** 自定义端点是否已配置（.baseUrl 非空即启用）。 */
    val hasCustomEndpoint: Boolean get() = baseUrl.isNotBlank()

    /** 实际生效的音色：customVoice 优先。 */
    fun effectiveVoice(): String = customVoice.ifBlank { voice }

    /** 实际生效的模型：customModel 优先。 */
    fun effectiveModel(): String = customModel.ifBlank { model }

    /** 供缓存键与请求载荷使用的收敛视图：音色/模型取生效值。 */
    fun normalized(): TtsSettingsValues = copy(voice = effectiveVoice(), model = effectiveModel())

    companion object {
        const val DEFAULT_VOICE = "alloy"
        const val DEFAULT_SPEED = 1f
        const val DEFAULT_MODEL = "tts-1"
        const val DEFAULT_FORMAT = "mp3"
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        val VOICES = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
        val MODELS = listOf("tts-1", "tts-1-hd")
        val FORMATS = listOf("mp3", "opus")
    }
}

/** SharedPreferences-backed TTS settings; values are validated on every read/write. */
class TtsSettings(
    context: Context,
    prefsName: String = "tellev_tts_prefs",
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun load(): TtsSettingsValues = TtsSettingsValues(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        voice = prefs.getString(KEY_VOICE, TtsSettingsValues.DEFAULT_VOICE)
            ?: TtsSettingsValues.DEFAULT_VOICE,
        speed = prefs.getFloat(KEY_SPEED, TtsSettingsValues.DEFAULT_SPEED),
        model = prefs.getString(KEY_MODEL, TtsSettingsValues.DEFAULT_MODEL)
            ?: TtsSettingsValues.DEFAULT_MODEL,
        format = prefs.getString(KEY_FORMAT, TtsSettingsValues.DEFAULT_FORMAT)
            ?: TtsSettingsValues.DEFAULT_FORMAT,
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        customVoice = prefs.getString(KEY_CUSTOM_VOICE, "").orEmpty(),
        customModel = prefs.getString(KEY_CUSTOM_MODEL, "").orEmpty(),
    ).validated()

    fun save(values: TtsSettingsValues) {
        val valid = values.validated()
        prefs.edit()
            .putBoolean(KEY_ENABLED, valid.enabled)
            .putString(KEY_VOICE, valid.voice)
            .putFloat(KEY_SPEED, valid.speed)
            .putString(KEY_MODEL, valid.model)
            .putString(KEY_FORMAT, valid.format)
            .putString(KEY_BASE_URL, valid.baseUrl)
            .putString(KEY_API_KEY, valid.apiKey)
            .putString(KEY_CUSTOM_VOICE, valid.customVoice)
            .putString(KEY_CUSTOM_MODEL, valid.customModel)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_VOICE = "voice"
        const val KEY_SPEED = "speed"
        const val KEY_MODEL = "model"
        const val KEY_FORMAT = "format"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_API_KEY = "apiKey"
        const val KEY_CUSTOM_VOICE = "customVoice"
        const val KEY_CUSTOM_MODEL = "customModel"
    }
}
