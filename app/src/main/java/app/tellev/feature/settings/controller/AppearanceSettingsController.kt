package app.tellev.feature.settings.controller

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
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun setThemeMode(mode: ThemeMode) {
        appPreferences.themeModeName = mode.name
        themeModeFlow.value = mode
        stateFlow.update {
            it.copy(
                themeMode = mode,
                info = "主题已切换为${mode.displayName()}。",
            )
        }
    }

    fun setThemeAccent(accent: ThemeAccent) {
        appPreferences.themeAccentName = accent.name
        themeAccentFlow.value = accent
        stateFlow.update {
            it.copy(
                themeAccent = accent,
                info = "主题色已切换为${accent.displayName()}。",
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

    private fun ThemeMode.displayName(): String = when (this) {
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
        ThemeMode.System -> "跟随系统"
    }

    private fun ThemeAccent.displayName(): String = when (this) {
        ThemeAccent.Warm -> "暖橘"
        ThemeAccent.Classic -> "经典蓝紫"
    }
}
