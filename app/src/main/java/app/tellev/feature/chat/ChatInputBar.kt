package app.tellev.feature.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import app.tellev.R
import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.util.UriUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** dsh 主题蓝：发送键与上下文圆环（DeepSeek 品牌蓝）。 */
internal val DshBlue = Color(0xFF4D6BFE)

/**
 * 语音输入状态机：长按输入框开始聆听，结果（含部分结果）回调给草稿。
 * 设备不支持语音识别或权限被拒时经 [error] 给出可读原因。
 */
internal class VoiceInputState {
    var listening by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = recognizer != null

    fun ensure(context: Context): Boolean {
        if (recognizer == null) {
            recognizer = runCatching {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } else {
                    null
                }
            }.getOrNull()
        }
        return recognizer != null
    }

    fun start(
        context: Context,
        scope: CoroutineScope,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val active = recognizer ?: return
        error = null
        active.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
            }
            override fun onError(code: Int) {
                listening = false
                onError(voiceErrorMessage(context, code))
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { active.startListening(intent) }
            .onFailure {
                listening = false
                scope.launch { onError(voiceErrorMessage(context, SpeechRecognizer.ERROR_NETWORK)) }
            }
    }

    fun stop() {
        listening = false
        runCatching { recognizer?.stopListening() }
    }

    fun release() {
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}

private fun voiceErrorMessage(context: Context, code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.chat_voice_failed, "audio")
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
        context.getString(R.string.chat_voice_failed, "permission")
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
        context.getString(R.string.chat_voice_failed, "network")
    SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.chat_voice_failed, "no-match")
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.chat_voice_failed, "timeout")
    else -> context.getString(R.string.chat_voice_failed, "code $code")
}

/**
 * dsh Composer 复刻（图一）：无框输入区 + 底部控制行
 * 左簇 [+] [盾⌄ 思考] [📎 附件]；右簇 [模型库⌄] [上下文圆环] [↑ 发送]。
 * 长按输入框语音输入；生成中发送键变停止。优化草稿/生图收进 + 菜单。
 */
