package app.tellev.feature.chat

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tellev.R
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.TokenBudget
import kotlin.math.max

/** 上下文分段（真实数据）：系统提示词/世界书/对话消息 各自的估算 token。 */
internal data class ContextSegments(
    val systemTokens: Int,
    val worldTokens: Int,
    val messageTokens: Int,
    val totalTokens: Int,
    val limitTokens: Int,
)

/**
 * 从上下文快照计算分段（参考图三）：
 *  - 对话消息 = 快照里非 system 消息的估算和；
 *  - 世界书   = 命中条目回查 state.worldBooks 的正文估算和；
 *  - 系统提示词 = 总数 − 世界书 − 消息（含人格/预设/记忆注入等前缀）。
 */
internal fun contextSegments(
    snapshot: ContextSnapshot?,
    worldBooks: List<app.tellev.core.model.WorldBook>,
): ContextSegments? {
    snapshot ?: return null
    val total = snapshot.estimatedTokenCount ?: return null
    val limit = snapshot.contextTokenLimit ?: 0
    val entryById = worldBooks.flatMap { it.entries }.associateBy({ it.id }, { it.content })
    val messageTokens = snapshot.messages
        .filter { it.role != MessageRole.System.name.lowercase() }
        .sumOf { TokenBudget.estimateTokens(it.content) }
    val worldTokens = snapshot.worldBookHits.sumOf { hit ->
        entryById[hit.entryId]?.let { TokenBudget.estimateTokens(it) } ?: 0
    }
    val systemTokens = max(0, total - messageTokens - worldTokens)
    return ContextSegments(
        systemTokens = systemTokens,
        worldTokens = worldTokens,
        messageTokens = messageTokens,
        totalTokens = total,
        limitTokens = limit,
    )
}

/**
 * dsh 复刻（参考图三）：锚定在输入栏数据库图标上方的小浮层——
 * 「上下文已用 N%」+ 「~已用 / 上限」，三段式细条（灰=系统提示词、紫=世界书、
 * 蓝=对话消息）与彩色方点图例（每行右侧 ~token 数）。token 为 0 的段不画。
 */
@Composable
internal fun ContextPopover(
    segments: ContextSegments?,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(0, -8),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.width(320.dp).padding(2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (segments == null) {
                    Text(
                        text = stringResource(R.string.ctxchip_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                val percent = if (segments.limitTokens > 0) {
                    (segments.totalTokens * 100L / segments.limitTokens).coerceAtMost(100)
                } else {
                    0
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = UiStrings.get(S.ctx_used_pct, "$percent%"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = UiStrings.get(
                            S.ctx_total,
                            formatTokenCount(segments.totalTokens),
                            if (segments.limitTokens > 0) formatTokenCount(segments.limitTokens) else "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))

                // 三段式细条：有 token 的段按比例分宽，剩余是浅色轨道。
                val systemColor = Color(0xFF8A8F98)
                val worldColor = Color(0xFF9B7BE8)
                val messageColor = Color(0xFF4C7FE8)
                val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                val totalWeight = max(1, segments.systemTokens + segments.worldTokens + segments.messageTokens)
                Row(
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (segments.systemTokens > 0) {
                        Box(
                            Modifier
                                .weight(segments.systemTokens.toFloat())
                                .height(6.dp)
                                .background(systemColor, RoundedCornerShape(3.dp)),
                        )
                    }
                    if (segments.worldTokens > 0) {
                        Box(
                            Modifier
                                .weight(segments.worldTokens.toFloat())
                                .height(6.dp)
                                .background(worldColor, RoundedCornerShape(3.dp)),
                        )
                    }
                    if (segments.messageTokens > 0) {
                        Box(
                            Modifier
                                .weight(segments.messageTokens.toFloat())
                                .height(6.dp)
                                .background(messageColor, RoundedCornerShape(3.dp)),
                        )
                    }
                    Box(
                        Modifier
                            .weight(max(0.5f, totalWeight * 0.02f))
                            .height(6.dp)
                            .background(trackColor, RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.height(12.dp))

                LegendRow(systemColor, UiStrings.get(S.ctx_segment_system), segments.systemTokens)
                if (segments.worldTokens > 0) {
                    Spacer(Modifier.height(8.dp))
                    LegendRow(worldColor, UiStrings.get(S.ctx_segment_world), segments.worldTokens)
                }
                Spacer(Modifier.height(8.dp))
                LegendRow(messageColor, UiStrings.get(S.ctx_segment_messages), segments.messageTokens)
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, tokens: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = UiStrings.get(S.ctx_tokens_approx, formatTokenCount(tokens)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
