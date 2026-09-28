package app.tellev.feature.chat

import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatMessageRenderCacheTest {

    private fun inputs(body: String = "正文") = RenderInputs(
        MessageReasoning.Parts(reasoning = "", body = body),
        MessageRole.Character, character = null, preset = null,
        userName = "U", depth = 0, includeNormal = true,
    )

    @Test
    fun `fast compute returns the real pipeline result and caches it`() = runBlocking {
        val in1 = inputs("快消息")
        val calls = AtomicInteger()
        val first = ChatMessageRenderCache.computeAndAwait(in1, "m1") {
            calls.incrementAndGet()
            listOf(TavernRenderSegment.Text("rendered"))
        }
        assertEquals(listOf(TavernRenderSegment.Text("rendered")), first)

        // Second call for the same inputs is served from cache, no recompute.
        val second = ChatMessageRenderCache.computeAndAwait(in1, "m1") {
            calls.incrementAndGet()
            listOf(TavernRenderSegment.Text("should not run"))
        }
        assertEquals(first, second)
        assertEquals(1, calls.get())
    }

    @Test
    fun `compute past the budget degrades to regex-free rendering and stays degraded`() = runBlocking {
        val in1 = inputs("灾难正则消息")
        val result = ChatMessageRenderCache.computeAndAwait(in1, "m-catastrophic") {
            Thread.sleep(10_000) // simulates an uninterruptible catastrophic regex
            listOf(TavernRenderSegment.Text("never"))
        }
        // Degraded shape: regex-free parse of the same body.
        assertEquals(renderMessagePartsUnregulated(in1.parts, in1.role), result)
        // And the degraded result is cached — no retry on the next await.
        val again = ChatMessageRenderCache.computeAndAwait(in1, "m-catastrophic") {
            listOf(TavernRenderSegment.Text("never"))
        }
        assertEquals(result, again)
    }

    @Test
    fun `concurrent awaits for the same inputs see one result`() = runBlocking {
        val in1 = inputs("并发")
        val calls = AtomicInteger()
        val results = (1..4).map {
            async {
                ChatMessageRenderCache.computeAndAwait(in1, "m-parallel") {
                    calls.incrementAndGet()
                    Thread.sleep(50)
                    listOf(TavernRenderSegment.Text("one"))
                }
            }
        }.awaitAll()
        assertTrue(results.all { it == listOf(TavernRenderSegment.Text("one")) })
        assertTrue("compute ran more than twice: ${calls.get()}", calls.get() <= 2)
    }

    @Test
    fun `cashed entry is retrievable synchronously`() = runBlocking {
        val in1 = inputs("同步查")
        ChatMessageRenderCache.computeAndAwait(in1, "m-sync") {
            listOf(TavernRenderSegment.Text("sync"))
        }
        assertNotNull(ChatMessageRenderCache.cached(in1))
    }
}
