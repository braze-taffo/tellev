package app.tellev.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.R
import app.tellev.LocalTellevGraph
import app.tellev.core.model.Attachment
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.ReasoningFamily
import app.tellev.core.provider.ReasoningSupport
import app.tellev.feature.chat.ChatUiState
import app.tellev.feature.chat.ModelOption
import app.tellev.feature.chat.buildAttachmentFromUri
import app.tellev.feature.chat.buildFileAttachmentFromUri
import app.tellev.feature.chat.forProvider
import app.tellev.ui.PromptOptimizationDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.tellev.feature.creation.CharacterCompletion
import app.tellev.feature.creation.CreationKind
import app.tellev.feature.creation.CreationSession
import app.tellev.feature.creation.CreationTurn
import app.tellev.feature.creation.CreationUiState
import app.tellev.feature.creation.CreationViewModel
import kotlinx.coroutines.launch

/**
 * 聊天式创建主机（PRD 核心屏）：把 AI 创建角色卡搬进聊天界面。
 *
 * 与 DshChatScreen 共享同一套设计语言与交互骨架：
 *  - 消息流复用聊天气泡形制（用户右/设计师左），动作行与轮尾 pill 同位
 *  - 输入框复用 DshComposer（回车发送/换行/生成中停止/附件入口）
 *  - 生成中流式气泡复用聊天 StreamingBubble 的位置与折叠思考交互
 *  - 引导模式的 ask_user 以气泡内大按钮呈现（点选即发送，免键盘）
 *  - 顶部常驻角色卡预览卡（DshCharacterCardPreview），草稿变化实时刷新
 *
 * 引擎层不改动：全部事件走既有 CreationViewModel（多轮会话/断点/工具回显）。
 */
