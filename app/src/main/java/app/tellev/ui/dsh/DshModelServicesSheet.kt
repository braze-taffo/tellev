package app.tellev.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tellev.R
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.ImageProviderProfile
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.OpenAiSpeechAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.tts.TtsSettingsValues
import app.tellev.feature.chat.DshModelGroup
import app.tellev.feature.chat.ModelCatalogState
import app.tellev.feature.chat.ModelOption
import app.tellev.feature.settings.ComfyParamsDialog
import app.tellev.feature.settings.ComfyWorkflowDialog
import app.tellev.feature.settings.CompatibilityAdvancedDialog
import app.tellev.feature.settings.ImageProfileEditDialog
import app.tellev.feature.settings.NovelAiImageParamsDialog
import app.tellev.feature.settings.ProviderDetailsPane
import app.tellev.feature.settings.SettingsUiState
import app.tellev.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型服务统一弹层：对话模型 / 生图引擎 / 语音朗读三类服务合并进同一个 sheet
 * （DeepSeek Harness agent-preset sheet 形制）。root = 三条服务行（当前值 + ›），
 * 各段钻入二级页配置：
 *  - 对话段复用 [DshModelListPane] / [DshEffortPane]（模型与档位选择）；
 *    「模型服务配置」行钻入 [ProviderDetailsPane]（服务商选择/自定义端点 CRUD/
 *    高级设置/地址密钥模型编辑/思考档案入口/恢复默认——连接配置与服务商管理
 *    融合后的单一面）；
 *  - 生图段经 [SettingsViewModel] 复用 ImageGenSettingsController（引擎选择 +
 *    ComfyUI/NovelAI/自定义端点配置）；引擎保存写 secretStore，聊天侧的
 *    refreshImageGenAvailability 由 secretStore.changes 自动触发。
 *  - 语音段复用 TtsSettings 的取值/校验（TtsSettingsValues）与连接测试。
 * 主题由根层 DshTheme 提供，本弹层不再自行嵌套。
 * 反馈：弹层打开期间 settingsState.info/error 由弹层底部公共反馈条内联消费
 * （设置页跳过 snackbar 消费，DshRoot 传参），各段保存/删除结果不丢。
 */
