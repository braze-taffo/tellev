package app.tellev.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.provider.OpenAiSpeechAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.tts.TtsSettingsValues
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    fun save(next: TtsSettingsValues) {
        values = next
        onSave(next)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(icon = Icons.Default.RecordVoiceOver, title = stringResource(R.string.tts_section_title))
        Text(
            text = stringResource(R.string.tts_section_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tts_enabled))
                Text(
                    stringResource(R.string.tts_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = values.enabled,
                onCheckedChange = { save(values.copy(enabled = it)) },
            )
        }
        Text(stringResource(R.string.tts_voice), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = false,
                onClick = { voiceMenu = true },
                label = { Text(values.effectiveVoice()) },
            )
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                TtsSettingsValues.VOICES.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text(voice) },
                        onClick = { save(values.copy(voice = voice, customVoice = "")) ; voiceMenu = false },
                    )
                }
            }
        }
        if (values.customVoice.isNotBlank()) {
            Text(
                stringResource(R.string.tts_custom_voice_active, values.customVoice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
        Text(stringResource(R.string.tts_model), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = false, onClick = { modelMenu = true }, label = { Text(values.effectiveModel()) })
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                TtsSettingsValues.MODELS.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = { save(values.copy(model = model, customModel = "")); modelMenu = false },
                    )
                }
            }
        }
        Text(stringResource(R.string.tts_format), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = false, onClick = { formatMenu = true }, label = { Text(values.format.uppercase()) })
            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }) {
                TtsSettingsValues.FORMATS.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.uppercase()) },
                        onClick = { save(values.copy(format = format)); formatMenu = false },
                    )
                }
            }
        }

        // ── 自定义端点：独立的 OpenAI 兼容 /v1/audio/speech 服务 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tts_custom_endpoint))
                Text(
                    stringResource(R.string.tts_custom_endpoint_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = values.baseUrl.isNotBlank(),
                onCheckedChange = { enabled -> save(values.copy(baseUrl = if (enabled) values.baseUrl.ifBlank { "https://" } else "")) },
            )
        }
        if (values.baseUrl.isNotBlank()) {
            OutlinedTextField(
                value = values.baseUrl,
                onValueChange = { save(values.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.tts_base_url)) },
                placeholder = { Text(stringResource(R.string.tts_base_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = values.apiKey,
                onValueChange = { save(values.copy(apiKey = it)) },
                label = { Text(stringResource(R.string.tts_api_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = values.customVoice,
            onValueChange = { save(values.copy(customVoice = it)) },
            label = { Text(stringResource(R.string.tts_custom_voice)) },
            supportingText = { Text(stringResource(R.string.tts_custom_voice_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = values.customModel,
            onValueChange = { save(values.copy(customModel = it)) },
            label = { Text(stringResource(R.string.tts_custom_model)) },
            supportingText = { Text(stringResource(R.string.tts_custom_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // 连接测试：只测自定义 TTS 端点（跟随聊天服务商时无法在设置页取到密钥，给出说明）。
        var testing by remember { mutableStateOf(false) }
        var testResult by remember { mutableStateOf<String?>(null) }
        Button(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val effective = values.validated()
                    if (!effective.hasCustomEndpoint) {
                        testResult = context.getString(R.string.tts_test_follows_chat)
                        testing = false
                        return@launch
                    }
                    // checkStatus 在 OkHttp Request.Builder().url() 上裸抛
                    // IllegalArgumentException(半截 URL 一点即崩),且同步 execute
                    // 会把主线程卡到超时。整体 runCatching + IO 调度兜住这两条。
                    val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val config = ProviderConfig(
                                providerType = ProviderCatalog.OPENAI_COMPATIBLE,
                                baseUrl = effective.baseUrl,
                                apiKey = effective.apiKey.takeIf(String::isNotBlank),
                            )
                            OpenAiSpeechAdapter().checkStatus(config)
                        }.getOrElse {
                            app.tellev.core.provider.ProviderStatus(available = false, message = it.message ?: "invalid URL")
                        }
                    }
                    testResult = if (status.available) {
                        context.getString(R.string.tts_test_ok)
                    } else {
                        context.getString(R.string.tts_test_failed, status.message)
                    }
                    testing = false
                }
            },
            enabled = !testing,
            colors = ButtonDefaults.buttonColors(),
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(R.string.tts_test_connection))
        }
        testResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!providerConfigured && values.baseUrl.isBlank()) {
            Text(
                text = stringResource(R.string.tts_provider_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
