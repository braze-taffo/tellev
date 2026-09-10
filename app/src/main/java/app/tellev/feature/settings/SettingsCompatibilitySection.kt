package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CompatibilitySwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CompatibilityAdvancedDialog(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Base URL 与路径分开配置；默认只发送兼容性较高的字段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (
                    state.selectedProviderId == "deepseek" &&
                    state.model in setOf("deepseek-chat", "deepseek-reasoner")
                ) {
                    Text(
                        "该旧模型将于 2026-07-24 停用，请迁移到 deepseek-v4-flash 或 deepseek-v4-pro。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.modelsPath,
                    onValueChange = viewModel::updateModelsPath,
                    label = { Text("模型列表路径") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.compatibility.chatCompletionsPath,
                    onValueChange = viewModel::updateChatCompletionsPath,
                    label = { Text("Chat Completions 路径") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.compatibility.authHeader,
                        onValueChange = viewModel::updateAuthHeader,
                        label = { Text("鉴权 Header") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.compatibility.authScheme,
                        onValueChange = viewModel::updateAuthScheme,
                        label = { Text("鉴权前缀") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Bearer；可留空") },
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.maxTokensField,
                    onValueChange = viewModel::updateMaxTokensField,
                    label = { Text("输出长度字段") },
                    supportingText = { Text("max_tokens 或 max_completion_tokens") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CompatibilitySwitch(
                    label = "发送 stream_options.include_usage",
                    checked = state.compatibility.includeUsage,
                    onCheckedChange = viewModel::updateIncludeUsage,
                )
                CompatibilitySwitch(
                    label = "启用模型列表接口",
                    checked = state.compatibility.supportsModelListing,
                    onCheckedChange = viewModel::updateSupportsModelListing,
                )
                CompatibilitySwitch(
                    label = "发送 top_k",
                    checked = state.compatibility.supportsTopK,
                    onCheckedChange = viewModel::updateSupportsTopK,
                )
                CompatibilitySwitch(
                    label = "启用 tools 字段",
                    checked = state.compatibility.supportsTools,
                    onCheckedChange = viewModel::updateSupportsTools,
                )
                CompatibilitySwitch(
                    label = "启用 reasoning 字段",
                    checked = state.compatibility.supportsReasoning,
                    onCheckedChange = viewModel::updateSupportsReasoning,
                )
                CompatibilitySwitch(
                    label = "启用图片消息",
                    checked = state.compatibility.supportsVision,
                    onCheckedChange = viewModel::updateSupportsVision,
                )
                OutlinedTextField(
                    value = state.extraHeadersJson,
                    onValueChange = viewModel::updateExtraHeadersJson,
                    label = { Text("附加 Headers（JSON）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = state.extraBodyJson,
                    onValueChange = viewModel::updateExtraBodyJson,
                    label = { Text("附加请求体（JSON）") },
                    supportingText = { Text("messages/model/stream 等核心字段不会被覆盖") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
