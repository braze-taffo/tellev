package app.tellev.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.tts.TtsSettings
import app.tellev.feature.settings.TtsSettingsSection

/**
 * 抽屉底部「设置」打开的面板（参考图三的抽屉设置项）：
 * 模型、自定义 TTS（内联展开）、自定义生图、使用统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    currentModel: String?,
    currentReasoningLabel: String,
    ttsProviderConfigured: Boolean,
    onOpenModel: () -> Unit,
    onOpenImageGen: () -> Unit,
    onOpenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val ttsSettings = remember(context) { TtsSettings(context) }
    var ttsExpanded by remember { mutableStateOf(false) }
    var ttsValues by remember { mutableStateOf(ttsSettings.load()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_drawer_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))

            ChatSettingsRow(
                icon = Icons.Default.SmartToy,
                title = stringResource(R.string.chat_settings_model),
                subtitle = listOfNotNull(
                    currentModel?.takeIf(String::isNotBlank),
                    currentReasoningLabel.takeIf(String::isNotBlank),
                ).joinToString(" · ").ifBlank { null },
                onClick = onOpenModel,
            )
            ChatSettingsRow(
                icon = Icons.Default.RecordVoiceOver,
                title = stringResource(R.string.chat_settings_tts),
                subtitle = if (ttsExpanded) null else stringResource(
                    if (ttsValues.enabled) R.string.tts_enabled else R.string.tts_section_desc,
                ),
                onClick = { ttsExpanded = !ttsExpanded },
            )
            if (ttsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    TtsSettingsSection(
                        context = context,
                        initial = ttsValues,
                        providerConfigured = ttsProviderConfigured,
                        onSave = {
                            ttsValues = it.validated()
                            ttsSettings.save(ttsValues)
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            ChatSettingsRow(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.chat_settings_imagegen),
                onClick = onOpenImageGen,
            )
            ChatSettingsRow(
                icon = Icons.Default.BarChart,
                title = stringResource(R.string.chat_settings_usage),
                onClick = onOpenUsage,
            )
        }
    }
}

@Composable
private fun ChatSettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
