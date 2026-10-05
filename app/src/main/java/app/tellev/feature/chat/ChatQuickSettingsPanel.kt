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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook

/**
 * 顶栏「设定集合键」打开的面板：世界书（本会话）、生成预设、用户设定三区块，
 * 与参考图的右上角调节入口一一对应。世界书写入会话的 world_info 绑定，
 * 预设/用户设定走 ChatViewModel 的既有切换路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatQuickSettingsPanel(
    worldBooks: List<WorldBook>,
    boundWorldBookName: String?,
    presets: List<GenerationPreset>,
    selectedPresetId: String?,
    personas: List<Persona>,
    selectedPersonaId: String?,
    onBindWorldBook: (String?) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectPersona: (String) -> Unit,
    onOpenReasoning: () -> Unit = {},
    reasoningLabel: String? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.chat_quick_settings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.size(12.dp))

            // 思考强度：会话级推理档位的快捷入口（滑杆面板见 ReasoningEffortSheet）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenReasoning)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_panel_reasoning),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!reasoningLabel.isNullOrBlank()) {
                        Text(
                            text = reasoningLabel,
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
            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item(key = "world_header") {
                    PanelSectionHeader(
                        icon = Icons.Default.Public,
                        title = stringResource(R.string.chat_panel_world_session),
                    )
                }
                item(key = "world_unbound") {
                    PanelRadioRow(
                        label = stringResource(R.string.chat_panel_world_unbound),
                        selected = boundWorldBookName == null,
                        onClick = { onBindWorldBook(null) },
                    )
                }
                items(worldBooks, key = { "world_${it.id}" }) { book ->
                    PanelRadioRow(
                        label = book.name,
                        selected = boundWorldBookName == book.name || boundWorldBookName == book.id,
                        onClick = { onBindWorldBook(book.name) },
                    )
                }
                item(key = "divider1") { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
                item(key = "preset_header") {
                    PanelSectionHeader(
                        icon = Icons.Default.Tune,
                        title = stringResource(R.string.chat_panel_preset_section),
                    )
                }
                items(presets, key = { "preset_${it.id}" }) { preset ->
                    PanelRadioRow(
                        label = preset.name,
                        selected = preset.id == selectedPresetId,
                        onClick = { onSelectPreset(preset.id) },
                    )
                }
                item(key = "divider2") { HorizontalDivider(Modifier.padding(vertical = 10.dp)) }
                item(key = "persona_header") {
                    PanelSectionHeader(
                        icon = Icons.Default.Person,
                        title = stringResource(R.string.chat_panel_persona_section),
                    )
                }
                if (personas.isEmpty()) {
                    item(key = "persona_empty") {
                        Text(
                            text = stringResource(R.string.setpers_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                items(personas, key = { "persona_${it.id}" }) { persona ->
                    PanelRadioRow(
                        label = persona.name,
                        selected = persona.id == selectedPersonaId,
                        onClick = { onSelectPersona(persona.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PanelRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