@Composable
internal fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    /** 会话装载/切换进行中：发送控件置灰，草稿与附件原样保留在输入栏。 */
    isLoading: Boolean = false,
    attachments: List<Attachment>,
    bubbleAlpha: Float,
    dataRoot: java.io.File,
    imageGenAvailable: Boolean = false,
    isGeneratingImage: Boolean = false,
    imageGenStatus: String? = null,
    onGenerateImage: () -> Unit = {},
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickDocument: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOptimizeDraft: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStopImage: () -> Unit = {},
    onOpenModelPicker: () -> Unit = {},
    onOpenReasoning: () -> Unit = {},
    onOpenContext: () -> Unit = {},
    /** 上下文占用 0..1，null 不显示圆环。 */
    contextRatio: Float? = null,
    onVoiceError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canSend = chatSendEnabled(
        hasDraft = text.isNotBlank() || attachments.isNotEmpty(),
        isGenerating = isGenerating,
        isLoading = isLoading,
    )
    var showAttachMenu by remember { mutableStateOf(false) }
    val voice = remember { VoiceInputState() }
    DisposableEffect(Unit) { onDispose { voice.release() } }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && voice.ensure(context)) {
            voice.start(
                context = context,
                scope = scope,
                onPartial = { partial -> if (text.isBlank()) onTextChange(partial) },
                onFinal = { final -> onTextChange(if (text.isBlank()) final else "$text$final") },
                onError = onVoiceError,
            )
        } else {
            onVoiceError(context.getString(R.string.chat_voice_unsupported))
        }
    }
    fun startVoice() {
        if (voice.listening) {
            voice.stop()
        } else if (voice.ensure(context)) {
            if (
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                voice.start(
                    context = context,
                    scope = scope,
                    onPartial = { partial -> if (text.isBlank()) onTextChange(partial) },
                    onFinal = { final -> onTextChange(if (text.isBlank()) final else "$text$final") },
                    onError = onVoiceError,
                )
            } else {
                voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            onVoiceError(context.getString(R.string.chat_voice_unsupported))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = bubbleAlpha))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // 生图进行中的状态行（可随时用 Stop 按钮取消）。
        if (isGeneratingImage) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = imageGenStatus ?: stringResource(R.string.chat_generating_image),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onStopImage) { Text(stringResource(R.string.chat_cancel_image_gen)) }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    AttachmentChip(
                        attachment = attachment,
                        dataRoot = dataRoot,
                        onRemove = { onRemoveAttachment(attachment.id) },
                    )
                }
            }
        }

        if (voice.listening) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.chat_input_voice_listening),
                    style = MaterialTheme.typography.bodySmall,
                    color = DshBlue,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { voice.stop() }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                // 长按输入框进入语音输入（dsh 手势：长按说话）。
                .pointerInput(voice) {
                    detectTapGestures(onLongPress = { startVoice() })
                },
            minLines = 1,
            maxLines = 6,
            placeholder = {
                Text(
                    stringResource(R.string.chat_input_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
            ),
            trailingIcon = {
                if (voice.listening) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(R.string.chat_input_voice),
                        tint = DshBlue,
                    )
                }
            },
        )

        // 底部控制行：左簇工具（间距 8）……右簇（间距 3，dsh「焊在一起」）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ComposerIcon(
                    icon = Icons.Default.Add,
                    contentDescription = stringResource(R.string.chat_attach_menu_title),
                    onClick = { showAttachMenu = true },
                )
                DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_image)) },
                        leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
                        onClick = { showAttachMenu = false; onPickImage() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_video)) },
                        leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                        onClick = { showAttachMenu = false; onPickVideo() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_audio)) },
                        leadingIcon = { Icon(Icons.Default.Audiotrack, contentDescription = null) },
                        onClick = { showAttachMenu = false; onPickAudio() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_attach_document)) },
                        leadingIcon = { Icon(Icons.Default.Article, contentDescription = null) },
                        onClick = { showAttachMenu = false; onPickDocument() },
                    )
                    // dsh 没有的 tellev 功能收进 + 菜单，不占控制行。
                    if (imageGenAvailable) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_generate_image)) },
                            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                            onClick = { showAttachMenu = false; onGenerateImage() },
                        )
                    }
                    if (text.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_optimize_title)) },
                            leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null) },
                            onClick = { showAttachMenu = false; onOptimizeDraft() },
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 盾⌄：思考强度（dsh 的 DeepThink 开关位）。
            ComposerIcon(
                icon = Icons.Default.Shield,
                contentDescription = stringResource(R.string.chat_panel_reasoning),
                withChevron = true,
                onClick = onOpenReasoning,
            )
            Spacer(Modifier.width(8.dp))
            // 📎：图片附件（dsh 的文件上传位）。
            ComposerIcon(
                icon = Icons.Default.AttachFile,
                contentDescription = stringResource(R.string.chat_attach_image),
                onClick = onPickImage,
            )

            Spacer(Modifier.weight(1f))

            // 右簇：模型库⌄ / 上下文圆环 / ↑发送（焊在一起）。
            ComposerIcon(
                icon = Icons.Default.Storage,
                contentDescription = stringResource(R.string.model_picker_title),
                withChevron = true,
                onClick = onOpenModelPicker,
            )
            Spacer(Modifier.width(3.dp))
            if (contextRatio != null) {
                ContextRing(
                    ratio = contextRatio.coerceIn(0f, 1f),
                    onClick = onOpenContext,
                )
                Spacer(Modifier.width(3.dp))
            }
            if (isGenerating) {
                SendButton(
                    container = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    icon = Icons.Default.Stop,
                    contentDescription = stringResource(R.string.chat_stop_generation),
                    enabled = true,
                    onClick = onStop,
                )
            } else {
                SendButton(
                    container = if (canSend) DshBlue else DshBlue.copy(alpha = 0.35f),
                    iconTint = Color.White,
                    icon = Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.chat_send_message),
                    enabled = canSend,
                    onClick = onSend,
                )
            }
        }
    }
}

