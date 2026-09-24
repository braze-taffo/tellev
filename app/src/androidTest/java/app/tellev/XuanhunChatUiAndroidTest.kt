package app.tellev

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.storage.CharacterImporter
import app.tellev.feature.chat.ChatScreen
import app.tellev.feature.chat.ChatViewModel
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opens the supplied card from the real chat screen and mounts its live runtime. */
class XuanhunChatUiAndroidTest {
    @Test fun chatButtonOpensCardFullscreen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val raw = runCatching { instrumentation.context.assets.open("xuanhun.json")
            .bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("Supply build/mvu-fixtures/xuanhun.json for the card replay", raw != null)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val graph = TellevGraph.create(instrumentation.targetContext)
        val models = ViewModelStore()
        val card = CharacterImporter().importFromJson(requireNotNull(raw))
            .copy(id = "xuanhun-ui-${UUID.randomUUID()}")
        try {
            graph.dataStore.bootstrap()
            graph.dataStore.saveCharacter(card)
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(graph.dataStore, graph.providerRegistry, graph.promptEngine, graph.secretStore,
                    graph.extensionHost, graph.permissionManager).also {
                    models.put("xuanhun-ui", it)
                    activity.setContent { CompositionLocalProvider(LocalTellevGraph provides graph) {
                        TellevTheme { ChatScreen(it) }
                    } }
                }
            }
            waitUntil("initial load") { !vm.uiState.value.isLoading }
            withContext(Dispatchers.Main) { vm.selectCharacter(card.id) }
            waitUntil("card scripts") { vm.uiState.value.characterUiExtensionId != null && !vm.uiState.value.isLoading }
            val extensionId = requireNotNull(vm.uiState.value.characterUiExtensionId)
            val webView = (graph.extensionHost as app.tellev.core.extension.WebViewJsExtensionHost)
                .webViewForUi(extensionId)
            assertNotNull(webView)
            tapCardButton()
            waitUntil("runtime attachment") { withContext(Dispatchers.Main) { webView?.parent != null } }
            assertTrue(withContext(Dispatchers.Main) { webView!!.height > 0 })
            delay(500)
            File(instrumentation.targetContext.getExternalFilesDir(null), "xuanhun-fullscreen.png")
                .outputStream().use { instrumentation.uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            Unit
        } finally {
            withContext(Dispatchers.Main) { models.clear(); activity.finish() }
            graph.dataStore.deleteCharacter(card.id)
            graph.dataStore.layout.chats.resolve(card.id).toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(label: String, predicate: suspend () -> Boolean) {
        try {
            withTimeout(20_000) { while (!predicate()) delay(100) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label", error)
        }
    }

    private fun tapCardButton() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screenshot = automation.takeScreenshot()
        val x = screenshot.width * 0.85f
        val y = screenshot.height * 0.08f
        screenshot.recycle()
        repeat(2) {
            val downTime = SystemClock.uptimeMillis()
            automation.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                x, y, 0), true)
            automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP,
                x, y, 0), true)
            SystemClock.sleep(300)
        }
    }
}
