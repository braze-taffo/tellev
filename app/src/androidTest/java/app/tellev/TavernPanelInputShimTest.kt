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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * On-device half of the message input shim: the jsdom harness
 * (tools/message-host-eval) executes the frontend/`#send_textarea` logic, but only
 * a real WebView proves `compat/message-host.js` is actually served to the panel
 * (page head order + CompatAssets alias) and that the synchronous
 * `TellevMessage.getInput()` hop answers on this WebView.
 */
class TavernPanelInputShimTest {
    @Test fun frontendOptionClickFillsAndReadsBackTheChatComposer() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".mvuvalidation"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        // A card frontend shaped like the preset option cards: writing the ST
        // input element is the whole mechanism, no Tellev API involved.
        val html = """<!doctype html><html><body><div id="card">
            <button id="fill" type="button">填入第一个选项</button>
            <script>
              document.getElementById('fill').addEventListener('click', function () {
                var area = document.querySelector('#send_textarea');
                if (!area) return;
                area.value = (area.value ? area.value + String.fromCharCode(10) : '') + '选项一';
              });
            </script>
            </div></body></html>"""
        // Stands in for the chat screen's composer: request('setInput') writes it,
        // the runtime's currentInput lambda reads it back for the shim.
        val draft = AtomicReference("用户先写的一句")
        val operations = AtomicReference<List<Pair<String, String>>>(emptyList())
        try {
            withContext(Dispatchers.Main) {
                activity.setContent {
                    TellevTheme {
                        TavernHtmlPanel(
                            html = html,
                            availableMaxHeight = 500.dp,
                            tavernRuntime = TavernMessageRuntime(
                                token = RuntimeToken("input-shim-test", 1, "test"),
                                messageIndex = 0,
                                variablesJson = { "{}" },
                                contextJson = { "{}" },
                                request = { operation, payload, callback ->
                                    operations.set(operations.get() + (operation to payload))
                                    if (operation == "setInput") {
                                        draft.set(payload.extractText())
                                    }
                                    // Mirrors the real bridge: the response is
                                    // delivered back through the WebView.
                                    callback(true, """{"ok":true}""")
                                },
                                currentInput = { draft.get() },
                            ),
                            onBoundaryDrag = { },
                        )
                    }
                }
            }
            var frame: WebView? = null
            waitUntil { frame = withContext(Dispatchers.Main) { frames(activity.window.decorView).singleOrNull() }; frame != null }
            val view = requireNotNull(frame)
            // message-host.js reached the panel and installed the shim.
            waitUntil { evaluate(view, "!!document.getElementById('send_textarea')") == "true" }
            // Out of the message's flow: the panel's resize script measures body
            // children, and a flowing shim would report a phantom 1px row.
            assertEquals(
                "fixed",
                unquote(evaluate(view, "getComputedStyle(document.getElementById('send_textarea')).position")),
            )

            // The shim answers with the live composer draft, not an empty string.
            assertEquals(
                "用户先写的一句",
                unquote(evaluate(view, "document.getElementById('send_textarea').value")),
            )

            // Clicking the frontend's option button appends to that draft.
            evaluate(view, "document.getElementById('fill').click()")
            waitUntil { draft.get().lineSequence().count() == 2 }
            assertEquals("用户先写的一句\n选项一", draft.get())
            assertEquals("setInput", operations.get().last().first)
            assertEquals("""{"text":"用户先写的一句\n选项一"}""", operations.get().last().second)

            // A second click keeps accumulating instead of replacing.
            evaluate(view, "document.getElementById('fill').click()")
            waitUntil { draft.get().lineSequence().count() == 3 }
            assertEquals("用户先写的一句\n选项一\n选项一", draft.get())
            assertEquals(
                "用户先写的一句\n选项一\n选项一",
                unquote(evaluate(view, "document.getElementById('send_textarea').value")),
            )
        } finally {
            withContext(Dispatchers.Main) { activity.finish() }
        }
    }

    private fun String.extractText(): String =
        runCatching { Json.parseToJsonElement(this).jsonObject.getValue("text").jsonPrimitive.content }
            .getOrDefault("")

    /** `evaluateJavascript` returns a JSON literal; unwrap plain strings. */
    private fun unquote(value: String): String =
        runCatching { Json.parseToJsonElement(value).jsonPrimitive.content }.getOrDefault(value)

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
