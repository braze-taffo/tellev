package app.tellev.core.memory

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.provider.OpenAiCompatibleAdapter
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MemorySummaryResponseTest {
    @Test fun `empty response diagnostics distinguish reasoning and preserve retryable messages`(): Unit = runBlocking {
        val validResponse = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"summary\\\":\\\"明天见面\\\",\\\"state\\\":[],\\\"events\\\":[]}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n"
        val cases = listOf(
            Triple("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private-thought\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n", "输出额度已耗尽", "length"),
            Triple("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n", "没有返回正文", "stop"),
            Triple("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private-thought\"}}]}\n", "只返回推理", "未提供"),
        )
        for ((firstResponse, errorKind, finishReason) in cases) {
            val root = Files.createTempDirectory("memory-response-")
            try {
                val requests = mutableListOf<String>()
                val adapter = OpenAiCompatibleAdapter(client = OkHttpClient.Builder().addInterceptor { chain ->
                    val buffer = Buffer()
                    chain.request().body!!.writeTo(buffer)
                    requests += buffer.readUtf8()
                    val wire = if (requests.size == 1) firstResponse else validResponse
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(wire.toResponseBody("text/event-stream".toMediaType())).build()
                }.build())
                val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
                disk.bootstrap()
                val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), Secrets())
                service.settings.write(MemorySettings(enabled = true, customBaseUrl = "https://fixture.invalid", customApiKey = "private-key", customModel = "summary-model"))
                val initial = ChatSession("response-test", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
                disk.saveChatSession(initial)
                service.initialize(initial, MemoryMode.ARCHIVE)
                disk.saveChatSession(initial.copy(messages = listOf(
                    ChatMessage("user", MessageRole.User, "User", "明天见面吗？", 1L),
                    ChatMessage("reply", MessageRole.Character, "角色", "好，明天见。", 2L),
                )))
                service.processPending(initial.id)
                val failed = service.store.read(initial.id)!!
                val diagnostic = requireNotNull(failed.error)
                assertTrue(diagnostic.contains(errorKind))
                assertTrue(diagnostic.contains("结束原因：$finishReason"))
                assertTrue(diagnostic.contains("模型：summary-model"))
                assertTrue(diagnostic.contains("正文字符：0"))
                assertTrue(diagnostic.contains(if (firstResponse.contains("private-thought")) "推理字符：15" else "推理字符：0"))
                assertFalse(diagnostic.contains("private-thought"))
                assertFalse(diagnostic.contains("private-key"))
                assertTrue(failed.processed.isEmpty())
                assertTrue(failed.records.isEmpty())
                assertEquals(2, disk.readChatSession(initial.id).messages.size)

                service.processPending(initial.id, force = true)
                val recovered = service.store.read(initial.id)!!
                assertNull(recovered.error)
                assertEquals(1, recovered.records.size)
                assertEquals(setOf("user", "reply"), recovered.processed.keys)
                service.processPending(initial.id, rebuild = true)
                assertNull(service.store.read(initial.id)!!.error)
                // Automatic extraction, retry and backfill use the same request for the same turn.
                assertEquals(3, requests.size)
                assertEquals(requests[0], requests[1])
                assertEquals(requests[1], requests[2])
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    private class Secrets : SecretStore {
        private val entries = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { entries[id] = value }
        override suspend fun readSecret(id: String) = entries[id]
        override suspend fun deleteSecret(id: String) { entries.remove(id) }
        override suspend fun listSecretIds() = entries.keys.toList()
    }
}
