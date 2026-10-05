package app.tellev.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.tts.TtsSettingsValues

@Composable
internal fun TtsSettingsSection(
    context: Context,
    initial: TtsSettingsValues,
    providerConfigured: Boolean,
    onSave: (TtsSettingsValues) -> Unit,
) {
    var values by remember(initial) { mutableStateOf(initial.validated()) }
    var voiceMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(icon = Icons.Default.RecordVoiceOver, title = stringResource(R.string.tts_section_title))
        Text(
            text = stringResource(R.string.tts_section_desc),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!providerConfigured) {
            Text(
                text = stringResource(R.string.tts_provider_hint),
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tts_enabled))
                Text(
                    stringResource(R.string.tts_enabled_desc),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = values.enabled,
                onCheckedChange = { values = values.copy(enabled = it); onSave(values) },
            )
        }
        Text(stringResource(R.string.tts_voice), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = false,
                onClick = { voiceMenu = true },
                label = { Text(values.voice) },
            )
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                TtsSettingsValues.VOICES.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = { values = values.copy(voice = voice); voiceMenu = false; onSave(values) },
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(stringResource(R.string.tts_speed, values.speed))
            Slider(
                value = values.speed,
                onValueChange = { values = values.copy(speed = it.coerceIn(TtsSettingsValues.MIN_SPEED, TtsSettingsValues.MAX_SPEED)) },
                onValueChangeFinished = { onSave(values) },
                valueRange = TtsSettingsValues.MIN_SPEED..TtsSettingsValues.MAX_SPEED,
            )
        }
        Text(stringResource(R.string.tts_model), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = false, onClick = { modelMenu = true }, label = { Text(values.model) })
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                TtsSettingsValues.MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = { values = values.copy(model = model); modelMenu = false; onSave(values) },
                    )
                }
            }
        }
        Text(stringResource(R.string.tts_format), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = false, onClick = { formatMenu = true }, label = { Text(values.format.uppercase()) })
            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                TtsSettingsValues.FORMATS.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.uppercase()) },
                        onClick = { values = values.copy(format = format); formatMenu = false; onSave(values) },
                    )
                }
            }
        }
    }
}
