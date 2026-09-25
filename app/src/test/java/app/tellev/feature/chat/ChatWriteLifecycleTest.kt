package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.memory.MemoryService
import app.tellev.core.prompt.*
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    @Test fun `cancelled generation preparation releases state and permits retry`() = runBlocking {
        val root = Files.createTempDirectory("tellev-generation-recovery-")
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val runtime = ChatSessionRuntime(disk)
        val scope = CoroutineScope(coroutineContext)
        try {
            disk.bootstrap()
            val character = CharacterCard("fixture", "Fixture")
            disk.saveCharacter(character)
            disk.saveChatSession(ChatSession("a", "A", character.id, null, emptyList()))
            val session = disk.readChatSession("a")
            runtime.activateSessionWrites(session)
            val state = MutableStateFlow(ChatUiState(selectedCharacter = character, currentSession = session))
            var attempts = 0
            val host = HostProbe().api
            val registry = ProviderRegistry(emptyList())
            val coordinator = ChatGenerationCoordinator(disk, registry, object : PromptEngine {
                override fun build(request: PromptBuildRequest): PromptBuildResult {
                    attempts++
                    assertTrue(state.value.isGenerating)
                    throw CancellationException("injected preparation cancellation")
                }
            }, host, runtime, app.tellev.core.provider.GenerationRuntimeResolver(disk, registry, TestSecrets()),
                MemoryService(disk, registry, TestSecrets()))
            repeat(2) {
                assertTrue(coordinator.sendMessageWithRole("hello", emptyList(), MessageRole.User,
                    uiState = state, scope = scope, characterScriptJob = null))
                coordinator.generationJob?.join()
                assertNull(state.value.error)
                assertFalse(state.value.isGenerating)
                assertNull(coordinator.generationJob)
                assertNull(coordinator.activeRegeneration)
            }
            assertEquals(2, attempts)
            assertTrue(state.value.messages.all { it.role == MessageRole.User })
        } finally {
            runtime.sessionWriteScope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `switch waits for accepted edit and variables and cannot overwrite the destination`() = exercise(failCount = 0)
    @Test fun `failed save no longer blocks switching and rolls back on re-entry`() = exercise(failCount = Int.MAX_VALUE)
    @Test fun `failed user edit save is reported without launching generation or crashing`() = exercise(failCount = Int.MAX_VALUE, userEdit = true)
    @Test fun `a single failed save recovers in place on the next edit`() = exercise(failCount = 1)

    @Test fun `retire tolerates a failing extension host flush and stays reactivatable`() = runBlocking {
        val root = Files.createTempDirectory("tellev-retire-flush-")
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val runtime = ChatSessionRuntime(disk)
        val host = HostProbe()
        host.failFlush = true
        try {
            disk.bootstrap()
            disk.saveChatSession(ChatSession("a", "A", "fixture", null, emptyList()))
            runtime.activateSessionWrites(disk.readChatSession("a"))
            runtime.retireSessionRuntime(host.api) // 扩展宿主收尾失败不得阻断退休
            runtime.activateSessionWrites(disk.readChatSession("a")) // 上一运行时确已退净
            Unit
        } finally {
            runtime.sessionWriteScope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `unreadable recovery keeps the owner blocked but never wedges the transition`() = runBlocking {
        val root = Files.createTempDirectory("tellev-recovery-read-")
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        var readsBroken = false
        val store = object : StDataStore by disk {
            override suspend fun commitChatMutation(base: ChatSession, desired: ChatSession,
                expectedRevision: Long?, operationId: String?): ChatSession = error("disk failed")
            override suspend fun readChatSession(id: String): ChatSession {
                if (readsBroken) throw java.io.IOException("unreadable")
                return disk.readChatSession(id)
            }
        }
        val runtime = ChatSessionRuntime(store)
        val host = HostProbe()
        try {
            disk.bootstrap()
            disk.saveChatSession(ChatSession("a", "A", "fixture", null, emptyList()))
            runtime.activateSessionWrites(disk.readChatSession("a"))
            val session = disk.readChatSession("a")
            runCatching { runtime.scheduleMetadataSave(session, session.copy(title = "dirty")) {}.await() }
            // 磁盘读不动的恢复：仍然阻断当前写入，但绝不能楔死会话切换。
            readsBroken = true
            val second = runtime.scheduleMetadataSave(session, session.copy(title = "second")) {}
            val failure = runCatching { second.await() }.exceptionOrNull()
            assertTrue(failure?.message?.contains("requires recovery") == true)
            runtime.retireSessionRuntime(host.api)
            readsBroken = false
            runtime.activateSessionWrites(disk.readChatSession("a"))
            Unit
        } finally {
            runtime.sessionWriteScope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private fun exercise(failCount: Int, userEdit: Boolean = false) = runBlocking {
        check(!(userEdit && failCount == 1)) { "组合未定义" }
        val permanentlyBroken = failCount == Int.MAX_VALUE
        val singleFailure = failCount == 1
        var remainingFailures = failCount
        val root = Files.createTempDirectory("tellev-chat-lifecycle-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val gate = CompletableDeferred<Unit>()
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            disk.saveCharacter(CharacterCard("fixture", "Fixture"))
            val message = ChatMessage("stable", if (userEdit) MessageRole.User else MessageRole.Character, "Fixture", "original", 0,
                swipes = listOf("original", "alternate"))
            val a = ChatSession("a", "A", "fixture", null, listOf(message))
            val b = ChatSession("b", "B", "fixture", null, listOf(message.copy(id = "other", content = "destination")))
            disk.saveChatSession(a)
            disk.saveChatSession(b)
            val started = CompletableDeferred<Unit>()
            var pause = false
            val store = object : StDataStore by disk {
                override suspend fun listChatSessions(characterId: String?, groupId: String?): List<ChatSession> =
                    error("Chat entry must not load inactive session bodies")
                override suspend fun commitChatMutation(base: ChatSession, desired: ChatSession,
                    expectedRevision: Long?, operationId: String?): ChatSession {
                    if (pause) { started.complete(Unit); gate.await(); if (remainingFailures > 0) { remainingFailures--; throw java.io.IOException("injected disk failure") } }
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
                if (userEdit) assertTrue(vm.uiState.value.messages.isEmpty())
                else assertEquals("edited", vm.uiState.value.messages[0].content)
                if (!singleFailure) vm.switchSession("b")
            }
            withTimeout(5_000) { started.await() }
            delay(100)
            if (userEdit) assertEquals(0, host.editEvents.get())
            if (!singleFailure) {
                assertEquals("a", vm.uiState.value.currentSession?.id)
                assertEquals("original", disk.readChatSession("a").messages[0].content)
            }
            gate.complete(Unit)
            if (failCount == 0) {
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
            } else if (permanentlyBroken) {
                waitUntil { vm.uiState.value.error?.contains("injected disk failure") == true }
                if (userEdit) assertEquals(0, host.editEvents.get())
                // 一次失败不再把切换卡死：retire 降级为上报，会话照常落到 b。
                waitUntil { vm.uiState.value.currentSession?.id == "b" }
                assertEquals("original", disk.readChatSession("a").messages[0].content)
                assertEquals("destination", disk.readChatSession("b").messages[0].content)
                assertEquals("destination", vm.uiState.value.messages[0].content)
                // 重进 a 从磁盘真相起步：失败编辑的脏视图没有存活。
                withContext(main) { vm.switchSession("a") }
                waitUntil { vm.uiState.value.currentSession?.id == "a" }
                assertEquals("original", vm.uiState.value.messages[0].content)
                if (!userEdit) {
                    // 磁盘仍在坏：再次编辑仍然失败，但只是一次上报，不再楔死。
                    withContext(main) { vm.editMessage(0, "still failing") }
                    waitUntil { vm.uiState.value.messages[0].content == "still failing" }
                    assertEquals("original", disk.readChatSession("a").messages[0].content)
                    withContext(main) { vm.switchSession("b") }
                    waitUntil { vm.uiState.value.currentSession?.id == "b" }
                }
            } else {
                // 单次失败：用户留在会话内，下一次动作原地自愈。
                waitUntil { vm.uiState.value.error?.contains("injected disk failure") == true }
                assertEquals("original", disk.readChatSession("a").messages[0].content)
                assertEquals("edited", vm.uiState.value.messages[0].content) // 恢复前的脏视图
                withContext(main) { vm.editMessage(0, "retry") }
                // 自愈把视图拉回磁盘真相；带着脏 base 的重放按三方合并如实冲突。
                waitUntil { vm.uiState.value.messages[0].content == "original" }
                assertEquals(0, host.editEvents.get())
                withContext(main) { vm.editMessage(0, "third") }
                waitUntil { disk.readChatSession("a").messages[0].content == "third" }
                waitUntil { vm.uiState.value.messages[0].content == "third" }
                assertEquals(1, host.editEvents.get())
                withContext(main) { vm.switchSession("b") }
                waitUntil { vm.uiState.value.currentSession?.id == "b" }
                assertEquals("destination", disk.readChatSession("b").messages[0].content)
            }
        } finally {
            gate.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(10_000) {
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
        val editEvents = java.util.concurrent.atomic.AtomicInteger()
        @Volatile var local: LocalVariableBackend? = null
        @Volatile var failFlush = false
        private val events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 32)
        val api = Proxy.newProxyInstance(ExtensionHost::class.java.classLoader,
            arrayOf(ExtensionHost::class.java)) { _, method, args ->
            when (method.name) {
                "setLocalVariableBackend" -> { local = args[0] as? LocalVariableBackend; Unit }
                "setContextProvider", "setMessageVariableBackend", "unload" -> Unit
                "flushWrites" -> { if (failFlush) throw IllegalStateException("extension flush broken") }
                "getEvents" -> events
                "emit", "reportHostEvent" -> {
                    val event = args[0] as ExtensionEvent
                    if (event.name == StEventCatalog.MESSAGE_EDITED) editEvents.incrementAndGet()
                    events.tryEmit(event)
                    Unit
                }
                "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                else -> error("Unexpected host call in storage test: ${method.name}")
            }
        } as ExtensionHost
    }
}
