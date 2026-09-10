package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Acceptance tests for refactored chat architecture regressions (R1-R4). */
@OptIn(ExperimentalCoroutinesApi::class)
class RefactorAcceptanceTest {
    @Test fun lateImageMustNotAppearInDifferentSession() = exercise { f ->
        withContext(f.main) { f.vm.generateImage("garden", "", false, ProviderCatalog.NOVELAI_IMAGE) }
        withTimeout(5000) { f.imageStarted.await() }
        withContext(f.main) { f.vm.switchSession("b") }
        waitUntil { f.vm.uiState.value.currentSession?.id == "b" && !f.vm.uiState.value.isLoading }
        f.imageGate.complete(Unit)
        waitUntil { !f.vm.uiState.value.isGeneratingImage }
        assertEquals("b", f.vm.uiState.value.currentSession?.id)
        assertEquals(1, GeneratedImageStore(f.disk.layout).read("a").size)
        assertTrue("An image from session a was displayed in session b", f.vm.uiState.value.generatedImages.isEmpty())
    }

    @Test fun variableWriteFailureMustReachUi() = exercise { f ->
        f.failWrites = true
        withContext(f.main) { requireNotNull(f.local).update { it["audit"] = JsonPrimitive(7) } }
        withTimeout(5000) { f.writeFailed.await() }
        val notified = withTimeoutOrNull(2000) {
            waitUntil { f.vm.uiState.value.error?.contains("audit-disk-failure") == true }
            true
        } ?: false
        assertTrue("Accepted variable write failed on disk but the UI has no error", notified)
    }

    @Test fun editedUserMessageMustRunNormalRegexOnce() = exercise { f ->
        withContext(f.main) { f.vm.editMessage(0, "x") }
        waitUntil { f.vm.uiState.value.messages.any { it.id != "original-user" && it.role == MessageRole.User } }
        assertEquals("xx", f.vm.uiState.value.messages.first { it.role == MessageRole.User }.content)
    }

    @Test fun editedUserMessageMustEmitEditEvents() = exercise { f ->
        f.events.clear()
        withContext(f.main) { f.vm.editMessage(0, "x") }
        waitUntil { f.events.any { it.name == StEventCatalog.GENERATION_ENDED } }
        assertTrue("MESSAGE_EDITED disappeared for user edits", f.events.any { it.name == StEventCatalog.MESSAGE_EDITED })
        assertTrue("MESSAGE_UPDATED disappeared for user edits", f.events.any { it.name == StEventCatalog.MESSAGE_UPDATED })
        assertTrue("USER_MESSAGE_RENDERED disappeared for user edits", f.events.any { it.name == StEventCatalog.USER_MESSAGE_RENDERED })
    }

    private fun exercise(block: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tellev-refactor-audit-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val models = ViewModelStore()
        Dispatchers.setMain(main)
        val f = Fixture(FileStDataStore(StDirectoryLayout.fromRoot(root)), main)
        try {
            f.disk.bootstrap()
            val raw = Json.parseToJsonElement("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Fixture","extensions":{"regex_scripts":[{"id":"audit-regex","findRegex":"/x/g","replaceString":"xx","placement":[1],"runOnEdit":true,"disabled":false}]}}}""").jsonObject
            f.disk.saveCharacter(CharacterCard("fixture", "Fixture", raw = raw))
            val user = ChatMessage("original-user", MessageRole.User, "User", "old", 0)
            f.disk.saveChatSession(ChatSession("a", "A", "fixture", null, listOf(user)))
            f.disk.saveChatSession(ChatSession("b", "B", "fixture", null, emptyList()))
            val store = object : StDataStore by f.disk {
                override suspend fun commitChatMutation(base: ChatSession, desired: ChatSession, expectedRevision: Long?, operationId: String?): ChatSession {
                    if (f.failWrites) {
                        f.writeFailed.complete(Unit)
                        throw java.io.IOException("audit-disk-failure")
                    }
                    return f.disk.commitChatMutation(base, desired, expectedRevision, operationId)
                }
            }
            val secrets = Secrets()
            secrets.putSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey", "audit-token")
            secrets.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-apikey", "audit-token")
            ProviderConfigPersistence.saveImageEngine(secrets, ProviderCatalog.NOVELAI_IMAGE)
            val imageAdapter = object : Adapter(ProviderCatalog.NOVELAI_IMAGE) {
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow<GenerateChunk> {
                    f.imageStarted.complete(Unit)
                    f.imageGate.await()
                    emit(GenerateChunk.Completed("AQID"))
                }
            }
            val textAdapter = object : Adapter(ProviderCatalog.OPENAI_COMPATIBLE) {
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow<GenerateChunk> {
                    emit(GenerateChunk.Completed("reply"))
                }
            }
            f.vm = withContext(main) {
                ChatViewModel(store, ProviderRegistry(listOf(imageAdapter, textAdapter)), object : PromptEngine {
                    override fun build(request: PromptBuildRequest) = PromptBuildResult(
                        listOf(PromptMessage(MessageRole.User, content = request.userInput)), emptyList(), null,
                        request.providerType, PromptDiagnostics(emptyList()))
                }, secrets, f.host(), ExtensionPermissionManager()).also { models.put("audit", it) }
            }
            waitUntil { f.vm.uiState.value.characters.isNotEmpty() && !f.vm.uiState.value.isLoading }
            withContext(main) { f.vm.selectCharacter("fixture") }
            waitUntil { f.vm.uiState.value.selectedCharacter != null && !f.vm.uiState.value.isLoading }
            if (f.vm.uiState.value.currentSession?.id != "a") withContext(main) { f.vm.switchSession("a") }
            waitUntil { f.vm.uiState.value.currentSession?.id == "a" && !f.vm.uiState.value.isLoading }
            assertEquals("Fixture must retain its edit regex", "xx", app.tellev.core.regex.CharacterRegexApplier.applyNormal(
                "x", MessageRole.User, f.vm.uiState.value.selectedCharacter, isEdit = true))
            block(f)
        } finally {
            f.imageGate.complete(Unit)
            withContext(main) { models.clear() }
            delay(150)
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10000) {
        while (!predicate()) delay(10)
    }

    private abstract class Adapter(override val id: String) : ProviderAdapter {
        override val displayName = id
        override val capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Images)
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "audit")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
    }

    private class Secrets : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String) = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds() = values.keys.toList()
    }

    private class Fixture(val disk: FileStDataStore, val main: ExecutorCoroutineDispatcher) {
        lateinit var vm: ChatViewModel
        @Volatile var failWrites = false
        var local: LocalVariableBackend? = null
        val imageStarted = CompletableDeferred<Unit>()
        val imageGate = CompletableDeferred<Unit>()
        val writeFailed = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<ExtensionEvent>()
        fun host(): ExtensionHost {
            val flow = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
            return Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
                when (method.name) {
                    "setLocalVariableBackend" -> { local = args[0] as LocalVariableBackend?; Unit }
                    "setContextProvider", "setMessageVariableBackend", "unload", "flushWrites" -> Unit
                    "getEvents" -> flow
                    "emit", "reportHostEvent" -> { val e = args[0] as ExtensionEvent; events.add(e); flow.tryEmit(e); Unit }
                    "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                    else -> error("Unexpected host call: ${method.name}")
                }
            } as ExtensionHost
        }
    }
}
