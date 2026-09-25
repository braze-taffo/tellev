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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R

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
        title = { Text(stringResource(R.string.setcompat_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setcompat_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (
                    state.selectedProviderId == "deepseek" &&
                    state.model in setOf("deepseek-chat", "deepseek-reasoner")
                ) {
                    Text(
                        stringResource(R.string.setcompat_legacy_model_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.modelsPath,
                    onValueChange = viewModel::updateModelsPath,
                    label = { Text(stringResource(R.string.setcompat_models_path_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.compatibility.chatCompletionsPath,
                    onValueChange = viewModel::updateChatCompletionsPath,
                    label = { Text(stringResource(R.string.setcompat_chat_completions_path_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.compatibility.authHeader,
                        onValueChange = viewModel::updateAuthHeader,
                        label = { Text(stringResource(R.string.setcompat_auth_header_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.compatibility.authScheme,
                        onValueChange = viewModel::updateAuthScheme,
                        label = { Text(stringResource(R.string.setcompat_auth_scheme_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.setcompat_auth_scheme_placeholder)) },
                    )
                }
                OutlinedTextField(
                    value = state.compatibility.maxTokensField,
                    onValueChange = viewModel::updateMaxTokensField,
                    label = { Text(stringResource(R.string.setcompat_max_tokens_field_label)) },
                    supportingText = { Text(stringResource(R.string.setcompat_max_tokens_field_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_include_usage),
                    checked = state.compatibility.includeUsage,
                    onCheckedChange = viewModel::updateIncludeUsage,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_supports_model_listing),
                    checked = state.compatibility.supportsModelListing,
                    onCheckedChange = viewModel::updateSupportsModelListing,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_top_k),
                    checked = state.compatibility.supportsTopK,
                    onCheckedChange = viewModel::updateSupportsTopK,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_supports_tools),
                    checked = state.compatibility.supportsTools,
                    onCheckedChange = viewModel::updateSupportsTools,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_supports_reasoning),
                    checked = state.compatibility.supportsReasoning,
                    onCheckedChange = viewModel::updateSupportsReasoning,
                )
                CompatibilitySwitch(
                    label = stringResource(R.string.setcompat_supports_vision),
                    checked = state.compatibility.supportsVision,
                    onCheckedChange = viewModel::updateSupportsVision,
                )
                OutlinedTextField(
                    value = state.extraHeadersJson,
                    onValueChange = viewModel::updateExtraHeadersJson,
                    label = { Text(stringResource(R.string.setcompat_extra_headers_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = state.extraBodyJson,
                    onValueChange = viewModel::updateExtraBodyJson,
                    label = { Text(stringResource(R.string.setcompat_extra_body_label)) },
                    supportingText = { Text(stringResource(R.string.setcompat_extra_body_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.setcompat_done)) }
        },
    )
}
