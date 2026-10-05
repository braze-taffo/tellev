package app.tellev.core.tts

import android.content.Context

/** User-selectable OpenAI-compatible speech settings. */
data class TtsSettingsValues(
    val enabled: Boolean = false,
    val voice: String = DEFAULT_VOICE,
    val speed: Float = DEFAULT_SPEED,
    val model: String = DEFAULT_MODEL,
    val format: String = DEFAULT_FORMAT,
) {
    fun validated(): TtsSettingsValues = copy(
        voice = voice.takeIf { it in VOICES } ?: DEFAULT_VOICE,
        speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
        model = model.takeIf { it in MODELS } ?: DEFAULT_MODEL,
        format = format.takeIf { it in FORMATS } ?: DEFAULT_FORMAT,
    )

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
    ).validated()

    fun save(values: TtsSettingsValues) {
        val valid = values.validated()
        prefs.edit()
            .putBoolean(KEY_ENABLED, valid.enabled)
            .putString(KEY_VOICE, valid.voice)
            .putFloat(KEY_SPEED, valid.speed)
            .putString(KEY_MODEL, valid.model)
            .putString(KEY_FORMAT, valid.format)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_VOICE = "voice"
        const val KEY_SPEED = "speed"
        const val KEY_MODEL = "model"
        const val KEY_FORMAT = "format"
    }
}
