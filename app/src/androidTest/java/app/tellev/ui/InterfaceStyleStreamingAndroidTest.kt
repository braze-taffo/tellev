package app.tellev.ui

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.LocalTellevGraph
import app.tellev.MainActivity
import app.tellev.R
import app.tellev.TellevGraph
import app.tellev.core.model.CharacterCard
import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import app.tellev.feature.chat.ChatScreen
import app.tellev.feature.chat.ChatViewModel
import app.tellev.ui.theme.InterfaceStyle
import app.tellev.ui.theme.TellevTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A gated in-process provider exercises the real stream/UI/cancellation path. */
class InterfaceStyleStreamingAndroidTest {
    @Test
    fun generationSurvivesStyleSwitchAndDshStopStillCancels() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".mvuvalidation"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val graph = TellevGraph.create(context)
        val models = ViewModelStore()
        val style = mutableStateOf(InterfaceStyle.Dsh)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val provider = object : ProviderAdapter {
            override val id = ProviderCatalog.OPENAI_COMPATIBLE
            override val displayName = "Controlled UI provider"
            override val capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Streaming)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "fixture")
            override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow {
                if (++calls == 1) {
                    emit(GenerateChunk.Delta("Live DSH stream."))
                    gate.await()
                    emit(GenerateChunk.Completed("Completed DSH response."))
                } else {
                    emit(GenerateChunk.Delta("Cancellable DSH stream."))
                    awaitCancellation()
                }
            }
        }
        val secrets = object : SecretStore {
            private val values = ConcurrentHashMap<String, String>()
            override suspend fun putSecret(id: String, value: String) { values[id] = value }
            override suspend fun readSecret(id: String) = values[id]
            override suspend fun deleteSecret(id: String) { values.remove(id) }
            override suspend fun listSecretIds() = values.keys.toList()
        }
        val cardId = "style-stream-${UUID.randomUUID()}"
        try {
            graph.dataStore.bootstrap()
            graph.dataStore.saveCharacter(CharacterCard(cardId, "Style stream fixture", firstMessage = "Stream greeting."))
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(graph.dataStore, ProviderRegistry(listOf(provider)), graph.promptEngine, secrets,
                    graph.extensionHost, graph.permissionManager).also {
                    models.put("style-stream", it)
                    activity.setContent {
                        CompositionLocalProvider(LocalTellevGraph provides graph) {
                            TellevTheme(interfaceStyle = style.value) { ChatScreen(it, interfaceStyle = style.value) }
                        }
                    }
                }
            }
            waitUntil("initial preset") { !vm.uiState.value.isLoading && vm.uiState.value.selectedPreset != null }
            withContext(Dispatchers.Main) { vm.selectCharacter(cardId) }
            waitUntil("initial session") { !vm.uiState.value.isLoading && vm.uiState.value.currentSession != null }
            val sessionId = requireNotNull(vm.uiState.value.currentSession?.id)
            withContext(Dispatchers.Main) { assertTrue(vm.sendMessage("Start the controlled stream.")) }
            waitUntil("first delta state") { vm.uiState.value.streamingText == "Live DSH stream." }
            waitUntil("first delta UI") { nodes().any { it.text?.toString() == "Live DSH stream." } }
            val draft = "Draft written during generation."
            assertTrue(nodes().first { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, draft) }))
            for (next in listOf(InterfaceStyle.Classic, InterfaceStyle.Dsh)) {
                withContext(Dispatchers.Main) { style.value = next }
                waitUntil { nodes().any { it.isEditable && it.text?.toString() == draft } }
                assertTrue(vm.uiState.value.isGenerating)
                assertEquals(sessionId, vm.uiState.value.currentSession?.id)
            }
            gate.complete(Unit)
            waitUntil { !vm.uiState.value.isGenerating }
            waitUntil { nodes().any { it.text?.toString() == "Completed DSH response." } }
            assertEquals("Completed DSH response.", graph.dataStore.readChatSession(sessionId).messages.last().content)
            withContext(Dispatchers.Main) { assertTrue(vm.sendMessage("Start a cancellable stream.")) }
            waitUntil { vm.uiState.value.streamingText == "Cancellable DSH stream." }
            waitUntil("stop button: " + activity.getString(R.string.chat_stop_generation)) {
                nodes().any { it.contentDescription?.toString() == activity.getString(R.string.chat_stop_generation) }
            }
            assertTrue(nodes().first { it.contentDescription?.toString() == activity.getString(R.string.chat_stop_generation) }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil { !vm.uiState.value.isGenerating }
            assertEquals(sessionId, vm.uiState.value.currentSession?.id)
            assertNull(vm.uiState.value.error)
        } finally {
            gate.complete(Unit)
            withContext(Dispatchers.Main) { models.clear(); activity.finish() }
            graph.dataStore.listChatSessionSummaries(characterId = cardId).forEach { graph.dataStore.deleteChatSession(it.id) }
            graph.dataStore.deleteCharacter(cardId)
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private suspend fun waitUntil(label: String = "UI", predicate: suspend () -> Boolean) {
        try {
            withTimeout(15_000) { while (!predicate()) delay(50) }
        } catch (error: TimeoutCancellationException) {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.takeScreenshot()?.let { image ->
                java.io.File(i.targetContext.filesDir, "style-stream-failure.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            throw AssertionError("Timed out: $label; " + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "), error)
        }
    }
}
