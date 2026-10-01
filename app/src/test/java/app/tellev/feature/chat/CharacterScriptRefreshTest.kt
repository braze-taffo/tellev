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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Save notifications must refresh card data without restarting unchanged scripts. */
@OptIn(ExperimentalCoroutinesApi::class)
class CharacterScriptRefreshTest {
    private fun card() = CharacterImporter().importFromJson("""
        {"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Fixture","extensions":{
          "tavern_helper":{"variables":{"counter":0},"scripts":[
            {"type":"script","id":"boot","name":"Boot","enabled":true,"content":"export {};"}
          ]}}}}
    """.trimIndent()).copy(id = "fixture")

    @Test fun `script boot reads canonical variables without saving them`() {
        val source = CharacterTavernHelperScripts.buildIsolatedScriptSource(card())
        assertFalse("Boot must not trigger a character save and another reload", source.contains("insertVariables"))
        assertTrue(source.contains("__tellevLoadScripts"))
    }

    @Test fun `variable saves preserve runtime and executable changes still reload`() = exercise(card())

    @Test fun `unchanged canonical variables do not save the card again`() = runBlocking {
        val root = Files.createTempDirectory("tellev-variable-noop-")
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val sessions = ChatSessionRuntime(disk)
        try {
            disk.bootstrap()
            disk.saveCharacter(card())
            disk.saveChatSession(ChatSession("history", "History", "fixture", null, emptyList()))
            val session = disk.readChatSession("history")
            sessions.activateSessionWrites(session)
            val state = kotlinx.coroutines.flow.MutableStateFlow(ChatUiState(selectedCharacter = disk.readCharacter("fixture"), currentSession = session))
            var saves = 0
            val store = object : StDataStore by disk {
                override suspend fun saveCharacter(card: CharacterCard) { saves++; disk.saveCharacter(card) }
            }
            val unchanged = buildJsonObject {
                put("type", "character")
                put("variables", buildJsonObject { put("counter", 0) })
            }
            repeat(3) { ChatTavernStorage.call("replaceVariables", unchanged, store, state, sessions) }
            assertEquals(0, saves)
            val changed = JsonObject(unchanged + ("variables" to buildJsonObject { put("counter", 1) }))
            ChatTavernStorage.call("replaceVariables", changed, store, state, sessions)
            assertEquals(1, saves)
            assertEquals(JsonPrimitive(1), CharacterTavernHelperScripts.extractCharacterVariables(disk.readCharacter("fixture"))?.get("counter"))
            ChatTavernStorage.call("replaceVariables", changed, store, state, sessions)
            assertEquals(1, saves)
            Unit
        } finally {
            sessions.sessionWriteScope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `supplied treatment card saves preserve runtime`() = supplied("被买下治疗少女疾病.png")

    @Test fun `supplied phone card saves preserve runtime`() = supplied("真实网恋小樱花.png")

    private fun supplied(name: String) = runBlocking {
        val directory = System.getenv("TELLEV_CARD_FIXTURES")
        assumeTrue("Set TELLEV_CARD_FIXTURES to replay supplied PNGs", !directory.isNullOrBlank())
        val path = Path.of(requireNotNull(directory), name)
        check(Files.isRegularFile(path)) { "Missing supplied card: $name" }
        val bytes = Files.readAllBytes(path)
        val card = CharacterImporter().importFromBytes(bytes, name).copy(id = "fixture")
        assertTrue(CharacterTavernHelperScripts.extract(card).isNotEmpty())
        exercise(card, bytes)
    }

    private fun exercise(card: CharacterCard, png: ByteArray? = null) = runBlocking {
        val root = Files.createTempDirectory("tellev-script-refresh-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val loads = AtomicInteger()
        val unloads = AtomicInteger()
        val reads = AtomicInteger()
        val responseGate = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            if (png == null) disk.saveCharacter(card) else disk.importCharacter(card, png, "fixture.png")
            disk.saveChatSession(ChatSession("history", "History", card.id, null, emptyList()))
            val store = object : StDataStore by disk {
                override suspend fun readCharacter(id: String): CharacterCard = disk.readCharacter(id).also { reads.incrementAndGet() }
            }
            val events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 64)
            val proxy = Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
                when (method.name) {
                    "setContextProvider", "setLocalVariableBackend", "setMessageVariableBackend", "flushWrites" -> Unit
                    "getEvents" -> events
                    "emit", "reportHostEvent" -> { events.tryEmit(args[0] as ExtensionEvent); Unit }
                    "emitMutable" -> (args[0] as ExtensionEvent).payload
                    "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                    else -> error("Unexpected host call: ${method.name}")
                }
            } as ExtensionHost
            val host = object : ExtensionHost by proxy {
                override suspend fun load(manifest: ExtensionManifest, scriptSource: String): ExtensionHandle {
                    loads.incrementAndGet()
                    return ExtensionHandle(manifest.id, manifest.name, true)
                }
                override suspend fun unload(extensionId: String) { unloads.incrementAndGet() }
            }
            val secrets = object : SecretStore {
                private val values = mutableMapOf<String, String>()
                override suspend fun putSecret(id: String, value: String) { values[id] = value }
                override suspend fun readSecret(id: String) = values[id]
                override suspend fun deleteSecret(id: String) { values.remove(id) }
                override suspend fun listSecretIds() = values.keys.toList()
            }
            val provider = object : ProviderAdapter {
                override val id = ProviderCatalog.OPENAI_COMPATIBLE
                override val displayName = "Fixture"
                override val capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Streaming)
                override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "fixture")
                override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow {
                    requests.incrementAndGet()
                    emit(GenerateChunk.Delta("fixture"))
                    responseGate.await()
                    emit(GenerateChunk.Completed("fixture reply", "stop"))
                }
            }
            val vm = withContext(main) {
                ChatViewModel(store, ProviderRegistry(listOf(provider)), object : PromptEngine {
                    override fun build(request: PromptBuildRequest) = PromptBuildResult(
                        emptyList(), emptyList(), 100, provider.id, PromptDiagnostics(emptyList()))
                }, secrets, host, ExtensionPermissionManager()).also { models.put("chat", it) }
            }
            waitUntil { !vm.uiState.value.isLoading }
            withContext(main) { vm.selectCharacter(card.id) }
            waitUntil { vm.uiState.value.characterUiExtensionId != null }
            val originalId = vm.uiState.value.characterUiExtensionId
            val initialLoads = loads.get()
            val initialUnloads = unloads.get()

