package app.tellev.feature.metrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.core.metrics.DailyUsageSummary
import kotlin.math.max

@Composable
fun UsageStatsRoute(onBack: () -> Unit = {}) {
    val graph = LocalTellevGraph.current
    val usageViewModel: UsageStatsViewModel = viewModel(
        factory = UsageStatsViewModelFactory(graph.dataStore.layout.root),
    )
    UsageStatsScreen(viewModel = usageViewModel, onBack = onBack)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(viewModel: UsageStatsViewModel, onBack: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usage_stats_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", fontSize = 32.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::reload) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.usage_stats_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: stringResource(R.string.usage_stats_error))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::reload) { Text(stringResource(R.string.usage_stats_retry)) }
                }
            }
            else -> UsageStatsContent(state, viewModel::setRange, Modifier.padding(padding))
        }
    }
}

@Composable
private fun UsageStatsContent(
    state: UsageStatsUiState,
    onRangeSelected: (UsageRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            UsageRange.entries.forEach { range ->
                FilterChip(
                    selected = state.range == range,
                    onClick = { onRangeSelected(range) },
                    label = {
                        Text(stringResource(when (range) {
                            UsageRange.SevenDays -> R.string.usage_stats_7d
                            UsageRange.ThirtyDays -> R.string.usage_stats_30d
                            UsageRange.Lifetime -> R.string.usage_stats_lifetime
                        }))
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SummaryCards(state)
        Spacer(Modifier.height(16.dp))
        ActivityHeatmap(state.daily)
        Spacer(Modifier.height(16.dp))
        TrendCard(state.daily)
        Spacer(Modifier.height(16.dp))
        ModelUsageCard(state)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryCards(state: UsageStatsUiState) {
    val items = listOf(
        stringResource(R.string.usage_stats_total_tokens) to formatTokens(state.aggregate.totalTokens),
        stringResource(R.string.usage_stats_peak_tokens) to formatTokens(state.aggregate.peakGenerationTokens),
        stringResource(R.string.usage_stats_requests) to state.aggregate.sampleCount.toString(),
        stringResource(R.string.usage_stats_active_days) to state.aggregate.activeDays.toString(),
        stringResource(R.string.usage_stats_streak) to state.aggregate.currentStreakDays.toString(),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            Card(
                modifier = Modifier.width(142.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActivityHeatmap(days: List<DailyUsageSummary>) {
    val byDate = days.associateBy { it.date }
    val maxTokens = max(1L, days.maxOfOrNull { it.totalTokens } ?: 1L)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_stats_activity), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 30).forEach { offset ->
                    val date = java.time.LocalDate.now().minusDays((29 - offset).toLong()).toString()
                    val ratio = (byDate[date]?.totalTokens ?: 0L).toFloat() / maxTokens.toFloat()
                    Box(
                        Modifier.size(18.dp).background(
                            heatColor(ratio), RoundedCornerShape(4.dp),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.usage_stats_activity_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrendCard(days: List<DailyUsageSummary>) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.usage_stats_daily_trend), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (days.isEmpty()) {
                Text(stringResource(R.string.usage_stats_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                    val maxValue = max(1L, days.maxOf { it.totalTokens }).toFloat()
                    val path = Path()
                    days.forEachIndexed { index, day ->
                        val x = if (days.size == 1) size.width / 2f else size.width * index / (days.size - 1).toFloat()
                        val y = size.height - size.height * (day.totalTokens / maxValue)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, primaryColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                    days.forEachIndexed { index, day ->
                        val x = if (days.size == 1) size.width / 2f else size.width * index / (days.size - 1).toFloat()
                        val y = size.height - size.height * (day.totalTokens / maxValue)
                        drawCircle(primaryColor, radius = 5f, center = Offset(x, y))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelUsageCard(state: UsageStatsUiState) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.usage_stats_model_usage), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            if (state.modelUsage.isEmpty()) {
                Text(stringResource(R.string.usage_stats_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.modelUsage.take(8).forEach { model ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.model, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.usage_stats_model_detail, formatTokens(model.totalTokens), model.requestCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${(model.percentage * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun heatColor(ratio: Float): Color = when {
    ratio <= 0f -> Color(0xFF2A2C30)
    ratio < 0.25f -> Color(0xFF31557A)
    ratio < 0.65f -> Color(0xFF3C86C8)
    else -> Color(0xFF76B5EA)
}

private fun formatTokens(value: Long): String = when {
    value >= 100_000_000L -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000L -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}
