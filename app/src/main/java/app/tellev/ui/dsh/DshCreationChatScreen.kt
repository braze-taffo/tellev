package app.tellev.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    onBack: () -> Unit,
    onOpenManualEditor: () -> Unit,
    onExportJson: (ByteArray) -> Unit = {},
    onSaved: (CreationKind, String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val session = state.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 新消息/流式增长时跟随底部（与聊天一致）。
    LaunchedEffect(session?.turns?.size, state.liveOutput.length) {
        val count = (session?.turns?.size ?: 0) + if (state.busy) 1 else 0
        if (count > 0) listState.animateScrollToItem((count - 1).coerceAtLeast(0))
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
                    session.card.name.ifBlank { stringResource(R.string.crs_editor_title_character) },
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
                    canSave = session.kind == CreationKind.Character &&
                        CharacterCompletion.evaluate(session.card).canChat,
                    onManual = { showMenu = false; onOpenManualEditor() },
                    onSave = {
                        showMenu = false
                        viewModel.saveArtifact(onSaved)
                    },
                    onExportJson = {
                        showMenu = false
                        scope.launch {
                            runCatching {
                                val bytes = viewModel.exportCharacter(
                                    app.tellev.feature.creation.CharacterExportFormat.Json,
                                )
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
            items(session.turns, key = { it.hashCode() }) { turn ->
                DshCreationTurnBubble(turn = turn)
            }
            // 引导追问：气泡内大按钮（点选即发送）。
            state.pendingQuestion?.let { question ->
                item(key = "question") {
                    DshCreationAskBubble(
                        question = question,
                        enabled = !state.busy,
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

        // ── 输入框：复用 DshComposer ──
        DshComposer(
            text = inputText,
            onTextChange = { inputText = it },
            isGenerating = state.busy,
            canSend = inputText.isNotBlank() && !state.busy,
            contextRatio = null,
            onSend = {
                val text = inputText.trim()
                if (text.isNotEmpty()) {
                    viewModel.send(text)
                    inputText = ""
                }
            },
            onStop = { viewModel.cancelGeneration() },
            onOpenModelMenu = { },
            onOpenContext = { },
            onLongPressVoice = { },
            listening = false,
            onCancelVoice = { },
            statsLeft = null,
            statsRight = null,
            onStatsLeft = { },
            onStatsRight = { },
            onPickFile = { },
            onOptimize = { },
        )
        Spacer(Modifier.navigationBarsPadding())
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
                Text(
                    turn.text,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = Dsh.textPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
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
                    onClick = { if (enabled) onAnswer(option.label) },
                    shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                    color = Dsh.bgBase,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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

/** ⋮ 菜单：手动编辑 / 保存 / 导出 JSON。 */
@Composable
private fun DshCreationMenu(
    expanded: Boolean,
    busy: Boolean,
    canSave: Boolean,
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
