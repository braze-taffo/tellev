package app.tellev.core.extension

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.MainActivity
import app.tellev.TellevGraph
import app.tellev.core.storage.CharacterImporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Device replay of the supplied card. The private fixture lives in build/mvu-fixtures. */
class XuanhunCardAndroidTest {
    private lateinit var activity: android.app.Activity

    @Before fun launchForegroundActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @org.junit.After fun closeForegroundActivity() {
        activity.finish()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun originalCardLoadsItsCombatScriptsInAndroidWebView() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = runCatching { instrumentation.context.assets.open("xuanhun.json")
            .bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("Supply build/mvu-fixtures/xuanhun.json for the card replay", raw != null)
        val card = CharacterImporter().importFromJson(requireNotNull(raw))
        assertEquals(7, CharacterTavernHelperScripts.extract(card).size)
        val graph = TellevGraph.create(instrumentation.targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val id = "character-device-replay"
        host.setContextProvider(object : ExtensionContextProvider {
            override fun snapshot() = buildJsonObject {
                put("chat", JsonArray(emptyList()))
                put("chatId", "character-device-replay")
                put("name2", card.name)
                put("characterWorldBooks", JsonArray(emptyList()))
                put("globalWorldBooks", JsonArray(emptyList()))
                put("worldBooks", JsonArray(emptyList()))
            }
        })
        val permissions = setOf(ExtensionPermission.Storage, ExtensionPermission.Clipboard,
            ExtensionPermission.UiPanel, ExtensionPermission.ProviderRequest)
        graph.permissionManager.grantAll(id, permissions - ExtensionPermission.ProviderRequest)
        try {
            host.load(ExtensionManifest(id = id, version = "character-card", permissions = permissions),
                CharacterTavernHelperScripts.buildIsolatedScriptSource(card))
            assertEquals("true", host.evaluateRuntime(id, "typeof Mvu !== 'undefined'"))
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelectorAll('iframe[id^=\"TH-script--\"]').length === 7"))
            assertEquals("true", host.evaluateRuntime(id,
                "!!document.getElementById('jy-hud-frame')"))
            assertEquals("true", host.evaluateRuntime(id,
                "localStorage.getItem('玄浑纪战斗.音效') !== undefined"))
            assertTrue(host.webViewForUi(id) != null)
        } finally {
            host.unload(id)
            host.setContextProvider(null)
        }
    }
}
