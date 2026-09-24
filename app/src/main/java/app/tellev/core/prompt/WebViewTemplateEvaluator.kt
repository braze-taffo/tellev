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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs production EJS on Chromium. The prompt engine invokes this from its worker dispatcher.
 *
 * Implements [PromptTemplateJsBridge] so DefaultPromptTemplateProcessor can also drive the
 * per-build lifecycle hooks (sticky-injection decay, outlet placeholder resolution) that
 * ST-Prompt-Template performs in handler.ts.
 */
class WebViewTemplateEvaluator(private val context: Context) : PromptTemplateJsBridge {
    @Volatile private var view: WebView? = null
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    @Volatile private var ready = CompletableDeferred<Unit>()

    override fun evaluate(request: JsonObject): JsonObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Template evaluation must run off the UI thread" }
        return runBlocking { evaluateAsync(request) }
    }

    override fun deactivateInjectedPrompts() {
        if (view == null) return
        check(Looper.myLooper() != Looper.getMainLooper()) { "Template evaluation must run off the UI thread" }
        runBlocking {
            withTimeout(30_000) {
                mutex.withLock {
                    withContext(Dispatchers.Main) {
                        view?.evaluateJavascript(
                            "window.__tellevTemplateDeactivate && window.__tellevTemplateDeactivate();", null)
                    }
                }
            }
        }
    }

    override fun replaceOutletPlaceholders(content: String): String {
        if (!content.contains(OUTLET_MARKER) || view == null) return content
        check(Looper.myLooper() != Looper.getMainLooper()) { "Template evaluation must run off the UI thread" }
        return runBlocking {
            withTimeout(30_000) {
                mutex.withLock {
                    ready.await()
                    val id = UUID.randomUUID().toString()
                    val result = CompletableDeferred<JsonObject>()
                    pending[id] = result
                    try {
                        withContext(Dispatchers.Main) {
                            view!!.evaluateJavascript(
                                "window.__tellevTemplateOutlet(${jsString(content)})" +
                                    ".then(v=>({content:v})).then(" +
                                    "v=>TemplateNative.complete('$id',true,JSON.stringify(v))," +
                                    "e=>TemplateNative.complete('$id',false,String(e.stack||e)))", null)
                        }
                        val parsed = result.await()
                        (parsed["content"] as? JsonPrimitive)?.content ?: content
                    } finally { pending.remove(id) }
                }
            }
        }
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

    private companion object {
        const val OUTLET_MARKER = "{{outletPromptsInjected:"

        // kotlinx escapes control characters but not U+2028/2029, which are
        // legal JSON but terminate a JS string literal in older parsers.
        fun jsString(value: String): String =
            JsonPrimitive(value).toString()
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
    }
}
