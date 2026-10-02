package app.tellev.core.network

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * App-level gate for cleartext (http://) traffic.
 *
 * The platform network-security config stays permissive (a user-typed LAN
 * backend like http://192.168.x.x:8188 must keep working once opted in), so
 * the default-deny is enforced here instead, on every OkHttpClient the app
 * owns: loopback is always allowed, any other cleartext host is blocked with
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

    private fun isLoopback(host: String): Boolean = when {
        host.equals("localhost", ignoreCase = true) -> true
        host.startsWith("127.") -> true // 127.0.0.0/8
        host == "::1" || host == "[::1]" -> true
        host == "0.0.0.0" -> true
        else -> false
    }
}
