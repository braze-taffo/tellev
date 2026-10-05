package app.tellev.core.metrics

import app.tellev.core.prompt.TokenBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.io.path.createDirectories

/** One completed text generation and its provider/local accounting. */
@Serializable
data class GenerationMetrics(
    val providerType: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** Total cached input tokens; provider-specific read/create components follow. */
    val cachedTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val ttftMs: Long? = null,
    val totalMs: Long? = null,
    val tokensPerSecond: Double? = null,
    val cacheHitRate: Double? = null,
    val estimatedCostUsd: Double? = null,
    val timestampMs: Long = 0L,
    val isEstimate: Boolean = false,
)

@Serializable
data class GenerationMetricsAggregate(
    val averageTtftMs: Double? = null,
    val averageTokensPerSecond: Double? = null,
    val totalTokens: Long = 0L,
    val totalCostUsd: Double = 0.0,
    /** Token-weighted cache hit rate: sum(read tokens) / sum(full prompt tokens). */
    val cacheHitRate: Double? = null,
    val cachedTokens: Long? = null,
    val sampleCount: Int = 0,
    val peakGenerationTokens: Long = 0L,
    val activeDays: Int = 0,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
)

@Serializable
data class DailyUsageSummary(
    val date: String,
    val requestCount: Int,
    val totalTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCount: Int,
    val modelTokens: Map<String, Long> = emptyMap(),
    val modelRequests: Map<String, Int> = emptyMap(),
)

@Serializable
data class ModelUsageSummary(
    val model: String,
    val requestCount: Int,
    val totalTokens: Long,
    val percentage: Double,
    val estimatedCount: Int,
)

data class ParsedUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val cachedTokens: Int?,
    val cacheHitRate: Double?,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
)

data class ModelPricing(
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    /** Fraction of the normal input price removed for OpenAI/Gemini cached input. */
    val cachedInputDiscount: Double,
    /** Optional Anthropic cache-read price as a fraction of normal input price. */
    val cacheReadInputFactor: Double? = null,
    /** Optional Anthropic cache-creation price as a fraction of normal input price. */
    val cacheCreationInputFactor: Double? = null,
)

object GenerationMetricsCalculator {
    /**
     * Prices are deliberately a small, configurable estimate table, not billing truth.
     * Providers and deployments can change prices; callers should extend this table or
     * provide their own pricing source when exact accounting is required.
     */
    private val pricing = linkedMapOf(
        "gpt-4o" to ModelPricing(2.50, 10.00, 0.50),
        "gpt-4o-mini" to ModelPricing(0.15, 0.60, 0.50),
        "gpt-4.1" to ModelPricing(2.00, 8.00, 0.50),
        "claude-3-5-sonnet" to ModelPricing(3.00, 15.00, 0.90, cacheReadInputFactor = 0.10, cacheCreationInputFactor = 1.25),
        "claude-3-5-haiku" to ModelPricing(0.80, 4.00, 0.90, cacheReadInputFactor = 0.10, cacheCreationInputFactor = 1.25),
        "claude-3-7-sonnet" to ModelPricing(3.00, 15.00, 0.90, cacheReadInputFactor = 0.10, cacheCreationInputFactor = 1.25),
        "claude-sonnet-4" to ModelPricing(3.00, 15.00, 0.90, cacheReadInputFactor = 0.10, cacheCreationInputFactor = 1.25),
        "gemini-1.5-pro" to ModelPricing(1.25, 5.00, 0.75),
        "gemini-1.5-flash" to ModelPricing(0.075, 0.30, 0.75),
        "gemini-2.0-flash" to ModelPricing(0.10, 0.40, 0.75),
        "gemini-2.5-flash" to ModelPricing(0.30, 2.50, 0.75),
    )

