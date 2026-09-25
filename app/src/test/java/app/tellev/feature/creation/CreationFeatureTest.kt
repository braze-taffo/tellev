package app.tellev.feature.creation

import app.tellev.core.model.TellevError
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.codec.WorldBookCodec
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import app.tellev.feature.chat.TavernRenderParser
import app.tellev.feature.chat.TavernRenderSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CreationFeatureTest {
    @Test
    fun connectionRetryOnlyAllowsPreRequestConnectFailureOnce() {
        val connectFailure = TellevError(
            code = "provider_network",
            message = "failed to connect to api.example/172.67.167.23 (port 443) after 30000ms: isConnected failed: ETIMEDOUT",
            retryable = true,
            causeType = "SocketTimeoutException",
        )
        assertTrue(shouldRetryCreationConnect(connectFailure, receivedDelta = false, alreadyRetried = false))
        assertFalse(shouldRetryCreationConnect(connectFailure, receivedDelta = true, alreadyRetried = false))
        assertFalse(shouldRetryCreationConnect(connectFailure, receivedDelta = false, alreadyRetried = true))
        assertFalse(shouldRetryCreationConnect(
            connectFailure.copy(message = "Read timed out"), receivedDelta = false, alreadyRetried = false,
        ))
        assertFalse(shouldRetryCreationConnect(
            connectFailure.copy(code = "provider_http_500"), receivedDelta = false, alreadyRetried = false,
        ))
    }

    @Test
    fun sourceChunkingCoversEveryCharacterAndPreservesFinalTail() {
        val source = (1..350).joinToString("\n") { "第${it}段：${"设定".repeat(30)}" } + "\n最后一句。"
        val chunks = mutableListOf<SourceChunk>()
        var cursor = 0
        while (true) {
            val chunk = nextSourceChunk(source, cursor, maxChars = 1_200) ?: break
            assertEquals(cursor, chunk.start)
            assertTrue(chunk.end > cursor)
            chunks += chunk
            cursor = chunk.end
        }
        assertEquals(source.length, cursor)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertTrue(chunks.size > 10)
    }

    @Test
    fun resumedExtractionKeepsItsAbsoluteChunkNumber() {
        val source = "世界设定。".repeat(3_000)
        val first = nextSourceChunk(source, 0)!!
        val count = countSourceChunks(source, first.end)
        assertEquals(1, count.completed)
        assertTrue(count.total > count.completed)
    }

    @Test
    fun agentPatchPreservesUnmentionedCardFieldsAndLore() {
        val original = CreationSession(
            kind = CreationKind.Character,
            card = CharacterDraft(name = "旧名", scenario = "保留的场景"),
            lore = listOf(LoreDraft("城市", listOf("云城"), "云城在山中。")),
        )
        val reply = CreationReplyParser.parse("""{"assistant_message":"已修改","card":{"name":"新名"},"lore":[{"title":"组织","keys":["夜巡"],"content":"夜巡守城。"}]}""")
        val updated = CreationReplyParser.apply(original, reply)
        assertEquals("新名", updated.card.name)
        assertEquals("保留的场景", updated.card.scenario)
        assertEquals(listOf("城市", "组织"), updated.lore.map { it.title })
    }

    @Test
    fun exportedCharacterRoundTripsOpeningFrontendAndEmbeddedLore() {
        val html = "<details class=\"card\"><summary>状态</summary>平静</details>"
        val session = CreationSession(
            kind = CreationKind.Character,
            card = CharacterDraft(
                name = "林月", firstMessage = "你走进茶馆。", frontendHtml = html,
                systemPrompt = "保持第三人称限知。",
            ),
            lore = listOf(LoreDraft("茶馆", listOf("茶馆"), "茶馆位于城南。")),
        )
        val exported = CharacterExporter().exportToJson(session.toCharacterCard())
        val imported = CharacterImporter().importFromJson(exported)
        assertTrue(imported.firstMessage.contains(html))
        assertTrue(TavernRenderParser.parseBody(imported.firstMessage).any { it is TavernRenderSegment.Frontend })
        assertTrue(imported.firstMessage.contains("你走进茶馆。"))
        assertEquals("茶馆位于城南。", imported.characterBook?.entries?.single()?.content)
        assertTrue(exported.contains("保持第三人称限知。"))
        assertTrue(portableFrontendIssues(html).isEmpty())
        assertFalse(portableFrontendIssues("<script>run()</script>").isEmpty())
    }

    @Test
    fun sourceArchiveAndCursorSurviveDraftReload() = runBlocking {
        val root = Files.createTempDirectory("tellev-creation-test").toFile()
        try {
            val repo = CreationRepository(root)
            val text = "远山。\n旧城有钟楼。"
            val session = CreationSession(kind = CreationKind.WorldBook)
            val (hash, length) = repo.saveSource(session.id, text)
            assertEquals(text, repo.readSource(session.id, hash))
            val saved = session.copy(sourceSha256 = hash, sourceLength = length, sourceCursor = 3)
            repo.save(saved)
            assertEquals(3, repo.load(session.id).sourceCursor)
            assertEquals(hash, repo.list().single().sourceSha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractedLoreRequiresExactSourceEvidence() {
        val chunk = SourceChunk(100, 116, "旧城有一座钟楼。夜里鸣钟。")
        val accepted = verifiedLoreFromChunk(
            chunk,
            listOf(
                LoreDraft("钟楼", listOf("钟楼"), "旧城有钟楼。", sourceQuote = "旧城有一座钟楼"),
                LoreDraft("臆测", listOf("国王"), "国王建造了钟楼。", sourceQuote = "国王建造了钟楼"),
            ),
            "设定.txt", "abc",
        )
        assertEquals(1, accepted.size)
        assertEquals(100, accepted.single().sourceOffset)
        assertEquals("设定.txt", accepted.single().sourceName)
    }

    @Test
    fun worldBookRoundTripsTriggerFields() {
        val session = CreationSession(
            kind = CreationKind.WorldBook,
            worldName = "北境",
            lore = listOf(LoreDraft(
                title = "城门", keys = listOf("城门"), secondaryKeys = listOf("夜晚"),
                content = "夜晚城门关闭。", selective = true, depth = 2,
            )),
        )
        val serialized = WorldBookCodec.serializeWorldBook(session.toWorldBook())
        val restored = WorldBookCodec.parseWorldBookEntries(serialized).single()
        assertEquals(listOf("城门"), restored.keys)
        assertEquals(listOf("夜晚"), restored.secondaryKeys)
        assertTrue(restored.selective)
        assertEquals(2, restored.depth)
        assertEquals("夜晚城门关闭。", restored.content)
    }

    @Test
    fun creationAgentOwnsItsPromptAndPreset() = runBlocking {
        val provider = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            var lastRequest: GenerateRequest? = null
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
                lastRequest = request
                return flowOf(GenerateChunk.Completed("""{"assistant_message":"想先确定视角吗？","card":{"name":"林月"}}"""))
            }
        }
        val secrets = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = Unit
            override suspend fun readSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String) = Unit
            override suspend fun listSecretIds(): List<String> = emptyList()
        }
        val updates = mutableListOf<CreationStreamUpdate>()
        val reply = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "写一个人物", updates::add)
        assertEquals("想先确定视角吗？", reply.message)
        assertEquals("ai-creation-agent", provider.lastRequest?.preset?.id)
        assertTrue(provider.lastRequest?.stream == true)
        assertTrue(provider.lastRequest?.prompt?.messages?.first()?.content.orEmpty().contains("第三人称限知"))
        assertTrue(provider.lastRequest?.preset?.prompts.isNullOrEmpty())
        assertEquals(0, updates.last().deltaCount)
        assertTrue(updates.any { it.providerLabel.startsWith("Fake · ") })
    }

    @Test
    fun malformedExampleJsonIsRepairedOnceBeforeApplyingDraft() = runBlocking {
        val malformed = """{"assistant_message":"已写好",\"card":{"exampleMessages":"<START>\n{{user}}: 你好"}}"""
        val repaired = """{"assistant_message":"已写好","card":{"exampleMessages":"<START>\n{{user}}: 你好"}}"""
        val responses = ArrayDeque(listOf(malformed, repaired))
        val requests = mutableListOf<GenerateRequest>()
        val provider = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
                requests += request
                return flowOf(GenerateChunk.Completed(responses.removeFirst()))
            }
        }
        val secrets = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = Unit
            override suspend fun readSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String) = Unit
            override suspend fun listSecretIds(): List<String> = emptyList()
        }
        val reply = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "写一段示例对话")
        val card = CreationReplyParser.apply(CreationSession(kind = CreationKind.Character), reply).card
        assertEquals("<START>\n{{user}}: 你好", card.exampleMessages)
        assertEquals(2, requests.size)
        assertEquals(0.0, requests.last().preset.temperature)
        assertTrue(requests.last().prompt.messages.first().content.contains("JSON 格式修复器"))
    }

    @Test
    fun repeatedMalformedReplyReportsRetryWithoutRawJson() = runBlocking {
        val provider = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> =
                flowOf(GenerateChunk.Completed("{invalid}"))
        }
        val secrets = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = Unit
            override suspend fun readSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String) = Unit
            override suspend fun listSecretIds(): List<String> = emptyList()
        }
        val failure = runCatching {
            CreationEngine(secrets, ProviderRegistry(listOf(provider)))
                .converse(CreationSession(kind = CreationKind.Character), "写一段示例对话")
        }.exceptionOrNull()
        assertEquals("AI 连续两次返回无效的草稿格式；本轮未应用，请点击「重试上一轮」。", failure?.message)
    }

    @Test
    fun creationAgentPublishesProviderReasoningAndDraftWhileStreaming() = runBlocking {
        val first = "{\"assistant_message\":\"完"
        val response = first + "成\",\"card\":{\"name\":\"林月\"}}"
        val provider = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Streaming)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> =
                kotlinx.coroutines.flow.flow {
                    emit(GenerateChunk.Delta("", reasoning = "先确定角色目标。"))
                    emit(GenerateChunk.Delta(first))
                    emit(GenerateChunk.Delta(response.drop(first.length)))
                    emit(GenerateChunk.Completed(response, reasoning = "先确定角色目标。"))
                }
        }
        val secrets = object : SecretStore {
            override suspend fun putSecret(id: String, value: String) = Unit
            override suspend fun readSecret(id: String): String? = null
            override suspend fun deleteSecret(id: String) = Unit
            override suspend fun listSecretIds(): List<String> = emptyList()
        }
        val updates = mutableListOf<CreationStreamUpdate>()
        val reply = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "写一位角色", updates::add)
        assertEquals("完成", reply.message)
        assertTrue(updates.any { it.phase == "模型正在思考" && it.reasoning == "先确定角色目标。" })
        assertTrue(updates.any { it.assistantMessage == "完" })
        assertTrue(updates.any { it.output == response && it.reasoning == "先确定角色目标。" })
        assertEquals(3, updates.last().deltaCount)
        assertTrue(updates.last().firstDeltaMillis != null)
        assertTrue((updates.last().lastDeltaMillis ?: -1) >= (updates.last().firstDeltaMillis ?: 0))
    }

    @Test
    fun creationStreamDoesNotInventReasoningWhenProviderOnlyReturnsText() {
        val plain = visibleCreationStream("""{"assistant_message":"完成"}""", "")
        assertEquals("", plain.reasoning)
        assertTrue(plain.output.contains("assistant_message"))
        val thinking = visibleCreationStream("<think>先列出冲突", "")
        assertEquals("先列出冲突", thinking.reasoning)
        assertEquals("", thinking.output)
        val completed = visibleCreationStream("<think>先列出冲突</think>{\"assistant_message\":\"完成\"}", "")
        assertEquals("先列出冲突", completed.reasoning)
        assertTrue(completed.output.contains("assistant_message"))
    }

    @Test
    fun liveAssistantMessagePreviewDecodesPartialJsonString() {
        val partial = """{"assistant_message":"第一行\n第二行，名字叫\"林月\""""
        assertEquals("第一行\n第二行，名字叫\"林月\"", previewAssistantMessage(partial))
        assertEquals("", previewAssistantMessage("""{"card":{"name":"林月"}}"""))
    }
}
