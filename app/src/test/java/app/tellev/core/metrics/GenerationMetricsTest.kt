package app.tellev.core.metrics

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GenerationMetricsTest {
    @Test
    fun `parses OpenAI usage and cached prompt details`() {
        val parsed = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", 100)
            put("completion_tokens", 25)
            put("total_tokens", 125)
            put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 40) })
        })
        assertEquals(100, parsed.promptTokens)
        assertEquals(25, parsed.completionTokens)
        assertEquals(125, parsed.totalTokens)
        assertEquals(40, parsed.cachedTokens)
        assertEquals(0.4, parsed.cacheHitRate!!, 0.0001)
    }

    @Test
    fun `parses Anthropic usage and cache read plus creation`() {
        val parsed = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("input_tokens", 80)
            put("output_tokens", 20)
            put("cache_read_input_tokens", 30)
            put("cache_creation_input_tokens", 10)
        })
        assertEquals(120, parsed.promptTokens)
        assertEquals(20, parsed.completionTokens)
        assertEquals(140, parsed.totalTokens)
        assertEquals(40, parsed.cachedTokens)
        assertEquals(0.25, parsed.cacheHitRate!!, 0.0001)
        assertEquals(30, parsed.cacheReadTokens)
        assertEquals(10, parsed.cacheCreationTokens)
        assertEquals(
            0.0005865,
            GenerationMetricsCalculator.estimateCostUsd(
                model = "claude-3-5-sonnet-20241022",
                promptTokens = parsed.promptTokens,
                completionTokens = parsed.completionTokens,
                cachedTokens = parsed.cachedTokens,
                cacheReadTokens = parsed.cacheReadTokens,
                cacheCreationTokens = parsed.cacheCreationTokens,
            )!!,
            0.0000001,
        )
    }

    @Test
    fun `malformed usage values are ignored without throwing`() {
        val parsed = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", buildJsonObject { put("value", 1) })
            put("completion_tokens", buildJsonArray { add(JsonPrimitive(1)) })
            put("total_tokens", "not-a-number")
            put("prompt_tokens_details", buildJsonObject {
                put("cached_tokens", -4)
            })
        })
        assertNull(parsed.promptTokens)
        assertNull(parsed.completionTokens)
        assertNull(parsed.totalTokens)
        assertNull(parsed.cachedTokens)
        assertNull(parsed.cacheHitRate)

        val overflow = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", "999999999999999999999999")
            put("completion_tokens", -1)
            put("total_tokens", JsonNull)
        })
        assertNull(overflow.promptTokens)
        assertNull(overflow.completionTokens)
        assertNull(overflow.totalTokens)

        val missing = GenerationMetricsCalculator.parseUsage(buildJsonObject { })
        assertNull(missing.promptTokens)
        assertNull(missing.completionTokens)
        assertNull(missing.totalTokens)
    }

    @Test
    fun `parses nested Gemini usage metadata`() {
        val parsed = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("usageMetadata", buildJsonObject {
                put("promptTokenCount", 120)
                put("candidatesTokenCount", 30)
                put("totalTokenCount", 150)
                put("cachedContentTokenCount", 60)
            })
        })
        assertEquals(120, parsed.promptTokens)
        assertEquals(30, parsed.completionTokens)
        assertEquals(150, parsed.totalTokens)
        assertEquals(60, parsed.cachedTokens)
        assertEquals(0.5, parsed.cacheHitRate!!, 0.0001)
    }

    @Test
    fun `missing usage falls back to prompt estimate and delta text estimate`() {
        val metrics = GenerationMetricsCalculator.fromCompletion(
            providerType = "local",
            model = "unknown-model",
            usage = null,
            estimatedPromptTokens = 12,
            deltaText = "abcdefgh",
            startedAtMs = 1_000L,
            firstDeltaAtMs = 1_125L,
            completedAtMs = 2_125L,
        )
        assertEquals(12, metrics.promptTokens)
        assertEquals(2, metrics.completionTokens)
        assertEquals(14, metrics.totalTokens)
        assertTrue(metrics.isEstimate)
        assertEquals(125L, metrics.ttftMs)
        assertEquals(1_125L, metrics.totalMs)
        assertEquals(2_000.0 / 1_125.0, metrics.tokensPerSecond!!, 0.0001)
        assertNull(metrics.estimatedCostUsd)
    }

    @Test
    fun `calculates throughput TTFT and known model cost`() {
        assertEquals(40.0, GenerationMetricsCalculator.tokensPerSecond(80, 2_000L)!!, 0.0001)
        assertEquals(250L, GenerationMetricsCalculator.ttftMs(1_000L, 1_250L))
        assertEquals(0.00045, GenerationMetricsCalculator.estimateCostUsd("gpt-4o-mini", 1_000, 500, 0)!!, 0.0000001)
        assertNull(GenerationMetricsCalculator.estimateCostUsd("custom-model", 1_000, 500, null))
    }

    @Test
    fun `aggregates averages totals and costs`() {
        val entries = listOf(
            GenerationMetrics(ttftMs = 100, tokensPerSecond = 10.0, totalTokens = 20, promptTokens = 100, cacheReadTokens = 25, estimatedCostUsd = 0.1),
            GenerationMetrics(ttftMs = 300, tokensPerSecond = 30.0, totalTokens = 40, promptTokens = 100, cacheReadTokens = 0, estimatedCostUsd = null),
        )
        val aggregate = GenerationMetricsCalculator.aggregate(entries)
        assertEquals(200.0, aggregate.averageTtftMs!!, 0.0001)
        assertEquals(20.0, aggregate.averageTokensPerSecond!!, 0.0001)
        assertEquals(60L, aggregate.totalTokens)
        assertEquals(0.1, aggregate.totalCostUsd, 0.0001)
        assertEquals(0.125, aggregate.cacheHitRate!!, 0.0001)
        assertEquals(25L, aggregate.cachedTokens)
        assertEquals(2, aggregate.sampleCount)
    }

    @Test
    fun `daily trend groups tokens models and estimate flags`() {
        val zone = java.time.ZoneId.of("UTC")
        val day1 = java.time.LocalDate.of(2026, 10, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val day1Later = java.time.LocalDate.of(2026, 10, 1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val day2 = java.time.LocalDate.of(2026, 10, 2).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val entries = listOf(
            GenerationMetrics(timestampMs = day1, model = "a", totalTokens = 100, promptTokens = 60, completionTokens = 40, isEstimate = true),
            GenerationMetrics(timestampMs = day1Later, model = "b", totalTokens = 50, promptTokens = 30, completionTokens = 20),
            GenerationMetrics(timestampMs = day2, model = "a", totalTokens = 25, promptTokens = 10, completionTokens = 15),
        )
        val daily = GenerationMetricsCalculator.dailyTrend(entries, zone)
        assertEquals(listOf("2026-10-01", "2026-10-02"), daily.map { it.date })
        assertEquals(2, daily[0].requestCount)
        assertEquals(150L, daily[0].totalTokens)
        assertEquals(90L, daily[0].promptTokens)
        assertEquals(60L, daily[0].completionTokens)
        assertEquals(1, daily[0].estimatedCount)
        assertEquals(mapOf("a" to 100L, "b" to 50L), daily[0].modelTokens)
        assertEquals(mapOf("a" to 1, "b" to 1), daily[0].modelRequests)
        assertEquals(1, daily[1].requestCount)
        assertEquals(25L, daily[1].totalTokens)
    }

    @Test
    fun `model usage sorts by tokens and computes share`() {
        val entries = listOf(
            GenerationMetrics(model = "a", totalTokens = 300, isEstimate = false),
            GenerationMetrics(model = "b", totalTokens = 100, isEstimate = true),
            GenerationMetrics(model = "a", totalTokens = 100, isEstimate = true),
        )
        val usage = GenerationMetricsCalculator.modelUsage(entries)
        assertEquals(listOf("a", "b"), usage.map { it.model })
        assertEquals(400L, usage[0].totalTokens)
        assertEquals(2, usage[0].requestCount)
        assertEquals(1, usage[0].estimatedCount)
        assertEquals(0.8, usage[0].percentage, 0.0001)
        assertEquals(0.2, usage[1].percentage, 0.0001)
    }

    @Test
    fun `aggregate reports peak generation and activity streaks`() {
        val zone = java.time.ZoneId.of("UTC")
        fun at(day: Int) = java.time.LocalDate.of(2026, 9, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val entries = listOf(
            GenerationMetrics(timestampMs = at(1), totalTokens = 10),
            GenerationMetrics(timestampMs = at(2), totalTokens = 900),
            GenerationMetrics(timestampMs = at(3), totalTokens = 5),
            GenerationMetrics(timestampMs = at(5), totalTokens = 7),
        )
        val aggregate = GenerationMetricsCalculator.aggregate(entries, zone)
        assertEquals(900L, aggregate.peakGenerationTokens)
        assertEquals(4, aggregate.activeDays)
        // September 1..3 is a three-day run; the September 5 gap breaks it.
        assertEquals(3, aggregate.longestStreakDays)
    }

    @Test
    fun `daily rollup file survives event truncation`() = runBlocking {
        val root = Files.createTempDirectory("generation-daily-test-")
        try {
            val store = GenerationMetricsStore(root, maxEntries = 1)
            store.load()
            store.record(GenerationMetrics(timestampMs = 1_700_000_000_000L, model = "a", totalTokens = 120))
            store.record(GenerationMetrics(timestampMs = 1_700_000_360_000L, model = "a", totalTokens = 80))
            val summaries = store.dailySummaries()
            assertEquals(1, summaries.size)
            assertEquals(200L, summaries.first().totalTokens)
            assertEquals(2, summaries.first().requestCount)
            assertEquals(mapOf("a" to 200L), summaries.first().modelTokens)
            // 事件明细按上限截断为 1 条，日汇总仍保留全天累计。
            assertEquals(1, store.load().size)
            val reopened = GenerationMetricsStore(root, maxEntries = 1)
            assertEquals(200L, reopened.dailySummaries().first().totalTokens)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persists bounded JSONL and reloads it`() = runBlocking {
        val root = Files.createTempDirectory("generation-metrics-test-")
        try {
            val store = GenerationMetricsStore(root, maxEntries = 2)
            store.load()
            store.record(GenerationMetrics(timestampMs = 1, totalTokens = 1))
            store.record(GenerationMetrics(timestampMs = 2, totalTokens = 2))
            store.record(GenerationMetrics(timestampMs = 3, totalTokens = 3))
            val reloaded = GenerationMetricsStore(root, maxEntries = 2)
            assertEquals(listOf(2L, 3L), reloaded.load().map { it.timestampMs })
            assertEquals(2, Files.readAllLines(root.resolve(GenerationMetricsStore.FILE_NAME)).size)
            assertFalse(reloaded.load().isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // ── session attribution（会话归属回归） ──────────────────────────────

    @Test
    fun `session attribution round trips and legacy records stay null`() {
        val metrics = GenerationMetricsCalculator.fromCompletion(
            providerType = "deepseek", model = "deepseek-chat", usage = null,
            estimatedPromptTokens = 10, deltaText = "hi",
            startedAtMs = 0, firstDeltaAtMs = 5, completedAtMs = 100,
            sessionId = "sess-1",
        )
        assertEquals("sess-1", metrics.sessionId)
        val legacy = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(GenerationMetrics.serializer(), """{"providerType":"deepseek","timestampMs":1}""")
        assertNull(legacy.sessionId)
    }

    @Test
    fun `cached tokens are not double counted in cost`() {
        // gpt-4o: input $2.50/M, cached factor 0.5。prompt 1000 中 800 命中缓存：
        // uncached 200*2.5 + cached 800*2.5*0.5 = 1500/1e6 = 0.0015。
        // 旧实现把缓存部分再按全价计入一次，得到 0.0035。
        val cost = GenerationMetricsCalculator.estimateCostUsd("gpt-4o", 1_000, 0, 800)!!
        assertEquals(0.0015, cost, 0.0000001)
    }

    @Test
    fun `aggregateHybrid takes long-term totals from daily summaries`() {
        val zone = java.time.ZoneId.systemDefault()
        fun at(day: Int) = java.time.LocalDate.of(2026, 9, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val daily = listOf(
            DailyUsageSummary("2026-09-01", 30, 3_000, 2_000, 1_000, 0, mapOf("m1" to 3_000), mapOf("m1" to 30)),
            DailyUsageSummary("2026-09-02", 20, 2_000, 1_000, 1_000, 0, mapOf("m1" to 2_000), mapOf("m1" to 20)),
        )
        val recent = listOf(
            GenerationMetrics(model = "m1", promptTokens = 10, completionTokens = 5, totalTokens = 15, ttftMs = 100, tokensPerSecond = 10.0),
        )
        val aggregate = GenerationMetricsCalculator.aggregateHybrid(recent, daily, zone)
        // 明细缓冲只有 1 条（15 tokens）；长期总量与请求数以日汇总为准。
        assertEquals(5_000L, aggregate.totalTokens)
        assertEquals(50, aggregate.sampleCount)
        assertEquals(2, aggregate.activeDays)
        assertEquals(100.0, aggregate.averageTtftMs!!, 0.0001)

        val modelUsage = GenerationMetricsCalculator.modelUsageFromDaily(daily)
        assertEquals(1, modelUsage.size)
        assertEquals("m1", modelUsage[0].model)
        assertEquals(5_000L, modelUsage[0].totalTokens)
        assertEquals(50, modelUsage[0].requestCount)
        assertEquals(1.0, modelUsage[0].percentage, 0.0001)
    }
}
