package app.tellev.feature.metrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.metrics.DailyUsageSummary
import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.metrics.GenerationMetricsAggregate
import app.tellev.core.metrics.GenerationMetricsCalculator
import app.tellev.core.metrics.GenerationMetricsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.LocalDate

enum class UsageRange(val days: Int?) {
    SevenDays(7),
    ThirtyDays(30),
    Lifetime(null),
}

data class UsageStatsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val range: UsageRange = UsageRange.ThirtyDays,
    val daily: List<DailyUsageSummary> = emptyList(),
    val recentEntries: List<GenerationMetrics> = emptyList(),
    val aggregate: GenerationMetricsAggregate = GenerationMetricsAggregate(),
    val modelUsage: List<app.tellev.core.metrics.ModelUsageSummary> = emptyList(),
)

class UsageStatsViewModel(
    private val store: GenerationMetricsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UsageStatsUiState())
    val uiState: StateFlow<UsageStatsUiState> = _uiState.asStateFlow()

    init { reload() }

    fun setRange(range: UsageRange) {
        _uiState.update { it.copy(range = range) }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val daily = store.dailySummaries()
                val recent = store.load()
                val selected = selectDaily(daily, _uiState.value.range)
                val selectedEntries = selectEntries(recent, _uiState.value.range)
                UsageStatsUiState(
                    isLoading = false,
                    range = _uiState.value.range,
                    daily = selected,
                    recentEntries = selectedEntries,
                    aggregate = GenerationMetricsCalculator.aggregate(selectedEntries),
                    modelUsage = GenerationMetricsCalculator.modelUsage(selectedEntries),
                )
            }.onSuccess { _uiState.value = it }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, error = error.message ?: "读取统计失败") }
                }
        }
    }

    private fun selectDaily(values: List<DailyUsageSummary>, range: UsageRange): List<DailyUsageSummary> {
        val days = range.days ?: return values
        val cutoff = LocalDate.now().minusDays((days - 1).toLong()).toString()
        return values.filter { it.date >= cutoff }
    }

    private fun selectEntries(values: List<GenerationMetrics>, range: UsageRange): List<GenerationMetrics> {
        val days = range.days ?: return values
        val cutoff = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return values.filter { it.timestampMs >= cutoff }
    }
}

class UsageStatsViewModelFactory(private val root: Path) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UsageStatsViewModel::class.java)) {
            return UsageStatsViewModel(GenerationMetricsStore(root)) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
