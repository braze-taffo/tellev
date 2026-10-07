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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.generationDiagnostics
import app.tellev.core.model.reasoningParts
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.tts.TtsPlaybackState
import app.tellev.core.tts.TtsRuntime
import app.tellev.core.tts.ttsUserMessage
import app.tellev.ui.CharacterAvatar
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun TavernMessageContent(
    segments: List<TavernRenderSegment>,
    availableMaxHeight: Dp,
    isUser: Boolean,
    highlightDialogue: Boolean,
    bubbleAlpha: Float,
    chatFontSizeSp: Int,
    modifier: Modifier = Modifier,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
    centerText: Boolean = false,
) {
    val dialogueColor = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier,
        horizontalAlignment = if (centerText) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is TavernRenderSegment.Text -> {
                    val text = segment.text
                    // AI messages with Markdown syntax render via the WebView (commonmark -> HTML),
                    // reusing TavernHtmlPanel. Plain text (user messages, short replies) stays on
                    // the cheap native Text() to avoid spinning up a WebView per bubble.
                    if (!isUser && MarkdownRenderer.looksLikeMarkdown(text)) {
                        TavernHtmlPanel(
                            html = remember(text, highlightDialogue, centerText) {
                                val rendered = MarkdownRenderer.render(text, highlightDialogue = highlightDialogue)
                                if (centerText) "<div style=\"text-align:center\">$rendered</div>" else rendered
                            },
                            availableMaxHeight = availableMaxHeight,
                            dialogueQuoteColor = if (highlightDialogue) dialogueColor.toCssHex() else null,
                            baseFontSizePx = chatFontSizeSp,
                            tavernRuntime = tavernRuntime,
                            onBoundaryDrag = onHtmlBoundaryDrag,
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = dialogueAnnotatedString(text, dialogueColor, highlightDialogue),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * (chatFontSizeSp / 16f),
                                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * (chatFontSizeSp / 16f),
                                ),
                                textAlign = if (centerText) TextAlign.Center else null,
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
                        chatFontSizeSp = chatFontSizeSp,
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
internal fun ReasoningBlock(content: String, highlightDialogue: Boolean, bubbleAlpha: Float, chatFontSizeSp: Int) {
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
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (expanded) stringResource(R.string.chat_reasoning) else stringResource(R.string.chat_reasoning_expand_hint),
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
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize * (chatFontSizeSp / 16f),
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * (chatFontSizeSp / 16f),
                    ),
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
    chatFontSizeSp: Int,
    userName: String,
    availableMaxHeight: Dp,
    tavernRuntime: TavernMessageRuntime,
    onHtmlBoundaryDrag: (Float) -> Unit,
    macroContext: app.tellev.core.prompt.MacroContext? = null,
    centerText: Boolean = false,
) {
    val streamingInputs = remember(text, reasoning, character, preset, userName, macroContext) {
        RenderInputs(
            MessageReasoning.fromResponse(text, reasoning), MessageRole.Character,
            character, preset, userName, 0, includeNormal = true,
            macroContext = macroContext,
        )
    }
    val segments = rememberRenderedSegments(streamingInputs, "streaming") {
        renderMessageParts(
            MessageReasoning.fromResponse(text, reasoning), MessageRole.Character,
            character, preset, userName, 0, includeNormal = true,
            macroContext = macroContext,
        )
    }.value
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centerText) Alignment.CenterHorizontally else Alignment.Start,
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
            chatFontSizeSp = chatFontSizeSp,
            centerText = centerText,
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
internal fun EditMessageCard(
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }

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
                    Text(stringResource(R.string.chat_bubble_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(text) }) {
                    Text(stringResource(R.string.chat_bubble_save))
                }
            }
        }
    }
}

/** A chat image file inside a bubble (generated or uploaded); tap to view full-screen. */
@Composable
internal fun ChatBubbleImage(file: java.io.File) {
    var showFull by remember(file) { mutableStateOf(false) }
    // 解码预算：宽=屏宽、高=两倍屏宽。Coil 的 size(Int) 是正方形预算，
    // 竖图会被压进宽×宽的框里再上采样显示；宽高分开给才能保住竖图清晰度，
    // 同时仍约束住 1024² 大图的内存。
    val context = LocalContext.current
    val decodeWidth = context.resources.displayMetrics.widthPixels
    val decodeBudget = coil.size.Size(decodeWidth, decodeWidth * 2)
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(file)
            .size(coil.size.SizeResolver(decodeBudget))
            .build(),
        contentDescription = stringResource(R.string.chat_image_content_desc),
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
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .size(coil.size.SizeResolver(decodeBudget))
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
