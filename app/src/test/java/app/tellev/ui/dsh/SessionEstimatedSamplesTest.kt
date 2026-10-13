package app.tellev.ui.dsh

import app.tellev.core.metrics.GenerationMetrics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 统计口径核对（B2）：usage 面板本会话区「估算」徽标的判定。
 * 口径 = 「本会话存在未上报样本」→ 打标（与制卡弹层 estimatedCount > 0 同语义），
 * 而不是「全部样本都是估算」；范围=共享明细缓冲（聊天账本无估算计数）。
 */
class SessionEstimatedSamplesTest {

    private fun metric(sessionId: String?, estimated: Boolean) = GenerationMetrics(
        sessionId = sessionId,
        isEstimate = estimated,
    )

    @Test
    fun `mixed session is flagged — unreported samples make the numbers incomplete`() {
        // 旧实现是 all{isEstimate}：真值+估算混合反而静默，账本数字不全无提示。
        val metrics = listOf(
            metric("s1", estimated = false),
            metric("s1", estimated = true),
        )
        assertTrue(sessionHasEstimatedSamples(metrics, "s1"))
    }

    @Test
    fun `all-estimate session stays flagged`() {
        assertTrue(sessionHasEstimatedSamples(listOf(metric("s1", true)), "s1"))
    }

    @Test
    fun `other sessions' estimates do not flag this one`() {
        assertFalse(sessionHasEstimatedSamples(listOf(metric("s2", true)), "s1"))
    }

    @Test
    fun `no unreported samples means no badge`() {
        assertFalse(sessionHasEstimatedSamples(emptyList(), "s1"))
        assertFalse(sessionHasEstimatedSamples(listOf(metric("s1", false)), "s1"))
    }

    @Test
    fun `legacy records without attribution match a null session only`() {
        assertTrue(sessionHasEstimatedSamples(listOf(metric(null, true)), null))
        assertFalse(sessionHasEstimatedSamples(listOf(metric(null, true)), "s1"))
    }
}
