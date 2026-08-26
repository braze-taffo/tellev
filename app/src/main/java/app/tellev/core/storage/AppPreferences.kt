package app.tellev.core.storage

import android.content.Context

/**
 * Lightweight app-level preferences backed by SharedPreferences, mirroring the
 * pattern in [app.tellev.core.security.AndroidKeystoreSecretStore]. Currently
 * only tracks the last update-check timestamp so the auto-check can be rate
 * limited to once per day.
 */
class AppPreferences(
    context: Context,
    prefsName: String = "tellev_prefs",
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    var lastUpdateCheckEpochMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    var betaRelayNoticeAccepted: Boolean
        get() = prefs.getBoolean(KEY_BETA_RELAY_NOTICE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BETA_RELAY_NOTICE_ACCEPTED, value).apply()

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_BETA_RELAY_NOTICE_ACCEPTED = "beta_relay_notice_accepted"
    }
}
