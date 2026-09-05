package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.Executors

/** Real JSONL storage and ViewModel; the gate pauses only the disk commit, never the UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatWriteLifecycleTest {
    @Test fun `switch waits for accepted edit and variables and cannot overwrite the destination`() = exercise(false)
    @Test fun `failed save keeps dirty state and blocks switching without crashing the UI`() = exercise(true)

    private fun exercise(fail: Boolean) = runBlocking {
        val root = Files.createTempDirectory("tellev-chat-lifecycle-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val gate = CompletableDeferred<Unit>()
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            disk.saveCharacter(CharacterCard("fixture", "Fixture"))
            val message = ChatMessage("stable", MessageRole.Character, "Fixture", "original", 0,
                swipes = listOf("original", "alternate"))
            val a = ChatSession("a", "A", "fixture", null, listOf(message))
            val b = ChatSession("b", "B", "fixture", null, listOf(message.copy(id = "other", content = "destination")))
            disk.saveChatSession(a)
            disk.saveChatSession(b)
            val started = CompletableDeferred<Unit>()
            var pause = false
            val store = object : StDataStore by disk {
                override suspend fun commitChatMutation(base: ChatSession, desired: ChatSession,
                    expectedRevision: Long?, operationId: String?): ChatSession {
                    if (pause) { started.complete(Unit); gate.await(); if (fail) throw java.io.IOException("injected disk failure") }
                    return disk.commitChatMutation(base, desired, expectedRevision, operationId)
                }
            }
            val host = HostProbe()
            val vm = withContext(main) {
                ChatViewModel(store, ProviderRegistry(emptyList()), object : PromptEngine {
                    override fun build(request: PromptBuildRequest): PromptBuildResult = error("No model calls in storage test")
                }, TestSecrets(), host.api, ExtensionPermissionManager()).also { models.put("chat", it) }
            }
            waitUntil { !vm.uiState.value.isLoading }
            withContext(main) { vm.selectCharacter("fixture") }
            waitUntil { vm.uiState.value.selectedCharacter != null && !vm.uiState.value.isLoading }
            if (vm.uiState.value.currentSession?.id != "a") withContext(main) { vm.switchSession("a") }
            waitUntil { vm.uiState.value.currentSession?.id == "a" }
            pause = true
            val sourceToken = vm.currentRuntimeToken("a")
            withContext(main) {
                vm.editMessage(0, "edited")
                requireNotNull(host.local).update { it["counter"] = JsonPrimitive(7) }
                assertEquals("edited", vm.uiState.value.messages[0].content)
                vm.switchSession("b")
            }
            withTimeout(5_000) { started.await() }
            delay(100)
            assertEquals("a", vm.uiState.value.currentSession?.id)
            assertEquals("original", disk.readChatSession("a").messages[0].content)
            gate.complete(Unit)
            if (fail) {
                waitUntil { vm.uiState.value.error?.contains("injected disk failure") == true }
                assertEquals("a", vm.uiState.value.currentSession?.id)
                assertEquals("edited", vm.uiState.value.messages[0].content)
                assertEquals("original", disk.readChatSession("a").messages[0].content)
                assertEquals("destination", disk.readChatSession("b").messages[0].content)
                withContext(main) { vm.editMessage(0, "must not submit") }
                assertEquals("edited", vm.uiState.value.messages[0].content)
            } else {
            waitUntil { vm.uiState.value.currentSession?.id == "b" }
            val saved = disk.readChatSession("a")
            assertEquals("edited", saved.messages[0].content)
            assertEquals(JsonPrimitive(7), saved.metadata["variables"]?.jsonObject?.get("counter"))
            assertEquals("destination", disk.readChatSession("b").messages[0].content)
            assertNull(disk.readChatSession("b").metadata["variables"])
            assertEquals("destination", vm.uiState.value.messages[0].content)
            val lateReply = CompletableDeferred<Boolean>()
            withContext(main) {
                vm.handleTavernMessageRequest("replaceVariables", """{"variables":{"counter":999},"options":{"type":"chat"}}""",
                    {}, { ok, _ -> lateReply.complete(ok) }, sourceToken)
            }
            assertFalse(withTimeout(5_000) { lateReply.await() })
            assertNull(disk.readChatSession("b").metadata["variables"])
            }
        } finally {
            gate.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }

    private class TestSecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private class HostProbe {
        @Volatile var local: LocalVariableBackend? = null
        private val events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 32)
        val api = Proxy.newProxyInstance(ExtensionHost::class.java.classLoader,
            arrayOf(ExtensionHost::class.java)) { _, method, args ->
            when (method.name) {
                "setLocalVariableBackend" -> { local = args[0] as? LocalVariableBackend; Unit }
                "setContextProvider", "setMessageVariableBackend", "unload", "flushWrites" -> Unit
                "getEvents" -> events
                "emit", "reportHostEvent" -> { events.tryEmit(args[0] as ExtensionEvent); Unit }
                "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                else -> error("Unexpected host call in storage test: ${method.name}")
            }
        } as ExtensionHost
    }
}