    fun parseUsage(usage: JsonObject?): ParsedUsage {
        if (usage == null) return ParsedUsage(null, null, null, null, null)
        val source = (usage["usageMetadata"] as? JsonObject) ?: usage
        val openAiPrompt = source.int("prompt_tokens")
        val anthropicInput = source.int("input_tokens")
        val geminiPrompt = source.int("promptTokenCount")
        val completion = source.int("completion_tokens")
            ?: source.int("output_tokens")
            ?: source.int("candidatesTokenCount")
        val openAiCached = (source["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens")
        val anthropicRead = source.int("cache_read_input_tokens")
        val anthropicCreation = source.int("cache_creation_input_tokens")
        val anthropicCached = safeSum(anthropicRead, anthropicCreation)
        val cached = openAiCached ?: anthropicCached ?: source.int("cachedContentTokenCount")
        // Anthropic's input_tokens excludes cache read/create tokens. Normalize all
        // providers to a full prompt denominator for hit rate and total tokens.
        val prompt = when {
            anthropicInput != null || anthropicRead != null || anthropicCreation != null ->
                safeSum(anthropicInput, anthropicRead, anthropicCreation)
            else -> openAiPrompt ?: geminiPrompt
        }
        val reportedTotal = source.int("total_tokens") ?: source.int("totalTokenCount")
        val isAnthropic = anthropicInput != null || anthropicRead != null || anthropicCreation != null
        val total = if (isAnthropic) safeSum(prompt, completion) else reportedTotal ?: safeSum(prompt, completion)
        val cacheHit = if (cached != null && prompt != null && prompt > 0) {
            (if (anthropicRead != null) anthropicRead else cached).toDouble() / prompt
        } else null
        return ParsedUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            cachedTokens = cached,
            cacheHitRate = cacheHit?.coerceIn(0.0, 1.0),
            cacheReadTokens = anthropicRead,
            cacheCreationTokens = anthropicCreation,
        )
    }

    fun estimateCostUsd(
        model: String?,
        promptTokens: Int?,
        completionTokens: Int?,
        cachedTokens: Int?,
        cacheReadTokens: Int? = null,
        cacheCreationTokens: Int? = null,
    ): Double? {
        val modelName = model?.lowercase()?.let { name ->
            pricing.keys.sortedByDescending(String::length).firstOrNull { key ->
                name == key || name.startsWith("$key-") || name.startsWith("$key/")
            }
        } ?: return null
        val rate = pricing.getValue(modelName)
        val prompt = promptTokens ?: return null
        val completion = completionTokens ?: return null
        val read = (cacheReadTokens ?: 0).coerceAtLeast(0)
        val creation = (cacheCreationTokens ?: 0).coerceAtLeast(0)
        val cached = (cachedTokens ?: 0).coerceIn(0, prompt)
        val boundedRead = read.coerceAtMost(prompt)
        val boundedCreation = creation.coerceAtMost((prompt - boundedRead).coerceAtLeast(0))
        val uncachedInput = (prompt - boundedRead - boundedCreation).coerceAtLeast(0)
        val readFactor = rate.cacheReadInputFactor ?: (1.0 - rate.cachedInputDiscount)
        val creationFactor = rate.cacheCreationInputFactor ?: (1.0 - rate.cachedInputDiscount)
        val inputCost = (uncachedInput * rate.inputUsdPerMillion +
            boundedRead * rate.inputUsdPerMillion * readFactor +
            boundedCreation * rate.inputUsdPerMillion * creationFactor +
            (if (read == 0 && creation == 0) cached * rate.inputUsdPerMillion * (1.0 - rate.cachedInputDiscount) else 0.0)) / 1_000_000.0
        return inputCost + completion * rate.outputUsdPerMillion / 1_000_000.0
    }

    fun tokensPerSecond(completionTokens: Int?, totalMs: Long?): Double? {
        if (completionTokens == null || totalMs == null || totalMs <= 0L) return null
        return completionTokens.toDouble() * 1000.0 / totalMs
    }

    fun ttftMs(startedAtMs: Long, firstDeltaAtMs: Long?): Long? =
        firstDeltaAtMs?.minus(startedAtMs)?.coerceAtLeast(0L)

    fun aggregate(
        entries: List<GenerationMetrics>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): GenerationMetricsAggregate {
        val ttft = entries.mapNotNull { it.ttftMs?.toDouble() }
        val speed = entries.mapNotNull { it.tokensPerSecond }
        val promptTotal = entries.mapNotNull { it.promptTokens?.toLong() }.sum()
        val cachedTotal = entries.mapNotNull { it.cacheReadTokens?.toLong() ?: it.cachedTokens?.toLong() }.sum()
        val activeDates = entries.mapTo(mutableSetOf()) { LocalDate.ofInstant(Instant.ofEpochMilli(it.timestampMs), zoneId) }
        val sortedDates = activeDates.sorted()
        val longest = longestStreak(sortedDates)
        val current = currentStreak(sortedDates, LocalDate.now(zoneId))
        return GenerationMetricsAggregate(
            averageTtftMs = ttft.averageOrNull(),
            averageTokensPerSecond = speed.averageOrNull(),
            totalTokens = entries.mapNotNull { it.totalTokens?.toLong() }.sum(),
            totalCostUsd = entries.mapNotNull { it.estimatedCostUsd }.sum(),
            cacheHitRate = if (promptTotal > 0L) (cachedTotal.toDouble() / promptTotal).coerceIn(0.0, 1.0) else null,
            cachedTokens = cachedTotal.takeIf { promptTotal > 0L },
            sampleCount = entries.size,
            peakGenerationTokens = entries.maxOfOrNull { it.totalTokens?.toLong() ?: 0L } ?: 0L,
            activeDays = activeDates.size,
            currentStreakDays = current,
            longestStreakDays = longest,
        )
    }

