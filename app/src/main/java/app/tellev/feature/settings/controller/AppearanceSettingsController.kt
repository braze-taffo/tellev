package app.tellev.feature.settings.controller

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.storage.AppPreferences
import app.tellev.feature.settings.SettingsUiState
import app.tellev.ui.theme.ThemeAccent
import app.tellev.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class AppearanceSettingsController(
    private val appPreferences: AppPreferences,
    private val themeModeFlow: MutableStateFlow<ThemeMode>,
    private val themeAccentFlow: MutableStateFlow<ThemeAccent>,
    private val chatBubbleAlphaFlow: MutableStateFlow<Float>,
    private val chatFontSizeSpFlow: MutableStateFlow<Int>,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun setThemeMode(mode: ThemeMode) {
        appPreferences.themeModeName = mode.name
        themeModeFlow.value = mode
        stateFlow.update {
            it.copy(
                themeMode = mode,
                info = UiStrings.get(S.setappctl_theme_switched, mode.displayName()),
            )
        }
    }

    fun setThemeAccent(accent: ThemeAccent) {
        appPreferences.themeAccentName = accent.name
        themeAccentFlow.value = accent
        stateFlow.update {
            it.copy(
                themeAccent = accent,
                info = UiStrings.get(S.setappctl_accent_switched, accent.displayName()),
            )
        }
    }

    fun setChatBubbleAlpha(alpha: Float) {
        val coerced = alpha.coerceIn(0f, 1f)
        appPreferences.chatBubbleAlpha = coerced
        chatBubbleAlphaFlow.value = coerced
        stateFlow.update {
            it.copy(chatBubbleAlpha = coerced)
        }
    }

    fun setChatFontSizeSp(size: Int) {
        val selected = size.coerceIn(14, 20)
        appPreferences.chatFontSizeSp = selected
        chatFontSizeSpFlow.value = selected
        stateFlow.update { it.copy(chatFontSizeSp = selected) }
    }

    /**
     * 持久化界面语言选择。视觉刷新由调用方 recreate() Activity 完成，
     * 重建后的 Activity/ViewModel 会以新语言重新组装。
     */
    fun setLanguage(tag: String) {
        appPreferences.languageTag = tag
        stateFlow.update { it.copy(languageTag = tag) }
    }

    private fun ThemeMode.displayName(): String = when (this) {
        ThemeMode.Light -> UiStrings.get(S.setappctl_theme_light)
        ThemeMode.Dark -> UiStrings.get(S.setappctl_theme_dark)
        ThemeMode.System -> UiStrings.get(S.setappctl_theme_system)
    }

    private fun ThemeAccent.displayName(): String = when (this) {
        ThemeAccent.Warm -> UiStrings.get(S.setappctl_accent_warm)
        ThemeAccent.Classic -> UiStrings.get(S.setappctl_accent_classic)
    }
}
