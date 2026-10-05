package app.tellev.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.prompt.TokenBudget

/** One context-budget segment: label, estimated tokens and share colour. */
internal data class ContextSegment(
    val label: String,
    val tokens: Int,
)

/**
 * Bottom-sheet rewarming of the context viewer: a single segmented bar plus the
 * three contributes the screenshot shows (system prompt / tool definitions /
 * conversation), then the deep links that already exist.
 *
 * Token splits are estimates produced by the same [TokenBudget] estimator the
 * prompt engine uses; the total line stays the snapshot's own reported estimate
 * so the two never disagree about the headline number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextBudgetSheet(
    snapshot: ContextSnapshot?,
    onDismiss: () -> Unit,
    onOpenFullViewer: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val segments = remember(snapshot) { segmentsOf(snapshot) }
    val estimatedTotal = segments.sumOf { it.tokens }.coerceAtLeast(0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.ctxsheet_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(4.dp))
            val used = snapshot?.estimatedTokenCount
            val limit = snapshot?.contextTokenLimit
            Text(
                when {
                    used == null -> stringResource(R.string.ctxchip_unavailable)
                    limit != null && limit > 0 -> stringResource(
                        R.string.ctxchip_used,
                        formatTokenCount(used),
                        formatTokenCount(limit),
                        used * 100 / limit,
                    )
                    else -> stringResource(R.string.ctxchip_used_no_limit, formatTokenCount(used))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))

            if (snapshot == null) {
                Text(
                    stringResource(R.string.ctxview_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                SegmentedBar(segments = segments, total = estimatedTotal)
                Spacer(Modifier.size(14.dp))
                segments.forEach { segment ->
                    val share = if (estimatedTotal <= 0) 0 else segment.tokens * 100 / estimatedTotal
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(segmentColor(segment.label), RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            segment.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "≈${formatTokenCount(segment.tokens)} · $share%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    stringResource(
                        R.string.ctxsheet_message_count,
                        snapshot.messages.size,
                        snapshot.worldBookHits.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.warnings.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(R.string.ctxsheet_warnings, snapshot.warnings.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenFullViewer, enabled = snapshot != null) {
                    Text(stringResource(R.string.ctxsheet_open_full))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
            }
        }
    }
}

@Composable
private fun SegmentedBar(segments: List<ContextSegment>, total: Int) {
    Row(
        Modifier.fillMaxWidth().height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (total <= 0) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(7.dp),
                    ),
            )
            return@Row
        }
        segments.forEach { segment ->
            // 至少给一格可见宽度，避免占比极小的分段在条上完全消失。
            val weight = (segment.tokens.toFloat() / total.toFloat()).coerceAtLeast(0.04f)
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxWidth()
                    .background(segmentColor(segment.label), RoundedCornerShape(7.dp)),
            )
        }
    }
}

private fun segmentColor(label: String): androidx.compose.ui.graphics.Color = when (label) {
    LABEL_SYSTEM -> androidx.compose.ui.graphics.Color(0xFF31557A)
    LABEL_TOOLS -> androidx.compose.ui.graphics.Color(0xFF5A5AA8)
    else -> androidx.compose.ui.graphics.Color(0xFF3C86C8)
}

private fun segmentsOf(snapshot: ContextSnapshot?): List<ContextSegment> {
    if (snapshot == null) return emptyList()
    var system = 0
    var tools = 0
    var chat = 0
    snapshot.messages.forEach { message ->
        val tokens = TokenBudget.estimateTokens(message.content)
        when (message.role.lowercase()) {
            "system" -> system += tokens
            "tool", "function" -> tools += tokens
            else -> chat += tokens
        }
    }
    return listOf(
        ContextSegment(LABEL_SYSTEM, system),
        ContextSegment(LABEL_TOOLS, tools),
        ContextSegment(LABEL_CHAT, chat),
    )
}

private const val LABEL_SYSTEM = "系统提示词"
private const val LABEL_TOOLS = "工具定义"
private const val LABEL_CHAT = "对话消息"
