package app.tellev.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.generationDiagnostics
import app.tellev.core.model.reasoningParts
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.ui.CharacterAvatar
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatBubble(
    message: ChatMessage,
    character: CharacterCard?,
    characterAvatar: java.io.File?,
    dataRoot: java.io.File,
    preset: GenerationPreset?,
    userName: String,
    depth: Int,
    htmlPanelMaxHeight: Dp,
    bubbleAlpha: Float,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    var pendingDiagnosticExport by remember { mutableStateOf("") }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingDiagnosticExport
        if (uri != null) exportScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(payload) }
                }.isSuccess
            }
            android.widget.Toast.makeText(context, if (success) "已导出" else "导出失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val isUser = message.role == MessageRole.User
    var dragAmount by remember { mutableFloatStateOf(0f) }
    var showActions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isUser) {
                CharacterAvatar(
                    file = characterAvatar,
                    fallbackText = message.name,
                    modifier = Modifier.size(24.dp),
                    fallbackTextStyle = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = message.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { showActions = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "操作",
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false },
                ) {
                    if (canRegenerate) {
                        DropdownMenuItem(
                            text = { Text("重新生成") },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                onRegenerate()
                                showActions = false
                            },
                        )
                    }
                    if (message.generationDiagnostics() != null) {
                        DropdownMenuItem(
                            text = { Text("导出生成诊断") },
                            onClick = {
                                pendingDiagnosticExport = message.generationDiagnostics().toString()
                                showActions = false
                                diagnosticExport.launch("generation-diagnostics.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出原始回复") },
                            onClick = {
                                pendingDiagnosticExport = message.generationDiagnostics(includeResponse = true).toString()
                                showActions = false
                                diagnosticExport.launch("generation-response.json")
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onEdit()
                            showActions = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            onDelete()
                            showActions = false
                        },
                    )
                }
            }
        }

        val parts = message.reasoningParts()
        val renderSegments = renderMessageParts(
            parts, message.role, character, preset, userName, depth,
            includeNormal = !CharacterRegexApplier.isNormalProcessed(message),
        )
        if (!isUser && parts.body.isBlank() && parts.reasoning.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("未收到正文", modifier = Modifier.padding(8.dp))
                if (canRegenerate) TextButton(onClick = onRegenerate) { Text("重试") }
            }
        }
        val hasFrontend = renderSegments.any { it is TavernRenderSegment.Frontend }
        // 生成图片消息：解析附件里的本地图片文件（生图结果落盘于 st-data/user/images）。
        // tellev-img 日志用于真机排查生图链路（附件→路径→文件存在性）。
        val imageFiles = remember(message.id, message.attachments, dataRoot) {
            message.attachments
                .filter { it.relativePath.isNotBlank() && it.mimeType.startsWith("image/") }
                .mapNotNull { attachment ->
                    val resolved = dataRoot.resolve(attachment.relativePath)
                    android.util.Log.i(
                        "tellev-img",
                        "attachment=${attachment.name} rel=${attachment.relativePath} resolved=${resolved.path} exists=${resolved.isFile}",
                    )
                    resolved.takeIf { it.isFile }
                }
        }
        val dragModifier = Modifier.pointerInput(message.id) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        dragAmount > 80f -> onSwipeRight()
                        dragAmount < -80f -> onSwipeLeft()
                    }
                    dragAmount = 0f
                },
                onDragCancel = { dragAmount = 0f },
                onHorizontalDrag = { _, amount ->
                    dragAmount += amount
                },
            )
        }

        // 生图消息优先走图片分支：无论正文是否前端渲染，图片都必须展示。
        if (hasFrontend && !isUser && imageFiles.isEmpty()) {
            if (message.swipes.size > 1) {
                HtmlSwipeControls(
                    currentIndex = message.swipeIndex,
                    totalSwipes = message.swipes.size,
                    onPrevious = onSwipeRight,
                    onNext = onSwipeLeft,
                )
            }
            TavernMessageContent(
                segments = renderSegments,
                availableMaxHeight = htmlPanelMaxHeight,
                isUser = isUser,
                highlightDialogue = message.role != MessageRole.System,
                bubbleAlpha = bubbleAlpha,
                modifier = Modifier.fillMaxWidth(),
                tavernRuntime = tavernRuntime,
                onHtmlBoundaryDrag = onHtmlBoundaryDrag,
            )
        } else if (imageFiles.isEmpty()) {
            TavernMessageContent(
                segments = renderSegments,
                availableMaxHeight = htmlPanelMaxHeight,
                isUser = isUser,
                highlightDialogue = message.role != MessageRole.System,
                bubbleAlpha = bubbleAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    // 半透明气泡：背景图透出 40%，前端卡片分支保持无底板。
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha))
                    .then(dragModifier),
                tavernRuntime = tavernRuntime,
                onHtmlBoundaryDrag = onHtmlBoundaryDrag,
            )
        } else {
            // 生成图片消息：图片与简短文字同处一个气泡底板。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha))
                    .then(dragModifier),
            ) {
                imageFiles.forEach { file ->
                    ChatBubbleImage(file = file)
                }
                val imagePrompt = message.metadata["image_prompt"]?.jsonPrimitive?.contentOrNull
                if (!imagePrompt.isNullOrBlank()) {
                    var showPrompt by remember(message.id) { mutableStateOf(false) }
                    TextButton(onClick = { showPrompt = !showPrompt }) {
                        Text(if (showPrompt) "收起图片提示词" else "查看图片提示词")
                    }
                    if (showPrompt) {
                        SelectionContainer {
                            Text(imagePrompt, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                if (parts.body.isNotBlank()) {
                    TavernMessageContent(
                        segments = renderSegments,
                        availableMaxHeight = htmlPanelMaxHeight,
                        isUser = isUser,
                        highlightDialogue = message.role != MessageRole.System,
                        bubbleAlpha = bubbleAlpha,
                        modifier = Modifier.fillMaxWidth(),
                        tavernRuntime = tavernRuntime,
                        onHtmlBoundaryDrag = onHtmlBoundaryDrag,
                    )
                }
            }
        }

        if (message.swipes.size > 1 && (!hasFrontend || isUser)) {
            SwipeIndicator(
                currentIndex = message.swipeIndex,
                totalSwipes = message.swipes.size,
            )
        }
    }
}

@Composable
internal fun TavernMessageContent(
    segments: List<TavernRenderSegment>,
    availableMaxHeight: Dp,
    isUser: Boolean,
    highlightDialogue: Boolean,
    bubbleAlpha: Float,
    modifier: Modifier = Modifier,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
) {
    val dialogueColor = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is TavernRenderSegment.Text -> {
                    val text = segment.text
                    // AI messages with Markdown syntax render via the WebView (commonmark -> HTML),
                    // reusing TavernHtmlPanel. Plain text (user messages, short replies) stays on
                    // the cheap native Text() to avoid spinning up a WebView per bubble.
                    if (!isUser && MarkdownRenderer.looksLikeMarkdown(text)) {
                        TavernHtmlPanel(
                            html = MarkdownRenderer.render(text, highlightDialogue = highlightDialogue),
                            availableMaxHeight = availableMaxHeight,
                            dialogueQuoteColor = if (highlightDialogue) dialogueColor.toCssHex() else null,
                            tavernRuntime = tavernRuntime,
                            onBoundaryDrag = onHtmlBoundaryDrag,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = dialogueAnnotatedString(text, dialogueColor, highlightDialogue),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    top = if (index == 0) 12.dp else 8.dp,
                                    end = 12.dp,
                                    bottom = 8.dp,
                                ),
                            )
                        }
                    }
                }
                is TavernRenderSegment.Reasoning -> {
                    ReasoningBlock(
                        content = segment.content,
                        highlightDialogue = highlightDialogue,
                        bubbleAlpha = bubbleAlpha,
                    )
                }
                is TavernRenderSegment.Frontend -> {
                    TavernHtmlPanel(
                        html = segment.html,
                        availableMaxHeight = availableMaxHeight,
                        tavernRuntime = tavernRuntime,
                        onBoundaryDrag = onHtmlBoundaryDrag,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReasoningBlock(content: String, highlightDialogue: Boolean, bubbleAlpha: Float) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (expanded) "思考过程" else "思考过程 · 点击展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = dialogueAnnotatedString(
                        content,
                        MaterialTheme.colorScheme.primary,
                        highlightDialogue,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = 10.dp,
                        end = 10.dp,
                        bottom = 10.dp,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun StreamingBubble(
    text: String,
    reasoning: String,
    characterName: String,
    character: CharacterCard?,
    preset: GenerationPreset?,
    bubbleAlpha: Float,
    userName: String,
    availableMaxHeight: Dp,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
) {
    val segments = renderMessageParts(
        MessageReasoning.fromResponse(text, reasoning), MessageRole.Character,
        character, preset, userName, 0, includeNormal = true,
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = characterName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        TavernMessageContent(
            segments = segments,
            availableMaxHeight = availableMaxHeight,
            isUser = false,
            highlightDialogue = true,
            bubbleAlpha = bubbleAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
            tavernRuntime = tavernRuntime,
            onHtmlBoundaryDrag = onHtmlBoundaryDrag,
        )
    }
}

internal fun dialogueAnnotatedString(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
): AnnotatedString = buildAnnotatedString {
    append(text)
    if (!enabled) return@buildAnnotatedString
    DialogueQuoteHighlighter.findRanges(text).forEach { range ->
        addStyle(SpanStyle(color = color), range.first, range.last + 1)
    }
}

@Composable
internal fun SwipeIndicator(
    currentIndex: Int,
    totalSwipes: Int,
) {
    Row(
        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${currentIndex + 1}/$totalSwipes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EditMessageCard(
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 10,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(text) }) {
                    Text("保存")
                }
            }
        }
    }
}

/** A generated-image file inside a chat bubble; tap to view full-screen. */
@Composable
internal fun ChatBubbleImage(file: java.io.File) {
    var showFull by remember(file) { mutableStateOf(false) }
    AsyncImage(
        model = file,
        contentDescription = "生成的图片",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { showFull = true },
    )
    if (showFull) {
        Dialog(
            onDismissRequest = { showFull = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showFull = false },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
