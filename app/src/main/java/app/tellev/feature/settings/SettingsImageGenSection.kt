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
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.tellev.core.provider.ComfyWorkflowTemplate
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.ProviderCatalog

/** 主设置页生图入口卡片的摘要行：当前引擎与各引擎配置状态。 */
internal fun imageGenSummary(state: SettingsUiState): String {
    val engineName = when (state.imageEngine) {
        ProviderCatalog.NOVELAI_IMAGE -> "NovelAI"
        else -> "ComfyUI"
    }
    val parts = listOf(
        "ComfyUI " + if (state.comfySettings.workflowJson.isNotBlank()) "已配置" else "未配置",
        "NovelAI " + if (state.novelAiToken.isNotBlank()) "已配置" else "未配置",
    )
    return "当前引擎：$engineName（${parts.joinToString(" · ")}）"
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
                Text("生图（引擎与模型）", style = MaterialTheme.typography.titleMedium)
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
) {
    // ── 生图引擎：聊天「生成图片」按钮走哪个引擎，各引擎配置见下方对应区块 ──
    item(key = "image_engine_header") {
        SectionHeader(
            icon = Icons.Default.Tune,
            title = "生图引擎",
        )
    }
    item(key = "image_engine") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "聊天输入栏「生成图片」按钮使用的引擎（立即生效，无需保存）；" +
                    "各引擎的连接与参数在下方对应区块配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.imageEngine == ProviderCatalog.COMFYUI,
                    onClick = { viewModel.selectImageEngine(ProviderCatalog.COMFYUI) },
                    label = { Text("ComfyUI（远程）") },
                )
                FilterChip(
                    selected = state.imageEngine == ProviderCatalog.NOVELAI_IMAGE,
                    onClick = { viewModel.selectImageEngine(ProviderCatalog.NOVELAI_IMAGE) },
                    label = { Text("NovelAI（远程）") },
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
            title = "生图模型（ComfyUI）",
        )
    }
    item(key = "comfy_url") {
        OutlinedTextField(
            value = state.comfyBaseUrl,
            onValueChange = viewModel::updateComfyBaseUrl,
            label = { Text("ComfyUI 服务地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("http://192.168.1.100:8188") },
            supportingText = {
                Text("电脑上运行的 ComfyUI 地址；配置工作流并保存后，对话输入栏会出现“生成图片”入口")
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
                label = { Text("模型（Checkpoint）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                singleLine = true,
                placeholder = { Text("填入工作流 %model% 占位符对应的模型名，可留空") },
                trailingIcon = {
                    if (state.comfyModels.isNotEmpty()) {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = comfyModelMenu)
                    }
                },
                supportingText = {
                    Text(
                        if (state.comfyModels.isEmpty()) {
                            "测试连接后可从 ComfyUI 获取模型列表"
                        } else {
                            "已获取 ${state.comfyModels.size} 个模型，可输入筛选"
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
                    "工作流 JSON（未配置）"
                } else {
                    "工作流 JSON（已配置，点击编辑）"
                },
            )
        }
    }
    item(key = "comfy_params") {
        OutlinedButton(
            onClick = onOpenComfyParamsDialog,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("生成参数与默认负面提示词")
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
                Text(if (state.isTestingComfy) "测试中..." else "测试连接")
            }
            FilledTonalButton(
                onClick = viewModel::saveComfyConfig,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存")
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

    item(key = "comfy_divider") {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    }

    // ── NovelAI 生图（远程）：行为对齐酒馆 novel 源 ──
    item(key = "novelai_header") {
        SectionHeader(
            icon = Icons.Default.Cloud,
            title = "NovelAI 生图（远程）",
        )
    }
    item(key = "novelai_token") {
        OutlinedTextField(
            value = state.novelAiToken,
            onValueChange = viewModel::updateNovelAiToken,
            label = { Text("NovelAI 令牌（Persistent Token）") },
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
                Text("novelai.net → 账户设置 → Persistent Token；需要有效订阅（如 Opus）")
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
                label = { Text("模型") },
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
            Text("生成参数、采样器与提示词前缀")
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
                Text(if (state.isTestingNovelAi) "测试中..." else "测试令牌")
            }
            FilledTonalButton(
                onClick = viewModel::saveNovelAiImageConfig,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("保存")
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
                        text = if (status.available) "令牌可用" else "不可用",
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
        title = { Text("ComfyUI 工作流 JSON") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "在 ComfyUI 网页开启开发者模式，用「保存（API 格式）」导出工作流并粘贴到此处，" +
                        "再把正向/负向提示词节点的文本改为占位符 \"%prompt%\" 与 \"%negative_prompt%\"。" +
                        "可选占位符：\"%model%\"、\"%seed%\"、\"%steps%\"、\"%scale%\"、\"%width%\"、" +
                        "\"%height%\"、\"%sampler%\"、\"%scheduler%\"、\"%denoise%\"、\"%clip_skip%\"" +
                        "（工作流中不含对应占位符时参数不生效）。修改后请回到设置页点击「保存」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        jsonError = false
                    },
                    label = { Text("API 格式工作流") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = jsonError,
                    supportingText = if (jsonError) {
                        { Text("JSON 无法解析，请检查后再确定") }
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
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        title = { Text("生图参数") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "参数会替换工作流中对应的占位符；采样器与调度器留空表示沿用工作流自身的值。" +
                        "修改后请回到设置页点击「保存」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text("步数") },
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
                        label = { Text("宽度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text("高度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sampler,
                        onValueChange = { sampler = it },
                        label = { Text("采样器（可留空）") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("euler, dpmpp_2m 等") },
                    )
                    OutlinedTextField(
                        value = scheduler,
                        onValueChange = { scheduler = it },
                        label = { Text("调度器（可留空）") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("normal, karras 等") },
                    )
                }
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it },
                    label = { Text("种子") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("-1 为每次随机") },
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("默认负面提示词") },
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
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        title = { Text("NovelAI 生图参数") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "与酒馆（SillyTavern）NovelAI 源一致：前缀与负面会自动拼进每次请求。" +
                        "修改后请回到设置页点击「保存」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it },
                        label = { Text("步数") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("上限 50；开 Anlas 防护时限 28") },
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
                        label = { Text("宽度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text("高度") },
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
                            label = { Text("采样器") },
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
                            label = { Text("调度器") },
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
                        label = { Text("种子") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("-1 为每次随机") },
                    )
                    OutlinedTextField(
                        value = upscale,
                        onValueChange = { upscale = it },
                        label = { Text("放大倍数") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text("1 为不放大") },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("SMEA（高分辨率增强）", modifier = Modifier.weight(1f))
                        Switch(checked = sm, onCheckedChange = { sm = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("DYN（更多样的 SMEA）", modifier = Modifier.weight(1f))
                        Switch(checked = smDyn, onCheckedChange = { smDyn = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Decrisper（高 CFG 去伪影）", modifier = Modifier.weight(1f))
                        Switch(checked = decrisper, onCheckedChange = { decrisper = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Variety+（多样性增强）", modifier = Modifier.weight(1f))
                        Switch(checked = varietyBoost, onCheckedChange = { varietyBoost = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Anlas 防护（免费额度内生成）", modifier = Modifier.weight(1f))
                        Switch(checked = anlasGuard, onCheckedChange = { anlasGuard = it })
                    }
                }
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("提示词前缀") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("酒馆默认：best quality, absurdres, aesthetic；支持 {prompt} 占位") },
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("默认负面提示词") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("酒馆默认负面会拼接在每次请求的负面之后") },
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
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
