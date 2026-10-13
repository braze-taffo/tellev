package app.tellev.core.network

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * App-level gate for cleartext (http://) traffic.
 *
 * The platform network-security config stays permissive (a user-typed LAN
 * backend like http://192.168.x.x:8188 must keep working once opted in), so
 * the default-deny is enforced here instead, on every OkHttpClient the app
 * owns: loopback — decided on the *resolved* address, never on a host-name
 * prefix — is always allowed, any other cleartext host is blocked with
 * an actionable message until the user enables the cleartext-HTTP toggle in
 * Settings → Models. [configure] is called once from TellevGraph.create and
 * reads the live preference on every request, so flipping the toggle takes
 * effect immediately without rebuilding the clients.
 */
object CleartextGuard : Interceptor {

    @Volatile
    private var allowRemoteCleartext: () -> Boolean = { false }

    fun configure(allowRemote: () -> Boolean) {
        allowRemoteCleartext = allowRemote
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isHttps || isLoopback(request.url.host) || allowRemoteCleartext()) {
            return chain.proceed(request)
        }
        throw IOException(UiStrings.get(S.net_cleartext_blocked, request.url.host))
    }

    /**
     * 判定必须落在解析后的真实地址上：`startsWith("127.")` 这类字符串前缀匹配会把
     * `127.attacker.example` 这种以 127. 开头的 DNS 名当成本机放行，即便用户关闭了
     * 明文开关。解析（UnknownHostException → false）失败时按非本机处理，请求随后会
     * 在 OkHttp 的同一套 DNS 上再失败一次，不会因此多放行任何主机。
     *
     * 前三个字面量是完整主机名/IP 的精确匹配，不是前缀，无法被拼造域名绕过。
     */
    private fun isLoopback(host: String): Boolean = when {
        host.equals("localhost", ignoreCase = true) -> true
        host == "::1" || host == "[::1]" -> true
        // 0.0.0.0 是不确定地址，实际落到本机；解析结果既不是 loopback 也不是 link-local。
        host == "0.0.0.0" -> true
        else -> try {
            val address = InetAddress.getByName(host)
            address.isLoopbackAddress || address.isLinkLocalAddress
        } catch (e: UnknownHostException) {
            false
        }
    }
}
