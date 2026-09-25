package app.tellev.core.memory

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.prompt.DefaultPromptEngine
import app.tellev.core.prompt.PromptBuildRequest
import app.tellev.core.prompt.TokenBudget
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

class MemoryFeatureTest {
    @Test fun `mode is immutable and survives chat JSONL`() = runBlocking {
        val root = Files.createTempDirectory("memory-mode-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val selected = ChatSession("session-1", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(selected)
            assertEquals(MemoryMode.ARCHIVE, MemoryMode.of(disk.readChatSession(selected.id)))
            assertTrue(runCatching { selected.withMemoryMode(MemoryMode.EPISODIC) }.isFailure)
            val switched = selected.copy(metadata = JsonObject(selected.metadata + (MemoryMode.METADATA_KEY to JsonPrimitive("EPISODIC"))))
            assertTrue(runCatching { disk.saveChatSession(switched) }.isFailure)
            disk.saveChatSession(selected.copy(metadata = JsonObject(emptyMap())))
            assertEquals(MemoryMode.ARCHIVE, MemoryMode.of(disk.readChatSession(selected.id)))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `saved connection and independent model override resolve without chat selection`() = runBlocking {
        val secrets = TestSecrets()
        ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(
            CustomProviderConfig("memory-api", "Memory", "https://memory.example", "key", "base-model"),
        ))
        val settings = MemorySettings(providerId = "custom:memory-api", providerModel = "small-model")
        val config = settings.textConfig(secrets)
        assertEquals("small-model", config?.model)
        assertEquals("https://memory.example", config?.baseUrl)
    }

    @Test fun `memory sidecar accepts imported unicode chat ids`() = runBlocking {
        val root = Files.createTempDirectory("memory-unicode-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val chat = ChatSession("林晚 剧本 1", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(chat)
            val store = MemoryStore(disk.layout)
            store.initialize(chat, MemoryMode.EPISODIC)
            assertNotNull(store.read(chat.id))
            disk.deleteChatSession(chat.id)
            assertNull(store.read(chat.id))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `archive extracts with separate model and backup contains sidecar`() = runBlocking {
        val root = Files.createTempDirectory("memory-archive-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val adapter = FakeAdapter()
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-1", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            disk.saveChatSession(initial.copy(messages = turn(1)))
            service.processPending(initial.id)
            val document = service.store.read(initial.id)
            assertNotNull(document)
            assertTrue(document!!.records.any { it.kind == "summary_turn" })
            assertTrue(document.records.any { it.kind == "state:person" })
            assertEquals(1, adapter.calls)
            assertEquals("cheap-memory", adapter.lastModel)
            assertTrue(service.context(disk.readChatSession(initial.id), "伦敦大学", emptySet()).contains("伦敦大学"))
            assertEquals("", service.context(disk.readChatSession(initial.id), "伦敦大学", setOf("u1", "a1")))
            val archive = root.resolve("backup.zip")
            disk.exportBackup(archive)
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries()
                var found = false
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.matches(Regex("memory/[a-f0-9]{64}\\.json"))) found = true
                }
                assertTrue(found)
            }
            val restoredRoot = Files.createTempDirectory("memory-restored-")
            try {
                val restored = FileStDataStore(StDirectoryLayout.fromRoot(restoredRoot))
                restored.bootstrap()
                restored.importBackup(archive)
                assertEquals(MemoryMode.ARCHIVE, MemoryMode.of(restored.readChatSession(initial.id)))
                assertTrue(MemoryStore(restored.layout).read(initial.id)!!.records.isNotEmpty())
            } finally { restoredRoot.toFile().deleteRecursively() }
            disk.deleteChatSession(initial.id)
            assertNull(service.store.read(initial.id))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `episodic mode batches ten replies and retrieves Chinese terms locally`() = runBlocking {
        val root = Files.createTempDirectory("memory-episode-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val adapter = FakeAdapter()
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-2", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.EPISODIC)
            disk.saveChatSession(initial.copy(messages = (1..9).flatMap(::turn)))
            service.processPending(initial.id)
            assertEquals(0, adapter.calls)
            disk.saveChatSession(initial.copy(messages = (1..10).flatMap(::turn)))
            service.processPending(initial.id)
            assertEquals(1, adapter.calls)
            assertTrue(service.store.read(initial.id)!!.records.any { it.kind == "fact" })
            assertTrue(service.context(disk.readChatSession(initial.id), "她去哪里留学", emptySet()).contains("伦敦大学"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `global pause makes no extraction request or injection`() = runBlocking {
        val root = Files.createTempDirectory("memory-off-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val adapter = FakeAdapter()
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            val chat = ChatSession("session-3", "Test", "char", null, turn(1)).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(chat)
            service.settings.write(MemorySettings(enabled = false, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            service.processPending(chat.id, rebuild = true)
            assertEquals(0, adapter.calls)
            assertEquals("", service.context(chat, "伦敦", emptySet()))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `memory provider failure leaves chat intact and work retryable`() = runBlocking {
        val root = Files.createTempDirectory("memory-failure-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val service = MemoryService(disk, ProviderRegistry(emptyList()), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "missing-adapter"))
            val initial = ChatSession("session-f", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            disk.saveChatSession(initial.copy(messages = turn(1)))
            service.processPending(initial.id)
            assertEquals(2, disk.readChatSession(initial.id).messages.size)
            val document = service.store.read(initial.id)!!
            assertEquals("待重试", document.stage)
            assertTrue(document.processed.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `edited source invalidates derived memory and preserves manual correction`() = runBlocking {
        val root = Files.createTempDirectory("memory-edit-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val service = MemoryService(disk, ProviderRegistry(listOf(FakeAdapter())), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-4", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            val chat = initial.copy(messages = turn(1))
            disk.saveChatSession(chat)
            service.processPending(chat.id)
            val record = service.store.read(chat.id)!!.records.first()
            service.correct(chat.id, record.id, "用户确认林晚去伦敦大学")
            val edited = chat.copy(messages = chat.messages.map { if (it.id == "u1") it.copy(content = "你要去哪里留学？") else it })
            disk.saveChatSession(edited)
            assertEquals("", service.context(edited, "伦敦大学", emptySet()))
            val invalidated = service.store.read(chat.id)!!
            assertTrue(invalidated.needsRebuild)
            assertTrue(invalidated.records.any { it.manual && it.text.contains("用户确认") })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `dedicated memory slot participates in prompt budget`() {
        val preset = GenerationPreset("plain", "Plain", "openai-compatible", maxContextTokens = 600, maxCompletionTokens = 80)
        val result = DefaultPromptEngine().build(PromptBuildRequest(
            character = CharacterCard("char", "林晚", description = "一位学生"),
            persona = null,
            messages = listOf(ChatMessage("old", MessageRole.User, "User", "旧消息".repeat(150), 1L)),
            worldBooks = emptyList(), preset = preset, userInput = "你还记得吗？",
            providerType = "openai-compatible",
            metadata = buildJsonObject { put("tellevMemoryContext", "林晚曾说她要去伦敦大学留学") },
        ))
        assertTrue(result.messages.any { it.content.contains("伦敦大学留学") })
        assertTrue(TokenBudget.estimateTotalTokens(result.messages) <= 520)
    }

    @Test fun `optional vector API indexes and retrieves with configured path`() = runBlocking {
        val calls = AtomicInteger()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val firstLine = reader.readLine().orEmpty()
                    var line = reader.readLine()
                    while (line != null && line.isNotEmpty()) line = reader.readLine()
                    if (firstLine.startsWith("POST /v1/embeddings")) calls.incrementAndGet()
                    val bytes = "{\"data\":[{\"embedding\":[0.2,0.4,0.6]}]}".toByteArray()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(header.toByteArray() + bytes)
                    client.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val root = Files.createTempDirectory("memory-vector-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val service = MemoryService(disk, ProviderRegistry(listOf(FakeAdapter())), secrets)
            service.settings.write(MemorySettings(
                enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory",
                vectorEnabled = true, vectorBaseUrl = "http://127.0.0.1:${server.localPort}/v1",
                vectorModel = "embed-model", vectorPath = "/v1/embeddings",
            ))
            val initial = ChatSession("session-v", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            disk.saveChatSession(initial.copy(messages = turn(1)))
            service.processPending(initial.id)
            val document = service.store.read(initial.id)!!
            assertTrue(document.records.all { it.vector == listOf(0.2f, 0.4f, 0.6f) })
            assertTrue(service.context(disk.readChatSession(initial.id), "伦敦大学", emptySet()).isNotBlank())
            assertTrue(calls.get() >= 3)
        } finally {
            server.close()
            serverThread.join(1000)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `episodic replacement retires superseded fact`() = runBlocking {
        val root = Files.createTempDirectory("memory-lifecycle-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val adapter = FakeAdapter("{\"facts\":[{\"subject\":\"林晚\",\"text\":\"林晚已经决定留在本地\",\"tags\":[\"留学\"],\"importance\":4,\"supersedes\":[\"old-fact\"]}]}")
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-life", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(initial)
            service.store.write(initial.id, MemoryDocument.empty(MemoryMode.EPISODIC).copy(
                records = listOf(MemoryRecord("old-fact", "fact", "林晚决定去伦敦大学留学", subject = "林晚")),
            ))
            disk.saveChatSession(initial.copy(messages = (1..10).flatMap(::turn)))
            service.processPending(initial.id)
            val records = service.store.read(initial.id)!!.records
            assertFalse(records.first { it.id == "old-fact" }.active)
            assertTrue(records.any { it.active && it.text.contains("留在本地") })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `archive rolls twelve turn summaries into one chapter`() = runBlocking {
        val root = Files.createTempDirectory("memory-chapter-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val adapter = FakeAdapter()
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-chapter", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            disk.saveChatSession(initial.copy(messages = (1..12).flatMap(::turn)))
            service.processPending(initial.id)
            val chapterCount = service.store.read(initial.id)!!.records.count { it.kind == "summary_chapter" }
            assertEquals(1, chapterCount)
            assertEquals(13, adapter.calls)
            service.processPending(initial.id)
            assertEquals(13, adapter.calls)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `long chat floor is fully read in bounded extraction slices`() = runBlocking {
        val root = Files.createTempDirectory("memory-long-floor-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val adapter = FakeAdapter()
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), TestSecrets())
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-long", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            val messages = listOf(
                ChatMessage("u-long", MessageRole.User, "User", "前情".repeat(5000) + "末尾甲", 1L),
                ChatMessage("a-long", MessageRole.Character, "林晚", "回答".repeat(5000) + "末尾乙", 2L),
            )
            disk.saveChatSession(initial.copy(messages = messages))
            service.processPending(initial.id)
            assertTrue(adapter.calls > 1)
            assertTrue(adapter.prompts.any { it.contains("末尾甲") })
            assertTrue(adapter.prompts.any { it.contains("末尾乙") })
            assertTrue(adapter.prompts.all { it.length < 9000 })
            assertEquals(messages.map { it.id }.toSet(), service.store.read(initial.id)!!.processed.keys)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `archive injects long-term summary and empty rebuild clears stale records`() = runBlocking {
        val root = Files.createTempDirectory("memory-summary-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val service = MemoryService(disk, ProviderRegistry(listOf(FakeAdapter())), TestSecrets())
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val chat = ChatSession("session-summary", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(chat)
            service.store.write(chat.id, MemoryDocument.empty(MemoryMode.ARCHIVE).copy(records = listOf(
                MemoryRecord("chronicle", "summary_chronicle", "林晚曾在机场坦白留学计划"),
                MemoryRecord("state", "state:relation", "林晚信任用户", subject = "林晚"),
            )))
            val context = service.context(chat, "今天吃什么", emptySet())
            assertTrue(context.contains("机场坦白"))
            assertTrue(context.contains("林晚信任用户"))
            service.processPending(chat.id, rebuild = true)
            assertTrue(service.store.read(chat.id)!!.records.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `edit during extraction cannot resurrect stale memory`() = runBlocking {
        val root = Files.createTempDirectory("memory-race-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = TestSecrets()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val adapter = object : ProviderAdapter {
                override val id = "openai-compatible"
                override val displayName = "Paused"
                override val capabilities = setOf(ProviderCapability.Chat)
                override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
                override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) = flow<GenerateChunk> {
                    started.complete(Unit)
                    release.await()
                    emit(GenerateChunk.Completed("{\"summary\":\"过期的摘要\",\"state\":[],\"events\":[]}"))
                }
            }
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val initial = ChatSession("session-race", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            val chat = initial.copy(messages = turn(1))
            disk.saveChatSession(chat)
            val job = async { service.processPending(chat.id) }
            started.await()
            disk.saveChatSession(chat.copy(messages = chat.messages.map { if (it.id == "u1") it.copy(content = "已经改变的剧情") else it }))
            release.complete(Unit)
            job.await()
            val document = service.store.read(chat.id)!!
            assertTrue(document.needsRebuild)
            assertTrue(document.records.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }

    private fun turn(index: Int): List<ChatMessage> = listOf(
        ChatMessage("u$index", MessageRole.User, "User", "你要去哪里？", index * 2L),
        ChatMessage("a$index", MessageRole.Character, "林晚", "我要去伦敦大学。", index * 2L + 1),
    )

    private class TestSecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private class FakeAdapter(private val customResponse: String? = null) : ProviderAdapter {
        var calls = 0
        var lastModel: String? = null
        val prompts = mutableListOf<String>()
        override val id = "openai-compatible"
        override val displayName = "Fake"
        override val capabilities = setOf(ProviderCapability.Chat)
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
        override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): kotlinx.coroutines.flow.Flow<GenerateChunk> {
            calls++
            lastModel = config.model
            prompts += request.prompt.messages.last().content
            val result = customResponse ?: if (request.prompt.messages.last().content.contains("只提取值得长期"))
                "{\"facts\":[{\"subject\":\"林晚\",\"text\":\"林晚决定去伦敦大学留学\",\"tags\":[\"留学\"],\"importance\":4}]}"
            else "{\"summary\":\"林晚透露留学计划\",\"state\":[{\"kind\":\"person\",\"subject\":\"林晚\",\"text\":\"林晚决定去伦敦大学留学\",\"tags\":[\"留学\"],\"importance\":4}]}"
            return flowOf(GenerateChunk.Completed(result))
        }
    }
}
