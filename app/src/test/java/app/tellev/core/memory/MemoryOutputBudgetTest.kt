package app.tellev.core.memory

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.provider.OpenAiCompatibleAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MemoryOutputBudgetTest {
    @Test fun `reasoning exhausts old budget and automatic processing recovers with migrated default`(): Unit = runBlocking {
        // Replay the observed DeepSeek failure through the real provider adapter.
        withFixture(ProviderCatalog.DEEPSEEK, { request ->
            if (request.getValue("max_tokens").jsonPrimitive.int == 1800) {
                """data: {"choices":[{"delta":{"reasoning_content":"private-thought"},"finish_reason":"length"}],"usage":{"completion_tokens":1800,"completion_tokens_details":{"reasoning_tokens":1800}}}

data: [DONE]
"""
            } else response()
        }) {
            service.settings.write(settings.copy(maxOutputTokens = 1800))
            service.processPending(session.id)
            val failed = service.store.read(session.id)!!
            assertTrue(failed.error!!.contains("输出额度已耗尽"))
            assertTrue(failed.error!!.contains("reasoning_tokens"))
            assertFalse(failed.error!!.contains("private-thought"))
            assertTrue(failed.processed.isEmpty())
            assertTrue(failed.records.isEmpty())
            assertEquals(1, requests.size) // No hidden paid retry.

            // Existing installations do not have maxOutputTokens in stored settings.
            secrets.putSecret("tellev-memory-settings-v1", """{"enabled":true,"providerId":"deepseek","providerModel":"summary-model"}""")
            assertEquals(16384, service.settings.read().maxOutputTokens)
            service.processPending(session.id)
            val recovered = service.store.read(session.id)!!
            assertNull(recovered.error)
            assertEquals(setOf("user", "reply"), recovered.processed.keys)
            assertEquals("明天见面", recovered.records.single().text)
            assertEquals(listOf(1800, 16384), requests.map { it.getValue("max_tokens").jsonPrimitive.int })
            assertEquals(2, disk.readChatSession(session.id).messages.size)
        }
    }

    @Test fun `truncation never commits even a parseable summary and manual retry recovers`(): Unit = runBlocking {
        for (reason in listOf("length", "MAX_TOKENS")) {
            var truncated = true
            withFixture(wire = { response(if (truncated) reason else "stop") }) {
                service.processPending(session.id)
                val failed = service.store.read(session.id)!!
                assertTrue(failed.error!!.contains("正文不完整"))
                assertTrue(failed.records.isEmpty())
                assertTrue(failed.processed.isEmpty())
                assertEquals(1, requests.size)
                truncated = false
                service.processPending(session.id, force = true)
                assertNull(service.store.read(session.id)!!.error)
                assertEquals(1, service.store.read(session.id)!!.records.size)
            }
        }
    }

    @Test fun `custom output budget overrides connection extra body without changing other options`(): Unit = runBlocking {
        withFixture {
            val advanced = OpenAiCompatibilitySettings(
                maxTokensField = "max_completion_tokens",
                extraBody = JsonObject(mapOf(
                    "max_tokens" to JsonPrimitive(1800),
                    "max_completion_tokens" to JsonPrimitive(1800),
                    "reasoning_effort" to JsonPrimitive("low"),
                )),
            )
            ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(
                CustomProviderConfig("memory", "Memory", "https://fixture.invalid", model = "summary-model", advanced = advanced),
            ))
            service.settings.write(settings.copy(providerId = "custom:memory", maxOutputTokens = 32768))
            assertEquals(32768, service.settings.read().maxOutputTokens)
            service.processPending(session.id)
            service.processPending(session.id, rebuild = true)
            assertEquals(2, requests.size)
            for (request in requests) {
                assertEquals(32768, request.getValue("max_completion_tokens").jsonPrimitive.int)
                assertFalse(request.containsKey("max_tokens"))
                assertEquals("low", request.getValue("reasoning_effort").jsonPrimitive.content)
            }
            assertEquals(advanced, ProviderConfigPersistence.listCustomConfigs(secrets).single().advanced)
            assertNull(service.store.read(session.id)!!.error)
        }
    }

    @Test fun `chapter and chronicle merging use the same configured output budget`(): Unit = runBlocking {
        withFixture {
            service.settings.write(settings.copy(maxOutputTokens = 8192))
            val turns = (1..11).map {
                MemoryRecord("turn-$it", "summary_turn", "既有剧情 $it", sourceIds = listOf("source-$it"))
            }
            val chapters = (1..7).map {
                MemoryRecord("chapter-$it", "summary_chapter", "既有章节 $it", sourceIds = listOf("old-$it"))
            }
            service.store.write(session.id, MemoryDocument.empty(MemoryMode.ARCHIVE).copy(records = turns + chapters))
            service.processPending(session.id)
            val result = service.store.read(session.id)!!
            assertNull(result.error)
            assertEquals(8, result.records.count { it.kind == "summary_chapter" })
            assertEquals(1, result.records.count { it.kind == "summary_chronicle" })
            assertEquals(listOf(8192, 8192, 8192), requests.map { it.getValue("max_tokens").jsonPrimitive.int })
        }
    }

    private class Fixture(
        val service: MemoryService,
        val disk: FileStDataStore,
        val secrets: Secrets,
        val session: ChatSession,
        val settings: MemorySettings,
        val requests: MutableList<JsonObject>,
    )

    private suspend fun withFixture(
        providerId: String = ProviderCatalog.OPENAI_COMPATIBLE,
        wire: (JsonObject) -> String = { response() },
        block: suspend Fixture.() -> Unit,
    ) {
        val root = Files.createTempDirectory("memory-budget-")
        try {
            val requests = mutableListOf<JsonObject>()
            val adapter = OpenAiCompatibleAdapter(providerId = providerId, client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                val request = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                requests += request
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(wire(request).toResponseBody("text/event-stream".toMediaType())).build()
            }.build())
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
            disk.bootstrap()
            val secrets = Secrets()
            secrets.putSecret("provider-$providerId-baseurl", "https://fixture.invalid")
            val service = MemoryService(disk, ProviderRegistry(listOf(adapter)), secrets)
            val settings = MemorySettings(enabled = true, providerId = providerId, providerModel = "summary-model")
            service.settings.write(settings)
            val initial = ChatSession("budget", "Test", "char", null, emptyList()).withMemoryMode(MemoryMode.ARCHIVE)
            disk.saveChatSession(initial)
            service.initialize(initial, MemoryMode.ARCHIVE)
            val session = initial.copy(messages = listOf(
                ChatMessage("user", MessageRole.User, "User", "明天见面吗？", 1L),
                ChatMessage("reply", MessageRole.Character, "角色", "好，明天见。", 2L),
            ))
            disk.saveChatSession(session)
            Fixture(service, disk, secrets, session, settings, requests).block()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class Secrets : SecretStore {
        private val entries = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { entries[id] = value }
        override suspend fun readSecret(id: String) = entries[id]
        override suspend fun deleteSecret(id: String) { entries.remove(id) }
        override suspend fun listSecretIds() = entries.keys.toList()
    }

    private companion object {
        fun response(finishReason: String = "stop") = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"summary\\\":\\\"明天见面\\\",\\\"state\\\":[],\\\"events\\\":[]}\"},\"finish_reason\":\"$finishReason\"}]}\n\ndata: [DONE]\n"
    }
}
