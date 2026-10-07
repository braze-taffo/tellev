package app.tellev.feature.chat

import android.net.Uri
import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.model.ReasoningEffort
import app.tellev.util.UriUtils
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * 旧 UI 层删除后保留的纯逻辑助手：发送闸门、附件构建、文本附件提取、
 * 反馈常量与少量 UI 状态计算。单元测试与新建的 ui/dsh 界面共同引用。
 */

/** 发送闸门：有草稿且不在生成/装载中。 */
internal fun chatSendEnabled(
    hasDraft: Boolean,
    isGenerating: Boolean,
    isLoading: Boolean,
): Boolean = hasDraft && !isGenerating && !isLoading

/** 最后一条角色/助手回复才允许「继续生成」。 */
internal fun canContinueResponse(messages: List<ChatMessage>, index: Int): Boolean {
    if (index !in messages.indices || index != messages.lastIndex) return false
    val role = messages[index].role
    return role == MessageRole.Character || role == MessageRole.Assistant
}

/** 会话级推理档位 override（无 override = Auto）。 */
internal fun sessionReasoningEffort(state: ChatUiState): ReasoningEffort =
    ReasoningSupport.sessionOverrideFrom(state.currentSession?.metadata) ?: ReasoningEffort.Auto

/** 旧统计行（保留给单测；新 UI 用 ui/dsh 的左右分组版）。 */
internal fun chatStatsLine(state: ChatUiState): String? {
    val rounds = state.messages.count { it.role == MessageRole.User }
    val steps = state.messages.count {
        it.role == MessageRole.Character || it.role == MessageRole.Assistant
    }
    if (rounds == 0 && steps == 0) return null
    return "r$rounds s$steps"
}

/** 消息 metadata.feedback 的两个取值；再次点击同一值即清除。 */
internal const val FEEDBACK_UP = "up"
internal const val FEEDBACK_DOWN = "down"

/** 模型菜单里的一行（组头 + 模型 id）。 */
internal data class ModelOption(
    val id: String,
    val group: String,
    val isCurrent: Boolean = false,
)

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
        // 与 journal 同纪律的落盘（temp+fsync+原子改名+目录同步）。
        app.tellev.core.storage.DurableFileOps.write(
            java.io.File(imagesDir, imageFileName).toPath(),
            bytes,
        )
    }
    return Attachment(
        id = "att-$attachmentId",
        name = name,
        mimeType = "image/jpeg",
        relativePath = relativePath,
        source = AttachmentSource.Chat,
        metadata = buildJsonObject {
            put("detail", JsonPrimitive("auto"))
        },
    )
}

/**
 * 非图片附件（音频/视频/文档）：限量读入后原样落盘到 user/files。
 */
internal suspend fun buildFileAttachmentFromUri(
    context: android.content.Context,
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
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
            // 文本类附件选取时提取内容（上限 32k 字符），发送时拼进本轮 userInput。
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

/**
 * DSH 分支标题递增（increasedForkTitle）：已以 (N)/（N）结尾则 N+1，否则追加 (1)。
 */
internal fun increasedForkTitle(title: String): String {
    val match = Regex("^(.*?)\\s*[（(](\\d+)[)）]\\s*$").find(title)
    return if (match != null) {
        "${match.groupValues[1]} (${match.groupValues[2].toInt() + 1})"
    } else {
        "$title (1)"
    }
}
