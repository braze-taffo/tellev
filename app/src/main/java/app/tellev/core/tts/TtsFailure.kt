package app.tellev.core.tts

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings

/** Diagnostic detail is retained for debugging; UI text always comes from i18n. */
sealed class TtsFailure(val code: String, val resourceKey: String, val diagnostic: String? = null) {
    fun asException(): TtsException = TtsException(this)
    fun userMessage(): String = UiStrings.get(resourceKey)

    data object EmptyText : TtsFailure("empty_text", S.tts_empty_text)
    data object Disabled : TtsFailure("disabled", S.tts_disabled)
    data object ProviderNotConfigured : TtsFailure("provider_not_configured", S.tts_provider_hint)
    data object ProviderUnavailable : TtsFailure("provider_unavailable", S.tts_provider_unavailable)
    data class ProviderError(val providerCode: String) : TtsFailure("provider_error", S.tts_request_failed, providerCode)
    data object Network : TtsFailure("network", S.tts_network_failed)
    data object CacheWrite : TtsFailure("cache_write", S.tts_cache_failed)
    data object NoAudio : TtsFailure("no_audio", S.tts_no_audio)
    data class PlaybackError(val detail: String?) : TtsFailure("playback_error", S.tts_playback_failed, detail)
}

class TtsException(val failure: TtsFailure) : Exception(failure.code)

fun Throwable.ttsUserMessage(): String =
    ((this as? TtsException)?.failure ?: TtsFailure.ProviderUnavailable).userMessage()
