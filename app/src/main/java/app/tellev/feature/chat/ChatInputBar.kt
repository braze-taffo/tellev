package app.tellev.feature.chat

import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.util.UriUtils
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    attachments: List<Attachment>,
    bubbleAlpha: Float,
    imageGenAvailable: Boolean = false,
    isGeneratingImage: Boolean = false,
    imageGenStatus: String? = null,
    onGenerateImage: () -> Unit = {},
    onPickImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onStopImage: () -> Unit = {},
) {
    val context = LocalContext.current
    val canSend = text.isNotBlank() || attachments.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    text = imageGenStatus ?: "正在生成图片…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onStopImage) { Text("取消生图") }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    val base64 = attachment.metadata["base64"]
                        ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        ?.jsonPrimitive?.content
                    Box(modifier = Modifier.size(72.dp)) {
                        if (base64 != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data("data:${attachment.mimeType};base64,$base64")
                                    .build(),
                                contentDescription = attachment.name,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveAttachment(attachment.id) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除附件",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onPickImage,
                enabled = !isGenerating,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = "添加图片",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 生图入口：仅在生图模型已配置时出现，未配置不占位、不影响布局。
            if (imageGenAvailable) {
                IconButton(
                    onClick = onGenerateImage,
                    enabled = !isGenerating && !isGeneratingImage,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha)),
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = "生成图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 6,
                placeholder = { Text("输入消息") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                    errorContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                ),
            )

            if (isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = bubbleAlpha)),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止生成",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary.copy(alpha = bubbleAlpha)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bubbleAlpha),
                        ),
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送消息",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Build a vision attachment from a picked image URI: downsample + base64. */
internal suspend fun buildAttachmentFromUri(
    context: android.content.Context,
    uri: Uri,
): Attachment? {
    val mimeType = UriUtils.resolveMimeType(context, uri) ?: "image/jpeg"
    if (!mimeType.startsWith("image/")) return null
    val name = UriUtils.resolveDisplayName(context, uri) ?: "image.jpg"
    val bytes = UriUtils.readAndDownsample(context.contentResolver, uri) ?: return null
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return Attachment(
        id = "att-${java.util.UUID.randomUUID()}",
        name = name,
        mimeType = mimeType,
        relativePath = "",
        source = AttachmentSource.Chat,
        metadata = buildJsonObject {
            put("base64", JsonPrimitive(base64))
            put("detail", JsonPrimitive("auto"))
        },
    )
}