@Composable
internal fun DshModelServicesSheet(
    // ── 对话段：模型/档位选择（DshModelEffortMenu 同源数据） ──
    options: List<ModelOption>,
    groups: List<DshModelGroup>,
    catalog: ModelCatalogState,
    currentModel: String?,
    currentEffort: ReasoningEffort,
    effortLevels: List<ReasoningEffort>,
    currentEffortLabel: String,
    modelLabel: String?,
    selectionEnabled: Boolean,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onRefreshCatalog: () -> Unit,
    onAssignGroup: (modelId: String, groupId: String, member: Boolean) -> Unit,
    onCreateGroup: (label: String) -> Unit,
    // ── 对话段：模型服务配置（ProviderDetailsPane 数据：服务商值 + 思考档案入口） ──
    providerLabel: String,
    profileSummary: String,
    onOpenProfile: () -> Unit,
    // ── 生图段：SettingsUiState + SettingsViewModel（引擎选择与各引擎配置） ──
    imageViewModel: SettingsViewModel,
    imageState: SettingsUiState,
    // ── 语音段 ──
    ttsValues: TtsSettingsValues,
    onTtsSave: (TtsSettingsValues) -> Unit,
    ttsProviderConfigured: Boolean,
    onDismiss: () -> Unit,
    // 入口合并：输入栏模型键进来时直达「对话」段（模型选择/档位/模型服务配置），
    // 抽屉「模型服务」仍从 root 三服务行开始（默认值保持既有行为）。
    initialPane: String = "root",
) {
    var pane by remember { mutableStateOf(initialPane) }
    // 自定义生图端点编辑对话框：null + new=false 关闭；profile=编辑既有；new=true 新建。
    var profileEditorFor by remember { mutableStateOf<ImageProviderProfile?>(null) }
    var profileEditorNew by remember { mutableStateOf(false) }
    // 生图高级配置对话框（原设置→生图页的三张弹窗，随重复入口合并进本弹层）。
    var showComfyWorkflow by remember { mutableStateOf(false) }
    var showComfyParams by remember { mutableStateOf(false) }
    var showNovelAiParams by remember { mutableStateOf(false) }
    // 服务商管理段（chat-providers）状态：密钥可见性、高级设置对话框、删除确认
    // 的待删配置 id——原 SettingsScreen 承载，随「模型配置」入口合并搬入本弹层。
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showAdvancedDialog by remember { mutableStateOf(false) }
    var pendingDeleteConfigId by remember { mutableStateOf<String?>(null) }
    // 弹层级内联反馈：settingsState.info/error 一旦产生就在弹层底部展示并即时
    // 清算（读取后 clear），防残留下次进入；弹层打开期间设置页跳过 snackbar
    // 消费（DshRoot 传 suppressFeedbackSnackbar），反馈不会被下层先吞。
    var serviceFeedback by remember { mutableStateOf<String?>(null) }
    var serviceFeedbackIsError by remember { mutableStateOf(false) }
    LaunchedEffect(imageState.info) {
        imageState.info?.let {
            serviceFeedback = it
            serviceFeedbackIsError = false
            imageViewModel.clearInfo()
        }
    }
    LaunchedEffect(imageState.error) {
        imageState.error?.let {
            serviceFeedback = it
            serviceFeedbackIsError = true
            imageViewModel.clearError()
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    // 统一弹层尺寸：宽度上限 560dp（手机近乎满宽），高度最多占屏 88%，
    // 各段内容在内部滚动——原先 400dp 宽 + 560dp 高装不下生图高级配置。
    val sheetMaxHeight = (configuration.screenHeightDp * 0.88f).dp
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.98f)
                .heightIn(max = sheetMaxHeight)
                .clip(RoundedCornerShape(Dsh.RADIUS_XL.dp))
                .background(Dsh.menuSurface)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            if (pane == "root") {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp, bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Dsh.borderL2)
                        .align(Alignment.CenterHorizontally),
                )
                Text(
                    stringResource(R.string.dsh_services_title),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    color = Dsh.textPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                val voiceSummary = if (ttsValues.enabled) {
                    "${ttsValues.effectiveVoice()} · ${ttsValues.effectiveModel()}"
                } else {
                    stringResource(R.string.dsh_services_voice_off)
                }
                DshServiceRow(
                    label = stringResource(R.string.dsh_services_chat),
                    value = modelLabel.orEmpty().ifBlank { providerLabel },
                    onClick = { pane = "chat" },
                )
                HorizontalDivider(color = Color(0x29797E91))
                DshServiceRow(
                    label = stringResource(R.string.dsh_services_image),
                    value = dshImageEngineLabel(imageState),
                    onClick = { pane = "image" },
                )
                HorizontalDivider(color = Color(0x29797E91))
                DshServiceRow(
                    label = stringResource(R.string.dsh_services_voice),
                    value = voiceSummary,
                    onClick = { pane = "voice" },
                )
            } else if (pane == "chat") {
                Column {
                    DshDrillHeader(
                        onBack = { pane = "root" },
                        title = stringResource(R.string.dsh_services_chat),
                    )
                    DshServiceRow(
                        label = stringResource(R.string.model_picker_title),
                        value = modelLabel.orEmpty(),
                        onClick = { pane = "chat-models" },
                    )
                    HorizontalDivider(color = Color(0x29797E91))
                    DshServiceRow(
                        label = stringResource(R.string.dsh_model_levels),
                        value = currentEffortLabel,
                        onClick = { pane = "chat-effort" },
                    )
                    HorizontalDivider(color = Color(0x29797E91))
                    DshServiceRow(
                        label = stringResource(R.string.setscreen_title_provider),
                        value = providerLabel,
                        onClick = { pane = "chat-providers" },
                    )
                }
            } else if (pane == "chat-models") {
                DshModelListPane(
                    options = options,
                    groups = groups,
                    catalog = catalog,
                    currentModel = currentModel,
                    allowGroupAssign = true,
                    selectionEnabled = selectionEnabled,
                    onBack = { pane = "chat" },
                    onSelectModel = onSelectModel,
                    onRefreshCatalog = onRefreshCatalog,
                    onAssignGroup = onAssignGroup,
                    onCreateGroup = onCreateGroup,
                )
            } else if (pane == "chat-effort") {
                DshEffortPane(
                    currentEffort = currentEffort,
                    effortLevels = effortLevels,
                    selectionEnabled = selectionEnabled,
                    onBack = { pane = "chat" },
                    onSelectEffort = onSelectEffort,
                )
            } else if (pane == "chat-providers") {
                // 模型服务配置单一面（ProviderDetailsPane）：服务商选择/自定义端点
                // CRUD/高级设置 + 地址/密钥/模型编辑 + 思考档案入口 + 恢复默认——
                // 连接配置与服务商管理融合后的唯一钻入面。
                Column {
                    DshDrillHeader(
                        onBack = { pane = "chat" },
                        title = stringResource(R.string.setscreen_title_provider),
                    )
                    ProviderDetailsPane(
                        state = imageState,
                        viewModel = imageViewModel,
                        apiKeyVisible = apiKeyVisible,
                        onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                        onOpenAdvancedDialog = { showAdvancedDialog = true },
                        onDeleteCustomConfigClick = { pendingDeleteConfigId = it },
                        profileSummary = profileSummary,
                        onOpenProfile = onOpenProfile,
                    )
                }
            } else if (pane == "image") {
                DshImageServicesPane(
                    imageViewModel = imageViewModel,
                    imageState = imageState,
                    onBack = { pane = "root" },
                    onOpenComfy = { pane = "image-comfy" },
                    onOpenNovelAi = { pane = "image-novelai" },
                    onOpenProfiles = { pane = "image-profiles" },
                )
            } else if (pane == "image-comfy") {
                DshImageComfyPane(
                    imageViewModel = imageViewModel,
                    imageState = imageState,
                    onBack = { pane = "image" },
                    onOpenWorkflow = { showComfyWorkflow = true },
                    onOpenParams = { showComfyParams = true },
                )
            } else if (pane == "image-novelai") {
                DshImageNovelAiPane(
                    imageViewModel = imageViewModel,
                    imageState = imageState,
                    onBack = { pane = "image" },
                    onOpenParams = { showNovelAiParams = true },
                )
            } else if (pane == "image-profiles") {
                DshImageProfilesPane(
                    imageViewModel = imageViewModel,
                    imageState = imageState,
                    onBack = { pane = "image" },
                    onEditProfile = { profile ->
                        profileEditorFor = profile
                        profileEditorNew = profile == null
                    },
                )
            } else if (pane == "voice") {
                DshVoiceServicesPane(
                    initial = ttsValues,
                    providerConfigured = ttsProviderConfigured,
                    onBack = { pane = "root" },
                    onSave = onTtsSave,
                )
            }
            // 公共内联反馈条：任何一段触发的 settingsState.info/error 都在弹层
            // 底部可见（弹层无 snackbar；设置页消费已在弹层打开期间跳过）。
            serviceFeedback?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (serviceFeedbackIsError) Dsh.errorPrimary else Dsh.statusGreen,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
    if (profileEditorFor != null || profileEditorNew) {
        ImageProfileEditDialog(
            initial = profileEditorFor,
            isTesting = imageState.isTestingImageProfile,
            testStatus = imageState.imageProfileStatus,
            onTest = imageViewModel::testImageProfile,
            onSave = { profile ->
                imageViewModel.saveImageProfile(profile)
                profileEditorFor = null
                profileEditorNew = false
            },
            onDismiss = {
                profileEditorFor = null
                profileEditorNew = false
                // 陈旧测试结果不得留到下一个端点的对话框里（controller 同款语义）。
                imageViewModel.clearImageProfileStatus()
            },
        )
    }
    // 服务商高级设置对话框（兼容路径/鉴权/会话字段等，仅支持的服务商可达；
    // 原设置页承载，随入口合并搬入本弹层）。
    if (showAdvancedDialog) {
        CompatibilityAdvancedDialog(
            state = imageState,
            viewModel = imageViewModel,
            onDismiss = { showAdvancedDialog = false },
        )
    }
    // 自定义配置删除确认（键与文案原样沿用；删除结果经 settingsState.info/error
    // 走上面的公共反馈条）。
    pendingDeleteConfigId?.let { configId ->
        val configName = imageState.customConfigs.firstOrNull { it.id == configId }?.name
            ?: stringResource(R.string.setscreen_this_config)
        AlertDialog(
            onDismissRequest = { pendingDeleteConfigId = null },
            title = { Text(stringResource(R.string.setscreen_delete_config_title)) },
            text = { Text(stringResource(R.string.setscreen_delete_config_message, configName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        imageViewModel.deleteCustomConfig(configId)
                        pendingDeleteConfigId = null
                    },
                ) {
                    Text(stringResource(R.string.setscreen_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteConfigId = null }) {
                    Text(stringResource(R.string.setscreen_cancel))
                }
            },
        )
    }
    // 生图高级配置（原设置→生图页的三张弹窗，随入口合并搬到本弹层）。
    if (showComfyWorkflow) {
        ComfyWorkflowDialog(
            state = imageState,
            viewModel = imageViewModel,
            onDismiss = { showComfyWorkflow = false },
        )
    }
    if (showComfyParams) {
        ComfyParamsDialog(
            state = imageState,
            viewModel = imageViewModel,
            onDismiss = { showComfyParams = false },
        )
    }
    if (showNovelAiParams) {
        NovelAiImageParamsDialog(
            state = imageState,
            viewModel = imageViewModel,
            onDismiss = { showNovelAiParams = false },
        )
    }
}

/** root/二级页通用的服务行：左侧标签、右侧当前值 + ›（ModelSelect 行对的形制）。 */
@Composable
private fun DshServiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 45.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Dsh.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = Color(0xFF4D70FF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 130.dp),
            textAlign = TextAlign.End,
        )
        Text(
            "›",
            fontSize = 20.sp,
            lineHeight = 20.sp,
            color = Dsh.textTertiary.copy(alpha = 0.42f),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 生图引擎标签：内置引擎用品牌名，imgprof: 引擎回查端点名（imageGenSummary 同源逻辑）。 */
private fun dshImageEngineLabel(state: SettingsUiState): String = when {
    state.imageEngine == ProviderCatalog.NOVELAI_IMAGE -> "NovelAI"
    ProviderConfigPersistence.isImageProfileEngineId(state.imageEngine) ->
        state.imageProfiles.firstOrNull {
            ProviderConfigPersistence.imageProfileEngineId(it.id) == state.imageEngine
        }?.name ?: "ComfyUI"
    else -> "ComfyUI"
}

/** 生图段：引擎单选行（点选即保存）+ 各引擎配置钻入行。 */
@Composable
private fun DshImageServicesPane(
    imageViewModel: SettingsViewModel,
    imageState: SettingsUiState,
    onBack: () -> Unit,
    onOpenComfy: () -> Unit,
    onOpenNovelAi: () -> Unit,
    onOpenProfiles: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = minOf((configuration.screenHeightDp * 0.72f).dp, 560.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = sheetMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        DshDrillHeader(onBack = onBack, title = stringResource(R.string.dsh_services_image))
        Text(
            stringResource(R.string.setimg_engine_desc),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        DshEngineRow(
            label = "ComfyUI",
            configured = imageState.comfySettings.workflowJson.isNotBlank(),
            selected = imageState.imageEngine == ProviderCatalog.COMFYUI,
            onClick = { imageViewModel.selectImageEngine(ProviderCatalog.COMFYUI) },
        )
        DshEngineRow(
            label = "NovelAI",
            configured = imageState.novelAiToken.isNotBlank(),
            selected = imageState.imageEngine == ProviderCatalog.NOVELAI_IMAGE,
            onClick = { imageViewModel.selectImageEngine(ProviderCatalog.NOVELAI_IMAGE) },
        )
        imageState.imageProfiles.forEach { profile ->
            DshEngineRow(
                label = profile.name,
                configured = profile.isConfigured,
                selected = imageState.imageEngine == ProviderConfigPersistence.imageProfileEngineId(profile.id),
                onClick = {
                    imageViewModel.selectImageEngine(
                        ProviderConfigPersistence.imageProfileEngineId(profile.id),
                    )
                },
            )
        }
        HorizontalDivider(color = Color(0x29797E91), modifier = Modifier.padding(vertical = 6.dp))
        DshServiceRow(
            label = stringResource(R.string.dsh_services_img_comfy_row),
            value = imageState.comfyBaseUrl.ifBlank { stringResource(R.string.setimg_state_unconfigured) },
            onClick = onOpenComfy,
        )
        DshServiceRow(
            label = stringResource(R.string.dsh_services_img_novelai_row),
            value = stringResource(
                if (imageState.novelAiToken.isNotBlank()) {
                    R.string.setimg_state_configured
                } else {
                    R.string.setimg_state_unconfigured
                },
            ),
            onClick = onOpenNovelAi,
        )
        DshServiceRow(
            label = stringResource(R.string.dsh_services_img_profiles),
            value = imageState.imageProfiles.size.toString(),
            onClick = onOpenProfiles,
        )
    }
}

/** 引擎单选行：当前引擎打勾；未配置的引擎仍可选（生成时才报缺配置，与设置页一致）。 */
@Composable
private fun DshEngineRow(
    label: String,
    configured: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .background(if (selected) Dsh.hover else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!configured) {
            Text(
                stringResource(R.string.setimg_state_unconfigured),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Dsh.textPrimary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** ComfyUI 引擎配置：地址/模型/工作流/采样参数 + 测试/保存（原设置→生图页功能全量并入）。 */
@Composable
private fun DshImageComfyPane(
    imageViewModel: SettingsViewModel,
    imageState: SettingsUiState,
    onBack: () -> Unit,
    onOpenWorkflow: () -> Unit,
    onOpenParams: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DshDrillHeader(onBack = onBack, title = "ComfyUI")
        DshSheetField(
            imageState.comfyBaseUrl,
            imageViewModel::updateComfyBaseUrl,
            stringResource(R.string.setimg_comfy_url_label),
            singleLine = true,
        )
        // 模型：可编辑下拉，测试连接后 state.comfyModels 有值即可从列表点选（带过滤）。
        DshModelDropdownField(
            value = imageState.comfyModel,
            onValueChange = imageViewModel::updateComfyModel,
            label = stringResource(R.string.setimg_comfy_model_label),
            options = imageState.comfyModels,
        )
        Text(
            if (imageState.comfyModels.isEmpty()) {
                stringResource(R.string.setimg_comfy_model_help_empty)
            } else {
                stringResource(R.string.setimg_comfy_model_help_count, imageState.comfyModels.size)
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshSheetActionButton(
                text = stringResource(
                    if (imageState.comfySettings.workflowJson.isBlank()) {
                        R.string.setimg_workflow_unconfigured
                    } else {
                        R.string.setimg_workflow_configured
                    },
                ),
                onClick = onOpenWorkflow,
                modifier = Modifier.weight(1f),
            )
            DshSheetActionButton(
                text = stringResource(R.string.setimg_comfy_params_button),
                onClick = onOpenParams,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshSheetActionButton(
                text = stringResource(
                    if (imageState.isTestingComfy) R.string.setimg_testing else R.string.setimg_test_connection,
                ),
                testing = imageState.isTestingComfy,
                enabled = !imageState.isTestingComfy,
                onClick = imageViewModel::testComfyConnection,
                modifier = Modifier.weight(1f),
            )
            DshSheetActionButton(
                text = stringResource(R.string.setimg_save),
                accent = true,
                onClick = imageViewModel::saveComfyConfig,
                modifier = Modifier.weight(1f),
            )
        }
        DshProviderStatusRow(imageState.comfyStatus)
    }
}

/** NovelAI 引擎配置：令牌 + 模型下拉 + 参数 + 测试/保存（原设置→生图页功能全量并入）。 */
@Composable
private fun DshImageNovelAiPane(
    imageViewModel: SettingsViewModel,
    imageState: SettingsUiState,
    onBack: () -> Unit,
    onOpenParams: () -> Unit,
) {
    val modelDisplay = NovelAiImageSettings.MODELS
        .firstOrNull { it.first == imageState.novelAiSettings.model }
        ?.second ?: imageState.novelAiSettings.model
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DshDrillHeader(onBack = onBack, title = "NovelAI")
        DshSecretField(
            imageState.novelAiToken,
            imageViewModel::updateNovelAiToken,
            stringResource(R.string.setimg_novelai_token_label),
        )
        // 模型：只读下拉（NovelAiImageSettings.MODELS 固定清单）。
        DshModelDropdownField(
            value = modelDisplay,
            onValueChange = {},
            label = stringResource(R.string.setimg_model_label),
            options = NovelAiImageSettings.MODELS.map { it.second },
            readOnly = true,
            onSelect = { display ->
                NovelAiImageSettings.MODELS.firstOrNull { it.second == display }?.let { (id, _) ->
                    imageViewModel.updateNovelAiSettings { it.copy(model = id) }
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshSheetActionButton(
                text = stringResource(R.string.setimg_novelai_params_button),
                onClick = onOpenParams,
                modifier = Modifier.weight(1f),
            )
            DshSheetActionButton(
                text = stringResource(R.string.setimg_save),
                accent = true,
                onClick = imageViewModel::saveNovelAiImageConfig,
                modifier = Modifier.weight(1f),
            )
        }
        DshSheetActionButton(
            text = stringResource(
                if (imageState.isTestingNovelAi) R.string.setimg_testing else R.string.setimg_test_token,
            ),
            testing = imageState.isTestingNovelAi,
            enabled = !imageState.isTestingNovelAi,
            onClick = imageViewModel::testNovelAiImage,
            modifier = Modifier.fillMaxWidth(),
        )
        DshProviderStatusRow(imageState.novelAiStatus)
    }
}

/**
 * 可编辑下拉字段（弹层形制）：输入框 + 尾部展开键，展开后按输入过滤选项。
 * [readOnly] 为 true 时输入框只读，点选走 [onSelect]（NovelAI 固定模型清单）。
 */
@Composable
private fun DshModelDropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    readOnly: Boolean = false,
    onSelect: ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = options.isNotEmpty()
    Box {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                DshSheetField(value, onValueChange, label, singleLine = true)
                // 只读模式：盖一层透明点击层，点击即展开列表。
                if (readOnly) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(enabled = enabled) { expanded = true },
                    )
                }
            }
            IconButton(
                onClick = { expanded = !expanded },
                enabled = enabled,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = if (enabled) Dsh.textSecondary else Dsh.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            val filter = value
            options
                .filter { it.contains(filter, ignoreCase = true) || it == value }
                .take(100)
                .forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 14.sp) },
                        onClick = {
                            onSelect?.invoke(option) ?: onValueChange(option)
                            expanded = false
                        },
                    )
                }
        }
    }
}

