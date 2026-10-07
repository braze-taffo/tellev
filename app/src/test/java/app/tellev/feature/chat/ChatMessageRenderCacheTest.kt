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
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

class ChatMessageRenderCacheTest {

    @Test fun `pending state contains no executable frontend or markdown`() {
        for (body in listOf("<html><body><script>sideEffect()</script></body></html>", "**markdown**")) {
            val pending = MessageRenderState(inputs(body), MessageRenderPhase.Pending)
            assertTrue(pending.segments.isEmpty())
            assertTrue(!pending.hasDisplay)
        }
    }

    @Test fun `timeout is final and late completion cannot overwrite degraded cache`() = runBlocking {
        val input = inputs("late-${UUID.randomUUID()}")
        val result = ChatMessageRenderCache.computeStateAndAwait(input, input.parts.body) {
            Thread.sleep(1250)
            listOf(TavernRenderSegment.Frontend("late"))
        }
        assertEquals(MessageRenderPhase.Degraded, result.phase)
        delay(400)
        assertEquals(result, ChatMessageRenderCache.cachedState(input))
    }

    @Test fun `superseded work cannot publish a fallback or late frontend`() = runBlocking {
        val group = "replace-${UUID.randomUUID()}"
        val old = inputs("old-$group")
        val fresh = inputs("new-$group")
        val started = java.util.concurrent.CountDownLatch(1)
        val first = async {
            try {
                ChatMessageRenderCache.computeStateAndAwait(old, group) {
                    started.countDown()
                    Thread.sleep(300)
                    listOf(TavernRenderSegment.Frontend("old"))
                }
            } catch (_: CancellationException) { null }
        }
        while (started.count > 0) delay(10)
        val result = ChatMessageRenderCache.computeStateAndAwait(fresh, group) { listOf(TavernRenderSegment.Text("fresh")) }
        assertEquals(MessageRenderPhase.Ready, result.phase)
        assertEquals(null, first.await())
        delay(350)
        assertEquals(null, ChatMessageRenderCache.cachedState(old))
        assertEquals(result, ChatMessageRenderCache.cachedState(fresh))
    }

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
    fun `render queued behind busy workers still gets its execution budget`() = runBlocking {
        // Occupy both workers with computes that outlive the budget: they
        // degrade at 1s but keep their threads busy until they finish (~1.25s).
        val started = java.util.concurrent.CountDownLatch(2)
        val occupiers = (1..2).map { n ->
            async {
                ChatMessageRenderCache.computeStateAndAwait(
                    inputs("占用$n-${UUID.randomUUID()}"), "busy-$n-${UUID.randomUUID()}",
                ) {
                    started.countDown()
                    Thread.sleep(1250)
                    listOf(TavernRenderSegment.Text("busy"))
                }
            }
        }
        // Earlier tests in this class can leave a slow compute occupying a
        // worker for a while; admission waits for a free thread. Wait with a
        // SUSPENDING loop — a blocking latch await here would starve the
        // runBlocking event loop and the occupiers would never run — and
        // bound it so a regression fails loudly instead of hanging.
        val allStarted = withTimeoutOrNull(30_000) {
            while (started.count > 0) delay(10)
            true
        } ?: false
        assertTrue(
            "occupier compute never started — admission starved past 30s",
            allStarted,
        )
        // Submitted while both slots are busy: the budget must start only once
        // the pipeline actually executes, so this fast render still goes
        // through the real pipeline instead of degrading in the queue.
        val probe = ChatMessageRenderCache.computeStateAndAwait(
            inputs("排队-${UUID.randomUUID()}"), "probe-${UUID.randomUUID()}",
        ) {
            listOf(TavernRenderSegment.Text("real pipeline"))
        }
        assertEquals(MessageRenderPhase.Ready, probe.phase)
        assertEquals(listOf(TavernRenderSegment.Text("real pipeline")), probe.segments)
        occupiers.forEach { assertTrue(it.await().segments.isNotEmpty()) }
    }

    @Test
    fun `degraded entry is recomputed after the retry window`() = runBlocking {
        ChatMessageRenderCache.degradedRetryMs = 0L
        try {
            val in1 = inputs("重试-${UUID.randomUUID()}")
            val first = ChatMessageRenderCache.computeStateAndAwait(in1, "retry-slow-${UUID.randomUUID()}") {
                Thread.sleep(1250)
                listOf(TavernRenderSegment.Text("slow"))
            }
            assertEquals(MessageRenderPhase.Degraded, first.phase)
            // Window is zero: the next lookup drops the degraded entry and
            // recomputes with the real pipeline instead of serving it forever.
            val second = ChatMessageRenderCache.computeStateAndAwait(in1, "retry-fast-${UUID.randomUUID()}") {
                listOf(TavernRenderSegment.Text("fast"))
            }
            assertEquals(MessageRenderPhase.Ready, second.phase)
            assertEquals(listOf(TavernRenderSegment.Text("fast")), second.segments)
        } finally {
            ChatMessageRenderCache.degradedRetryMs = 10_000L
        }
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
