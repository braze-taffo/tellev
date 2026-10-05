package app.tellev.feature.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R

/** Compact "≈9K / 1M (0%)" token formatter shared by the chip and its semantics. */
internal fun formatTokenCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}

/**
 * Chat top-bar chip mirroring the screenshot's context meter: the used/limit
 * token ratio of the last assembled prompt. Tapping it opens the full context
 * viewer; when no generation has run yet it says so instead of showing 0%.
 */
@Composable
internal fun ContextUsageChip(
    usedTokens: Int?,
    limitTokens: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (usedTokens == null) {
        stringResource(R.string.ctxchip_unavailable)
    } else {
        val percent = limitTokens?.takeIf { it > 0 }?.let { (usedTokens * 100.0 / it).toInt() }
        if (percent != null) {
            stringResource(
                R.string.ctxchip_used,
                formatTokenCount(usedTokens),
                formatTokenCount(limitTokens),
                percent,
            )
        } else {
            stringResource(R.string.ctxchip_used_no_limit, formatTokenCount(usedTokens))
        }
    }
    val description = stringResource(R.string.ctxchip_open_viewer)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics { contentDescription = "$label。$description" },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Leading icon variant used when the chip is placed next to the session title. */
@Composable
internal fun ContextUsageIcon(modifier: Modifier = Modifier) {
    Icon(
        Icons.Default.DataUsage,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}
