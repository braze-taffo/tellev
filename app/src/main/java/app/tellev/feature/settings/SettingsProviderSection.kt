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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        }?.name ?: "自定义配置"
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
        SectionHeader(icon = Icons.Default.Settings, title = "模型服务")
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
                    text = "当前使用",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedProviderLabel(state),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = state.model.ifBlank { "尚未指定模型" },
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
                        label = { Text("快速切换配置") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        supportingText = { Text("选择后立即用于下一次生成") },
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
                                        Text("当前", style = MaterialTheme.typography.labelSmall)
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
                    Text("管理模型服务配置")
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
            }?.name ?: "自定义配置"
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
                label = { Text("服务商") },
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
                    text = { Text("新建自定义配置") },
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
                    label = { Text("配置名称") },
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
                        contentDescription = "删除该配置",
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
            label = { Text("接口地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://api.example.com") },
        )
    }

    item(key = "provider_api_key") {
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = { viewModel.updateApiKey(it) },
            label = { Text("API 密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleApiKeyVisible) {
                    Icon(
                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (apiKeyVisible) "隐藏 API 密钥" else "显示 API 密钥",
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
                label = { Text("模型") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = { Text("可选择或手动填写模型 ID") },
                trailingIcon = {
                    if (availableModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded)
                    }
                },
                supportingText = {
                    Text(
                        if (availableModels.isEmpty()) {
                            "当前服务未返回模型列表，可手动填写"
                        } else {
                            "可输入筛选，已获取 ${availableModels.size} 个模型"
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
                Text("高级设置")
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
                Text(if (state.isTesting) "测试中..." else "测试连接")
            }
            FilledTonalButton(
                onClick = { viewModel.saveProviderConfig() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存")
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
                        text = if (status.available) "已连接" else "连接失败",
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