@Composable
fun DshCreationChatScreen(
    viewModel: CreationViewModel,
    modelState: ChatUiState,
    onRefreshModels: () -> Unit,
    onSelectModel: (String, String, (Boolean) -> Unit) -> Boolean,
    onBack: () -> Unit,
    onOpenManualEditor: () -> Unit,
    onExportJson: (ByteArray) -> Unit = {},
    onSaved: (CreationKind, String) -> Unit,
    /** Declared reasoning profile for a model id; null when none is stored. */
    modelProfileOf: (String) -> app.tellev.core.provider.ModelReasoningProfile? = { null },
    /** Provider request accounting shared with the chat chain (今日/累计 sections). */
    onOpenUsageStats: () -> Unit = {},
    onAssignModelGroup: (modelId: String, groupId: String, member: Boolean) -> Unit,
    onCreateModelGroup: (label: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val session = state.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val graph = LocalTellevGraph.current
    val dataRoot = graph.dataStore.layout.root.toFile()
    var inputText by rememberSaveable(session?.id) { mutableStateOf("") }
    var pendingAttachments by rememberSaveable(session?.id, stateSaver = ChatAttachmentListSaver) {
        mutableStateOf<List<Attachment>>(emptyList())
    }
    var showMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember(session?.id) { mutableStateOf(false) }
    var showUsagePanel by remember(session?.id) { mutableStateOf(false) }
    var showOptimize by remember(session?.id) { mutableStateOf(false) }
    var showPresetPicker by remember(session?.id) { mutableStateOf(false) }
    var availablePresets by remember { mutableStateOf<List<app.tellev.core.model.GenerationPreset>>(emptyList()) }
    val presetCategory = app.tellev.core.provider.presetCategoryForProvider(
        modelState.providerConfig?.providerType ?: modelState.selectedProvider,
    )
    // 草案侧预设列表：与聊天同源（dataStore 预设库），但独立读取，
    // 不依赖聊天页是否已加载；打开菜单时刷新一次（预设可能刚被编辑）。
    fun reloadPresets() {
        scope.launch {
            runCatching {
                graph.dataStore.listPresets().filter { it.category == presetCategory }
            }.onSuccess { availablePresets = it }
        }
    }
    var readingAttachment by remember(session?.id) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val controlsBusy = state.busy || state.isOptimizingPrompt || modelState.isSelectingModel ||
        modelState.isLoading || modelState.isGenerating || readingAttachment
    val currentModel = modelState.providerConfig?.model
    val selectedCatalog = modelState.modelCatalog.forProvider(modelState.selectedProvider)
    val providerLabel = selectedCatalog.groups.firstOrNull()?.label
        ?: modelState.selectedProvider
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !controlsBusy) {
            val sessionId = session?.id
            readingAttachment = true
            scope.launch {
                try {
                    val attachment = withContext(Dispatchers.IO) {
                        buildAttachmentFromUri(context, uri, dataRoot)
                            ?: buildFileAttachmentFromUri(context, uri, dataRoot)
                    }
                    requireNotNull(attachment) { context.getString(R.string.crs_error_read_image) }
                    val hasText = (attachment.metadata["textContent"] as? JsonPrimitive)
                        ?.contentOrNull?.isNotBlank() == true
                    require(attachment.mimeType.startsWith("image/") || hasText) {
                        context.getString(R.string.crs_attachment_unsupported)
                    }
                    if (viewModel.state.value.current?.id == sessionId) {
                        pendingAttachments = pendingAttachments + attachment
                        viewModel.clearNotice()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    viewModel.showError(context.getString(R.string.chat_attach_failed, error.message.orEmpty()))
                } finally {
                    readingAttachment = false
                }
            }
        }
    }
    DisposableEffect(session?.id) {
        onDispose { viewModel.cancelPromptOptimization() }
    }

    // 新消息/流式增长时跟随底部（与聊天一致）。轮次与忙碌边界用动画过渡；
    // token 频率的跟必须瞬时滚动——每段文本重启一次滚动动画会让列表在整段
    // 生成过程持续抖动。跟的目标文本与流式气泡同一口径（liveOutput 空时
    // 回落 liveAssistantMessage）。
    LaunchedEffect(session?.turns?.size, state.busy) {
        val count = (session?.turns?.size ?: 0) + if (state.busy) 1 else 0
        if (count > 0) listState.animateScrollToItem((count - 1).coerceAtLeast(0))
    }
    val liveDisplayText = state.liveOutput.ifBlank { state.liveAssistantMessage }
    LaunchedEffect(liveDisplayText.length) {
        if (state.busy) {
            val count = (session?.turns?.size ?: 0) + 1
            listState.scrollToItem((count - 1).coerceAtLeast(0))
        }
    }

    if (session == null) {
        Box(Modifier.fillMaxSize().background(Dsh.bgBase), contentAlignment = Alignment.Center) {
            Text(
                state.error ?: stringResource(R.string.crs_loading_draft),
                fontSize = 13.sp,
                color = if (state.error != null) Dsh.errorPrimary else Dsh.textTertiary,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(Dsh.bgBase).imePadding()) {
        // ── 顶栏：返回 + 草稿名/完成度 + ⋮（手动编辑/保存/导出） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.crs_back),
                    tint = Dsh.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    // 世界书草稿的标题在世界书名字段上；角色卡才用卡名。
                    if (session.kind == CreationKind.WorldBook) {
                        session.worldName.ifBlank { stringResource(R.string.crs_editor_title_worldbook) }
                    } else {
                        session.card.name.ifBlank { stringResource(R.string.crs_editor_title_character) }
                    },
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    creationPhaseLabel(state),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Dsh.textTertiary,
                    maxLines = 1,
                )
            }
            Box {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(999.dp))
                        .clickable { showMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        stringResource(R.string.chat_more_options),
                        tint = Dsh.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DshCreationMenu(
                    expanded = showMenu,
                    busy = state.busy,
                    // 世界书保存门槛与 CreationViewModel.checkedWorldBook 的头两条一致
                    // （世界书名 + 至少一条条目）；条目级合法性由保存时的校验与错误条兜底。
                    canSave = when (session.kind) {
                        CreationKind.Character -> CharacterCompletion.evaluate(session.card).canChat
                        CreationKind.WorldBook -> session.worldName.isNotBlank() && session.lore.isNotEmpty()
                    },
                    presetLabel = presetMenuLabel(availablePresets, session.presetId),
                    currentPresetId = session.presetId,
                    onPreset = {
                        showMenu = false
                        reloadPresets()
                        showPresetPicker = true
                    },
                    onManual = { showMenu = false; onOpenManualEditor() },
                    onSave = {
                        showMenu = false
                        viewModel.saveArtifact(onSaved)
                    },
                    onExportJson = {
                        showMenu = false
                        scope.launch {
                            runCatching {
                                val bytes = if (session.kind == CreationKind.WorldBook) {
                                    viewModel.exportWorldBook()
                                } else {
                                    viewModel.exportCharacter(
                                        app.tellev.feature.creation.CharacterExportFormat.Json,
                                    )
                                }
                                onExportJson(bytes)
                            }.onFailure { e ->
                                viewModel.showError(e.message ?: context.getString(R.string.crs_export_failed, ""))
                            }
                        }
                    },
                    onDismiss = { showMenu = false },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Dsh.borderL3))

        // ── 常驻角色卡预览（可折叠） ──
        if (session.kind == CreationKind.Character) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                DshCharacterCardPreview(
                    card = session.card,
                    coverFile = state.coverPreviewPng?.let { png ->
                        remember(state.coverPreviewPng) {
                            java.io.File.createTempFile("cover", ".png").also { it.writeBytes(png) }
                        }
                    },
                )
            }
        } else {
            // 世界书会话：草稿名 + 条目数一行摘要。
            Surface(
                shape = RoundedCornerShape(Dsh.RADIUS_LG.dp),
                color = Dsh.menuSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        session.worldName.ifBlank { stringResource(R.string.crs_editor_title_worldbook) },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Dsh.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.dsh_creation_lore_count, session.lore.size),
                        fontSize = 11.sp,
                        color = Dsh.textTertiary,
                    )
                }
            }
        }

        // ── 消息流（聊天气泡形制） ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (session.turns.isEmpty() && !state.busy) {
                item(key = "empty") { DshCreationEmptyState(onPrompt = { inputText = it }) }
            }
            items(session.turns.size, key = { index -> "turn-$index" }) { index ->
                DshCreationTurnBubble(turn = session.turns[index])
            }
            // 引导追问：气泡内大按钮（点选即发送）。
            // 等待回答期间 busy 仍为 true，「生成中」与「等待用户回答」是两种互斥语义，
            // 可点性只能由 pendingQuestion 表达，否则选项恒被禁用、点击被静默丢弃。
            state.pendingQuestion?.let { question ->
                item(key = "question") {
                    DshCreationAskBubble(
                        question = question,
                        enabled = state.pendingQuestion != null,
                        onAnswer = { label ->
                            viewModel.answerAgentQuestion(label)
                        },
                    )
                }
            }
            // 流式生成气泡（与聊天同位）。
            if (state.busy) {
                item(key = "streaming") {
                    DshCreationStreamingBubble(
                        text = state.liveOutput.ifBlank { state.liveAssistantMessage },
                        reasoning = state.liveReasoning,
                        phase = state.modelPhase,
                        providerLabel = state.providerLabel,
                    )
                }
            }
            // 下一步建议（点击只填入输入框，不发送——与聊天建议同语义）。
            if (state.suggestions.isNotEmpty() && !state.busy) {
                item(key = "suggestions") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.suggestions.forEach { suggestion ->
                            Text(
                                suggestion,
                                fontSize = 12.sp,
                                color = Dsh.blue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Dsh.blue.copy(alpha = 0.1f))
                                    .clickable { inputText = suggestion }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── 错误/信息条（聊天风格） ──
        state.error?.let { error ->
            Surface(
                color = Dsh.errorPrimary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text(
                    error,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Dsh.errorPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }

        val operationStatus = when {
            modelState.isSelectingModel -> stringResource(R.string.crs_model_saving)
            readingAttachment -> stringResource(R.string.crs_attachment_reading)
            else -> currentModel?.let { "$providerLabel · $it" }
        }
        operationStatus?.let { label ->
            Text(
                label,
                color = Dsh.textTertiary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        // ── 输入框：复用 DshComposer ──
        // 统计 pill 与聊天 dock 同口径：一旦有对话轮次就常驻显示（轮/步 +
        // 速度），而不是等首个请求完成才出现——生成中与失败轮同样可见。
        // token 数在请求完成后补上；没有任何轮次时不显示（空态干净）。
        val usage = session.usage
        val usageTotal = usage.totalTokens
        val rounds = session.turns.count { it.role == "user" }
        val steps = session.turns.count { it.role != "user" }
        val hasActivity = rounds > 0 || steps > 0 || state.busy
        val usageRight = if (usage.completedRequests > 0) {
            dshCompactTokens(usageTotal) + " tok"
        } else null
        val usageLeft = if (hasActivity) {
            val speed = usage.lastTokensPerSecond?.let {
                (if (usage.lastEstimated) "≈" else "") + "%.0f tok/s".format(it)
            }
            listOfNotNull(
                stringResource(R.string.chat_stats_round, rounds),
                stringResource(R.string.chat_stats_step, steps),
                speed,
            ).joinToString(" · ")
        } else null
        DshComposer(
            text = inputText,
            onTextChange = { inputText = it },
            isGenerating = state.busy && state.pendingQuestion == null,
            canSend = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) &&
                (!controlsBusy || state.pendingQuestion != null && !modelState.isSelectingModel && !readingAttachment),
            contextRatio = null,
            onSend = {
                if (viewModel.send(inputText.trim(), pendingAttachments)) {
                    inputText = ""
                    pendingAttachments = emptyList()
                }
            },
            onStop = { viewModel.cancelGeneration() },
            onOpenModelMenu = {
                if (!controlsBusy) {
                    showModelMenu = true
                    onRefreshModels()
                }
            },
            onOpenContext = { },
            onLongPressVoice = { },
            listening = false,
            onCancelVoice = { },
            statsLeft = usageLeft,
            statsRight = usageRight,
            onStatsLeft = { showUsagePanel = true },
            onStatsRight = { showUsagePanel = true },
            attachments = pendingAttachments.map { it.toDshPendingAttachment() },
            onRemoveAttachment = { id ->
                if ((!state.busy || state.pendingQuestion != null) && !state.isOptimizingPrompt) {
                    pendingAttachments = pendingAttachments.filterNot { it.id == id }
                }
            },
            onPickFile = { if (!controlsBusy) filePicker.launch(arrayOf("*/*")) },
            onOptimize = { if (!controlsBusy && inputText.isNotBlank()) showOptimize = true },
            toolsEnabled = !controlsBusy,
            inputLabel = stringResource(R.string.crs_input_label),
        )
        Spacer(Modifier.navigationBarsPadding())
    }
    if (showModelMenu) {
        // Same effort ladder as chat: the current model's declared profile
        // narrows the family levels; the draft stores the choice itself.
        val family = ReasoningSupport.familyFor(
            modelState.providerConfig?.providerType ?: modelState.selectedProvider,
            modelState.providerConfig?.options ?: JsonObject(emptyMap()),
        )
        val configModelId = modelState.providerConfig?.model?.takeIf(String::isNotBlank)
        val modelProfile = remember(configModelId) {
            configModelId?.let { modelId -> modelProfileOf(modelId) }
        }
        val effortLevels = remember(family, modelProfile) {
            ReasoningSupport.uiLevelsForModel(family, modelProfile)
        }
        val currentEffort = session.reasoningEffort
        val currentEffortLabel = if (family == ReasoningFamily.DeepSeek &&
            currentEffort != ReasoningEffort.Auto && currentEffort != ReasoningEffort.Off
        ) {
            stringResource(R.string.chat_reasoning_effort_on)
        } else {
            dshEffortLabel(currentEffort)
        }
        DshModelEffortMenu(
            // 模型列表 = 当前/最近兜底 + 用户自定义供应商分组（此前 groups 恒空，
            // 制卡页模型菜单里看不到 id 分组——本轮补上）。
            options = modelOptions(
                modelState,
                groupCurrent = stringResource(R.string.model_group_current),
                groupRecent = stringResource(R.string.model_group_recent),
            ),
            groups = modelState.modelGroups,
            catalog = selectedCatalog,
            currentModel = currentModel,
            currentEffort = currentEffort,
            effortLevels = effortLevels,
            currentEffortLabel = currentEffortLabel,
            modelLabel = currentModel,
            onSelectModel = { model ->
                onSelectModel(model, modelState.selectedProvider) { saved ->
                    if (saved) showModelMenu = false
                    else viewModel.showError(context.getString(R.string.chatvm_model_set_failed, model))
                }
            },
            onSelectEffort = { effort -> viewModel.setReasoningEffort(effort) },
            onRefreshCatalog = onRefreshModels,
            onAssignGroup = { modelId, groupId, member -> onAssignModelGroup(modelId, groupId, member) },
            onCreateGroup = { label -> onCreateModelGroup(label) },
            onDismiss = { if (!modelState.isSelectingModel) showModelMenu = false },
            modelsOnly = false,
            selectionEnabled = !controlsBusy,
        )
        modelState.error?.let { error ->
            LaunchedEffect(error) { viewModel.showError(error) }
        }
    }
    if (showUsagePanel) {
        // 本草稿区=账本口径（无明细缓冲截断）；今日/累计=共享明细（含聊天）。
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val todayMetrics = state.recentMetrics.filter {
            java.time.Instant.ofEpochMilli(it.timestampMs).atZone(zone).toLocalDate() == today
        }
        val buckets = app.tellev.feature.chat.ChatTokenUsageLedger.Buckets(
            uncachedInput = session.usage.uncachedInputTokens,
            cacheRead = session.usage.cacheReadTokens,
            cacheWrite = session.usage.cacheWriteTokens,
            output = session.usage.completionTokens,
        )
        DshUsagePanel(
            sessionLabel = stringResource(R.string.dsh_creation_usage_draft),
            sessionBuckets = buckets,
            sessionReasoningTokens = session.usage.reasoningTokens,
            sessionEstimated = session.usage.estimatedCount > 0,
            todayMetrics = todayMetrics,
            allMetrics = state.recentMetrics,
            detailBufferNote = stringResource(R.string.dsh_usage_buffer_note, state.recentMetrics.size),
            onClear = { viewModel.clearUsage() },
            onDismiss = { showUsagePanel = false },
        )
    }
    if (showOptimize) {
        PromptOptimizationDialog(            providerLabel = currentModel?.let { "$providerLabel · $it" },
            onRun = { options, onPreview, onDone ->
                if (modelState.isSelectingModel || modelState.isLoading || state.busy) false
                else viewModel.optimizeDraft(inputText, options, onPreview, onDone)
            },
            onCancelRun = { viewModel.cancelPromptOptimization() },
            onApply = { optimized ->
                viewModel.cancelPromptOptimization()
                inputText = optimized
                showOptimize = false
            },
            onDismiss = {
                viewModel.cancelPromptOptimization()
                showOptimize = false
            },
        )
    }
    if (showPresetPicker && session != null) {
        DshCreationPresetSheet(
            presets = availablePresets,
            currentPresetId = session.presetId,
            onSelect = { viewModel.setPreset(it) },
            onDismiss = { showPresetPicker = false },
        )
    }
}

/** 顶栏副标题：生成中给 phase，空闲给字段完成度。 */
@Composable
private fun creationPhaseLabel(state: CreationUiState): String {
    if (state.busy) {
        val phase = state.modelPhase.ifBlank { state.operationLabel }
        return if (phase.isNotBlank()) phase else stringResource(R.string.dsh_creation_phase_busy)
    }
    val session = state.current
    if (session != null && session.kind == CreationKind.Character) {
        val score = CharacterCompletion.evaluate(session.card).score
        return stringResource(R.string.dsh_creation_completion, score)
    }
    return stringResource(R.string.dsh_creation_phase_idle)
}

/** 空态：一句话引导 + 两个快捷卡。 */
@Composable
private fun DshCreationEmptyState(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.dsh_creation_empty_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Dsh.textPrimary,
        )
        Text(
            stringResource(R.string.dsh_creation_empty_hint),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
        )
        Spacer(Modifier.height(12.dp))
        val randomLabel = stringResource(R.string.dsh_creation_quick_random)
        val randomPrompt = stringResource(R.string.dsh_creation_random_prompt)
        val guideLabel = stringResource(R.string.dsh_creation_quick_guide)
        val guidePrompt = stringResource(R.string.dsh_creation_guide_prompt)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DshQuickChip(randomLabel) { onPrompt(randomPrompt) }
            DshQuickChip(guideLabel) { onPrompt(guidePrompt) }
        }
    }
}

