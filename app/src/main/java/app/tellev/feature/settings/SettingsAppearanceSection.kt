package app.tellev.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tellev.ui.theme.ThemeAccent
import app.tellev.ui.theme.ThemeMode
import app.tellev.ui.theme.lightColors

@Composable
internal fun ThemeOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Like [ThemeOption] but shows a two-dot swatch (primary + container) of the
 *  palette instead of an icon, so the choice is visible at a glance. */
@Composable
internal fun AccentOption(
    accent: ThemeAccent,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = accent.lightColors()
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.width(30.dp)) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

internal fun LazyListScope.appearanceSectionItems(
    state: SettingsUiState,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetThemeAccent: (ThemeAccent) -> Unit,
    onSetChatBubbleAlpha: (Float) -> Unit,
) {
    item(key = "theme_header") {
        SectionHeader(
            icon = Icons.Default.DarkMode,
            title = "主题",
        )
    }

    item(key = "theme_options") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeOption(
                icon = Icons.Default.LightMode,
                label = "浅色",
                selected = state.themeMode == ThemeMode.Light,
                onClick = { onSetThemeMode(ThemeMode.Light) },
            )
            ThemeOption(
                icon = Icons.Default.DarkMode,
                label = "深色",
                selected = state.themeMode == ThemeMode.Dark,
                onClick = { onSetThemeMode(ThemeMode.Dark) },
            )
            ThemeOption(
                icon = Icons.Default.PhoneAndroid,
                label = "跟随系统",
                selected = state.themeMode == ThemeMode.System,
                onClick = { onSetThemeMode(ThemeMode.System) },
            )
        }
    }

    item(key = "accent_label") {
        Text(
            text = "主题色",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
    }

    item(key = "accent_options") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccentOption(
                accent = ThemeAccent.Warm,
                label = "暖橘",
                selected = state.themeAccent == ThemeAccent.Warm,
                onClick = { onSetThemeAccent(ThemeAccent.Warm) },
            )
            AccentOption(
                accent = ThemeAccent.Classic,
                label = "经典蓝紫",
                selected = state.themeAccent == ThemeAccent.Classic,
                onClick = { onSetThemeAccent(ThemeAccent.Classic) },
            )
        }
    }

    item(key = "bubble_alpha") {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "气泡不透明度：${(state.chatBubbleAlpha * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = state.chatBubbleAlpha,
                onValueChange = onSetChatBubbleAlpha,
                valueRange = 0f..1f,
            )
        }
    }

    item(key = "divider_4") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }
}