/** 自定义生图端点列表：使用/编辑/删除 + 新建（编辑对话框由外层 sheet 持有）。 */
@Composable
private fun DshImageProfilesPane(
    imageViewModel: SettingsViewModel,
    imageState: SettingsUiState,
    onBack: () -> Unit,
    onEditProfile: (ImageProviderProfile?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DshDrillHeader(onBack = onBack, title = stringResource(R.string.dsh_services_img_profiles))
        Text(
            stringResource(R.string.setimg_profile_desc),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (imageState.imageProfiles.isEmpty()) {
            Text(
                stringResource(R.string.setimg_profile_empty_hint),
                fontSize = 13.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
        imageState.imageProfiles.forEach { profile ->
            val engineId = ProviderConfigPersistence.imageProfileEngineId(profile.id)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            profile.name,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Dsh.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            profile.summary(),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Dsh.textTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (imageState.imageEngine == engineId) {
                        Text(
                            stringResource(R.string.setimg_profile_in_use),
                            fontSize = 11.sp,
                            color = Dsh.blue,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DshSheetActionButton(
                        text = stringResource(R.string.setimg_profile_use_engine),
                        enabled = profile.isConfigured,
                        onClick = { imageViewModel.selectImageEngine(engineId) },
                        modifier = Modifier.weight(1f),
                    )
                    DshSheetActionButton(
                        text = stringResource(R.string.setimg_profile_edit),
                        onClick = { onEditProfile(profile) },
                        modifier = Modifier.weight(1f),
                    )
                    DshSheetActionButton(
                        text = stringResource(R.string.setimg_profile_delete),
                        destructive = true,
                        onClick = { imageViewModel.deleteImageProfile(profile.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        DshSheetActionButton(
            text = stringResource(R.string.setimg_profile_add),
            accent = true,
            onClick = { onEditProfile(null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** 语音段：TtsSettings 的 dsh 形制——开关/音色/语速/模型/格式/自定义端点 + 连接测试。 */
@Composable
private fun DshVoiceServicesPane(
    initial: TtsSettingsValues,
    providerConfigured: Boolean,
    onBack: () -> Unit,
    onSave: (TtsSettingsValues) -> Unit,
) {
    val context = LocalContext.current
    var values by remember(initial) { mutableStateOf(initial.validated()) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save(next: TtsSettingsValues) {
        values = next
        onSave(next)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DshDrillHeader(onBack = onBack, title = stringResource(R.string.dsh_services_voice))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tts_enabled),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Dsh.textPrimary,
                )
                Text(
                    stringResource(R.string.tts_enabled_desc),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Dsh.textTertiary,
                )
            }
            Switch(
                checked = values.enabled,
                onCheckedChange = { save(values.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Dsh.blue),
            )
        }
        Text(
            stringResource(R.string.tts_voice),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        TtsSettingsValues.VOICES.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { voice ->
                    val selected = values.customVoice.isBlank() && values.voice == voice
                    DshTtsChip(
                        text = voice,
                        selected = selected,
                        onClick = { save(values.copy(voice = voice, customVoice = "")) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (values.customVoice.isNotBlank()) {
            Text(
                stringResource(R.string.tts_custom_voice_active, values.customVoice),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.blue,
            )
        }
        Text(
            stringResource(R.string.tts_speed, values.speed),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        Slider(
            value = values.speed,
            onValueChange = {
                values = values.copy(speed = it.coerceIn(TtsSettingsValues.MIN_SPEED, TtsSettingsValues.MAX_SPEED))
            },
            onValueChangeFinished = { onSave(values) },
            valueRange = TtsSettingsValues.MIN_SPEED..TtsSettingsValues.MAX_SPEED,
            colors = SliderDefaults.colors(
                thumbColor = Dsh.blue,
                activeTrackColor = Dsh.blue,
                inactiveTrackColor = Dsh.borderL2,
            ),
        )
        Text(
            stringResource(R.string.tts_model),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TtsSettingsValues.MODELS.forEach { model ->
                val selected = values.customModel.isBlank() && values.model == model
                DshTtsChip(
                    text = model,
                    selected = selected,
                    onClick = { save(values.copy(model = model, customModel = "")) },
                )
            }
        }
        Text(
            stringResource(R.string.tts_format),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TtsSettingsValues.FORMATS.forEach { format ->
                DshTtsChip(
                    text = format.uppercase(),
                    selected = values.format == format,
                    onClick = { save(values.copy(format = format)) },
                )
            }
        }
        HorizontalDivider(color = Color(0x29797E91))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.tts_custom_endpoint),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Dsh.textPrimary,
                )
                Text(
                    stringResource(R.string.tts_custom_endpoint_desc),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Dsh.textTertiary,
                )
            }
            Switch(
                checked = values.baseUrl.isNotBlank(),
                onCheckedChange = { enabled ->
                    save(values.copy(baseUrl = if (enabled) values.baseUrl.ifBlank { "https://" } else ""))
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Dsh.blue),
            )
        }
        if (values.baseUrl.isNotBlank()) {
            DshSheetField(
                values.baseUrl,
                { save(values.copy(baseUrl = it)) },
                stringResource(R.string.tts_base_url),
                singleLine = true,
            )
            DshSecretField(
                values.apiKey,
                { save(values.copy(apiKey = it)) },
                stringResource(R.string.tts_api_key),
            )
        }
        DshSheetField(
            values.customVoice,
            { save(values.copy(customVoice = it)) },
            stringResource(R.string.tts_custom_voice),
            singleLine = true,
        )
        DshSheetField(
            values.customModel,
            { save(values.copy(customModel = it)) },
            stringResource(R.string.tts_custom_model),
            singleLine = true,
        )
        // 连接测试：只测自定义 TTS 端点（跟随聊天服务商时取不到密钥，给说明文案）。
        DshSheetActionButton(
            text = stringResource(
                if (testing) R.string.setprov_testing else R.string.tts_test_connection,
            ),
            testing = testing,
            enabled = !testing,
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
                    // IllegalArgumentException（半截 URL 一点即崩），且同步 execute
                    // 会把主线程卡到超时。整体 runCatching + IO 调度兜住。
                    val status = withContext(Dispatchers.IO) {
                        runCatching {
                            val config = ProviderConfig(
                                providerType = ProviderCatalog.OPENAI_COMPATIBLE,
                                baseUrl = effective.baseUrl,
                                apiKey = effective.apiKey.takeIf(String::isNotBlank),
                            )
                            OpenAiSpeechAdapter().checkStatus(config)
                        }.getOrElse {
                            ProviderStatus(available = false, message = it.message ?: "invalid URL")
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
            modifier = Modifier.fillMaxWidth(),
        )
        testResult?.let {
            Text(
                it,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.textTertiary,
            )
        }
        if (!providerConfigured && values.baseUrl.isBlank()) {
            Text(
                stringResource(R.string.tts_provider_hint),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.errorPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** TTS 音色/模型/格式选项胶囊（未选中透明描边，选中蓝底蓝字）。 */
@Composable
private fun DshTtsChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
        color = if (selected) Dsh.blue.copy(alpha = 0.12f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            if (selected) Dsh.blue.copy(alpha = 0.4f) else Dsh.borderL2,
        ),
        modifier = modifier,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            color = if (selected) Dsh.blue else Dsh.textTertiary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 弹层动作按钮（测试/保存/删除等的统一形制：hover 底、测试转圈、save 蓝字/删除红字）。 */
@Composable
private fun DshSheetActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testing: Boolean = false,
    accent: Boolean = false,
    destructive: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
        color = Dsh.hover,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Dsh.textSecondary,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                fontSize = 13.sp,
                color = when {
                    destructive -> Dsh.errorPrimary
                    accent -> Dsh.blue
                    else -> Dsh.textSecondary
                },
            )
        }
    }
}

/** 生图引擎连通状态：一行结论 + 一行详情（设置页状态卡的弹层版）。 */
@Composable
private fun DshProviderStatusRow(status: ProviderStatus?) {
    if (status == null) return
    Text(
        stringResource(
            if (status.available) R.string.setimg_status_connected else R.string.setimg_status_failed,
        ),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = if (status.available) Dsh.statusGreen else Dsh.errorPrimary,
    )
    if (status.message.isNotBlank()) {
        Text(
            status.message,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
    }
}