/** dsh 控制行的无底色图标键（30dp 触控区 + 20dp 线性图标，可带小 ⌄）。 */
@Composable
private fun ComposerIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    withChevron: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (withChevron) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 上下文占用圆环（dsh 右簇的 16px 环，font-size:0 只留环）。 */
@Composable
private fun ContextRing(ratio: Float, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 2.5f
            drawCircle(
                color = Color.Gray.copy(alpha = 0.25f),
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = DshBlue,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 发送/停止圆钮（dsh：34dp 蓝色圆 + 白色 ↑）。 */
@Composable
private fun SendButton(
    container: Color,
    iconTint: Color,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = iconTint,
            )
        }
    }
}

/** 附件预览：图片显示缩略图，其余类型显示文件芯片。 */
@Composable
private fun AttachmentChip(
    attachment: Attachment,
    dataRoot: java.io.File,
    onRemove: () -> Unit,
) {
    val isImage = attachment.mimeType.startsWith("image/")
    Box(modifier = Modifier.size(72.dp)) {
        if (isImage) {
            val base64 = attachment.metadata["base64"]
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.jsonPrimitive?.content
            val previewModel: Any? = base64?.let { "data:${attachment.mimeType};base64,$it" }
                ?: attachment.relativePath.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(dataRoot, it).takeIf { file -> file.isFile } }
            if (previewModel != null) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(previewModel)
                        .build(),
                    contentDescription = attachment.name,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(72.dp),
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = attachmentIcon(attachment.mimeType),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.chat_remove_attachment),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun attachmentIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("video/") -> Icons.Default.Videocam
    mimeType.startsWith("audio/") -> Icons.Default.Audiotrack
    else -> Icons.Default.Article
}

/**
 * 发送闸门：有草稿（文字或附件）且既没有在生成、也没有在装载/切换会话时才可发送。
 *
 * 装载期间业务层（ChatViewModel.sendMessage）本来就会拒绝这次发送，但那种拒绝是静默的：
 * 用户在输入栏里打完字点发送却毫无反应。这里先把控件置灰，同时不清空草稿/附件，
 * 会话就绪后用户可以直接续发。生成中走的是另一条分支（显示停止控件），此处一并覆盖。
 */
internal fun chatSendEnabled(
    hasDraft: Boolean,
    isGenerating: Boolean,
    isLoading: Boolean,
): Boolean = hasDraft && !isGenerating && !isLoading

/**
 * Build a vision attachment from a picked image URI: downsample to JPEG and store under
 * the data root instead of inlining base64 into the chat JSONL. The request adapters
 * read the file back at send time.
 */
internal suspend fun buildAttachmentFromUri(
    context: android.content.Context,
    uri: Uri,
    dataRoot: java.io.File,
): Attachment? {
    val mimeType = UriUtils.resolveMimeType(context, uri) ?: "image/jpeg"
    if (!mimeType.startsWith("image/")) return null
    val name = UriUtils.resolveDisplayName(context, uri) ?: "image.jpg"
    val bytes = UriUtils.readAndDownsample(context.contentResolver, uri) ?: return null
    val attachmentId = java.util.UUID.randomUUID().toString().substring(0, 8)
    val imageFileName = "att-${System.currentTimeMillis()}-$attachmentId.jpg"
    val relativePath = "user/images/$imageFileName"
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val imagesDir = java.io.File(dataRoot, "user/images")
        imagesDir.mkdirs()
        // 与 journal 同纪律的落盘（temp+fsync+原子改名+目录同步）：
        // 消息持久化引用此文件前先确保字节完整到盘，且中途被杀不留半文件。
        app.tellev.core.storage.DurableFileOps.write(
            java.io.File(imagesDir, imageFileName).toPath(),
            bytes,
        )
    }
    return Attachment(
        id = "att-$attachmentId",
        name = name,
        // The downsampled bytes are always JPEG regardless of the source format.
        mimeType = "image/jpeg",
        relativePath = relativePath,
        source = AttachmentSource.Chat,
        metadata = buildJsonObject {
            put("detail", JsonPrimitive("auto"))
        },
    )
}

