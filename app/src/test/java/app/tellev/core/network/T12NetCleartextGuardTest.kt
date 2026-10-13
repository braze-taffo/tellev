package app.tellev.core.network

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TELLER-2-5：明文 HTTP 的放行判定必须落在解析后的真实地址上。
 *
 * 覆盖回归点：旧实现用 `host.startsWith("127.")` 做本机判定，`127.attacker.invalid`
 * 这类以 127. 开头的 DNS 名会被当成本机放行（即便用户关闭了明文开关）。本组用例
 * 顺带守住既有行为：本机字面量放行、LAN 主机在开关关闭时被拒、开关开启后放行、
 * HTTPS 不受开关影响。
 */
class T12NetCleartextGuardTest {

    @Before
    fun toggleOff() {
        CleartextGuard.configure { false }
    }

    @After
    fun resetToggle() {
        CleartextGuard.configure { false }
    }

    @Test
    fun `loopback ipv4 literal is allowed`() {
        val chain = chainFor("http://127.0.0.1:11434/api/tags")
        assertEquals(200, CleartextGuard.intercept(chain).code)
        assertEquals("127.0.0.1", chain.proceeded?.url?.host)
    }

    @Test
    fun `ipv6 loopback literal is allowed`() {
        val chain = chainFor("http://[::1]:8080/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `localhost literal is allowed`() {
        val chain = chainFor("http://localhost:8188/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `link-local ipv4 literal is allowed`() {
        val chain = chainFor("http://169.254.7.7:8188/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `link-local ipv6 literal is allowed`() {
        val chain = chainFor("http://[fe80::1]:8188/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `remote lan host is blocked while the toggle is off`() {
        assertBlocked("http://192.168.31.204:8188/v1/models")
    }

    @Test
    fun `dns name starting with 127 is not treated as loopback`() {
        assertBlocked("http://127.attacker.invalid/card.png")
    }

    @Test
    fun `remote lan host is allowed once the toggle is on`() {
        CleartextGuard.configure { true }
        val chain = chainFor("http://192.168.31.204:8188/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `https host is allowed while the toggle is off`() {
        val chain = chainFor("https://api.example.com/v1/models")
        assertEquals(200, CleartextGuard.intercept(chain).code)
    }

    @Test
    fun `blocked host message names the host and the cleartext reason`() {
        val chain = chainFor("http://192.168.31.204:8188/v1/models")
        try {
            CleartextGuard.intercept(chain)
            fail("明文请求应被拦截")
        } catch (e: IOException) {
            assertEquals(UiStrings.get(S.net_cleartext_blocked, "192.168.31.204"), e.message)
        }
    }

    private fun assertBlocked(url: String) {
        val chain = chainFor(url)
        try {
            CleartextGuard.intercept(chain)
            fail("明文请求应被拦截：$url")
        } catch (expected: IOException) {
            assertEquals(null, chain.proceeded)
        }
    }

    private fun chainFor(url: String): FakeChain = FakeChain(Request.Builder().url(url).build())
}

/**
 * 最小 Interceptor.Chain：只让用例观察「是否放行 / 交给谁」，不触发真实连接。
 */
private class FakeChain(private val request: Request) : Interceptor.Chain {

    var proceeded: Request? = null

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        proceeded = request
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody(null))
            .build()
    }

    override fun connection(): Connection? = null

    override fun call(): Call = throw UnsupportedOperationException()

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}
