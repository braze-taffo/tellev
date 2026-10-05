package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.core.model.ChatSession
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 发送闸门回归：会话装载/切换期间发送控件不可用且草稿/附件保留，生成中仍是停止控件。
 * 全部使用真实 JSONL 存储与本地桩适配器，不触网。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatSendGateTest {

    @Test fun `draft send stays disabled while the chat is loading`() {
        // 有草稿且空闲：可发送。
        assertTrue(chatSendEnabled(hasDraft = true, isGenerating = false, isLoading = false))
        // 正在装载/切换会话：即使有草稿也不可发送（置灰而不是静默拒绝）。
        assertFalse(chatSendEnabled(hasDraft = true, isGenerating = false, isLoading = true))
        // 附件也算草稿；但只要装载或生成任一为真，发送闸门都不可用。
        assertFalse(chatSendEnabled(hasDraft = true, isGenerating = true, isLoading = true))
        // 空输入栏无需门禁。
        assertFalse(chatSendEnabled(hasDraft = false, isGenerating = false, isLoading = false))
        assertFalse(chatSendEnabled(hasDraft = false, isGenerating = false, isLoading = true))
    }

    @Test fun `generating chat keeps the stop control instead of the send control`() {
        // isGenerating=true 时输入栏显示停止控件，发送闸门自然不可用。
        assertFalse(chatSendEnabled(hasDraft = true, isGenerating = true, isLoading = false))
        assertTrue(chatSendEnabled(hasDraft = true, isGenerating = false, isLoading = false))
    }

    @Test fun `composer and screen wire the loading gate`() {
        // 旧 UI 已整体重制：闸门现在由 ui/dsh 的 DshChatScreen 统一计算后传给 DshComposer。
        val screen = sourceOf("ui/dsh/DshChatScreen.kt")
        assertTrue(
            "DshChatScreen must consult the loading gate for canSend",
            screen.contains("chatSendEnabled("),
        )
        assertTrue(
            "the gate must consume the loading state",
            screen.contains("isLoading = state.isLoading"),
        )
        assertTrue(
            "the gate must consume the generating state",
            screen.contains("isGenerating = state.isGenerating"),
        )
    }

    @Test fun `send during a session transition is refused, reported and keeps the draft`() = runBlocking {
        val root = Files.createTempDirectory("tellev-send-gate-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val character = CharacterCard("fixture", "Fixture")
            disk.saveCharacter(character)
            disk.saveChatSession(ChatSession("a", "A", character.id, null, emptyList()))
            disk.saveChatSession(ChatSession("b", "B", character.id, null, emptyList()))
            val store = object : StDataStore by disk {
                // 把「读目标会话」卡住：这正是切换窗口里 isLoading 保持 true 的状态。
                override suspend fun readChatSession(id: String): ChatSession {
                    if (id == "b") {
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                    return disk.readChatSession(id)
                }
            }
            val vm = withContext(main) {
                ChatViewModel(
                    store,
                    ProviderRegistry(listOf(textAdapter)),
                    object : PromptEngine {
                        override fun build(request: PromptBuildRequest) = PromptBuildResult(
                            listOf(PromptMessage(MessageRole.User, content = request.userInput)),
                            emptyList(), null, request.providerType, PromptDiagnostics(emptyList()),
                        )
                    },
                    Secrets(),
                    host(),
                    ExtensionPermissionManager(),
                ).also { models.put("chat", it) }
            }
            waitUntil { !vm.uiState.value.isLoading }
            withContext(main) { vm.selectCharacter(character.id) }
            waitUntil { vm.uiState.value.currentSession?.id == "a" && !vm.uiState.value.isLoading }
            val attachment = Attachment("att-1", "draft.jpg", "image/jpeg", "user/images/draft.jpg", AttachmentSource.Chat)

            withContext(main) { vm.switchSession("b") }
            withTimeout(5_000) { readStarted.await() }
            assertTrue("the transition must still be loading", vm.uiState.value.isLoading)

            // 业务层门禁：拒绝、且经既有 error → Snackbar 通道给出本地化提示（不硬编码中文）。
            assertFalse(vm.sendMessage("草稿", listOf(attachment)))
            assertEquals(UiStrings.get(S.chatvm_send_blocked_loading), vm.uiState.value.error)
            assertTrue("the refused draft must not become a message", vm.uiState.value.messages.isEmpty())
            assertTrue(disk.readChatSession("a").messages.isEmpty())

            // 装载窗口结束：同一份草稿与附件可以直接续发，不会因为刚才的拒绝丢失。
            releaseRead.complete(Unit)
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.currentSession?.id == "b" }
            assertTrue(vm.sendMessage("草稿", listOf(attachment)))
            waitUntil {
                disk.readChatSession("b").messages.any {
                    it.role == MessageRole.User && it.content == "草稿" && it.attachments.single().id == "att-1"
                }
            }
        } finally {
            releaseRead.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }

    private fun sourceOf(name: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "app/src/main/java/app/tellev/$name")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: error("cannot locate $name from ${System.getProperty("user.dir")}")
        }
    }

    private class Secrets : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    /** 本地桩：固定回复一次 Completed，不产生任何网络访问。 */
    private val textAdapter = object : ProviderAdapter {
        override val id = ProviderCatalog.OPENAI_COMPATIBLE
        override val displayName = "stub"
        override val capabilities = setOf(ProviderCapability.Chat)
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "stub")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow<GenerateChunk> {
            emit(GenerateChunk.Completed("stub reply"))
        }
    }

    private fun host(): ExtensionHost {
        val events = MutableSharedFlow<app.tellev.core.extension.ExtensionEvent>(extraBufferCapacity = 128)
        return Proxy.newProxyInstance(
            ExtensionHost::class.java.classLoader,
            arrayOf(ExtensionHost::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "setContextProvider", "setMessageVariableBackend", "setLocalVariableBackend",
                "unload", "flushWrites", "emit", "reportHostEvent", "emitMutable" -> Unit
                "getEvents" -> events
                "snapshotExtensionSettings", "collectInjectedPrompts" -> kotlinx.serialization.json.JsonObject(emptyMap())
                else -> error("Unexpected host call in send-gate test: ${method.name}")
            }
        } as ExtensionHost
    }
}
