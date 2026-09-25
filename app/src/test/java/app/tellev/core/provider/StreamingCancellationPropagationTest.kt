package app.tellev.core.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 语义回归：流式适配器里 `call.execute()` 的阻塞 body 读期间，协程取消必须立刻断开
 * 连接，否则取消要等到 OkHttp 读超时（生产配置 5 分钟）才生效，retire 的 join() 会
 * 卡死会话切换。
 *
 * 用客户端 Socket 的 InputStream.read() 模拟"只被 call.cancel() 解除"的原生阻塞读
 * （OkHttp call.cancel() 正是靠关闭 socket 让读线程抛 SocketException）。
 * 对照三种挂 cancel 钩子的方式：
 * - default：生产者 Job 上的默认 invokeOnCompletion（只在终态触发）；
 * - guard：空子 Job 上的默认 invokeOnCompletion（无协程体可等，取消级联到达即终态）；
 * - watcher：并行的 awaitCancellation() 协程在 finally 里 cancel（纯公共 API 保证）。
 */
class StreamingCancellationPropagationTest {

    private enum class Mode { DEFAULT, GUARD, WATCHER }

    /** 服务端只负责让客户端 socket 保持已连接；读端阻塞在 client.getInputStream().read()。 */
    private class BlockedRead {
        private val server = ServerSocket(0)
        val client = Socket("127.0.0.1", server.localPort).also { server.accept() }

        fun readUntilClosed(): Boolean {
            return try {
                client.getInputStream().read()
                false
            } catch (_: java.io.IOException) {
                true // 读被 socket.close() 解除
            } finally {
                runCatching { client.close() }
                runCatching { server.close() }
            }
        }
    }

    private fun blockingStream(mode: Mode, blockedRead: BlockedRead, unblocked: CountDownLatch) = flow {
        suspend fun runBlocked() {
            when (mode) {
                Mode.DEFAULT -> {
                    currentCoroutineContext()[Job]!!.invokeOnCompletion { runCatching { blockedRead.client.close() } }
                    blockedRead.readUntilClosed(); unblocked.countDown()
                }
                Mode.GUARD -> {
                    val guard = Job(currentCoroutineContext()[Job])
                    guard.invokeOnCompletion { runCatching { blockedRead.client.close() } }
                    try {
                        blockedRead.readUntilClosed(); unblocked.countDown()
                    } finally {
                        guard.complete()
                    }
                }
                Mode.WATCHER -> coroutineScope {
                    val watcher = launch {
                        try { awaitCancellation() } finally { runCatching { blockedRead.client.close() } }
                    }
                    try {
                        blockedRead.readUntilClosed(); unblocked.countDown()
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        }
        runBlocked()
        emit("unreachable")
    }.flowOn(Dispatchers.IO)

    private fun assertUnblocksPromptly(mode: Mode) = runBlocking {
        val blockedRead = BlockedRead()
        val unblocked = CountDownLatch(1)
        val job = launch { blockingStream(mode, blockedRead, unblocked).collect { } }
        delay(300) // 等生产者注册好钩子并进入阻塞读

        val start = System.nanoTime()
        job.cancel()
        val prompt = unblocked.await(3, TimeUnit.SECONDS)
        val unblockMs = (System.nanoTime() - start) / 1_000_000
        withTimeout(3_000) { job.join() }
        val joinMs = (System.nanoTime() - start) / 1_000_000
        println("PROBE $mode: unblocked=$prompt after ${unblockMs}ms, join returned at ${joinMs}ms")
        assertTrue("$mode 应在取消后立即解除阻塞读（实际 ${unblockMs}ms）", prompt)
        assertTrue("$mode 的 join() 应在解除阻塞后很快返回，实际 ${joinMs}ms", joinMs < 3_000)
    }

    @Test
    fun `guard child job unblocks blocked read on cancel`() = assertUnblocksPromptly(Mode.GUARD)

    @Test
    fun `watcher coroutine unblocks blocked read on cancel`() = assertUnblocksPromptly(Mode.WATCHER)

    @Test
    fun `default handler timing probe - no assertion`() = runBlocking {
        val blockedRead = BlockedRead()
        val unblocked = CountDownLatch(1)
        val job = launch { blockingStream(Mode.DEFAULT, blockedRead, unblocked).collect { } }
        delay(300)

        val start = System.nanoTime()
        job.cancel()
        val prompt = unblocked.await(1_200, TimeUnit.MILLISECONDS)
        val probeMs = (System.nanoTime() - start) / 1_000_000
        println("PROBE default-on-producer-job: fired within 1.2s=$prompt after ${probeMs}ms")

        runCatching { blockedRead.client.close() } // 手动解除阻塞，收尾
        withTimeout(3_000) { job.join() }
        assertTrue("阻塞解除后协程应能结束", unblocked.await(1, TimeUnit.SECONDS))
    }
}
