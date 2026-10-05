package app.tellev.core.memory

import app.tellev.core.model.ChatSession
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Correction history + rollback (A4) and context-viewer provenance (A3)
 * for the long-term memory store.
 */
class MemoryCorrectionHistoryTest {

    private class TestSecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private class NoopAdapter : ProviderAdapter {
        override val id = "openai-compatible"
        override val displayName = "Noop"
        override val capabilities = setOf(ProviderCapability.Chat)
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
        override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) =
            flowOf(GenerateChunk.Completed("{}"))
    }

    private fun newService(rootTag: String): Triple<FileStDataStore, MemoryService, java.nio.file.Path> {
        val root = Files.createTempDirectory(rootTag)
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        runBlocking { disk.bootstrap() }
        val service = MemoryService(disk, ProviderRegistry(listOf(NoopAdapter())), TestSecrets())
        return Triple(disk, service, root)
    }

    @Test fun `correction pushes history and rollback restores`() = runBlocking {
        val (disk, service, root) = newService("memory-history-")
        try {
            val session = ChatSession("s1", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(session)
            service.initialize(session, MemoryMode.EPISODIC)
            val document = service.store.read("s1")!!
            service.store.write("s1", document.copy(records = listOf(
                MemoryRecord(id = "r1", kind = "fact", text = "原始版本"),
            )))

            service.correct("s1", "r1", "第一次修正")
            service.correct("s1", "r1", "第二次修正")
            var record = service.store.read("s1")!!.records.single()
            assertEquals("第二次修正", record.text)
            assertEquals(listOf("第一次修正", "原始版本"), record.history.map { it.text })

            // Rollback to the oldest version; the current text goes back onto the stack.
            assertTrue(service.rollbackCorrection("s1", "r1", 1))
            record = service.store.read("s1")!!.records.single()
            assertEquals("原始版本", record.text)
            assertEquals("第二次修正", record.history.first().text)
            assertTrue(record.manual)

            // Out-of-range and missing ids are a no-op returning false.
            assertFalse(service.rollbackCorrection("s1", "r1", 99))
            assertFalse(service.rollbackCorrection("s1", "missing", 0))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `correction history is capped at five entries`() = runBlocking {
        val (disk, service, root) = newService("memory-history-cap-")
        try {
            val session = ChatSession("s1", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(session)
            service.initialize(session, MemoryMode.EPISODIC)
            val document = service.store.read("s1")!!
            service.store.write("s1", document.copy(records = listOf(
                MemoryRecord(id = "r1", kind = "fact", text = "v0"),
            )))
            for (round in 1..8) service.correct("s1", "r1", "v$round")
            val record = service.store.read("s1")!!.records.single()
            assertEquals("v8", record.text)
            assertEquals(5, record.history.size)
            assertEquals(listOf("v7", "v6", "v5", "v4", "v3"), record.history.map { it.text })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `contextDetail carries provenance for injected records`() = runBlocking {
        val (disk, service, root) = newService("memory-provenance-")
        try {
            val message = app.tellev.core.model.ChatMessage(
                id = "u1", role = app.tellev.core.model.MessageRole.User, name = "User",
                content = "问一个普通问题", createdAtMillis = 0L,
            )
            val session = ChatSession("s1", "Test", "char", null, listOf(message)).withMemoryMode(MemoryMode.EPISODIC)
            disk.saveChatSession(session)
            service.initialize(session, MemoryMode.EPISODIC)
            service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://example.invalid", customModel = "cheap-memory"))
            val document = service.store.read("s1")!!
            // sourceIds point at a real message whose hash matches `processed`,
            // so the record survives validation.
            val processedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(message.content.toByteArray())
                .joinToString("") { "%02x".format(it) }
            service.store.write("s1", document.copy(
                processed = mapOf("u1" to processedHash),
                records = listOf(
                    MemoryRecord(
                        id = "r1", kind = "fact", text = "林晚决定去伦敦大学留学",
                        sourceIds = listOf("u1"),
                    ),
                    MemoryRecord(id = "r2", kind = "fact", text = "另一条不相关记录", active = false),
                ),
            ))
            val detail = service.contextDetail(disk.readChatSession("s1")!!, "留学", emptySet())
            assertTrue(detail.text.contains("林晚决定去伦敦大学留学"))
            val injected = detail.injected.associateBy { it.recordId }
            val r1 = injected.getValue("r1")
            assertTrue(r1.score != null && r1.score > 0.0)
            assertEquals(listOf("u1"), r1.sourceIds)
            assertFalse(injected.containsKey("r2"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `contextDetail is empty when memory mode is off`() = runBlocking {
        val (disk, service, root) = newService("memory-provenance-off-")
        try {
            val session = ChatSession("s1", "Test", "char", null, emptyList())
            disk.saveChatSession(session)
            val detail = service.contextDetail(session, "anything", emptySet())
            assertEquals("", detail.text)
            assertTrue(detail.injected.isEmpty())
        } finally { root.toFile().deleteRecursively() }
    }
}
