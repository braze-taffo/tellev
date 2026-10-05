package app.tellev.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tellev.R
import app.tellev.core.model.ReasoningEffort
import kotlin.math.roundToInt

/**
 * dsh-better-reasoning-effort 的 Composer 档位滑块（图四）复刻：
 * 深色渐变胶囊轨道（100deg #03040a→#071126→#101d4c→#302262→#5d35a0）+
 * 白色圆形滑钮 + 端部光晕；下方分隔线与「模型名 | 档位 ›」行
 * （上游 .re-model-row：名字省略，档位蓝色，点击进模型列表）。
 * 弹层锚定在输入栏上方居中，宽 min(312dp, 屏宽-32dp)。
 */
@Composable
fun ReasoningEffortSliderPopup(
    current: ReasoningEffort,
    modelLabel: String?,
    onSelect: (ReasoningEffort) -> Unit,
    onOpenModelPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val levels = ReasoningEffort.entries
    val density = LocalDensity.current
    val offsetY = -with(density) { 110.dp.roundToPx() }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, offsetY),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(312.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            EffortSliderReplica(
                current = current,
                levels = levels,
                onCommit = {
                    onSelect(it)
                    onDismiss()
                },
            )
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(45.dp)
                    .pointerInput(Unit) { detectTapGestures { onOpenModelPicker() } }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelLabel.orEmpty(),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = reasoningEffortLabel(current),
                    fontSize = 12.sp,
                    color = EffortAccentBlue,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.Gray.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** 上游配色里的档位蓝（.bre-model-row-effort / dsw-static-deepseek-500）。 */
internal val EffortAccentBlue = Color(0xFF4D70FF)

@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.Auto -> R.string.chat_reasoning_effort_auto
        ReasoningEffort.Off -> R.string.chat_reasoning_effort_off
        ReasoningEffort.Low -> R.string.chat_reasoning_effort_low
        ReasoningEffort.Medium -> R.string.chat_reasoning_effort_medium
        ReasoningEffort.High -> R.string.chat_reasoning_effort_high
        ReasoningEffort.Max -> R.string.chat_reasoning_effort_max
    },
)

@Composable
private fun EffortSliderReplica(
    current: ReasoningEffort,
    levels: List<ReasoningEffort>,
    onCommit: (ReasoningEffort) -> Unit,
) {
    val maxIndex = (levels.size - 1).coerceAtLeast(1)
    var fraction by remember(current) {
        mutableFloatStateOf(levels.indexOf(current).coerceIn(0, maxIndex) / maxIndex.toFloat())
    }
    var trackWidth by remember { mutableFloatStateOf(1f) }

    Column(modifier = Modifier.padding(14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .pointerInput(levels) {
                    fun commit(x: Float) {
                        val f = (x / trackWidth).coerceIn(0f, 1f)
                        val index = (f * maxIndex).roundToInt()
                        onCommit(levels[index])
                    }
                    detectTapGestures { commit(it.x) }
                }
                .pointerInput(levels) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = it.x },
                        onDragEnd = {
                            val f = (dragged / trackWidth).coerceIn(0f, 1f)
                            onCommit(levels[(f * maxIndex).roundToInt()])
                        },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                        fraction = (dragged / trackWidth).coerceIn(0f, 1f)
                    }
                }
                .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                // 深色渐变轨道（上游 .bre-effort-track 的 100deg 五段色）。
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF03040A), Color(0xFF071126), Color(0xFF101D4C),
                            Color(0xFF302262), Color(0xFF5D35A0),
                        ),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                )
                // 滑钮位置的高光 flare：白色椭圆渐变（上游 .bre-effort-flare 的简化）。
                val knobX = size.width * (0.03f + 0.94f * fraction)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.96f),
                            Color(0xFFBCBDFF).copy(alpha = 0.80f),
                            Color(0xFF6A57FF).copy(alpha = 0.50f),
                            Color.Transparent,
                        ),
                        center = Offset(knobX, size.height / 2f),
                        radius = size.height * 1.15f,
                    ),
                    radius = size.height * 1.15f,
                    center = Offset(knobX, size.height / 2f),
                )
                // 白色滑钮（28dp 等比：轨道高 30dp 时直径 28dp）+ 描边光晕。
                drawCircle(
                    color = Color.White,
                    radius = size.height * 0.4667f,
                    center = Offset(knobX, size.height / 2f),
                )
                drawCircle(
                    color = Color.White,
                    radius = size.height * 0.4667f + 1f,
                    center = Offset(knobX, size.height / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.2f,
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
    }
}
