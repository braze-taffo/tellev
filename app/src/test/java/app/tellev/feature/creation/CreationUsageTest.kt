package app.tellev.feature.creation

import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Draft usage aggregation: every completed provider request counts, reported
 * token sums stay honest, and a legacy draft decodes with defaults.
 */
class CreationUsageTest {

    private fun metric(
        prompt: Int? = 100,
        completion: Int? = 20,
        cacheRead: Int? = null,
        cacheWrite: Int? = null,
        reasoning: Int? = null,
        estimate: Boolean = false,
        model: String = "fixture-model",
    ) = GenerationMetrics(
        providerType = "custom:fixture",
        model = model,
        promptTokens = prompt,
        completionTokens = completion,
        cacheReadTokens = cacheRead,
        cacheCreationTokens = cacheWrite,
        reasoningTokens = reasoning,
        isEstimate = estimate,
    )

    @Test
    fun `every completed request counts even without a usage frame`() {
        val usage = CreationUsage()
            .plus(metric())
            .plus(metric(prompt = null, completion = null, estimate = true))
        assertEquals(2, usage.completedRequests)
        // Only the reported sample contributes tokens; the unreported one still counts.
        assertEquals(1, usage.estimatedCount)
        assertEquals(100L, usage.promptTokens)
        assertEquals(20L, usage.completionTokens)
        assertEquals(120L, usage.totalTokens)
    }

    @Test
    fun `cache buckets split the prompt into disjoint parts`() {
        val usage = CreationUsage().plus(metric(prompt = 100, cacheRead = 60, cacheWrite = 10))
        assertEquals(60L, usage.cacheReadTokens)
        assertEquals(10L, usage.cacheWriteTokens)
        assertEquals(30L, usage.uncachedInputTokens)
        assertEquals(100L, usage.uncachedInputTokens + usage.cacheReadTokens + usage.cacheWriteTokens)
    }

    @Test
    fun `reasoning stays a subset note and never inflates the total`() {
        val usage = CreationUsage().plus(metric(prompt = 50, completion = 30, reasoning = 12))
        assertEquals(12L, usage.reasoningTokens)
        assertEquals(80L, usage.totalTokens)
    }

    @Test
    fun `accumulation is additive across rounds`() {
        val usage = CreationUsage().plus(metric(prompt = 10, completion = 1))
            .plus(metric(prompt = 20, completion = 2))
            .plus(metric(prompt = 30, completion = 3))
        assertEquals(3, usage.completedRequests)
        assertEquals(60L, usage.promptTokens)
        assertEquals(6L, usage.completionTokens)
        assertEquals("fixture-model", usage.lastModel)
    }

    @Test
    fun `legacy draft decodes with default usage and effort`() {
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<CreationSession>("""{"kind":"Character"}""")
        assertEquals(CreationUsage(), decoded.usage)
        assertEquals(ReasoningEffort.Auto, decoded.reasoningEffort)
        assertFalse(decoded.usage.completedRequests > 0)
    }

    @Test
    fun `usage and effort round trip through serialization`() {
        val session = CreationSession(
            kind = CreationKind.Character,
            usage = CreationUsage().plus(metric()),
            reasoningEffort = ReasoningEffort.High,
        )
        val encoded = Json.encodeToString(CreationSession.serializer(), session)
        val decoded = Json.decodeFromString(CreationSession.serializer(), encoded)
        assertEquals(session.usage, decoded.usage)
        assertEquals(ReasoningEffort.High, decoded.reasoningEffort)
    }

    @Test
    fun `draft isolation keeps effort and usage per draft`() {
        val first = CreationSession(kind = CreationKind.Character, reasoningEffort = ReasoningEffort.Low)
            .copy(usage = CreationUsage().plus(metric(prompt = 5, completion = 1)))
        val second = CreationSession(kind = CreationKind.Character)
        assertEquals(ReasoningEffort.Low, first.reasoningEffort)
        assertEquals(ReasoningEffort.Auto, second.reasoningEffort)
        assertEquals(0, second.usage.completedRequests)
        assertTrue(first.usage.totalTokens > second.usage.totalTokens)
    }
}