            withContext(main) { assertTrue(vm.sendMessage("hello")) }
            waitUntil { vm.uiState.value.isGenerating && vm.uiState.value.streamingText == "fixture" }

            // The canonical backend saves exactly the same way as insertVariables at boot.
            // A real variable update must reach the UI, but must not unload the runtime.
            repeat(3) { index ->
                val current = disk.readCharacter(card.id)
                val rawData = current.raw["data"]?.jsonObject ?: current.raw
                val extensions = rawData["extensions"]!!.jsonObject
                val helper = extensions["tavern_helper"]!!.jsonObject
                val variables = JsonObject((helper["variables"] as? JsonObject).orEmpty() + ("counter" to JsonPrimitive(index + 1)))
                val changedData = JsonObject(rawData + ("extensions" to JsonObject(extensions +
                    ("tavern_helper" to JsonObject(helper + ("variables" to variables))))))
                val changed = current.copy(raw = if (current.raw["data"] != null) JsonObject(current.raw + ("data" to changedData)) else changedData)
                val beforeReads = reads.get()
                disk.saveCharacter(changed)
                waitUntil { reads.get() > beforeReads && CharacterTavernHelperScripts.extractCharacterVariables(
                    requireNotNull(vm.uiState.value.selectedCharacter))?.get("counter") == JsonPrimitive(index + 1) }
                withContext(main) { Unit } // Let the refresh finish, including its reload decision.
                assertEquals("A variable save restarted the card runtime", initialLoads, loads.get())
                assertEquals(initialUnloads, unloads.get())
                assertEquals(originalId, vm.uiState.value.characterUiExtensionId)
                assertEquals(card.name, vm.uiState.value.selectedCharacter?.name)
                assertTrue("Card variable writes interrupted generation", vm.uiState.value.isGenerating)
            }

            responseGate.complete(Unit)
            waitUntil { !vm.uiState.value.isGenerating && vm.uiState.value.messages.lastOrNull()?.content == "fixture reply" }
            assertNull(vm.uiState.value.error)
            assertEquals("fixture reply", disk.readChatSession("history").messages.last().content)
            withContext(main) { assertTrue(vm.sendMessage("retry")) }
            waitUntil { vm.uiState.value.messages.count { it.content == "fixture reply" } == 2 && !vm.uiState.value.isGenerating }
            assertEquals(2, requests.get())

            // Removing an enabled script changes executable content and must unload it.
            val current = disk.readCharacter(card.id)
            val rawData = current.raw["data"]?.jsonObject ?: current.raw
            val extensions = rawData["extensions"]!!.jsonObject
            val helper = extensions["tavern_helper"]!!.jsonObject
            val disabledHelper = JsonObject(helper + ("scripts" to JsonArray(emptyList())))
            val disabledData = JsonObject(rawData + ("extensions" to JsonObject(extensions + ("tavern_helper" to disabledHelper))))
            disk.saveCharacter(current.copy(raw = if (current.raw["data"] != null) JsonObject(current.raw + ("data" to disabledData)) else disabledData))
            waitUntil { vm.uiState.value.characterUiExtensionId == null }
            assertTrue(unloads.get() > initialUnloads)
            assertEquals("history", vm.uiState.value.currentSession?.id)
            Unit
        } finally {
            responseGate.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }
}
