package app.tellev.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R

/**
 * 回复下方的操作行（参考图一）：复制 / 点赞 / 点踩 / 分享 / 语音播放 /
 * 重新生成 / 继续生成。键序与参考图一致（复制在最前）；播放与重生成为
 * tellev 的核心操作，放在分享之后。用户消息不渲染此行。
 */
@Composable
internal fun MessageActionRow(
    isSpeaking: Boolean,
    speakEnabled: Boolean,
    canRegenerate: Boolean,
    canContinue: Boolean,
    feedback: String?,
    onSpeak: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onCopy: () -> Unit,
    onFeedback: (String) -> Unit,
    onShare: () -> Unit,
    onOpenContext: () -> Unit = {},
    overflow: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.chat_copy_message),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onFeedback(FEEDBACK_UP) }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.ThumbUp,
                contentDescription = stringResource(R.string.chat_feedback_up),
                modifier = Modifier.size(18.dp),
                tint = if (feedback == FEEDBACK_UP) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onFeedback(FEEDBACK_DOWN) }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.ThumbDown,
                contentDescription = stringResource(R.string.chat_feedback_down),
                modifier = Modifier.size(18.dp),
                tint = if (feedback == FEEDBACK_DOWN) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Share,
                contentDescription = stringResource(R.string.chat_share),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (speakEnabled) {
            IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = stringResource(
                        if (isSpeaking) R.string.tts_stop else R.string.chat_speak_current,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = if (isSpeaking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canRegenerate) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.chat_regenerate),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canContinue) {
            IconButton(onClick = onContinue, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.chat_continue),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // dsh 图一操作行末位的数据库键：打开上下文分段弹层（图三）。
        IconButton(onClick = onOpenContext, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Storage,
                contentDescription = stringResource(R.string.ctx_label_system),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        overflow?.invoke()
    }
}

/** 消息 metadata.feedback 的两个取值；再次点击同一值即清除。 */
internal const val FEEDBACK_UP = "up"
internal const val FEEDBACK_DOWN = "down"
