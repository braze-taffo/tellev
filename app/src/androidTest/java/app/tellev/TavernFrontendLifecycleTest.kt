package app.tellev

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.model.*
import app.tellev.feature.chat.ChatScreen
import app.tellev.feature.chat.ChatViewModel
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TavernFrontendLifecycleTest {
    @Test fun messageButtonPersistsAndOldFrontendCannotWriteAfterSwitch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".mvuvalidation"))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val graph = TellevGraph.create(instrumentation.targetContext)
        val models = ViewModelStore()
        val id = "frontend-validation-${UUID.randomUUID()}"
        val html = """```html
            <!DOCTYPE html><html><body><button id="validation-button" onclick="replaceVariables({count:2},{type:'chat'}).then(()=>this.textContent='saved')">write</button></body></html>
            ```""".trimIndent()
        try {
            graph.dataStore.bootstrap()
            graph.dataStore.saveCharacter(CharacterCard(id, "Frontend validation"))
            val message = ChatMessage("$id-message", MessageRole.Character, "Fixture", html, 1)
            for (suffix in listOf("a", "b")) graph.dataStore.saveChatSession(ChatSession("$id-$suffix", suffix, id, null, listOf(message)))
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(graph.dataStore, graph.providerRegistry, graph.promptEngine, graph.secretStore,
                    graph.extensionHost, graph.permissionManager).also {
                    models.put("test", it)
                    activity.setContent { TellevTheme { ChatScreen(it) } }
                }
            }
            waitUntil { !vm.uiState.value.isLoading }
            withContext(Dispatchers.Main) { vm.selectCharacter(id) }
            waitUntil { vm.uiState.value.currentSession != null && !vm.uiState.value.isLoading }
            if (vm.uiState.value.currentSession?.id != "$id-a") {
                withContext(Dispatchers.Main) { vm.switchSession("$id-a") }
                waitUntil { vm.uiState.value.currentSession?.id == "$id-a" && !vm.uiState.value.isLoading }
            }
            suspend fun frame(): WebView {
                var found: WebView? = null
                waitUntil { found = withContext(Dispatchers.Main) { webViews(activity.window.decorView).singleOrNull() }; found != null }
                val view = requireNotNull(found)
                waitUntil { evaluate(view, "!!document.getElementById('validation-button')") == "true" }
                return view
            }
            val first = frame()
            val token = vm.currentRuntimeToken("$id-a")
            evaluate(first, "document.getElementById('validation-button').click(); true")
            waitUntil { vm.uiState.value.currentSession?.metadata?.get("variables")?.jsonObject?.get("count") == JsonPrimitive(2) }
            withContext(Dispatchers.Main) { vm.switchSession("$id-b") }
            waitUntil { vm.uiState.value.currentSession?.id == "$id-b" && !vm.uiState.value.isLoading }
            val second = frame()
            assertNotSame(first, second)
            val rejected = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                vm.handleTavernMessageRequest("replaceVariables", """{"variables":{"count":999},"options":{"type":"chat"}}""",
                    {}, { ok, _ -> rejected.complete(!ok) }, token)
            }
            assertTrue(withTimeout(5_000) { rejected.await() })
            assertNull(graph.dataStore.readChatSession("$id-b").metadata["variables"])
            assertEquals(JsonPrimitive(2), graph.dataStore.readChatSession("$id-a").metadata["variables"]?.jsonObject?.get("count"))
            withContext(Dispatchers.Main) { vm.switchSession("$id-a") }
            waitUntil { vm.uiState.value.currentSession?.id == "$id-a" && !vm.uiState.value.isLoading }
            frame()
            assertEquals(1, withContext(Dispatchers.Main) { webViews(activity.window.decorView).size })
            assertEquals(JsonPrimitive(2), vm.uiState.value.currentSession?.metadata?.get("variables")?.jsonObject?.get("count"))
        } finally {
            withContext(Dispatchers.Main) { models.clear(); activity.finish() }
            graph.dataStore.deleteCharacter(id)
            graph.dataStore.layout.chats.resolve(id).toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(15_000) {
        while (!predicate()) delay(25)
    }
    private fun webViews(view: View): List<WebView> = when(view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private suspend fun evaluate(view: WebView, code: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { view.evaluateJavascript(code) { result.complete(it) } }
        return withTimeout(5_000) { result.await() }
    }
}
