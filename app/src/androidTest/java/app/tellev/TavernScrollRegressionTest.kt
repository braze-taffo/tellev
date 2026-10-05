package app.tellev

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.extension.RuntimeToken
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.storage.CharacterImporter
import app.tellev.feature.chat.*
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class TavernScrollRegressionTest {
    @Before fun enableMetrics() { TavernPanelStats.enabled = true }
    @After fun disableMetrics() { TavernPanelStats.enabled = false }
    private fun activity(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".mvuvalidation"))
        return instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    }
    private fun runtime(id: String, effects: AtomicInteger = AtomicInteger()) = TavernMessageRuntime(
        RuntimeToken("scroll-test", 1, "test"), 0, { "{}" }, { "{}" },
        { _, _, callback -> effects.incrementAndGet(); callback(true, "{}") }, messageId = id,
    )

    @Test fun pendingHtmlIsNotLoadedAndFinalRegexFrontendRunsExactlyOnce() = runBlocking {
        val activity = activity()
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val effects = AtomicInteger()
        val id = UUID.randomUUID().toString()
        val source = """<html><body><div id="raw">raw</div><script>TellevMessage.request('effect','effect','{}')</script></body></html>"""
        val parts = MessageReasoning.Parts(body = source)
        val card = CharacterImporter().importFromJson("""{"name":"fixture","extensions":{"regex_scripts":[
            {"findRegex":"/raw/g","replaceString":"final","placement":[2],"markdownOnly":true}]}}""")
        val inputs = RenderInputs(parts, MessageRole.Character, card, null, "U", 0, false)
        val before = TavernPanelStats.loads.get()
        val created = TavernPanelStats.creates.get()
        try {
            withContext(Dispatchers.Main) {
                activity.setContent { TellevTheme {
                    val state by rememberRenderedSegments(inputs, id) {
                        started.countDown()
                        release.await()
                        renderMessageParts(parts, MessageRole.Character, card, null, "U", 0, false)
                    }
                    if (!state.hasDisplay) PendingMessagePanel(400.dp, runtime(id, effects))
                    else TavernMessageContent(state.segments, 400.dp, false, false, .6f, 16,
                        tavernRuntime = runtime(id, effects), onHtmlBoundaryDrag = {})
                } }
            }
            waitUntil { started.count == 0L }
            delay(80)
            assertEquals(created, TavernPanelStats.creates.get())
            assertEquals(before, TavernPanelStats.loads.get())
            assertEquals(0, effects.get())
            release.countDown()
            val view = frame(activity)
            waitUntil { evaluate(view, "!!document.getElementById('final')") == "true" }
            waitUntil { effects.get() == 1 }
            delay(200)
            assertEquals(before + 1, TavernPanelStats.loads.get())
            assertEquals(1, effects.get())
        } finally { release.countDown(); withContext(Dispatchers.Main) { activity.finish() } }
    }

    @Test fun rebindingClosesAllOldReadsAndRejectsOldCallbacks() = runBlocking {
        val activity = activity()
        val id = mutableStateOf("a")
        val effects = AtomicInteger()
        try {
            withContext(Dispatchers.Main) { activity.setContent { TellevTheme {
                TavernHtmlPanel("<html><body><div id='${id.value}'>${id.value}</div></body></html>", 400.dp,
                    tavernRuntime = runtime(id.value, effects), onBoundaryDrag = {})
            } } }
            val view = frame(activity)
            waitUntil { evaluate(view, "!!document.getElementById('a')") == "true" }
            val old = withContext(Dispatchers.Main) { (view.tag as TavernPanelController).bridge!! }
            withContext(Dispatchers.Main) { id.value = "b" }
            waitUntil { evaluate(view, "!!document.getElementById('b')") == "true" }
            assertTrue(old.isClosed())
            assertEquals("{}", old.getContext())
            assertEquals("{}", old.getAllVariables())
            assertEquals(-1, old.getCurrentMessageId())
            assertEquals("", old.getInput())
            old.request("late", "write", "{}")
            old.resize(9999)
            old.forwardBoundaryDrag(999.0)
            delay(150)
            assertEquals(0, effects.get())
            assertEquals("true", evaluate(view, "!!document.getElementById('b')"))
        } finally { withContext(Dispatchers.Main) { activity.finish() } }
    }

    @Test fun mixedLongHistoriesReuseShellsAndReleasePages() = runBlocking {
        for (count in listOf(50, 200, 1000)) {
            val activity = activity()
            var list: LazyListState? = null
            val creates = TavernPanelStats.creates.get()
            val releases = TavernPanelStats.releases.get()
            val sizes = ChatPanelSizes()
            try {
                withContext(Dispatchers.Main) { activity.setContent { TellevTheme {
                    list = rememberLazyListState()
                    LazyColumn(state = requireNotNull(list)) {
                        items((0 until count).toList(), key = { it }, contentType = { "message" }) { index ->
                            val segments = if (index % 3 == 0) listOf(TavernRenderSegment.Text("plain $index"))
                                else if (index % 3 == 1) listOf(TavernRenderSegment.Text("**markdown $index**"))
                                else listOf(TavernRenderSegment.Frontend("<html><body><div style='height:240px'>$index</div></body></html>"))
                            TavernMessageContent(segments, 300.dp, false, false, .6f, 16,
                                tavernRuntime = runtime("$count-$index").copy(messageIndex = index, sizes = sizes), onHtmlBoundaryDrag = {})
                        }
                    }
                } } }
                waitUntil { list != null }
                for (index in listOf(count - 1, count / 2, 0, count - 1, 0)) {
                    withContext(Dispatchers.Main) { requireNotNull(list).scrollToItem(index) }
                    delay(250)
                    assertTrue(withContext(Dispatchers.Main) { frames(activity.window.decorView).size } < 16)
                }
                assertTrue("native shells should be reused", TavernPanelStats.creates.get() - creates < 24)
            } finally {
                withContext(Dispatchers.Main) { activity.setContent { } }
                try {
                    waitUntil { TavernPanelStats.creates.get() - creates == TavernPanelStats.releases.get() - releases }
                } finally { withContext(Dispatchers.Main) { activity.finish() }; sizes.clear() }
            }
        }
    }

    private suspend fun frame(activity: MainActivity): WebView {
        var view: WebView? = null
        waitUntil { view = withContext(Dispatchers.Main) { frames(activity.window.decorView).singleOrNull() }; view != null }
        return requireNotNull(view)
    }
    private fun frames(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { frames(view.getChildAt(it)) }
        else -> emptyList()
    }
    private suspend fun evaluate(view: WebView, script: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { view.evaluateJavascript(script) { result.complete(it) } }
        return withTimeout(5000) { result.await() }
    }
    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(10)
    }
}
