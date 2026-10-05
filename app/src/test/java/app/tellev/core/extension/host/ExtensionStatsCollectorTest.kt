package app.tellev.core.extension.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStatsCollectorTest {

    @Test
    fun `records count total max errors and sorts by count`() {
        val collector = ExtensionStatsCollector()
        collector.record("ext", "stGetVariables", 10.0, failed = false)
        collector.record("ext", "stGetVariables", 30.0, failed = false)
        collector.record("ext", "stGetVariables", 20.0, failed = true)
        collector.record("ext", "stGetContext", 5.0, failed = false)

        val stats = collector.stats("ext")
        assertEquals(listOf("stGetVariables", "stGetContext"), stats.map { it.name })
        val getVars = stats.first()
        assertEquals(3, getVars.count)
        assertEquals(60.0, getVars.totalMillis, 0.001)
        assertEquals(30.0, getVars.maxMillis, 0.001)
        assertEquals(1, getVars.errorCount)
        assertTrue(getVars.lastCalledAtMillis > 0)
    }

    @Test
    fun `stats are isolated per extension and clear removes them`() {
        val collector = ExtensionStatsCollector()
        collector.record("a", "stGetContext", 1.0, failed = false)
        collector.record("b", "stGetContext", 2.0, failed = false)

        assertEquals(1, collector.stats("a").single().count)
        assertEquals(2.0, collector.stats("b").single().totalMillis, 0.001)

        collector.clear("a")
        assertTrue(collector.stats("a").isEmpty())
        assertEquals(1, collector.stats("b").single().count)

        collector.record("c", "stGetContext", 1.0, failed = false)
        collector.clear(null)
        assertTrue(collector.stats("c").isEmpty())
    }

    @Test
    fun `recent calls keep the newest fifty newest-first`() {
        val collector = ExtensionStatsCollector()
        repeat(60) { index -> collector.record("ext", "api-$index", 1.0, failed = index == 59) }

        val recent = collector.recentCalls("ext")
        assertEquals(50, recent.size)
        assertEquals("api-59", recent.first().name)
        assertTrue(recent.first().failed)
        assertEquals("api-10", recent.last().name)
    }

    @Test
    fun `tracked names are capped so dynamic paths cannot grow unbounded`() {
        val collector = ExtensionStatsCollector()
        repeat(300) { index -> collector.record("ext", "api GET /api/thing/$index", 1.0, failed = false) }
        assertEquals(200, collector.stats("ext").size)
    }

    @Test
    fun `concurrent recording does not lose counts`() {
        // 多轮重复：非原子创建的竞态是概率性的，单轮 1600 次不足以稳定命中。
        repeat(10) { round ->
            val collector = ExtensionStatsCollector()
            val threads = 8
            val perThread = 200
            val pool = Executors.newFixedThreadPool(threads)
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    repeat(perThread) { collector.record("ext", "stGetVariables", 1.0, failed = false) }
                    done.countDown()
                }
            }
            done.await()
            pool.shutdown()
            assertEquals(threads * perThread, collector.stats("ext").single().count)
            // The recent-calls trail is capped; the aggregate counter is not.
            assertEquals(50, collector.recentCalls("ext").size)
        }
    }
}
