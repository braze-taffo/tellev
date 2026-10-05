package app.tellev.ui.dsh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.R

/**
 * dsh Composer（图一）像素复刻：24dp 圆角输入卡，无框输入区，
 * 控制行左簇 [+] [盾⌄] [📎]（间距 8）、右簇 [库⌄] [圆环] [↑]（间距 3，
 * 上游「焊在一起」）。统计条为卡片外的 28px 单行（上游 stats-line 规格）。
 */
@Composable
fun DshComposer(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    contextRatio: Float?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenAttachMenu: () -> Unit,
    onOpenReasoning: () -> Unit,
    onPickImage: () -> Unit,
    onOpenModelMenu: () -> Unit,
    onOpenContext: () -> Unit,
    onLongPressVoice: () -> Unit,
    listening: Boolean,
    onCancelVoice: () -> Unit,
    statsLeft: String?,
    statsRight: String?,
    onStatsLeft: () -> Unit,
    onStatsRight: () -> Unit,
) {
    Column {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Column {
                if (listening) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MicTone()
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.chat_input_voice_listening),
                            fontSize = 13.sp,
                            color = Dsh.blue,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.chat_cancel),
                            fontSize = 13.sp,
                            color = Dsh.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onCancelVoice)
                                .padding(6.dp),
                        )
                    }
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPressVoice() }) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Dsh.textPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Dsh.blue),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty() && !listening) {
                                Text(
                                    stringResource(R.string.chat_input_placeholder),
                                    fontSize = 15.sp,
                                    color = Dsh.textTertiary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                // 控制行（上游 gap：左簇 8 / 右簇 3，行 padding 6）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DshIconKey(Icons.Default.Add, stringResource(R.string.chat_attach_menu_title), onOpenAttachMenu)
                    Spacer(Modifier.width(8.dp))
                    DshIconKey(Icons.Default.Shield, stringResource(R.string.chat_panel_reasoning), onOpenReasoning, chevron = true)
                    Spacer(Modifier.width(8.dp))
                    DshIconKey(Icons.Default.AttachFile, stringResource(R.string.chat_attach_image), onPickImage)
                    Spacer(Modifier.weight(1f))
                    DshIconKey(Icons.Default.Storage, stringResource(R.string.model_picker_title), onOpenModelMenu, chevron = true)
                    Spacer(Modifier.width(3.dp))
                    if (contextRatio != null) {
                        ContextRing16(contextRatio.coerceIn(0f, 1f), onOpenContext)
                        Spacer(Modifier.width(3.dp))
                    }
                    SendOrStop(isGenerating, canSend, onSend, onStop)
                }
            }
        }
        // 统计条（图一底部）：28px 单行居中，左组开指标、右组开上下文。
        if (statsLeft != null || statsRight != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (statsLeft != null) {
                    Text(
                        statsLeft,
                        fontSize = 12.sp,
                        color = Dsh.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onStatsLeft).padding(horizontal = 4.dp),
                    )
                }
                if (statsLeft != null && statsRight != null) {
                    Spacer(Modifier.width(14.dp))
                }
                if (statsRight != null) {
                    Text(
                        statsRight,
                        fontSize = 12.sp,
                        color = Dsh.textTertiary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onStatsRight).padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MicTone() {
    Icon(
        Icons.Default.Mic,
        contentDescription = stringResource(R.string.chat_input_voice),
        tint = Dsh.blue,
        modifier = Modifier.size(16.dp),
    )
}

/** dsh 控制行图标键：30dp 触控、20dp 线性图标、可选 12dp ⌄。 */
@Composable
fun DshIconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    chevron: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Dsh.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            if (chevron) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Dsh.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** 右簇 16dp 上下文圆环（上游「font-size:0 只留环」）。 */
@Composable
private fun ContextRing16(ratio: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val stroke = 2.4f
            drawCircle(
                color = Color.Gray.copy(alpha = 0.25f),
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Dsh.blue,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 34dp 发送/停止圆钮。 */
@Composable
private fun SendOrStop(
    isGenerating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = if (isGenerating) true else canSend
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                when {
                    isGenerating -> Dsh.textPrimary.copy(alpha = 0.16f)
                    canSend -> Dsh.blue
                    else -> Dsh.blue.copy(alpha = 0.35f)
                }
            )
            .clickable(enabled = enabled) { if (isGenerating) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isGenerating) Icons.Default.Stop else Icons.Default.ArrowUpward,
            contentDescription = stringResource(
                if (isGenerating) R.string.chat_stop_generation else R.string.chat_send_message,
            ),
            tint = if (isGenerating) Dsh.textPrimary else Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}
