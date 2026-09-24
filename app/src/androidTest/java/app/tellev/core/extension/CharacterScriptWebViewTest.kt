package app.tellev.core.extension

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import app.tellev.MainActivity
import app.tellev.TellevGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Exercises Web Storage and the actual child-frame loader in Android WebView. */
class CharacterScriptWebViewTest {
    private lateinit var activity: android.app.Activity

    @org.junit.Before fun launchForegroundActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @org.junit.After fun closeForegroundActivity() {
        activity.finish()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun scriptsUseOwnFramesAndOriginScopedStorage() = runBlocking {
        val graph = TellevGraph.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val id = "character-webview-test-${UUID.randomUUID()}"
        val otherId = "character-webview-test-${UUID.randomUUID()}"
        val scripts = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("storage"))
                put("name", JsonPrimitive("Storage"))
                put("content", JsonPrimitive("""
                    localStorage.setItem('card.sound', 'on');
                    parent.document.body.insertAdjacentHTML('beforeend', '<main id="card-ui">visible</main>');
                    window.probeId = getScriptId();
                """.trimIndent()))
            })
            add(buildJsonObject {
                put("id", JsonPrimitive("listener"))
                put("name", JsonPrimitive("Listener"))
                put("content", JsonPrimitive("window.probeId = getScriptId();"))
            })
        }
        try {
            host.load(ExtensionManifest(id = id), "export {}; await window.__tellevLoadScripts($scripts);")
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#card-ui')?.textContent === 'visible'"))
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#TH-script--Storage--storage')?.contentWindow.probeId === 'storage'"))
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#TH-script--Listener--listener')?.contentWindow.probeId === 'listener'"))
            assertEquals("true", host.evaluateRuntime(id,
                "localStorage.getItem('card.sound') === 'on'"))

            host.unload(id)
            host.load(ExtensionManifest(id = id), "export {};")
            assertEquals("true", host.evaluateRuntime(id,
                "localStorage.getItem('card.sound') === 'on'"))

            host.load(ExtensionManifest(id = otherId), "export {};")
            assertEquals("true", host.evaluateRuntime(otherId,
                "localStorage.getItem('card.sound') === null"))
        } finally {
            host.unload(id)
            host.unload(otherId)
        }
    }
}
