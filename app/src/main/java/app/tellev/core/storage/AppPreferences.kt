package app.tellev.core.storage

import android.content.Context

/**
 * Lightweight app-level preferences backed by SharedPreferences, mirroring the
 * pattern in [app.tellev.core.security.AndroidKeystoreSecretStore]. Currently
 * tracks small app-wide migration and update-check markers.
 */
class AppPreferences(
    context: Context,
    prefsName: String = "tellev_prefs",
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    var lastUpdateCheckEpochMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    fun shouldShowPresetLimitUpgradeNotice(
        firstInstallTime: Long,
        lastUpdateTime: Long,
    ): Boolean {
        val alreadyHandled = prefs.getBoolean(KEY_PRESET_LIMIT_NOTICE_HANDLED, false)
        val shouldShow = shouldShowPresetLimitUpgradeNotice(
            alreadyHandled = alreadyHandled,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
        )
        // A clean install already receives the corrected defaults. Mark it now
        // so a later unrelated app update does not show this migration notice.
        if (!shouldShow && !alreadyHandled) markPresetLimitUpgradeNoticeHandled()
        return shouldShow
    }

    fun markPresetLimitUpgradeNoticeHandled() {
        prefs.edit().putBoolean(KEY_PRESET_LIMIT_NOTICE_HANDLED, true).apply()
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_PRESET_LIMIT_NOTICE_HANDLED = "preset_limits_1_5_1_notice_handled"
    }
}

internal fun shouldShowPresetLimitUpgradeNotice(
    alreadyHandled: Boolean,
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = !alreadyHandled && lastUpdateTime > firstInstallTime