@Composable
private fun DshQuickChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        color = Dsh.blue,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Dsh.blue.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 一条对话轮次：user 右（persona 蓝底气泡）/ agent 左（白卡无气泡）。 */
@Composable
private fun DshCreationTurnBubble(turn: CreationTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(Dsh.RADIUS_XL.dp),
                color = Dsh.userBubble,
                modifier = Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.78f),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    if (turn.text.isNotBlank()) {
                        Text(
                            turn.text,
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                    turn.attachments.forEach { attachment ->
                        Text(
                            attachment.name,
                            fontSize = 12.sp,
                            color = Dsh.textSecondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                color = Dsh.menuSurface,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                modifier = Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.84f),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.dsh_creation_designer),
                        fontSize = 11.sp,
                        color = Dsh.textTertiary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        turn.text,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = Dsh.textPrimary,
                    )
                }
            }
        }
    }
}

/** 引导追问（ask_user）：选项大按钮点选即发送。 */
@Composable
private fun DshCreationAskBubble(
    question: app.tellev.feature.creation.CreationAgentQuestion,
    enabled: Boolean,
    onAnswer: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
        color = Dsh.hover,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                question.question,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            question.options.forEach { option ->
                Surface(
                    onClick = { onAnswer(option.label) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    enabled = enabled,
                    shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                    color = Dsh.bgBase,
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(option.label, fontSize = 13.sp, color = Dsh.textPrimary)
                        if (option.description.isNotBlank()) {
                            Text(
                                option.description,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = Dsh.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 流式生成气泡：phase 标题 + 逐字正文 + 可折叠思考。 */
@Composable
private fun DshCreationStreamingBubble(
    text: String,
    reasoning: String,
    phase: String,
    providerLabel: String,
) {
    var showReasoning by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
        color = Dsh.menuSurface,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = Dsh.blue,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    phase.ifBlank { stringResource(R.string.dsh_creation_phase_busy) },
                    fontSize = 11.sp,
                    color = Dsh.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (providerLabel.isNotBlank()) {
                    Text(providerLabel, fontSize = 10.sp, color = Dsh.textTertiary)
                }
            }
            if (reasoning.isNotBlank()) {
                Text(
                    stringResource(R.string.dsh_creation_reasoning_toggle),
                    fontSize = 11.sp,
                    color = Dsh.blue,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { showReasoning = !showReasoning }
                        .padding(4.dp),
                )
                if (showReasoning) {
                    Text(
                        reasoning.takeLast(2_000),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Dsh.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (text.isNotBlank()) {
                Text(
                    text,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = Dsh.textPrimary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** ⋮ 菜单：手动编辑 / 生成预设 / 保存 / 导出 JSON。 */
@Composable
private fun DshCreationMenu(
    expanded: Boolean,
    busy: Boolean,
    canSave: Boolean,
    presetLabel: String,
    currentPresetId: String,
    onPreset: () -> Unit,
    onManual: () -> Unit,
    onSave: () -> Unit,
    onExportJson: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.dsh_creation_menu_manual), fontSize = 14.sp) },
            onClick = onManual,
        )
        // 本草案的生成预设（采样/预算）：空 id = 跟随聊天当前所选。
        androidx.compose.material3.DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.dsh_creation_menu_preset, presetLabel),
                    fontSize = 14.sp,
                    color = if (currentPresetId.isBlank()) Dsh.textSecondary else Dsh.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            enabled = !busy,
            onClick = onPreset,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.dsh_creation_menu_save), fontSize = 14.sp) },
            enabled = canSave && !busy,
            onClick = onSave,
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(stringResource(R.string.dsh_creation_menu_export), fontSize = 14.sp) },
            enabled = !busy,
            onClick = onExportJson,
        )
    }
}

