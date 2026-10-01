package app.tellev

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.extension.RuntimeToken
import app.tellev.feature.chat.*
import app.tellev.ui.theme.TellevTheme
import app.tellev.ui.theme.ThemeAccent
import app.tellev.ui.theme.darkColors
import app.tellev.ui.theme.lightColors
import kotlinx.serialization.json.JsonPrimitive
import android.graphics.Bitmap
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TavernMixedRenderingTest {
    @Test fun mixedCardsRenderLogsAndInheritReadableLightAndDarkColors() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".mvuvalidation"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val card = """<body><style>.choice {padding:16px;color:var(--SmartThemeBodyColor,#eee);background:var(--SmartThemeBlurTintColor,rgba(24,22,28,.82));}</style><div class="choice" id="choice">梦境大讨论：回北剑宗</div></body>"""
        val log = """<div style="margin:12px auto"><details class="mvu-update-done" open><summary>变量更新</summary><div id="log">生命：100 → 85</div></details></div>"""
        val segments = TavernRenderParser.parseBody("正文\n```html\n$card\n```\n$log")
        assertEquals(2, segments.count { it is TavernRenderSegment.Frontend })
        assertFalse(segments.filterIsInstance<TavernRenderSegment.Text>().any { it.text.contains("<div") })
        try {
            for (dark in listOf(false, true)) {
                withContext(Dispatchers.Main) {
                    activity.setContent {
                        TellevTheme(darkTheme = dark) {
                            Surface(modifier = Modifier.fillMaxSize()) { Column {
                                segments.forEachIndexed { index, segment ->
                                    when (segment) {
                                        is TavernRenderSegment.Text -> Text(segment.text)
                                        is TavernRenderSegment.Frontend -> TavernHtmlPanel(segment.html, 250.dp,
                                            tavernRuntime = TavernMessageRuntime(RuntimeToken("mixed-$dark-$index", 1, "test"),
                                                index, { "{}" }, { "{}" }, { _, _, _ -> }), onBoundaryDrag = {})
                                        else -> Unit
                                    }
                                }
                            } }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                var views = emptyList<WebView>()
                val foreground = (if (dark) ThemeAccent.Warm.darkColors() else ThemeAccent.Warm.lightColors()).onSurface.toCssHex()
                waitUntil {
                    views = withContext(Dispatchers.Main) { frames(activity.window.decorView) }
                    views.size == 2 && evaluate(views[0], "!!document.getElementById('choice')") == "true" &&
                        evaluate(views[1], "!!document.getElementById('log')") == "true" &&
                        views.all { evaluate(it, "getComputedStyle(document.documentElement).getPropertyValue('--SmartThemeBodyColor').trim()") == JsonPrimitive(foreground).toString() }
                }
                val contrast = evaluate(views[0], """(function(){
                    var s=getComputedStyle(document.getElementById('choice'));
                    function luminance(c){var v=c.match(/[\d.]+/g).slice(0,3).map(Number).map(function(x){x/=255;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4)});return .2126*v[0]+.7152*v[1]+.0722*v[2]}
                    var a=luminance(s.color),b=luminance(s.backgroundColor);
                    return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);
                })()""")
                assertTrue("Theme $dark rendered with insufficient contrast: $contrast", contrast.toDouble() >= 4.5)
                assertEquals("\"生命：100 → 85\"", evaluate(views[1], "document.getElementById('log').textContent"))
                assertEquals("true", evaluate(views[1], "document.querySelector('details').open"))
                evaluate(views[1], "document.querySelector('summary').click()")
                assertEquals("false", evaluate(views[1], "document.querySelector('details').open"))
                // DOM readiness precedes Android layout and the WebView's first paint.
                views.forEach { evaluate(it, "window.__paintReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__paintReady=true))") }
                val density = instrumentation.targetContext.resources.displayMetrics.density
                waitUntil { views.all { evaluate(it, "window.__paintReady") == "true" } &&
                    withContext(Dispatchers.Main) { views.all { it.height in 1 until (200 * density).toInt() } } }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    instrumentation.targetContext.cacheDir.resolve("render-${if (dark) "dark" else "light"}.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }

    @Test fun suppliedDaoLogAfterDreamPanelRendersAndExpands() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.targetContext.cacheDir.resolve("mixed-cards.txt")
        org.junit.Assume.assumeTrue("Optional supplied-card replay fixture", fixture.isFile)
        val segments = TavernRenderParser.parseBody(fixture.readText())
        assertEquals(2, segments.count { it is TavernRenderSegment.Frontend })
        assertFalse(segments.filterIsInstance<TavernRenderSegment.Text>().any { it.text.contains("mvu-update-done") })
        val log = segments.filterIsInstance<TavernRenderSegment.Frontend>().last().html
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withContext(Dispatchers.Main) { activity.setContent {
                TellevTheme { Surface {
                    TavernHtmlPanel(log, 500.dp, tavernRuntime = TavernMessageRuntime(RuntimeToken("supplied-log", 1, "test"),
                        0, { "{}" }, { "{}" }, { _, _, _ -> }), onBoundaryDrag = {})
                } }
            } }
            var view: WebView? = null
            waitUntil {
                view = withContext(Dispatchers.Main) { frames(activity.window.decorView).singleOrNull() }
                view != null && evaluate(view!!, "!!document.querySelector('.mvu-update-done')") == "true"
            }
            val frame = requireNotNull(view)
            assertEquals("false", evaluate(frame, "document.querySelector('details').open"))
            evaluate(frame, "document.querySelector('summary').click()")
            assertEquals("true", evaluate(frame, "document.querySelector('details').open"))
            waitUntil { evaluate(frame, "getComputedStyle(document.querySelector('details > div')).opacity") == "\"1\"" }
            assertEquals("true", evaluate(frame, "document.querySelector('details > div').textContent.includes('INITIALIZATION')"))
            assertEquals("true", evaluate(frame, "document.querySelector('style:not(#tellev-host-style)').textContent.includes('.mvu-update-done')"))
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
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
