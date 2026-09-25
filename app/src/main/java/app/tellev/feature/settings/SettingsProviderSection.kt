package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence

internal data class ProviderSwitchOption(
    val id: String,
    val label: String,
)

internal fun providerSwitchOptions(state: SettingsUiState): List<ProviderSwitchOption> {
    val builtIns = state.providers
        .filter { provider ->
            provider.id != ProviderCatalog.OPENAI_COMPATIBLE || state.customConfigs.isEmpty()
        }
        .map { ProviderSwitchOption(it.id, it.displayName) }
    val custom = state.customConfigs.map {
        ProviderSwitchOption(ProviderConfigPersistence.selectedIdFor(it.id), it.name)
    }
    return builtIns + custom
}

internal fun selectedProviderLabel(state: SettingsUiState): String {
    val id = state.selectedProviderId
    return if (ProviderConfigPersistence.isCustomConfigId(id)) {
        state.customConfigs.firstOrNull {
            it.id == ProviderConfigPersistence.customIdFrom(id)
        }?.name ?: UiStrings.get(S.setprov_custom_config_fallback)
    } else {
        state.providers.firstOrNull { it.id == id }?.displayName ?: id
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderQuickSwitchCard(
    state: SettingsUiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    val options = remember(state.providers, state.customConfigs) { providerSwitchOptions(state) }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(icon = Icons.Default.Settings, title = stringResource(R.string.setprov_header))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setprov_current_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedProviderLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.model.ifBlank { stringResource(R.string.setprov_no_model) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProviderLabel(state),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.setprov_quick_switch_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        supportingText = { Text(stringResource(R.string.setprov_quick_switch_help)) },
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                trailingIcon = {
                                    if (option.id == state.selectedProviderId) {
                                        Text(stringResource(R.string.setprov_current_tag), style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    if (option.id != state.selectedProviderId) onSelect(option.id)
                                },
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onManage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.setprov_manage))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.providerDetailsItems(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    onOpenAdvancedDialog: () -> Unit,
    onDeleteCustomConfigClick: (String) -> Unit,
) {
    item(key = "provider_selector") {
        val isCustom = ProviderConfigPersistence.isCustomConfigId(state.selectedProviderId)
        val selectedLabel = if (isCustom) {
            state.customConfigs.firstOrNull {
                it.id == ProviderConfigPersistence.customIdFrom(state.selectedProviderId)
            }?.name ?: stringResource(R.string.setprov_custom_config_fallback)
        } else {
            state.providers.find { it.id == state.selectedProviderId }?.displayName
                ?: state.selectedProviderId
        }
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.setprov_provider_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // Built-in providers; the openai-compatible slot is
                // superseded by the named custom configs below.
                state.providers
                    .filter { it.id != ProviderCatalog.OPENAI_COMPATIBLE }
                    .forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                viewModel.selectProvider(provider.id)
                                expanded = false
                            },
                        )
                    }
                if (state.customConfigs.isNotEmpty()) {
                    HorizontalDivider()
                    state.customConfigs.forEach { config ->
                        DropdownMenuItem(
                            text = { Text(config.name) },
                            onClick = {
                                viewModel.selectProvider(
                                    ProviderConfigPersistence.selectedIdFor(config.id)
                                )
                                expanded = false
                            },
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.setprov_new_custom)) },
                    leadingIcon = {
                        Icon(Icons.Default.Add, contentDescription = null)
                    },
                    onClick = {
                        viewModel.createCustomConfig()
                        expanded = false
                    },
                )
            }
        }
    }

    item(key = "provider_custom_name") {
        if (ProviderConfigPersistence.isCustomConfigId(state.selectedProviderId)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.customConfigName,
                    onValueChange = { viewModel.updateCustomConfigName(it) },
                    label = { Text(stringResource(R.string.setprov_config_name_label)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        onDeleteCustomConfigClick(
                            ProviderConfigPersistence.customIdFrom(state.selectedProviderId)
                        )
                    },
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.setprov_cd_delete_config),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    item(key = "provider_base_url") {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = { viewModel.updateBaseUrl(it) },
            label = { Text(stringResource(R.string.setprov_base_url_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.example.com") },
        )
    }

    item(key = "provider_api_key") {
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { viewModel.updateApiKey(it) },
            label = { Text(stringResource(R.string.setprov_api_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleApiKeyVisible) {
                    Icon(
                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (apiKeyVisible) stringResource(R.string.setprov_cd_hide_api_key) else stringResource(R.string.setprov_cd_show_api_key),
                    )
                }
            },
        )
    }

    item(key = "provider_model") {
        var modelMenuExpanded by remember { mutableStateOf(false) }
        val availableModels = remember(state.availableModels) {
            state.availableModels.distinct()
        }
        val modelFilter = state.model.takeUnless { current ->
            availableModels.any { it == current }
        }.orEmpty()
        val matchingModels = remember(availableModels, modelFilter) {
            if (modelFilter.isBlank()) {
                availableModels
            } else {
                availableModels.filter { it.contains(modelFilter, ignoreCase = true) }
            }
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded && matchingModels.isNotEmpty(),
            onExpandedChange = {
                modelMenuExpanded = it && availableModels.isNotEmpty()
            },
        ) {
            OutlinedTextField(
                value = state.model,
                onValueChange = {
                    viewModel.updateModel(it)
                    modelMenuExpanded = availableModels.isNotEmpty()
                },
                label = { Text(stringResource(R.string.setprov_model_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setprov_model_placeholder)) },
                trailingIcon = {
                    if (availableModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                    }
                },
                supportingText = {
                    Text(
                        if (availableModels.isEmpty()) {
                            stringResource(R.string.setprov_model_help_empty)
                        } else {
                            stringResource(R.string.setprov_model_help_count, availableModels.size)
                        },
                    )
                },
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded && matchingModels.isNotEmpty(),
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                matchingModels.take(100).forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            viewModel.updateModel(model)
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
    }

    if (ProviderConfigPersistence.hasAdvancedSettings(state.selectedProviderId)) {
        item(key = "provider_advanced") {
            OutlinedButton(
                onClick = onOpenAdvancedDialog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setprov_advanced))
            }
        }
    }

    item(key = "provider_actions") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.weight(1f),
                enabled = !state.isTesting,
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (state.isTesting) stringResource(R.string.setprov_testing) else stringResource(R.string.setprov_test_connection))
            }
            FilledTonalButton(
                onClick = { viewModel.saveProviderConfig() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setprov_save))
            }
        }
    }

    if (state.providerStatus != null) {
        item(key = "provider_status") {
            val status = state.providerStatus!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (status.available) stringResource(R.string.setprov_status_connected) else stringResource(R.string.setprov_status_failed),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (status.available) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.available) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