    fun dailyTrend(
        entries: List<GenerationMetrics>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<DailyUsageSummary> = entries.groupBy {
        LocalDate.ofInstant(Instant.ofEpochMilli(it.timestampMs), zoneId).toString()
    }.map { (date, values) ->
        DailyUsageSummary(
            date = date,
            requestCount = values.size,
            totalTokens = values.sumOf { it.totalTokens?.toLong() ?: 0L },
            promptTokens = values.sumOf { it.promptTokens?.toLong() ?: 0L },
            completionTokens = values.sumOf { it.completionTokens?.toLong() ?: 0L },
            estimatedCount = values.count { it.isEstimate },
            modelTokens = values.groupingBy { it.model?.takeIf(String::isNotBlank) ?: "unknown" }
                .fold(0L) { total, metric -> total + (metric.totalTokens?.toLong() ?: 0L) },
            modelRequests = values.groupingBy { it.model?.takeIf(String::isNotBlank) ?: "unknown" }
                .eachCount(),
        )
    }.sortedBy { it.date }

    fun modelUsage(entries: List<GenerationMetrics>): List<ModelUsageSummary> {
        val total = entries.sumOf { it.totalTokens?.toLong() ?: 0L }.coerceAtLeast(1L)
        return entries.groupBy { it.model?.takeIf(String::isNotBlank) ?: "unknown" }
            .map { (model, values) ->
                val tokens = values.sumOf { it.totalTokens?.toLong() ?: 0L }
                ModelUsageSummary(
                    model = model,
                    requestCount = values.size,
                    totalTokens = tokens,
                    percentage = tokens.toDouble() / total.toDouble(),
                    estimatedCount = values.count { it.isEstimate },
                )
            }.sortedByDescending { it.totalTokens }
    }

    private fun longestStreak(dates: List<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        var longest = 1
        var current = 1
        dates.zipWithNext().forEach { (previous, next) ->
            if (next == previous.plusDays(1)) current++ else current = 1
            longest = maxOf(longest, current)
        }
        return longest
    }

    private fun currentStreak(dates: List<LocalDate>, today: LocalDate): Int {
        if (dates.isEmpty()) return 0
        var cursor = if (dates.last() == today || dates.last() == today.minusDays(1)) dates.last() else return 0
        var count = 0
        for (date in dates.asReversed()) {
            if (date == cursor) {
                count++
                cursor = cursor.minusDays(1)
            } else if (date.isBefore(cursor)) break
        }
        return count
    }

    fun fromCompletion(
        providerType: String?,
        model: String?,
        usage: JsonObject?,
        estimatedPromptTokens: Int?,
        deltaText: String,
        startedAtMs: Long,
        firstDeltaAtMs: Long?,
        completedAtMs: Long,
        timestampMs: Long = completedAtMs,
    ): GenerationMetrics {
        val parsed = parseUsage(usage)
        val estimatedCompletion = TokenBudget.estimateTokens(deltaText).takeIf { deltaText.isNotEmpty() }
        val prompt = parsed.promptTokens ?: estimatedPromptTokens
        val completion = parsed.completionTokens ?: estimatedCompletion
        val total = parsed.totalTokens ?: if (prompt != null && completion != null) prompt + completion else null
        val cached = parsed.cachedTokens
        val totalMs = (completedAtMs - startedAtMs).coerceAtLeast(0L)
        return GenerationMetrics(
            providerType = providerType,
            model = model,
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            cachedTokens = cached,
            cacheReadTokens = parsed.cacheReadTokens,
            cacheCreationTokens = parsed.cacheCreationTokens,
            ttftMs = ttftMs(startedAtMs, firstDeltaAtMs),
            totalMs = totalMs,
            tokensPerSecond = tokensPerSecond(completion, totalMs),
            cacheHitRate = parsed.cacheHitRate ?: if (cached != null && prompt != null && prompt > 0) {
                (cached.toDouble() / prompt).coerceIn(0.0, 1.0)
            } else null,
            estimatedCostUsd = estimateCostUsd(
                model = model,
                promptTokens = prompt,
                completionTokens = completion,
                cachedTokens = cached,
                cacheReadTokens = parsed.cacheReadTokens,
                cacheCreationTokens = parsed.cacheCreationTokens,
            ),
            timestampMs = timestampMs,
            isEstimate = usage == null || parsed.promptTokens == null || parsed.completionTokens == null,
        )
    }

    private fun JsonObject.int(key: String): Int? = runCatching {
        val primitive = this[key] as? kotlinx.serialization.json.JsonPrimitive ?: return@runCatching null
        if (primitive.isString) return@runCatching null
        val value = primitive.content.toLongOrNull() ?: return@runCatching null
        value.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }.getOrNull()

    private fun safeSum(vararg values: Int?): Int? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        val sum = present.fold(0L) { acc, value -> acc + value }
        return sum.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    }

