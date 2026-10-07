package app.tellev.ui.dsh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
 * dsh Composer 官方复刻（harness InputBar.module.css）：
 * 输入卡 = 圆角 28、底 input-major、0.5px border-l2 描边 + elevation-soft、
 * padding-top 8、文/行 gap 12；编辑面 min-height 36、14sp/24、光标蓝、无占位文字。
 * 控制行（padding 2/8/6）：左下角 [📎 附件]；右侧焊死 [模型/档位合并图标]
 * [上下文环 14px] [发送 34 圆]（gap 3）。长按输入框 = 语音输入（Initial 拦截，
 * 不与文本选择的长按冲突）。
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
    attachments: List<DshPendingAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onPickFile: () -> Unit = {},
    onOptimize: () -> Unit = {},
    onOpenModelMenu: () -> Unit,
    onOpenModelConfig: () -> Unit = {},
    onOpenContext: () -> Unit,
    onLongPressVoice: () -> Unit,
    listening: Boolean,
    onCancelVoice: () -> Unit,
    statsLeft: String?,
    statsRight: String?,
    onStatsLeft: () -> Unit,
    onStatsRight: () -> Unit,
) {
    val cardShape = RoundedCornerShape(Dsh.RADIUS_PANEL.dp)
    Column {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Surface(
                shape = cardShape,
                color = Dsh.inputMajor,
                shadowElevation = 2.dp,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (listening) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = stringResource(R.string.chat_input_voice),
                                tint = Dsh.blue,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.chat_input_voice_listening),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Dsh.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.chat_cancel),
                                fontSize = 13.sp,
                                color = Dsh.textTertiary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                                    .clickable(onClick = onCancelVoice)
                                    .padding(6.dp),
                            )
                        }
                    }
                    if (attachments.isNotEmpty()) {
                        DshAttachmentStrip(attachments, onRemoveAttachment)
                    }
                    // 编辑面：无占位文字；长按=语音（detectTapGestures 直挂输入框，
                    // 空文本时不与文本选择冲突；有文本时长按为选择，语音用键盘/重进空态）。
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 36.dp)
                            .padding(start = 14.dp, end = 8.dp)
                            .padding(vertical = 4.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { onLongPressVoice() })
                            },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Dsh.textPrimary,
                            fontSize = 14.sp,
                            lineHeight = 24.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Dsh.blue),
                        maxLines = 8,
                    )
                    // 控制行：左下角 📎；右簇 [合并图标][环][发送] gap 3。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 回形针：直接唤起系统文件选择器（不再套一层附件弹层）。
                        // 官方 composer 工具键 28dp 偏小，这里整盒 40dp、图标 20dp，
                        // 命中区够大且视觉与右侧 34dp 发送键协调。
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onPickFile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                DshIcons.Paperclip,
                                contentDescription = stringResource(R.string.dsh_attach_file),
                                tint = Dsh.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        // 优化提示词：常驻键（弹层本体已迁至设置页，这里仍可快捷打开）。
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .alpha(if (text.isNotBlank()) 1f else 0.4f)
                                .clickable(enabled = text.isNotBlank(), onClick = onOptimize),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                DshIcons.WandSparkles,
                                contentDescription = stringResource(R.string.dsh_optimize_prompt),
                                tint = Dsh.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 模型/思考档位合并图标（图一右下的模型键）。
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                                .clickable(onClick = onOpenModelMenu),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    DshIcons.Database,
                                    contentDescription = stringResource(R.string.model_picker_title),
                                    tint = Dsh.textSecondary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = Dsh.textTertiary,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(3.dp))
                        if (contextRatio != null) {
                            ContextRing(contextRatio.coerceIn(0f, 1f), onOpenContext)
                            Spacer(Modifier.width(3.dp))
                        }
                        SendOrStop(isGenerating, canSend, onSend, onStop)
                    }
                }
            }
            // dock 统计条（官方 .dock：居中、gap 12、padding-top 4；图一带小图标）。
            if (statsLeft != null || statsRight != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (statsLeft != null) {
                        StatsPill(statsLeft, Icons.Default.Speed, onStatsLeft)
                    }
                    if (statsRight != null) {
                        StatsPill(statsRight, Icons.Default.Storage, onStatsRight)
                    }
                }
            }
        }
    }
}

/** 官方 dock pill：12sp tertiary tabular、radius 999、可选 14dp 图标。 */
@Composable
private fun StatsPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Dsh.textTertiary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = Dsh.textTertiary,
        )
    }
}

/**
 * 官方 ContextMeter：svg 14×14、r=5.5、stroke 2、轨道 border-l3、进度 tertiary、
 * 圆头、-90° 起点；触发器命中 28 高、radius 8。
 */
@Composable
private fun ContextRing(ratio: Float, onClick: () -> Unit) {
    val trackColor = Dsh.borderL3
    val fillColor = Dsh.textTertiary
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            drawCircle(color = trackColor, radius = radius, style = Stroke(width = stroke))
            drawArc(
                color = fillColor,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 官方发送键：34 圆、button-info-fill 底、恒白箭头、disabled 0.4、translateY(-2px)。 */
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
            .offset(y = (-2).dp)
            .size(34.dp)
            .alpha(if (enabled || isGenerating) 1f else 0.4f)
            .clip(CircleShape)
            .background(Dsh.sendBlue)
            .clickable(enabled = enabled) { if (isGenerating) onStop() else onSend() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isGenerating) Icons.Default.Stop else Icons.Default.ArrowUpward,
            contentDescription = stringResource(
                if (isGenerating) R.string.chat_stop_generation else R.string.chat_send_message,
            ),
            tint = Color.White,
            modifier = Modifier.size(17.dp),
        )
    }
}

/** 待发附件：只用于 composer 上方的缩略条（名称 + 是否图片）。 */
data class DshPendingAttachment(
    val id: String,
    val name: String,
    val isImage: Boolean,
    val file: java.io.File? = null,
)

/** 附件条：横向滚动的小卡片，图片显示缩略图，其余显示文件名，右上角 × 移除。 */
@Composable
private fun DshAttachmentStrip(
    attachments: List<DshPendingAttachment>,
    onRemove: (String) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments.size, key = { attachments[it].id }) { index ->
            val item = attachments[index]
            Box {
                Surface(
                    shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                    color = Dsh.selector,
                    modifier = Modifier.padding(top = 6.dp, end = 6.dp),
                ) {
                    if (item.isImage && item.file != null) {
                        coil.compose.AsyncImage(
                            model = item.file,
                            contentDescription = item.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(56.dp),
                        )
                    } else {
                        Row(
                            modifier = Modifier.height(56.dp).widthIn(max = 140.dp).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(DshIcons.Paperclip, null, tint = Dsh.textTertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.name,
                                fontSize = 12.sp,
                                color = Dsh.textSecondary,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Dsh.textSecondary)
                        .clickable { onRemove(item.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        DshIcons.Close,
                        contentDescription = stringResource(R.string.dsh_attach_remove),
                        tint = Dsh.bgBase,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
