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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tellev.R
import app.tellev.core.model.ReasoningEffort
import app.tellev.feature.chat.ModelOption
import kotlin.math.roundToInt

/**
 * dsh 三大弹层（图二/三/四）像素复刻。
 * 弹层一律 Popup 锚定在 composer 上方：模型菜单居中 312dp；
 * 思考滑块居中 312dp；上下文分段右锚 300dp。
 */

// ── 图二：模型菜单 ─────────────────────────────────────────────

@Composable
internal fun DshModelMenu(
    options: List<ModelOption>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(312.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Dsh.textTertiary) },
                placeholder = { Text(stringResource(R.string.model_picker_search), fontSize = 15.sp, color = Dsh.textTertiary) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Dsh.bgSurface,
                    unfocusedContainerColor = Dsh.bgSurface,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            val visible = remember(options, query) {
                if (query.isBlank()) options
                else options.filter { it.id.contains(query.trim(), ignoreCase = true) }
            }
            val grouped = remember(visible) { visible.groupBy { it.group } }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(380.dp),
            ) {
                grouped.forEach { (group, rows) ->
                    item(key = "h-$group") {
                        Text(
                            group,
                            fontSize = 13.sp,
                            color = Dsh.textTertiary,
                            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(rows, key = { "${group}/${it.id}" }) { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.id) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.id,
                                fontSize = 17.sp,
                                color = Dsh.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (option.isCurrent) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Dsh.textPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 图四：思考强度滑块（bre- CSS 逐值复刻） ─────────────────────

@Composable
internal fun DshEffortSlider(
    current: ReasoningEffort,
    modelLabel: String?,
    onSelect: (ReasoningEffort) -> Unit,
    onOpenModelMenu: () -> Unit,
    onDismiss: () -> Unit,
) {
    val levels = remember { ReasoningEffort.entries }
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(312.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                EffortTrack(current, levels, onSelect)
            }
            HorizontalDivider(color = Dsh.borderL2)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(45.dp)
                    .clickable { onOpenModelMenu() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modelLabel.orEmpty(),
                    fontSize = 13.sp,
                    color = Dsh.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    dshEffortLabel(current),
                    fontSize = 12.sp,
                    color = Dsh.effortBlue,
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Dsh.textTertiary.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun EffortTrack(
    current: ReasoningEffort,
    levels: List<ReasoningEffort>,
    onCommit: (ReasoningEffort) -> Unit,
) {
    val maxIndex = (levels.size - 1).coerceAtLeast(1)
    var fraction by remember(current) {
        mutableFloatStateOf(levels.indexOf(current).coerceIn(0, maxIndex) / maxIndex.toFloat())
    }
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(levels) {
                detectTapGestures { offset ->
                    val f = (offset.x / width).coerceIn(0f, 1f)
                    onCommit(levels[(f * maxIndex).roundToInt()])
                }
            }
            .pointerInput(levels) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = it.x },
                    onDragEnd = {
                        val f = (dragged / width).coerceIn(0f, 1f)
                        onCommit(levels[(f * maxIndex).roundToInt()])
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                    fraction = (dragged / width).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
            // 深色渐变轨道（.bre-effort-track 100deg）。
            drawRoundRect(
                brush = Brush.horizontalGradient(Dsh.trackDark),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            )
            val knobX = size.width * (14f / 312f + (1f - 28f / 312f) * fraction)
            // flare（.bre-effort-flare 简化：白色径向高光）。
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.96f),
                        Color(0xFFBCBDFF).copy(alpha = 0.80f),
                        Color(0xFF6A57FF).copy(alpha = 0.50f),
                        Color.Transparent,
                    ),
                    center = androidx.compose.ui.geometry.Offset(knobX, size.height / 2f),
                    radius = size.height * 1.15f,
                ),
                radius = size.height * 1.15f,
                center = androidx.compose.ui.geometry.Offset(knobX, size.height / 2f),
            )
            // 白色圆钮 28dp（轨道 30dp → 直径比例 28/30）+ 白描边。
            drawCircle(
                color = Color.White,
                radius = size.height * 0.4667f,
                center = androidx.compose.ui.geometry.Offset(knobX, size.height / 2f),
            )
            drawCircle(
                color = Color.White,
                radius = size.height * 0.4667f + 1f,
                center = androidx.compose.ui.geometry.Offset(knobX, size.height / 2f),
                style = Stroke(width = 1.2f, cap = StrokeCap.Round),
            )
        }
    }
}

/** 滑杆上的档位短标（上游 off/minimal/…/max 风格，用 tellev 档位资源）。 */
@Composable
internal fun dshEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.Auto -> R.string.chat_reasoning_effort_auto
        ReasoningEffort.Off -> R.string.chat_reasoning_effort_off
        ReasoningEffort.Low -> R.string.chat_reasoning_effort_low
        ReasoningEffort.Medium -> R.string.chat_reasoning_effort_medium
        ReasoningEffort.High -> R.string.chat_reasoning_effort_high
        ReasoningEffort.Max -> R.string.chat_reasoning_effort_max
    },
)

// ── 图三：上下文分段弹层 ────────────────────────────────────────

@Composable
internal fun DshContextPopover(
    usedPercent: Int,
    usedLabel: String,
    limitLabel: String,
    systemTokens: Long,
    worldTokens: Long,
    messageTokens: Long,
    systemLabel: String,
    worldLabel: String,
    messagesLabel: String,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(-with(density) { 12.dp.roundToPx() }, -with(density) { 120.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ctx_usage_title, "$usedPercent%"),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Dsh.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.ctx_usage_total, usedLabel, limitLabel),
                    fontSize = 13.sp,
                    color = Dsh.textTertiary,
                )
            }
            Spacer(Modifier.height(12.dp))
            val total = (systemTokens + worldTokens + messageTokens).coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SegmentBar(Dsh.segSystem, systemTokens.toFloat() / total)
                SegmentBar(Dsh.segWorld, worldTokens.toFloat() / total)
                SegmentBar(Dsh.segMessages, messageTokens.toFloat() / total)
            }
            Spacer(Modifier.height(10.dp))
            LegendRow(Dsh.segSystem, systemLabel, systemTokens)
            LegendRow(Dsh.segWorld, worldLabel, worldTokens)
            LegendRow(Dsh.segMessages, messagesLabel, messageTokens)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentBar(color: Color, fraction: Float) {
    Box(
        modifier = Modifier
            .weight(kotlin.math.max(0.02f, fraction))
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun LegendRow(color: Color, label: String, tokens: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, color = Dsh.textPrimary, modifier = Modifier.weight(1f))
        Text("~" + dshCompactTokens(tokens), fontSize = 14.sp, color = Dsh.textSecondary)
    }
}

/** ~9K / ~1.2M / ~536。 */
internal fun dshCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000L -> (tokens / 100_000L / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}M" else "%.1fM".format(it) }
    tokens >= 1_000L -> (tokens / 100L / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}K" else "%.1fK".format(it) }
    else -> tokens.toString()
}
