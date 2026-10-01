package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.*
import app.tellev.core.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Reproductions of GitHub issues #6, #7 and #8 against real routes and JSONL storage. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenIssueRegressionTest {
    @Test fun worldInfoCannotReadOutsideWorlds() = runBlocking {
        val root = Files.createTempDirectory("issue6-read-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            root.resolve("sentinel.json").writeText("""{"secret":"outside worlds"}""")
            val router = VirtualApiRouter(disk, ProviderRegistry(emptyList()), Secrets())
            val response = router.route(VirtualApiRequest("POST", "/api/worldinfo/get", body = """{"name":"../sentinel"}"""))
            assertEquals("Traversal read must be rejected", 400, response.status)
            assertFalse(response.body.contains("outside worlds"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun worldInfoCannotOverwriteOutsideWorlds() = runBlocking {
        val root = Files.createTempDirectory("issue6-write-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val sentinel = root.resolve("sentinel.json")
            sentinel.writeText("original")
            val router = VirtualApiRouter(disk, ProviderRegistry(emptyList()), Secrets())
            val response = router.route(VirtualApiRequest("POST", "/api/worldinfo/edit", body = """{"name":"../sentinel","data":{"entries":{}}}"""))
            assertEquals("Traversal write must be rejected", 400, response.status)
            assertEquals("original", sentinel.readText())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failedMirrorMustTryTheNextMirror() = runBlocking {
        val root = Files.createTempDirectory("issue8-retry-")
        try {
            val calls = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.add(chain.request().url.host)
                val failed = chain.request().url.host == "first.test"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (failed) 404 else 200).message("fixture")
                    .body((if (failed) "missing" else "apk bytes").toResponseBody()).build()
            }.build()
            val target = root.resolve("update.apk").toFile()
            UpdateChecker(client).downloadApk(updateInfo(), mirrors(), target) {}
            assertEquals(listOf("first.test", "second.test"), calls)
            assertEquals("apk bytes", target.readText())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun allHttpFailuresMustFailInsteadOfReturningAnApk() = runBlocking {
        val root = Files.createTempDirectory("issue8-all-fail-")
        try {
            val calls = CopyOnWriteArrayList<String>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls.add(chain.request().url.host)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("unavailable").body("failed".toResponseBody()).build()
            }.build()
            val target = root.resolve("update.apk").toFile()
            val error = runCatching { UpdateChecker(client).downloadApk(updateInfo(), mirrors(), target) {} }.exceptionOrNull()
            assertTrue("HTTP failure returned success: $error", error is IOException)
            assertEquals(2, calls.size)
            assertFalse(target.exists())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun invalidNamesAreRejectedByWorldRoutesAndAllNamedStorage() = runBlocking {
        val root = Files.createTempDirectory("issue6-storage-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val router = VirtualApiRouter(disk, ProviderRegistry(emptyList()), Secrets())
            val sentinel = root.resolve("sentinel.json")
            sentinel.writeText("original")
            val names = listOf("../sentinel", "../../escape", "..\\sentinel", "/tmp/escape", root.resolve("escape").toString(),
                "C:\\escape", "C:escape", ".", "..", "", " ", "folder/name", "folder\\name")
            for (name in names) {
                val body = buildJsonObject { put("name", name); put("data", buildJsonObject { put("entries", buildJsonObject {}) }) }.toString()
                for (route in listOf("/api/worldinfo/get", "/api/worldinfo/edit")) {
                    assertEquals("$route accepted '$name'", 400, router.route(VirtualApiRequest("POST", route, body = body)).status)
                }
                val operations: List<suspend () -> Unit> = listOf(
                    { disk.saveWorldBook(WorldBook(name, "World", emptyList())) },
                    { disk.readWorldBook(name); Unit },
                    { disk.deleteWorldBook(name) },
                    { disk.saveCharacter(CharacterCard(name, "Card")) },
                    { disk.readCharacter(name); Unit },
                    { disk.deleteCharacter(name) },
                    { disk.savePersona(Persona(name, "Persona", "")) },
                    { disk.deletePersona(name) },
                    { disk.savePreset(GenerationPreset(id = name, name = "Preset", providerType = ProviderCatalog.OPENAI_COMPATIBLE)) },
                    { disk.readPreset(PresetCategory.OpenAi, name); Unit },
                    { disk.deletePreset(name, ProviderCatalog.OPENAI_COMPATIBLE); Unit },
                    { disk.saveChatSession(ChatSession(name, "Chat", "fixture", null, emptyList())) },
                    { disk.saveChatSession(ChatSession("valid", "Chat", name, null, emptyList())) },
                    { disk.saveChatSession(ChatSession("valid", "Chat", null, name, emptyList())) },
                    { disk.listChatSessionSummaries(characterId = name); Unit },
                    { disk.saveGroup(GroupChat(name, "Group", emptyList())) },
                )
                for ((index, operation) in operations.withIndex()) {
                    val error = runCatching { operation() }.exceptionOrNull()
                    assertTrue("Storage operation $index accepted '$name': $error", error is IllegalArgumentException)
                }
                assertEquals("original", sentinel.readText())
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unicodeAndSpaceWorldNamesStillPreserveRawData() = runBlocking {
        val root = Files.createTempDirectory("issue6-valid-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val router = VirtualApiRouter(disk, ProviderRegistry(emptyList()), Secrets())
            val name = "世界书 lore.v1"
            val data = buildJsonObject { put("entries", buildJsonObject {}); put("custom-field", "preserved") }
            val body = buildJsonObject { put("name", name); put("data", data) }.toString()
            assertEquals(200, router.route(VirtualApiRequest("POST", "/api/worldinfo/edit", body = body)).status)
            val read = router.route(VirtualApiRequest("POST", "/api/worldinfo/get", body = body))
            assertEquals(200, read.status)
            assertEquals(data, Json.parseToJsonElement(read.body))
            assertEquals(data, disk.readWorldBook(name).raw)
            disk.deleteWorldBook(name)
            assertFalse(Files.exists(disk.layout.worlds.resolve("$name.json")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun emptyAndTruncatedDownloadsRetryAndDiscardPartialBytes() = runBlocking {
        for (size in listOf(0, 3)) {
            val root = Files.createTempDirectory("issue8-incomplete-")
            try {
                val calls = CopyOnWriteArrayList<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    calls.add(chain.request().url.host)
                    val text = if (chain.request().url.host == "first.test") "apk bytes".take(size) else "apk bytes"
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body(text.toResponseBody()).build()
                }.build()
                val target = root.resolve("update.apk").toFile()
                target.writeText("old partial file")
                UpdateChecker(client).downloadApk(updateInfo(), mirrors(), target) {}
                assertEquals(2, calls.size)
                assertEquals("apk bytes", target.readText())
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun interruptedDownloadCleansUpAndCancellationIsNotRetried() = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = Files.createTempDirectory("issue8-interrupt-")
            try {
                val calls = CopyOnWriteArrayList<String>()
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    calls.add(chain.request().url.host)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                        .body("apk bytes".toResponseBody()).build()
                }.build()
                val target = root.resolve("update.apk").toFile()
                val error = runCatching {
                    UpdateChecker(client).downloadApk(updateInfo(), mirrors(), target) {
                        if (cancel) throw CancellationException("cancel download") else throw IOException("read interrupted")
                    }
                }.exceptionOrNull()
                assertTrue(if (cancel) error is CancellationException else error is IOException)
                assertEquals(if (cancel) 1 else 2, calls.size)
                assertFalse(target.exists())
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun checksumMismatchDeletesTheDownloadAndDoesNotTryAnotherMirror() = runBlocking {
        val root = Files.createTempDirectory("issue8-checksum-")
        try {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("apk bytes".toResponseBody()).build()
            }.build()
            val target = root.resolve("update.apk").toFile()
            val error = runCatching { UpdateChecker(client).downloadApk(updateInfo().copy(sha256 = "wrong"), mirrors(), target) {} }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, calls)
            assertFalse(target.exists())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun switchingDuringStreamingSavesThePartialOnlyToTheOrigin() = exerciseSwitch(regenerate = false, complete = false)
    @Test fun switchingDuringRegenerationSavesTheSwipeOnlyToTheOrigin() = exerciseSwitch(regenerate = true, complete = false)
    @Test fun switchingAfterCompletionKeepsTheReplyInTheOrigin() = exerciseSwitch(regenerate = false, complete = true)
    @Test fun switchingAfterRegenerationKeepsTheSwipeInTheOrigin() = exerciseSwitch(regenerate = true, complete = true)

    private fun exerciseSwitch(regenerate: Boolean, complete: Boolean) = runBlocking {
        val root = Files.createTempDirectory("issue7-session-switch-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val models = ViewModelStore()
        Dispatchers.setMain(main)
        val completionGate = CompletableDeferred<Unit>()
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            disk.saveCharacter(CharacterCard("fixture", "Fixture"))
            val user = ChatMessage("question", MessageRole.User, "User", "hello", 1)
            val reply = ChatMessage("answer", MessageRole.Character, "Fixture", "old answer", 2, swipes = listOf("old answer"))
            disk.saveChatSession(ChatSession("a", "A", "fixture", null, if (regenerate) listOf(user, reply) else emptyList()))
            val destinationMessage = ChatMessage("b-message", MessageRole.Character, "Fixture", "B stays intact", 1)
            disk.saveChatSession(ChatSession("b", "B", "fixture", null, listOf(destinationMessage)))
            val destination = disk.readChatSession("b")
            val destinationFile = disk.layout.chats.resolve("fixture/b.jsonl")
            val destinationBytes = Files.readAllBytes(destinationFile)
            val events = CopyOnWriteArrayList<ExtensionEvent>()
            val eventFlow = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
            val host = Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
                when (method.name) {
                    "setLocalVariableBackend", "setMessageVariableBackend", "setContextProvider", "unload", "flushWrites" -> Unit
                    "getEvents" -> eventFlow
                    "emit", "reportHostEvent" -> { val event = args[0] as ExtensionEvent; events.add(event); eventFlow.tryEmit(event); Unit }
                    "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                    "emitMutable" -> { val event = args[0] as ExtensionEvent; events.add(event); eventFlow.tryEmit(event); event.payload }
                    else -> error("Unexpected host call: ${method.name}")
                }
            } as ExtensionHost
            val adapter = object : ProviderAdapter {
                override val id = ProviderCatalog.OPENAI_COMPATIBLE
                override val displayName = "fixture"
                override val capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Streaming)
                override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "fixture")
                override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow<GenerateChunk> {
                    emit(GenerateChunk.Delta("from A"))
                    completionGate.await()
                    emit(GenerateChunk.Completed("from A complete"))
                }
            }
            val secrets = Secrets()
            secrets.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-apikey", "fixture")
            val vm = withContext(main) {
                ChatViewModel(disk, ProviderRegistry(listOf(adapter)), object : PromptEngine {
                    override fun build(request: PromptBuildRequest) = PromptBuildResult(
                        listOf(PromptMessage(MessageRole.User, content = request.userInput)), emptyList(), null,
                        request.providerType, PromptDiagnostics(emptyList()))
                }, secrets, host, ExtensionPermissionManager()).also { models.put("audit", it) }
            }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.characters.isNotEmpty() }
            withContext(main) { vm.selectCharacter("fixture") }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.currentSession != null }
            if (vm.uiState.value.currentSession?.id != "a") withContext(main) { vm.switchSession("a") }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.currentSession?.id == "a" }
            withContext(main) { assertTrue(if (regenerate) vm.regenerateResponse("answer") else vm.sendMessage("hello")) }
            waitUntil { vm.uiState.value.streamingText == "from A" }
            if (complete) {
                completionGate.complete(Unit)
                waitUntil { !vm.uiState.value.isGenerating && vm.uiState.value.messages.last().content == "from A complete" }
            }
            withContext(main) { vm.switchSession("b") }
            waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.currentSession?.id == "b" }
            withContext(main) { vm.stopGeneration() }
            completionGate.complete(Unit)
            withContext(main) { /* Drain the UI dispatcher after releasing the old stream. */ }
            val origin = disk.readChatSession("a")
            assertEquals(if (complete) "from A complete" else "from A", origin.messages.last().content)
            if (regenerate) {
                assertEquals(2, origin.messages.size)
                assertEquals(2, origin.messages.last().swipes.size)
                assertEquals("old answer", origin.messages.last().swipes.first())
            }
            assertEquals(destination, disk.readChatSession("b"))
            assertArrayEquals(destinationBytes, Files.readAllBytes(destinationFile))
            assertEquals(destination.messages, vm.uiState.value.messages)
            assertFalse(vm.uiState.value.isGenerating)
            assertEquals("", vm.uiState.value.streamingText)
        } finally {
            completionGate.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(5)
    }

    private fun updateInfo() = UpdateInfo("v1.0.1", "1.0.1", "fixture", "", "", "https://github.com/fixture.apk", 9, "", null)
    private fun mirrors() = listOf(UpdateMirror("first", "first", "https://first.test/"), UpdateMirror("second", "second", "https://second.test/"))

    private class Secrets : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String) = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds() = values.keys.toList()
    }
}
