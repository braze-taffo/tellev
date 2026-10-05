package app.tellev.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.provider.ComfyWorkflowTemplate
import app.tellev.core.provider.ImageProviderProfile
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence

/** 主设置页生图入口卡片的摘要行：当前引擎与各引擎配置状态。 */
internal fun imageGenSummary(state: SettingsUiState): String {
    val engineName = when {
        state.imageEngine == ProviderCatalog.NOVELAI_IMAGE -> "NovelAI"
        ProviderConfigPersistence.isImageProfileEngineId(state.imageEngine) ->
            state.imageProfiles.firstOrNull {
                ProviderConfigPersistence.imageProfileEngineId(it.id) == state.imageEngine
            }?.name ?: UiStrings.get(S.setimg_profile_generic_name)
        else -> "ComfyUI"
    }
    val configured = UiStrings.get(S.setimg_state_configured)
    val unconfigured = UiStrings.get(S.setimg_state_unconfigured)
    val parts = listOf(
        "ComfyUI " + if (state.comfySettings.workflowJson.isNotBlank()) configured else unconfigured,
        "NovelAI " + if (state.novelAiToken.isNotBlank()) configured else unconfigured,
    )
    val extra = state.imageProfiles.count { it.isConfigured }
    return UiStrings.get(S.setimg_entry_summary, engineName,
        (parts + if (extra > 0) listOf(UiStrings.get(S.setimg_entry_profiles, extra)) else emptyList())
            .joinToString(" · "))
}

