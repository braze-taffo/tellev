package app.tellev.core.metrics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** decode 速度（TTFT 剔除）与窗口分层的回归。 */
class DecodeSpeedAndWindowTest {

    @Test
    fun `decode window excludes ttft wait`() {
        // 10s total, 4s TTFT → 6s decode window; 120 tokens → 20 tok/s decode.
        val metrics = GenerationMetricsCalculator.fromCompletion(
            providerType = "openai-compatible",
            model = "m",
            usage = buildJsonObject {
                put("prompt_tokens", 100)
                put("completion_tokens", 120)
                put("total_tokens", 220)
            },
            estimatedPromptTokens = null,
            deltaText = "x",
            startedAtMs = 0L,
            firstDeltaAtMs = 4_000L,
            completedAtMs = 10_000L,
        )
        assertEquals(4_000L, metrics.ttftMs)
        assertEquals(10_000L, metrics.totalMs)
        assertEquals(6_000L, metrics.decodeMs)
        assertEquals(20.0, metrics.decodeTokensPerSecond!!, 0.001)
        // total-window speed is slower (12 tok/s) — decode must not equal it.
        assertEquals(12.0, metrics.tokensPerSecond!!, 0.001)
    }

    @Test
    fun `no ttft sample keeps decode equal to total`() {
        val metrics = GenerationMetricsCalculator.fromCompletion(
            providerType = "openai-compatible",
            model = "m",
            usage = null,
            estimatedPromptTokens = 10,
            deltaText = "hello",
            startedAtMs = 0L,
            firstDeltaAtMs = null,
            completedAtMs = 5_000L,
        )
        assertEquals(5_000L, metrics.decodeMs)
        assertEquals(metrics.tokensPerSecond!!, metrics.decodeTokensPerSecond!!, 0.001)
    }

    @Test
    fun `zero-length decode window reports nulls`() {
        val metrics = GenerationMetricsCalculator.fromCompletion(
            providerType = "openai-compatible",
            model = "m",
            usage = buildJsonObject {
                put("prompt_tokens", 1)
                put("completion_tokens", 1)
                put("total_tokens", 2)
            },
            estimatedPromptTokens = null,
            deltaText = "x",
            startedAtMs = 1_000L,
            firstDeltaAtMs = 1_000L,
            completedAtMs = 1_000L,
        )
        assertNull(metrics.decodeMs)
        assertNull(metrics.decodeTokensPerSecond)
    }
}