/**
 * 非图片附件（音频/视频/文档）：限量读入后原样落盘到 user/files，
 * 作为文件-backed 附件参与消息持久化与附件栏展示。
 */
internal suspend fun buildFileAttachmentFromUri(
    context: Context,
    uri: Uri,
    dataRoot: java.io.File,
): Attachment? {
    val mimeType = UriUtils.resolveMimeType(context, uri) ?: "application/octet-stream"
    if (mimeType.startsWith("image/")) return null
    val name = UriUtils.resolveDisplayName(context, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "attachment"
    val bytes = UriUtils.readBounded(context, uri, maxBytes = 64L * 1024 * 1024) ?: return null
    val attachmentId = java.util.UUID.randomUUID().toString().substring(0, 8)
    val extension = name.substringAfterLast('.', "bin").take(8)
    val fileName = "att-${System.currentTimeMillis()}-$attachmentId.$extension"
    val relativePath = "user/files/$fileName"
    withContext(Dispatchers.IO) {
        val filesDir = java.io.File(dataRoot, "user/files")
        filesDir.mkdirs()
        app.tellev.core.storage.DurableFileOps.write(
            java.io.File(filesDir, fileName).toPath(),
            bytes,
        )
    }
    return Attachment(
        id = "att-$attachmentId",
        name = name,
        mimeType = mimeType,
        relativePath = relativePath,
        source = AttachmentSource.Chat,
        metadata = buildJsonObject {
            put("file", JsonPrimitive(relativePath))
            // 文本类附件在选取时提取内容（上限 32k 字符）：发送时由生成协调器
            // 拼进本轮 userInput，模型能直接读到文档内容，而不只是文件名。
            extractTextAttachmentContent(bytes, mimeType, name)?.let { content ->
                put("textContent", JsonPrimitive(content))
            }
        },
    )
}

/** 文本附件的单体提取上限；超出部分截断并标注。 */
internal const val TEXT_ATTACHMENT_CHAR_LIMIT = 32_000

/** 该 MIME/扩展名是否按文本附件提取内容。 */
internal fun isTextAttachment(mimeType: String, name: String): Boolean {
    if (mimeType.startsWith("text/")) return true
    if (mimeType in setOf("application/json", "application/xml", "application/javascript",
            "application/x-yaml", "application/toml")
    ) {
        return true
    }
    return name.substringAfterLast('.', "").lowercase() in
        setOf("txt", "md", "markdown", "json", "log", "csv", "yaml", "yml", "toml", "xml", "html", "js", "ts", "kt", "py")
}

/**
 * 从附件字节提取 UTF-8 文本（[isTextAttachment] 命中时）。
 * 超过 [TEXT_ATTACHMENT_CHAR_LIMIT] 截断并追加省略标注；二进制内容（含 NUL）
 * 返回 null，避免把乱码灌进提示词。
 */
internal fun extractTextAttachmentContent(
    bytes: ByteArray,
    mimeType: String,
    name: String,
): String? {
    if (!isTextAttachment(mimeType, name)) return null
    if (bytes.isEmpty()) return null
    if (bytes.take(4096).any { it == 0.toByte() }) return null
    val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    return if (decoded.length <= TEXT_ATTACHMENT_CHAR_LIMIT) {
        decoded
    } else {
        decoded.take(TEXT_ATTACHMENT_CHAR_LIMIT) + "\n…(truncated)"
    }
}
