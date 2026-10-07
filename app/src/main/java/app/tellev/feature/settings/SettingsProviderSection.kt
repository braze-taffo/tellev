package app.tellev.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
        var pickerOpen by remember { mutableStateOf(false) }
        val availableModels = remember(state.availableModels) {
            state.availableModels.distinct()
        }
        OutlinedTextField(
            value = state.model,
            onValueChange = { viewModel.updateModel(it) },
            label = { Text(stringResource(R.string.setprov_model_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.setprov_model_placeholder)) },
            supportingText = {
                Text(
                    if (availableModels.isEmpty()) {
                        stringResource(R.string.setprov_model_help_empty)
                    } else {
                        stringResource(R.string.setprov_model_help_count, availableModels.size)
                    },
                )
            },
            trailingIcon = {
                if (availableModels.isNotEmpty()) {
                    IconButton(onClick = { pickerOpen = true }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.setprov_model_pick))
                    }
                }
            },
        )
        if (pickerOpen && availableModels.isNotEmpty()) {
            SettingsModelPickerPopup(
                availableModels = availableModels,
                currentModel = state.model,
                manualLabel = UiStrings.get(S.model_group_manual),
                manualPlaceholder = UiStrings.get(S.model_picker_manual),
                applyLabel = UiStrings.get(S.model_picker_apply),
                searchPlaceholder = UiStrings.get(S.model_picker_search),
                onSelect = {
                    viewModel.updateModel(it)
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
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
            val status = state.providerStatus ?: return@item
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

/**
 * DSH 风格模型选择弹层：搜索 + 可滚列表（当前模型对勾）+ 底部手动输入行。
 * 替换原 ExposedDropdownMenuBox「截断 100 条」的旧选择器。
 */
@Composable
internal fun SettingsModelPickerPopup(
    availableModels: List<String>,
    currentModel: String,
    manualLabel: String,
    manualPlaceholder: String,
    applyLabel: String,
    searchPlaceholder: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var manual by remember(currentModel) { mutableStateOf(currentModel) }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(312.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text(searchPlaceholder, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val visible = remember(availableModels, query) {
                if (query.isBlank()) availableModels
                else availableModels.filter { it.contains(query.trim(), ignoreCase = true) }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(visible.size, key = { visible[it] }) { index ->
                    val model = visible[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (model == currentModel) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelect(model) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            model,
                            fontSize = 15.sp,
                            fontWeight = if (model == currentModel) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (model == currentModel) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.setprov_model_help_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                manualLabel,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    singleLine = true,
                    placeholder = { Text(manualPlaceholder, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = RoundedCornerShape(10.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Surface(
                    onClick = { onSelect(manual.trim()) },
                    enabled = manual.isNotBlank() && manual.trim() != currentModel,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        applyLabel,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