@Composable
internal fun ImageGenEntryCard(
    state: SettingsUiState,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(R.string.setimg_entry_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    imageGenSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun LazyListScope.imageGenDetailsItems(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenComfyWorkflowDialog: () -> Unit,
    onOpenComfyParamsDialog: () -> Unit,
    onOpenNovelAiParamsDialog: () -> Unit,
    novelAiTokenVisible: Boolean,
    onToggleNovelAiTokenVisible: () -> Unit,
    onEditImageProfile: (ImageProviderProfile?) -> Unit,
) {
    // ── 生图引擎：聊天「生成图片」按钮走哪个引擎，各引擎配置见下方对应区块 ──
    item(key = "image_engine_header") {
        SectionHeader(
            icon = Icons.Default.Tune,
            title = stringResource(R.string.setimg_engine_header),
        )
    }
    item(key = "image_engine") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setimg_engine_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.imageEngine == ProviderCatalog.COMFYUI,
                    onClick = { viewModel.selectImageEngine(ProviderCatalog.COMFYUI) },
                    label = { Text(stringResource(R.string.setimg_engine_comfy)) },
                )
                FilterChip(
                    selected = state.imageEngine == ProviderCatalog.NOVELAI_IMAGE,
                    onClick = { viewModel.selectImageEngine(ProviderCatalog.NOVELAI_IMAGE) },
                    label = { Text(stringResource(R.string.setimg_engine_novelai)) },
                )
            }
        }
    }
    item(key = "image_engine_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // ── 生图模型（ComfyUI）；未配置时聊天界面不出现生图入口 ──
    item(key = "comfy_header") {
        SectionHeader(
            icon = Icons.Default.Palette,
            title = stringResource(R.string.setimg_comfy_header),
        )
    }
    item(key = "comfy_url") {
        OutlinedTextField(
            value = state.comfyBaseUrl,
            onValueChange = viewModel::updateComfyBaseUrl,
            label = { Text(stringResource(R.string.setimg_comfy_url_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("http://192.168.1.100:8188") },
            supportingText = {
                Text(stringResource(R.string.setimg_comfy_url_help))
            },
        )
    }
    item(key = "comfy_model") {
        var comfyModelMenu by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = comfyModelMenu && state.comfyModels.isNotEmpty(),
            onExpandedChange = {
                comfyModelMenu = it && state.comfyModels.isNotEmpty()
            },
        ) {
            OutlinedTextField(
                value = state.comfyModel,
                onValueChange = {
                    viewModel.updateComfyModel(it)
                    comfyModelMenu = state.comfyModels.isNotEmpty()
                },
                label = { Text(stringResource(R.string.setimg_comfy_model_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setimg_comfy_model_placeholder)) },
                trailingIcon = {
                    if (state.comfyModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = comfyModelMenu)
                    }
                },
                supportingText = {
                    Text(
                        if (state.comfyModels.isEmpty()) {
                            stringResource(R.string.setimg_comfy_model_help_empty)
                        } else {
                            stringResource(R.string.setimg_comfy_model_help_count, state.comfyModels.size)
                        },
                    )
                },
            )
            ExposedDropdownMenu(
                expanded = comfyModelMenu && state.comfyModels.isNotEmpty(),
                onDismissRequest = { comfyModelMenu = false },
            ) {
                val filter = state.comfyModel
                state.comfyModels
                    .filter { it.contains(filter, ignoreCase = true) || it == state.comfyModel }
                    .take(100)
                    .forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.updateComfyModel(model)
                                comfyModelMenu = false
                            },
                        )
                    }
            }
        }
    }
    item(key = "comfy_workflow") {
        OutlinedButton(
            onClick = onOpenComfyWorkflowDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.comfySettings.workflowJson.isBlank()) {
                    stringResource(R.string.setimg_workflow_unconfigured)
                } else {
                    stringResource(R.string.setimg_workflow_configured)
                },
            )
        }
    }
    item(key = "comfy_params") {
        OutlinedButton(
            onClick = onOpenComfyParamsDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setimg_comfy_params_button))
        }
    }
    item(key = "comfy_actions") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::testComfyConnection,
                modifier = Modifier.weight(1f),
                enabled = !state.isTestingComfy,
            ) {
                if (state.isTestingComfy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (state.isTestingComfy) stringResource(R.string.setimg_testing) else stringResource(R.string.setimg_test_connection))
            }
            FilledTonalButton(
                onClick = viewModel::saveComfyConfig,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setimg_save))
            }
        }
    }
    if (state.comfyStatus != null) {
        item(key = "comfy_status") {
            val status = state.comfyStatus!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (status.available) stringResource(R.string.setimg_status_connected) else stringResource(R.string.setimg_status_failed),
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

    item(key = "comfy_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // ── NovelAI 生图（远程）：行为对齐酒馆 novel 源 ──
    item(key = "novelai_header") {
        SectionHeader(
            icon = Icons.Default.Cloud,
            title = stringResource(R.string.setimg_novelai_header),
        )
    }
    item(key = "novelai_token") {
        OutlinedTextField(
            value = state.novelAiToken,
            onValueChange = viewModel::updateNovelAiToken,
            label = { Text(stringResource(R.string.setimg_novelai_token_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (novelAiTokenVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = onToggleNovelAiTokenVisible) {
                    Icon(
                        if (novelAiTokenVisible) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = null,
                    )
                }
            },
            supportingText = {
                Text(stringResource(R.string.setimg_novelai_token_help))
            },
        )
    }
    item(key = "novelai_model") {
        var novelModelMenu by remember { mutableStateOf(false) }
        val modelDisplay = NovelAiImageSettings.MODELS
            .firstOrNull { it.first == state.novelAiSettings.model }
            ?.second ?: state.novelAiSettings.model
        ExposedDropdownMenuBox(
            expanded = novelModelMenu,
            onExpandedChange = { novelModelMenu = it },
        ) {
            OutlinedTextField(
                value = modelDisplay,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.setimg_model_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = novelModelMenu)
                },
            )
            ExposedDropdownMenu(
                expanded = novelModelMenu,
                onDismissRequest = { novelModelMenu = false },
            ) {
                NovelAiImageSettings.MODELS.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            viewModel.updateNovelAiSettings { it.copy(model = id) }
                            novelModelMenu = false
                        },
                    )
                }
            }
        }
    }
    item(key = "novelai_params") {
        OutlinedButton(
            onClick = onOpenNovelAiParamsDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setimg_novelai_params_button))
        }
    }
    item(key = "novelai_actions") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::testNovelAiImage,
                modifier = Modifier.weight(1f),
                enabled = !state.isTestingNovelAi,
            ) {
                if (state.isTestingNovelAi) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (state.isTestingNovelAi) stringResource(R.string.setimg_testing) else stringResource(R.string.setimg_test_token))
            }
            FilledTonalButton(
                onClick = viewModel::saveNovelAiImageConfig,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setimg_save))
            }
        }
    }
    if (state.novelAiStatus != null) {
        item(key = "novelai_status") {
            val status = state.novelAiStatus!!
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (status.available) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (status.available) stringResource(R.string.setimg_token_ok) else stringResource(R.string.setimg_token_unavailable),
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
    item(key = "novelai_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // ── 自定义生图端口：任意多个 OpenAI Images 兼容端点，可切换为当前引擎 ──
    item(key = "imgprofile_header") {
        SectionHeader(
            icon = Icons.Default.Dns,
            title = stringResource(R.string.setimg_profile_header),
        )
    }
    item(key = "imgprofile_desc") {
        Text(
            stringResource(R.string.setimg_profile_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.imageProfiles.isEmpty()) {
        item(key = "imgprofile_empty") {
            Text(
                stringResource(R.string.setimg_profile_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.imageProfiles.forEach { profile ->
        val engineId = ProviderConfigPersistence.imageProfileEngineId(profile.id)
        item(key = "imgprofile_row_${profile.id}") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                profile.summary(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.imageEngine == engineId) {
                            Text(
                                stringResource(R.string.setimg_profile_in_use),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.selectImageEngine(engineId) },
                            modifier = Modifier.weight(1f),
                            enabled = profile.isConfigured,
                        ) {
                            Text(stringResource(R.string.setimg_profile_use_engine))
                        }
                        OutlinedButton(
                            onClick = { onEditImageProfile(profile) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.setimg_profile_edit))
                        }
                        OutlinedButton(
                            onClick = { viewModel.deleteImageProfile(profile.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                stringResource(R.string.setimg_profile_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
    item(key = "imgprofile_add") {
        OutlinedButton(
            onClick = { onEditImageProfile(null) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.setimg_profile_add))
        }
    }
}


@Composable
internal fun ComfyWorkflowDialog(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    var text by remember(state.comfySettings.workflowJson) {
        mutableStateOf(state.comfySettings.workflowJson)
    }
    var jsonError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setimg_workflow_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setimg_workflow_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        jsonError = false
                    },
                    label = { Text(stringResource(R.string.setimg_workflow_field_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = jsonError,
                    supportingText = if (jsonError) {
                        { Text(stringResource(R.string.setimg_workflow_json_error)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank() && ComfyWorkflowTemplate.parse(text.trim()) == null) {
                        jsonError = true
                    } else {
                        viewModel.updateComfySettings { it.copy(workflowJson = text) }
                        onDismiss()
                    }
                },
            ) {
                Text(stringResource(R.string.setimg_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setimg_cancel))
            }
        },
    )
}

@Composable
internal fun ComfyParamsDialog(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val settings = state.comfySettings
    var steps by remember { mutableStateOf(settings.steps.toString()) }
    var cfg by remember { mutableStateOf(settings.cfgScale.toString()) }
    var width by remember { mutableStateOf(settings.width.toString()) }
    var height by remember { mutableStateOf(settings.height.toString()) }
    var sampler by remember { mutableStateOf(settings.sampler) }
    var scheduler by remember { mutableStateOf(settings.scheduler) }
    var seed by remember { mutableStateOf(settings.seed.toString()) }
    var negative by remember { mutableStateOf(settings.negativePrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setimg_params_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setimg_params_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text(stringResource(R.string.setimg_steps_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = cfg,
                        onValueChange = { cfg = it },
                        label = { Text("CFG") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it },
                        label = { Text(stringResource(R.string.setimg_width_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text(stringResource(R.string.setimg_height_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sampler,
                        onValueChange = { sampler = it },
                        label = { Text(stringResource(R.string.setimg_sampler_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.setimg_sampler_placeholder)) },
                    )
                    OutlinedTextField(
                        value = scheduler,
                        onValueChange = { scheduler = it },
                        label = { Text(stringResource(R.string.setimg_scheduler_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.setimg_scheduler_placeholder)) },
                    )
                }
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text(stringResource(R.string.setimg_seed_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.setimg_seed_help)) },
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text(stringResource(R.string.setimg_negative_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("lowres, bad anatomy, bad hands, blurry") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateComfySettings { current ->
                        current.copy(
                            steps = steps.trim().toIntOrNull() ?: current.steps,
                            cfgScale = cfg.trim().toDoubleOrNull() ?: current.cfgScale,
                            width = width.trim().toIntOrNull() ?: current.width,
                            height = height.trim().toIntOrNull() ?: current.height,
                            sampler = sampler.trim(),
                            scheduler = scheduler.trim(),
                            seed = seed.trim().toLongOrNull() ?: -1L,
                            negativePrompt = negative,
                        )
                    }
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.setimg_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setimg_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelAiImageParamsDialog(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val settings = state.novelAiSettings
    var steps by remember { mutableStateOf(settings.steps.toString()) }
    var cfg by remember { mutableStateOf(settings.scale.toString()) }
    var width by remember { mutableStateOf(settings.width.toString()) }
    var height by remember { mutableStateOf(settings.height.toString()) }
    var sampler by remember { mutableStateOf(settings.sampler) }
    var samplerMenu by remember { mutableStateOf(false) }
    var scheduler by remember { mutableStateOf(settings.scheduler) }
    var schedulerMenu by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf(settings.seed.toString()) }
    var upscale by remember { mutableStateOf(settings.upscaleRatio.toString()) }
    var sm by remember { mutableStateOf(settings.sm) }
    var smDyn by remember { mutableStateOf(settings.smDyn) }
    var decrisper by remember { mutableStateOf(settings.decrisper) }
    var varietyBoost by remember { mutableStateOf(settings.varietyBoost) }
    var anlasGuard by remember { mutableStateOf(settings.anlasGuard) }
    var prefix by remember { mutableStateOf(settings.promptPrefix) }
    var negative by remember { mutableStateOf(settings.negativePrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setimg_novelai_params_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.setimg_novelai_params_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text(stringResource(R.string.setimg_steps_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(stringResource(R.string.setimg_novelai_steps_help)) },
                    )
                    OutlinedTextField(
                        value = cfg,
                        onValueChange = { cfg = it },
                        label = { Text("CFG") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it },
                        label = { Text(stringResource(R.string.setimg_width_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text(stringResource(R.string.setimg_height_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = samplerMenu,
                        onExpandedChange = { samplerMenu = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = sampler,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.setimg_novelai_sampler_label)) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = samplerMenu) },
                        )
                        ExposedDropdownMenu(
                            expanded = samplerMenu,
                            onDismissRequest = { samplerMenu = false },
                        ) {
                            NovelAiImageSettings.SAMPLERS.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        sampler = name
                                        samplerMenu = false
                                    },
                                )
                            }
                        }
                    }
                    ExposedDropdownMenuBox(
                        expanded = schedulerMenu,
                        onExpandedChange = { schedulerMenu = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = scheduler,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.setimg_novelai_scheduler_label)) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = schedulerMenu) },
                        )
                        ExposedDropdownMenu(
                            expanded = schedulerMenu,
                            onDismissRequest = { schedulerMenu = false },
                        ) {
                            NovelAiImageSettings.SCHEDULERS.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        scheduler = name
                                        schedulerMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = seed,
                        onValueChange = { seed = it },
                        label = { Text(stringResource(R.string.setimg_seed_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text(stringResource(R.string.setimg_seed_help)) },
                    )
                    OutlinedTextField(
                        value = upscale,
                        onValueChange = { upscale = it },
                        label = { Text(stringResource(R.string.setimg_upscale_label)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text(stringResource(R.string.setimg_upscale_help)) },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setimg_smea_label), modifier = Modifier.weight(1f))
                        Switch(checked = sm, onCheckedChange = { sm = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setimg_smea_dyn_label), modifier = Modifier.weight(1f))
                        Switch(checked = smDyn, onCheckedChange = { smDyn = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setimg_decrisper_label), modifier = Modifier.weight(1f))
                        Switch(checked = decrisper, onCheckedChange = { decrisper = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setimg_variety_label), modifier = Modifier.weight(1f))
                        Switch(checked = varietyBoost, onCheckedChange = { varietyBoost = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setimg_anlas_guard_label), modifier = Modifier.weight(1f))
                        Switch(checked = anlasGuard, onCheckedChange = { anlasGuard = it })
                    }
                }
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text(stringResource(R.string.setimg_prefix_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.setimg_prefix_help)) },
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text(stringResource(R.string.setimg_negative_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.setimg_novelai_negative_help)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.updateNovelAiSettings { current ->
                        current.copy(
                            steps = steps.trim().toIntOrNull() ?: current.steps,
                            scale = cfg.trim().toDoubleOrNull() ?: current.scale,
                            width = width.trim().toIntOrNull() ?: current.width,
                            height = height.trim().toIntOrNull() ?: current.height,
                            sampler = sampler.trim().ifBlank { current.sampler },
                            scheduler = scheduler.trim().ifBlank { current.scheduler },
                            seed = seed.trim().toLongOrNull() ?: -1L,
                            upscaleRatio = upscale.trim().toDoubleOrNull() ?: current.upscaleRatio,
                            sm = sm,
                            smDyn = smDyn,
                            decrisper = decrisper,
                            varietyBoost = varietyBoost,
                            anlasGuard = anlasGuard,
                            promptPrefix = prefix,
                            negativePrompt = negative,
                        )
                    }
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.setimg_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setimg_cancel))
            }
        },
    )
}

/**
 * Edit dialog for one custom image endpoint profile. [initial] null creates a
 * new profile; all request fields are free-form so non-OpenAI relays stay
 * reachable, and blank optional fields are omitted from request bodies.
 */
@Composable
internal fun ImageProfileEditDialog(
    initial: ImageProviderProfile?,
    isTesting: Boolean,
    testStatus: app.tellev.core.provider.ProviderStatus?,
    onTest: (ImageProviderProfile) -> Unit,
    onSave: (ImageProviderProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: ImageProviderProfile.DEFAULT_PATH) }
    var modelsPath by remember { mutableStateOf(initial?.modelsPath ?: ImageProviderProfile.DEFAULT_MODELS_PATH) }
    var size by remember { mutableStateOf(initial?.size ?: "1024x1024") }
    var quality by remember { mutableStateOf(initial?.quality ?: "") }
    var style by remember { mutableStateOf(initial?.style ?: "") }
    var responseFormat by remember { mutableStateOf(initial?.responseFormat ?: "") }
    var sendNegativePrompt by remember { mutableStateOf(initial?.sendNegativePrompt ?: false) }
    var extraBody by remember { mutableStateOf(initial?.extraBody?.toString() ?: "") }
    var extraBodyError by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }

    val nameError = showValidation && name.isBlank()
    val baseUrlError = showValidation && baseUrl.isBlank()

    fun buildProfile(): ImageProviderProfile? {
        val extra = if (extraBody.isBlank()) {
            kotlinx.serialization.json.JsonObject(emptyMap())
        } else {
            ImageProviderProfile.parseExtraBody(extraBody) ?: return null
        }
        return ImageProviderProfile(
            id = initial?.id ?: ProviderConfigPersistence.newImageProviderProfileId(),
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
            path = path.trim().ifBlank { ImageProviderProfile.DEFAULT_PATH },
            modelsPath = modelsPath.trim().ifBlank { ImageProviderProfile.DEFAULT_MODELS_PATH },
            size = size.trim(),
            quality = quality.trim(),
            style = style.trim(),
            responseFormat = responseFormat.trim(),
            sendNegativePrompt = sendNegativePrompt,
            extraBody = extra,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                if (initial == null) R.string.setimg_profile_add else R.string.setimg_profile_edit,
            ))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setimg_profile_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = nameError,
                    supportingText = { if (nameError) Text(stringResource(R.string.setimg_profile_required)) },
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.setimg_profile_baseurl)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://api.example.com") },
                    isError = baseUrlError,
                    supportingText = { if (baseUrlError) Text(stringResource(R.string.setimg_profile_required)) },
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.setimg_profile_apikey)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.setimg_profile_model)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("dall-e-3") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text(stringResource(R.string.setimg_profile_path)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = modelsPath,
                        onValueChange = { modelsPath = it },
                        label = { Text(stringResource(R.string.setimg_profile_models_path)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = size,
                        onValueChange = { size = it },
                        label = { Text(stringResource(R.string.setimg_profile_size)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = quality,
                        onValueChange = { quality = it },
                        label = { Text(stringResource(R.string.setimg_profile_quality)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it },
                        label = { Text(stringResource(R.string.setimg_profile_style)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = responseFormat,
                        onValueChange = { responseFormat = it },
                        label = { Text(stringResource(R.string.setimg_profile_response_format)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("url / b64_json") },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.setimg_profile_send_negative), modifier = Modifier.weight(1f))
                    Switch(checked = sendNegativePrompt, onCheckedChange = { sendNegativePrompt = it })
                }
                OutlinedTextField(
                    value = extraBody,
                    onValueChange = {
                        extraBody = it
                        extraBodyError = false
                    },
                    label = { Text(stringResource(R.string.setimg_profile_extra_body)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = extraBodyError,
                    supportingText = {
                        Text(
                            stringResource(
                                if (extraBodyError) R.string.setimg_profile_extra_body_invalid
                                else R.string.setimg_profile_extra_body_help,
                            ),
                        )
                    },
                )
                if (testStatus != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (testStatus.available) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (testStatus.available) stringResource(R.string.setimg_status_connected)
                                else stringResource(R.string.setimg_status_failed),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (testStatus.available) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = testStatus.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (testStatus.available) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        buildProfile()?.let(onTest) ?: run { extraBodyError = true }
                    },
                    enabled = !isTesting,
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.setimg_test_connection))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        showValidation = true
                        val profile = buildProfile()
                        if (profile != null && name.isNotBlank() && baseUrl.isNotBlank()) {
                            onSave(profile)
                        } else if (profile == null) {
                            extraBodyError = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.setimg_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setimg_cancel))
            }
        },
    )
}
