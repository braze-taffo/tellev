package app.tellev.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.metrics.GenerationMetricsAggregate
import java.text.DateFormat
import java.util.Date

@Composable
fun GenerationMetricsDialog(
    latest: GenerationMetrics?,
    history: List<GenerationMetrics>,
    aggregate: GenerationMetricsAggregate,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.metrics_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.metrics_summary), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.metrics_count, aggregate.sampleCount))
                Text(stringResource(R.string.metrics_average_ttft, formatNumber(aggregate.averageTtftMs), stringResource(R.string.metrics_ms)))
                Text(stringResource(R.string.metrics_average_speed, formatNumber(aggregate.averageTokensPerSecond), stringResource(R.string.metrics_tokens_second)))
                Text(stringResource(R.string.metrics_total_tokens, aggregate.totalTokens))
                Text(stringResource(R.string.metrics_cache_hit_rate, formatPercent(aggregate.cacheHitRate)))
                Text(stringResource(R.string.metrics_cached_tokens, aggregate.cachedTokens ?: stringResource(R.string.metrics_unknown)))
                Text(stringResource(R.string.metrics_total_cost, formatCost(aggregate.totalCostUsd)))

                latest?.let { metric ->
                    Text(stringResource(R.string.metrics_latest), style = MaterialTheme.typography.titleSmall)
                    MetricRow(metric)
                }
                Text(stringResource(R.string.metrics_history), style = MaterialTheme.typography.titleSmall)
                if (history.isEmpty()) {
                    Text(stringResource(R.string.metrics_empty))
                } else {
                    history.asReversed().take(20).forEach { MetricRow(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.metrics_close)) } },
    )
}

@Composable
private fun MetricRow(metric: GenerationMetrics) {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(metric.timestampMs))
    Text(
        stringResource(
            R.string.metrics_row,
            time,
            metric.model ?: stringResource(R.string.metrics_unknown),
            metric.promptTokens ?: stringResource(R.string.metrics_unknown),
            metric.completionTokens ?: stringResource(R.string.metrics_unknown),
            metric.ttftMs ?: stringResource(R.string.metrics_unknown),
            formatNumber(metric.tokensPerSecond),
            metric.cachedTokens ?: stringResource(R.string.metrics_unknown),
            formatPercent(metric.cacheHitRate),
            formatCost(metric.estimatedCostUsd),
            if (metric.isEstimate) stringResource(R.string.metrics_estimated) else stringResource(R.string.metrics_reported),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun formatNumber(value: Double?): String = value?.let { "%.2f".format(it) } ?: "-"
private fun formatPercent(value: Double?): String = value?.let { "%.2f%%".format(it * 100.0) } ?: "-"
private fun formatCost(value: Double?): String = value?.let { "$%.6f".format(it) } ?: "-"