/** 菜单行展示：空 id → “跟随聊天”；id 失效（预设被删）→ 也回落“跟随聊天”。 */
@Composable
private fun presetMenuLabel(presets: List<app.tellev.core.model.GenerationPreset>, presetId: String): String {
    val name = presets.firstOrNull { it.id == presetId }?.name
    return name ?: stringResource(R.string.dsh_creation_preset_follow_chat)
}

/**
 * 草案生成预设选择：跟随聊天 / 具体预设单选。与聊天“预设点选”同一数据源
 * （dataStore 预设库按服务商类目过滤）；保存即写回草案并随草案持久化。
 * 预设本身的参数编辑仍在聊天侧「调整预设」完成（同一份预设库）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DshCreationPresetSheet(
    presets: List<app.tellev.core.model.GenerationPreset>,
    currentPresetId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Dsh.menuSurface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.dsh_creation_preset_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Dsh.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                DshCreationPresetRow(
                    label = stringResource(R.string.dsh_creation_preset_follow_chat),
                    selected = currentPresetId.isBlank(),
                    onClick = { onSelect(""); onDismiss() },
                )
                presets.forEach { preset ->
                    DshCreationPresetRow(
                        label = preset.name,
                        selected = preset.id == currentPresetId,
                        onClick = { onSelect(preset.id); onDismiss() },
                    )
                }
            }
        }
    }
}

@Composable
private fun DshCreationPresetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Dsh.textPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
