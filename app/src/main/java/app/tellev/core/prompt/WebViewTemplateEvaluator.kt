package app.tellev.core.prompt

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tellev.core.extension.CompatAssets
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Runs production EJS on Chromium. The prompt engine invokes this from its worker dispatcher. */
class WebViewTemplateEvaluator(private val context: Context) {
    private var view: WebView? = null
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var ready = CompletableDeferred<Unit>()

    fun evaluate(request: JsonObject): JsonObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Template evaluation must run off the UI thread" }
        return runBlocking { evaluateAsync(request) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun evaluateAsync(request: JsonObject): JsonObject = mutex.withLock {
        withTimeout(30_000) {
            withContext(Dispatchers.Main) {
                if (view == null) {
                    ready = CompletableDeferred()
                    view = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        addJavascriptInterface(Bridge(), "TemplateNative")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                                CompatAssets.intercept(context, r.url.toString())
                        }
                        loadDataWithBaseURL("https://extensions.tellev.local/template/",
                            "<script src=\"https://extensions.tellev.local/compat/globals.js\"></script><script>" +
                                CompatAssets.source(context, "template.js") +
                                ";TemplateNative.ready();</script>", "text/html", "UTF-8", null)
                    }
                }
            }
            ready.await()
            val id = UUID.randomUUID().toString()
            val result = CompletableDeferred<JsonObject>()
            pending[id] = result
            try {
                withContext(Dispatchers.Main) {
                    view!!.evaluateJavascript("window.__tellevTemplate($request).then(" +
                        "v=>TemplateNative.complete('$id',true,JSON.stringify(v))," +
                        "e=>TemplateNative.complete('$id',false,String(e.stack||e)))", null)
                }
                result.await()
            } finally { pending.remove(id) }
        }
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { ready.complete(Unit) }
        @JavascriptInterface fun complete(id: String, ok: Boolean, value: String) {
            val task = pending[id] ?: return
            if (ok) runCatching { Json.parseToJsonElement(value).jsonObject }
                .onSuccess { task.complete(it) }.onFailure { task.completeExceptionally(it) }
            else task.completeExceptionally(IllegalArgumentException("EJS: $value"))
        }
    }
}
