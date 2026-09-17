package app.tellev

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.extension.RuntimeToken
import app.tellev.feature.chat.TavernHtmlPanel
import app.tellev.feature.chat.TavernMessageRuntime
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TavernPanelSizingTest {
    @Test fun longHtmlCollapsesAndExpandsWithoutRetainingViewportHeight() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".mvuvalidation"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        // Same flex/grid collapse structure as the supplied Sakura preset, with
        // enough source text to hit the previous whole-screen minimum heuristic.
        val html = """<!doctype html><html><head><style>
            body { display:flex; align-items:flex-start; justify-content:center; }
            #card { width:100%; overflow:hidden; }
            #header { height:64px; }
            #content { display:grid; grid-template-rows:0fr; transition:grid-template-rows .2s; }
            #card.expanded #content { grid-template-rows:1fr; }
            #clip { overflow:hidden; min-height:0; }
            #text { height:800px; }
            </style></head><body><div id="card">
            <div id="header" onclick="document.getElementById('card').classList.toggle('expanded')">Toggle</div>
            <div id="content"><div id="clip"><div id="text">${"fixture ".repeat(240)}</div></div></div>
            </div></body></html>"""
        try {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    TellevTheme {
                        TavernHtmlPanel(html, 500.dp, tavernRuntime = TavernMessageRuntime(
                            RuntimeToken("sizing-test", 1, "test"), 0, { "{}" }, { "{}" }, { _, _, _ -> },
                        ), onBoundaryDrag = {})
                    }
                }
            }
            var frame: WebView? = null
            waitUntil { frame = withContext(Dispatchers.Main) { frames(activity.window.decorView).singleOrNull() }; frame != null }
            val view = requireNotNull(frame)
            waitUntil { evaluate(view, "!!document.getElementById('header')") == "true" }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            suspend fun height() = withContext(Dispatchers.Main) { view.height / density }
            waitUntil { height() in 62f..68f }
            repeat(2) {
                evaluate(view, "document.getElementById('header').click()")
                waitUntil { height() > 350f }
                evaluate(view, "document.getElementById('header').click()")
                waitUntil { height() in 62f..68f }
            }
            assertTrue(height() < 70f)
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(50)
    }
    private fun frames(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { frames(view.getChildAt(it)) }
        else -> emptyList()
    }
    private suspend fun evaluate(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
        return withTimeout(5_000) { result.await() }
    }
}
