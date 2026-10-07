package app.tellev.feature.chat

import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.metrics.GenerationMetricsCalculator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** dsh token-meter 语义的回归：四互斥桶、替换（重刷不重复计数）、删除扣回。 */
class ChatTokenUsageLedgerTest {

    private fun sessionMeta(vararg pairs: Pair<String, JsonObject>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun bucketsJson(uncached: Long, read: Long, write: Long = 0, output: Long): JsonObject = buildJsonObject {
        put("uncachedInput", uncached)
        put("cacheRead", read)
        put("cacheWrite", write)
        put("output", output)
    }

    @Test
    fun `session buckets read back what was written`() {
        val meta = buildJsonObject {
            put(ChatTokenUsageLedger.SESSION_KEY, bucketsJson(uncached = 100, read = 40, write = 10, output = 50))
        }
        val b = ChatTokenUsageLedger.sessionBuckets(meta)
        assertEquals(100L, b.uncachedInput)
        assertEquals(40L, b.cacheRead)
        assertEquals(10L, b.cacheWrite)
        assertEquals(50L, b.output)
        assertEquals(150L, b.billedInput)
        assertEquals(200L, b.total)
    }

    @Test
    fun `corrupt or missing ledger yields zero buckets`() {
        assertEquals(ChatTokenUsageLedger.Buckets(), ChatTokenUsageLedger.sessionBuckets(null))
        assertEquals(ChatTokenUsageLedger.Buckets(), ChatTokenUsageLedger.sessionBuckets(buildJsonObject { }))
        val broken = buildJsonObject { put(ChatTokenUsageLedger.SESSION_KEY, buildJsonObject { put("oops", 1) }) }
        assertEquals(ChatTokenUsageLedger.Buckets(), ChatTokenUsageLedger.sessionBuckets(broken))
    }

    @Test
    fun `addReplacing subtracts the previous swipe before adding the new one`() {
        val first = buildJsonObject {
            put(ChatTokenUsageLedger.SESSION_KEY, bucketsJson(uncached = 100, read = 0, write = 0, output = 50))
        }
        // Regenerate the same message: old buckets (100/0/0/50) replaced by (20/80/0/60).
        val updated = ChatTokenUsageLedger.sessionMetadataWith(
            sessionMetadata = first,
            previous = ChatTokenUsageLedger.Buckets(uncachedInput = 100, output = 50),
            next = ChatTokenUsageLedger.Buckets(uncachedInput = 20, cacheRead = 80, output = 60),
        )
        assertNotNull(updated)
        val b = ChatTokenUsageLedger.sessionBuckets(updated)
        assertEquals(20L, b.uncachedInput)
        assertEquals(80L, b.cacheRead)
        assertEquals(0L, b.cacheWrite)
        assertEquals(60L, b.output)
    }

    @Test
    fun `new message just adds`() {
        val empty = buildJsonObject { }
        val updated = ChatTokenUsageLedger.sessionMetadataWith(
            sessionMetadata = empty,
            previous = ChatTokenUsageLedger.Buckets(),
            next = ChatTokenUsageLedger.Buckets(uncachedInput = 10, output = 5),
        )
        assertEquals(15L, ChatTokenUsageLedger.sessionBuckets(updated).total)
    }

    @Test
    fun `replacement with identical usage is a no-op`() {
        val meta = buildJsonObject {
            put(ChatTokenUsageLedger.SESSION_KEY, bucketsJson(uncached = 10, read = 0, write = 0, output = 5))
        }
        val same = ChatTokenUsageLedger.Buckets(uncachedInput = 10, output = 5)
        assertNull(
            ChatTokenUsageLedger.sessionMetadataWith(
                sessionMetadata = meta,
                previous = same,
                next = same,
            ),
        )
    }

    @Test
    fun `estimated metrics contribute zero buckets`() {
        val estimated = GenerationMetrics(
            promptTokens = 1234,
            completionTokens = 10,
            totalTokens = 1244,
            isEstimate = true,
        )
        assertTrue(ChatTokenUsageLedger.bucketsOf(estimated).isZero())
    }

    @Test
    fun `reported metrics split into disjoint buckets`() {
        val reported = GenerationMetrics(
            promptTokens = 1000,
            completionTokens = 200,
            totalTokens = 1200,
            cacheReadTokens = 600,
            cacheCreationTokens = 100,
            isEstimate = false,
        )
        val b = ChatTokenUsageLedger.bucketsOf(reported)
        assertEquals(300L, b.uncachedInput)
        assertEquals(600L, b.cacheRead)
        assertEquals(100L, b.cacheWrite)
        assertEquals(200L, b.output)
    }

    @Test
    fun `message metadata round-trips`() {
        val meta = ChatTokenUsageLedger.messageMetadataWith(
            buildJsonObject { },
            ChatTokenUsageLedger.Buckets(uncachedInput = 1, cacheRead = 2, cacheWrite = 3, output = 4),
        )
        assertEquals(
            ChatTokenUsageLedger.Buckets(uncachedInput = 1, cacheRead = 2, cacheWrite = 3, output = 4),
            ChatTokenUsageLedger.messageBuckets(meta),
        )
    }
}

/** DeepSeek 官方 usage 字段解析的回归。 */
class DeepSeekUsageParseTest {

    @Test
    fun `deepseek cache hit and miss map to disjoint buckets`() {
        val usage = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", 1000)
            put("completion_tokens", 200)
            put("total_tokens", 1200)
            put("prompt_cache_hit_tokens", 700)
            put("prompt_cache_miss_tokens", 300)
            put("reasoning_tokens", 120)
        })
        assertEquals(1000, usage.promptTokens)
        assertEquals(700, usage.cacheReadTokens)
        assertEquals(300, usage.uncachedInputTokens)
        assertEquals(200, usage.completionTokens)
        assertEquals(120, usage.reasoningTokens)
        assertNull(usage.cacheCreationTokens)
    }

    @Test
    fun `reasoning above output is dropped as contradictory`() {
        val usage = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", 10)
            put("completion_tokens", 5)
            put("total_tokens", 15)
            put("reasoning_tokens", 9)
        })
        assertNull(usage.reasoningTokens)
    }

    @Test
    fun `openai cached tokens split buckets`() {
        val usage = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", 100)
            put("completion_tokens", 10)
            put("total_tokens", 110)
            put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 40) })
        })
        assertEquals(40, usage.cacheReadTokens)
        assertEquals(60, usage.uncachedInputTokens)
    }

    @Test
    fun `no cache fields leaves uncached null`() {
        val usage = GenerationMetricsCalculator.parseUsage(buildJsonObject {
            put("prompt_tokens", 100)
            put("completion_tokens", 10)
            put("total_tokens", 110)
        })
        assertNull(usage.uncachedInputTokens)
        assertNull(usage.cacheReadTokens)
    }
}
