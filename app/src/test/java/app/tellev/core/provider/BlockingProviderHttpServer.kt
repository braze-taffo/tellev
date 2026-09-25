package app.tellev.core.provider

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Small local HTTP peer with a response body that can remain open during cancellation. */
internal class BlockingProviderHttpServer(handler: (TestHttpExchange) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val acceptThread = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: SocketException) { break }
            executor.execute { socket.use { handler(TestHttpExchange(it)) } }
        }
    }.apply { isDaemon = true; start() }

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    override fun close() {
        server.close()
        executor.shutdownNow()
        acceptThread.join(1_000)
    }
}

internal class TestHttpExchange(private val socket: Socket) {
    val requestMethod: String
    val requestPath: String

    init {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val requestLine = input.readHttpLine().split(' ')
        requestMethod = requestLine[0]
        requestPath = URI(requestLine[1]).path
        var contentLength = 0
        while (true) {
            val line = input.readHttpLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        while (contentLength > 0) {
            val skipped = input.skip(contentLength.toLong()).toInt()
            if (skipped > 0) contentLength -= skipped else if (input.read() >= 0) contentLength-- else break
        }
    }

    fun respondJson(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    fun stallBody(started: CountDownLatch, release: CountDownLatch) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
    }

    fun stallBeforeHeaders(started: CountDownLatch, release: CountDownLatch) {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
    }
}

private fun InputStream.readHttpLine(): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value < 0 || value == '\n'.code) break
        if (value != '\r'.code) bytes.write(value)
    }
    return bytes.toString(StandardCharsets.ISO_8859_1.name())
}
