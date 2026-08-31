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

    /**
     * Theme preference stored as the enum name so the storage layer stays
     * independent of the UI-layer ThemeMode type; callers parse with
     * [app.tellev.ui.theme.parseThemeMode].
     */
    var themeModeName: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun shouldShowPresetLimitUpgradeNotice(
        firstInstallTime: Long,
        lastUpdateTime: Long,
    ): Boolean {
        val alreadyHandled = prefs.getBoolean(KEY_PRESET_LIMIT_NOTICE_HANDLED, false)
        val shouldShow = shouldShowOnceAfterUpdate(
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

    /**
     * One-time QQ group notice: shown the first launch after an app update
     * (same once-after-update semantics as the preset-limit notice above).
     * Fresh installs skip it; they can find the group in 设置 → 关于.
     */
    fun shouldShowQqGroupNotice(
        firstInstallTime: Long,
        lastUpdateTime: Long,
    ): Boolean {
        val alreadyHandled = prefs.getBoolean(KEY_QQ_GROUP_NOTICE_HANDLED, false)
        val shouldShow = shouldShowOnceAfterUpdate(
            alreadyHandled = alreadyHandled,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
        )
        if (!shouldShow && !alreadyHandled) markQqGroupNoticeHandled()
        return shouldShow
    }

    fun markQqGroupNoticeHandled() {
        prefs.edit().putBoolean(KEY_QQ_GROUP_NOTICE_HANDLED, true).apply()
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_PRESET_LIMIT_NOTICE_HANDLED = "preset_limits_1_5_1_notice_handled"
        const val KEY_QQ_GROUP_NOTICE_HANDLED = "qq_group_notice_handled"
        const val KEY_THEME_MODE = "theme_mode"

        /** Literal "System" — the ThemeMode.System enum name, kept as a
         *  string so this layer does not depend on the UI enum. */
        const val DEFAULT_THEME_MODE = "System"
    }
}

/** True when the app has been updated since install and this one-shot
 *  notice has not been shown/marked yet. Fresh installs (equal timestamps)
 *  never trigger it. */
internal fun shouldShowOnceAfterUpdate(
    alreadyHandled: Boolean,
    firstInstallTime: Long,
    lastUpdateTime: Long,
): Boolean = !alreadyHandled && lastUpdateTime > firstInstallTime
