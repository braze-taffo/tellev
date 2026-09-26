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
     * Whether every cold start checks for a new release. Defaults to true,
     * which is the behaviour existing installs already have; turning it off
     * only skips the automatic launch check — the manual check in 设置 → 关于
     * keeps working either way.
     */
    var autoUpdateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, DEFAULT_AUTO_UPDATE_CHECK)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).apply()

    /**
     * Theme preference stored as the enum name so the storage layer stays
     * independent of the UI-layer ThemeMode type; callers parse with
     * [app.tellev.ui.theme.parseThemeMode].
     */
    var themeModeName: String
        get() = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    /**
     * Accent palette preference stored as the enum name so the storage layer
     * stays independent of the UI-layer ThemeAccent type; callers parse with
     * [app.tellev.ui.theme.parseThemeAccent]. Defaults to the Warm palette so
     * existing installs (which have no stored value) pick up the new look.
     */
    var themeAccentName: String
        get() = prefs.getString(KEY_THEME_ACCENT, DEFAULT_THEME_ACCENT) ?: DEFAULT_THEME_ACCENT
        set(value) = prefs.edit().putString(KEY_THEME_ACCENT, value).apply()

    /**
     * Chat bubble opacity (1 = opaque, 0 = fully transparent). Stored as a
     * float so Settings can expose it as a slider; defaults to the legacy
     * hardcoded bubble alpha so existing installs see no visual change.
     */
    var chatBubbleAlpha: Float
        get() = prefs.getFloat(KEY_CHAT_BUBBLE_ALPHA, DEFAULT_CHAT_BUBBLE_ALPHA)
        set(value) = prefs.edit().putFloat(KEY_CHAT_BUBBLE_ALPHA, value).apply()

    /** Chat message body size in sp. The old bodyLarge size is 16 sp. */
    var chatFontSizeSp: Int
        get() = prefs.getInt(KEY_CHAT_FONT_SIZE, DEFAULT_CHAT_FONT_SIZE).coerceIn(14, 20)
        set(value) = prefs.edit().putInt(KEY_CHAT_FONT_SIZE, value.coerceIn(14, 20)).apply()

    /**
     * UI language as a BCP-47 tag (e.g. "en", "ja", "ko", "zh-CN"). An empty
     * string means "follow the system locale", which is the default so
     * existing installs see no change. Consumed by [app.tellev.core.i18n.AppLocale].
     */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE_TAG, DEFAULT_LANGUAGE_TAG) ?: DEFAULT_LANGUAGE_TAG
        set(value) = prefs.edit().putString(KEY_LANGUAGE_TAG, value).apply()

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
     * QQ group notice: shown once on the very first launch, whether that is a
     * fresh install or an update over an older version. The dialog closing
     * marks it handled; afterwards the group stays listed in 设置 → 关于.
     */
    fun shouldShowQqGroupNotice(): Boolean =
        !prefs.getBoolean(KEY_QQ_GROUP_NOTICE_HANDLED, false)

    fun markQqGroupNoticeHandled() {
        prefs.edit().putBoolean(KEY_QQ_GROUP_NOTICE_HANDLED, true).apply()
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_update_check_ms"
        const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"
        const val KEY_PRESET_LIMIT_NOTICE_HANDLED = "preset_limits_1_5_1_notice_handled"
        const val KEY_QQ_GROUP_NOTICE_HANDLED = "qq_group_notice_handled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_ACCENT = "theme_accent"
        const val KEY_CHAT_BUBBLE_ALPHA = "chat_bubble_alpha"
        const val KEY_CHAT_FONT_SIZE = "chat_font_size_sp"
        const val KEY_LANGUAGE_TAG = "language_tag"

        /** Defaults to on so an upgrade keeps the previous launch-check behaviour. */
        const val DEFAULT_AUTO_UPDATE_CHECK = true

        /** Literal "System" — the ThemeMode.System enum name, kept as a
         *  string so this layer does not depend on the UI enum. */
        const val DEFAULT_THEME_MODE = "System"

        /** Literal "Warm" — the ThemeAccent.Warm enum name; same rationale as
         *  [DEFAULT_THEME_MODE]. */
        const val DEFAULT_THEME_ACCENT = "Warm"

        /** Legacy hardcoded bubble alpha in ChatScreen; keep as default so
         *  existing installs see no visual change. */
        const val DEFAULT_CHAT_BUBBLE_ALPHA = 0.6f
        const val DEFAULT_CHAT_FONT_SIZE = 16

        /** Empty = follow the system locale. */
        const val DEFAULT_LANGUAGE_TAG = ""
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