    private fun List<Double>.averageOrNull(): Double? = takeIf { isNotEmpty() }?.average()
}

/** A bounded, process-safe JSONL history under the existing st-data root. */
class GenerationMetricsStore(
    private val root: Path,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val file: Path = root.resolve(FILE_NAME)
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow<List<GenerationMetrics>>(emptyList())
    val entries: Flow<List<GenerationMetrics>> = mutableEntries.asStateFlow()

    suspend fun load(): List<GenerationMetrics> = mutex.withLock {
        val loaded = withContext(Dispatchers.IO) {
            if (!Files.isRegularFile(file)) return@withContext emptyList()
            Files.readAllLines(file).mapNotNull { line ->
                runCatching { json.decodeFromString(GenerationMetrics.serializer(), line) }.getOrNull()
            }.takeLast(maxEntries)
        }
        mutableEntries.value = loaded
        loaded
    }

    suspend fun dailySummaries(): List<DailyUsageSummary> = mutex.withLock {
        withContext(Dispatchers.IO) {
            readDailySummaries()
        }
    }

    suspend fun record(metrics: GenerationMetrics) = mutex.withLock {
        val updated = (mutableEntries.value + metrics).takeLast(maxEntries)
        withContext(Dispatchers.IO) {
            root.createDirectories()
            writeAtomic(file, updated.map { json.encodeToString(GenerationMetrics.serializer(), it) })
            val existing = readDailySummaries().associateBy { it.date }.toMutableMap()
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(metrics.timestampMs), ZoneId.systemDefault()).toString()
            val model = metrics.model?.takeIf(String::isNotBlank) ?: "unknown"
            val previous = existing[date]
            existing[date] = DailyUsageSummary(
                date = date,
                requestCount = (previous?.requestCount ?: 0) + 1,
                totalTokens = (previous?.totalTokens ?: 0L) + (metrics.totalTokens?.toLong() ?: 0L),
                promptTokens = (previous?.promptTokens ?: 0L) + (metrics.promptTokens?.toLong() ?: 0L),
                completionTokens = (previous?.completionTokens ?: 0L) + (metrics.completionTokens?.toLong() ?: 0L),
                estimatedCount = (previous?.estimatedCount ?: 0) + if (metrics.isEstimate) 1 else 0,
                modelTokens = previous?.modelTokens.orEmpty().toMutableMap().apply {
                    this[model] = (this[model] ?: 0L) + (metrics.totalTokens?.toLong() ?: 0L)
                },
                modelRequests = previous?.modelRequests.orEmpty().toMutableMap().apply {
                    this[model] = (this[model] ?: 0) + 1
                },
            )
            writeAtomic(dailyFile, existing.values.sortedBy { it.date }.map { json.encodeToString(DailyUsageSummary.serializer(), it) })
        }
        mutableEntries.value = updated
    }

    private fun readDailySummaries(): List<DailyUsageSummary> {
        if (!Files.isRegularFile(dailyFile)) return emptyList()
        return Files.readAllLines(dailyFile).mapNotNull { line ->
            runCatching { json.decodeFromString(DailyUsageSummary.serializer(), line) }.getOrNull()
        }
    }

    private fun writeAtomic(target: Path, lines: List<String>) {
        val temporary = Files.createTempFile(root, target.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, lines, Charsets.UTF_8)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100
        const val FILE_NAME = "generation-metrics.jsonl"
        const val DAILY_FILE_NAME = "generation-metrics-daily.jsonl"
    }

    private val dailyFile: Path = root.resolve(DAILY_FILE_NAME)
}
