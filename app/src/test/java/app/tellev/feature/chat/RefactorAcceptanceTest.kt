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
    @Test fun reasoningOnlyCompletionDoesNotPersistAnEmptyReply() = exercise { f ->
        f.textResponse = "<think>internal thought</think>"
        assertTrue(withContext(f.main) { f.vm.sendMessage("hello") })
        waitUntil { f.vm.uiState.value.error?.contains("未返回有效回复内容") == true }
        assertFalse(f.vm.uiState.value.messages.any { it.role == MessageRole.Character })
        assertFalse(f.disk.readChatSession("a")!!.messages.any { it.role == MessageRole.Character })
    }

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

    @Test fun userMacrosArePersistedOnceBeforeGeneration() = exercise { f ->
        val userName = f.vm.uiState.value.selectedPersona?.name ?: "User"
        f.textResponse = "{{setvar::count::99}}[{{getvar::count}}]"
        f.events.clear()
        assertTrue(withContext(f.main) { f.vm.sendMessage("{{user}} {{incvar::count}}") })
        waitUntil { f.events.any { it.name == StEventCatalog.GENERATION_ENDED } }
        val saved = f.disk.readChatSession("a")
        assertEquals("$userName 1", saved.messages.last { it.role == MessageRole.User }.content)
        assertEquals("1", (saved.metadata["variables"] as JsonObject)["count"]!!.jsonPrimitive.content)
        assertEquals(f.textResponse, saved.messages.last { it.role == MessageRole.Character }.content)
    }

    @Test fun generationEventFiltersReachProviderWithoutEditingStoredChat() = exercise { f ->
        f.eventTransform = { event ->
            val data = event.payload.getValue("args").jsonArray.first().jsonObject
            if (event.name == StEventCatalog.CHAT_COMPLETION_PROMPT_READY) {
                assertEquals(false, data.getValue("dryRun").jsonPrimitive.boolean)
                assertEquals("raw input", data.getValue("chat").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
                event.payload
            } else if (event.name == StEventCatalog.GENERATE_AFTER_DATA) {
                assertEquals(false, event.payload.getValue("args").jsonArray[1].jsonPrimitive.boolean)
                assertEquals("raw input", data.getValue("prompt").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
                event.payload
            } else {
                assertEquals(StEventCatalog.CHAT_COMPLETION_SETTINGS_READY, event.name)
                val messages = data.getValue("messages").jsonArray
                val updated = JsonObject(messages.single().jsonObject + ("content" to JsonPrimitive("filtered input")))
                buildJsonObject { put("args", JsonArray(listOf(JsonObject(data + ("messages" to JsonArray(listOf(updated))))))) }
            }
        }
        assertTrue(withContext(f.main) { f.vm.sendMessage("raw input") })
        waitUntil { f.events.any { it.name == StEventCatalog.GENERATION_ENDED } }
        assertEquals("filtered input", f.outgoingRequest!!.prompt.messages.single().content)
        assertEquals("raw input", f.disk.readChatSession("a").messages.last { it.role == MessageRole.User }.content)
        val events = f.events.map { it.name }
        assertTrue(events.indexOf(StEventCatalog.CHAT_COMPLETION_PROMPT_READY) < events.indexOf(StEventCatalog.CHAT_COMPLETION_SETTINGS_READY))
    }

    @Test fun scriptGenerationUsesCompletionEventsWithoutChangingStoredChatOrPreset() = exercise { f ->
        val before = f.disk.readChatSession("a")
        val originalPreset = f.vm.uiState.value.selectedPreset
        f.eventTransform = { event ->
            if (event.name != StEventCatalog.CHAT_COMPLETION_SETTINGS_READY) event.payload else {
                val data = event.payload["args"]!!.jsonArray.first().jsonObject
                buildJsonObject { put("args", JsonArray(listOf(JsonObject(data + buildJsonObject {
                    put("temperature", 0.2); put("messages", JsonArray(listOf(buildJsonObject {
                        put("role", "user"); put("content", "filtered script input")
                    })))
                })))) }
            }
        }
        val result = f.context!!.generateText(buildJsonObject { put("user_input", "script input") })!!
        assertEquals("reply", result["text"]!!.jsonPrimitive.content)
        assertEquals("filtered script input", f.outgoingRequest!!.prompt.messages.single().content)
        assertEquals(0.2, f.outgoingRequest!!.preset.temperature!!, 0.0)
        assertEquals(before, f.disk.readChatSession("a"))
        assertEquals(originalPreset, f.vm.uiState.value.selectedPreset)
        val names = f.events.map { it.name }
        assertTrue(names.indexOf(StEventCatalog.GENERATE_AFTER_DATA) < names.indexOf(StEventCatalog.CHAT_COMPLETION_SETTINGS_READY))
        assertTrue(names.indexOf(StEventCatalog.CHAT_COMPLETION_SETTINGS_READY) < names.indexOf("js_generation_ended"))
        assertEquals(1, names.count { it == StEventCatalog.GENERATE_AFTER_DATA })
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
                    f.outgoingRequest = request
                    emit(GenerateChunk.Completed(f.textResponse))
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
        @Volatile var textResponse = "reply"
        @Volatile var outgoingRequest: GenerateRequest? = null
        var eventTransform: (ExtensionEvent) -> JsonObject = { it.payload }
        var local: LocalVariableBackend? = null
        var context: ExtensionContextProvider? = null
        val imageStarted = CompletableDeferred<Unit>()
        val imageGate = CompletableDeferred<Unit>()
        val writeFailed = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<ExtensionEvent>()
        fun host(): ExtensionHost {
            val flow = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
            return Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
                when (method.name) {
                    "setLocalVariableBackend" -> { local = args[0] as LocalVariableBackend?; Unit }
                    "setContextProvider" -> { context = args[0] as ExtensionContextProvider?; Unit }
                    "setMessageVariableBackend", "unload", "flushWrites" -> Unit
                    "getEvents" -> flow
                    "emit", "reportHostEvent" -> { val e = args[0] as ExtensionEvent; events.add(e); flow.tryEmit(e); Unit }
                    "emitMutable" -> { val e = args[0] as ExtensionEvent; events.add(e); flow.tryEmit(e); eventTransform(e) }
                    "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                    else -> error("Unexpected host call: ${method.name}")
                }
            } as ExtensionHost
        }
    }
}
