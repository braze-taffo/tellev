package app.tellev.feature.creation

import app.tellev.core.model.TellevError
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.codec.WorldBookCodec
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CreationFeatureTest {
    @Test
    fun deletingDraftRemovesItsSourceAndCoverButKeepsOtherDraftsAndSavedCard() = runBlocking {
        val directory = Files.createTempDirectory("creation-delete").toFile()
        try {
            val draftsRoot = directory.resolve("drafts")
            val repository = CreationRepository(draftsRoot)
            val removed = CreationSession(kind = CreationKind.Character, savedArtifactId = "saved-card")
            val kept = CreationSession(kind = CreationKind.WorldBook)
            val (sourceHash, _) = repository.saveSource(removed.id, "世界设定原文")
            val coverHash = repository.saveCover(removed.id, PngCardParser.createMinimalPng())
            repository.save(removed.copy(sourceSha256 = sourceHash, coverSha256 = coverHash))
            repository.save(kept)
            val savedCard = directory.resolve("characters/saved-card.png")
            checkNotNull(savedCard.parentFile).mkdirs()
            savedCard.writeText("已保存成品")

            repository.delete(removed.id)

            assertEquals(listOf(kept.id), repository.list().map { it.id })
            assertFalse(draftsRoot.resolve("${removed.id}.$sourceHash.source.txt").exists())
            assertFalse(draftsRoot.resolve("${removed.id}.$coverHash.cover.png").exists())
            assertEquals("已保存成品", savedCard.readText())
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    @Test
    fun coverPersistsWithDraftAndExportsAsImportablePngCard() = runBlocking {
        val directory = Files.createTempDirectory("creation-cover").toFile()
        try {
            val repository = CreationRepository(directory)
            val cover = PngCardParser.createMinimalPng()
            val session = CreationSession(
                kind = CreationKind.Character,
                card = CharacterDraft(name = "封面角色", firstMessage = "你好"),
            )
            val digest = repository.saveCover(session.id, cover)
            repository.save(session.copy(coverSha256 = digest))
            val reopened = repository.load(session.id)
            assertEquals(digest, reopened.coverSha256)
            assertTrue(cover.contentEquals(repository.readCover(reopened.id, reopened.coverSha256)))

            val png = CharacterExporter().exportToPng(reopened.toCharacterCard(), cover)
            val imported = CharacterImporter().importFromBytes(png, "character.png")
            assertEquals("封面角色", imported.name)
            assertEquals("你好", imported.firstMessage)
            assertEquals("chara_card_v2", PngCardParser.extractCardJson(png)?.get("spec")?.toString()?.trim('"'))
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

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
    fun worldBookExportProducesStJsonReadableBySillyTavern() {
        val session = CreationSession(
            kind = CreationKind.WorldBook,
            worldName = "北境",
            lore = listOf(
                LoreDraft("城门", listOf("城门"), "城门夜里关。", secondaryKeys = listOf("夜晚"), selective = true),
                LoreDraft("王法", listOf("王法"), "王法如山。", constant = true, insertionOrder = 20),
            ),
        )
        val bytes = worldBookExportBytes(session.toWorldBook())
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("北境", root["name"]!!.jsonPrimitive.content)
        val entries = WorldBookCodec.parseWorldBookEntries(root)
        assertEquals(2, entries.size)
        val gate = entries.first { it.keys == listOf("城门") }
        assertEquals(listOf("夜晚"), gate.secondaryKeys)
        assertTrue(gate.selective)
        val law = entries.first { it.keys == listOf("王法") }
        assertTrue(law.constant)
        assertEquals(20, law.insertionOrder)
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

    // ── 工具回路 ──────────────────────────────────────────────

    private fun fakeProvider(
        responses: List<String>,
        finishReasons: List<String?> = List(responses.size) { null },
        requests: MutableList<GenerateRequest> = mutableListOf(),
    ): ProviderAdapter {
        val queue = ArrayDeque(responses.mapIndexed { index, text ->
            text to (finishReasons.getOrNull(index))
        })
        return object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
                requests += request
                val (text, finishReason) = queue.removeFirst()
                return flowOf(GenerateChunk.Completed(text, finishReason = finishReason))
            }
        }
    }

    private fun noSecrets(): SecretStore = object : SecretStore {
        override suspend fun putSecret(id: String, value: String) = Unit
        override suspend fun readSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String) = Unit
        override suspend fun listSecretIds(): List<String> = emptyList()
    }

    private fun engine(provider: ProviderAdapter) =
        CreationEngine(noSecrets(), ProviderRegistry(listOf(provider)))

    @Test
    fun textOnlyReplyEndsTheTurnWithoutToolCalls() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(listOf("想先确定视角吗？"), requests = requests)
        val updates = mutableListOf<CreationStreamUpdate>()
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.Character), "写一个人物", updates::add)
        assertEquals("想先确定视角吗？", reply.message)
        assertFalse(reply.capped)
        assertEquals(1, reply.rounds)
        assertEquals(1, requests.size)
        assertEquals("ai-creation-agent", requests.single().preset.id)
        assertEquals(1_000_000, requests.single().preset.maxContextTokens)
        val system = requests.single().prompt.messages.first().content
        assertTrue(system.contains("第三人称限知"))
        assertTrue(system.contains("<tool_call>"))
        assertTrue(requests.single().preset.prompts.isNullOrEmpty())
        assertEquals(0, updates.last().deltaCount)
        assertTrue(updates.any { it.providerLabel.startsWith("Fake · ") })
    }

    @Test
    fun toolRoundAppliesWritesAndFeedsResultsBackAsUserMessages() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(
            listOf(
                "<tool_call>{\"name\":\"set_card_fields\",\"arguments\":{\"name\":\"林月\"}}</tool_call>",
                "已把主角命名为林月。",
            ),
            requests = requests,
        )
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.Character), "写一个人物")
        assertEquals("已把主角命名为林月。", reply.message)
        assertEquals("林月", reply.session.card.name)
        assertEquals(2, requests.size)
        val secondMessages = requests[1].prompt.messages
        assertTrue(secondMessages.any { it.role == app.tellev.core.model.MessageRole.Assistant && it.content.contains("set_card_fields") })
        val feedback = secondMessages.first { it.content.contains("<tool_result") }
        assertEquals(app.tellev.core.model.MessageRole.User, feedback.role)
        assertTrue(feedback.content.contains("ok=\"true\""))
    }

    @Test
    fun malformedToolBlockIsFedBackAndRecovered() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(
            listOf(
                "<tool_call>{\"name\":}</tool_call>",
                "<tool_call>{\"name\":\"set_card_fields\",\"arguments\":{\"description\":\"夜行者。\"}}</tool_call>",
                "已补上角色描述。",
            ),
            requests = requests,
        )
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.Character), "补全角色")
        assertEquals("已补上角色描述。", reply.message)
        assertEquals("夜行者。", reply.session.card.description)
        assertEquals(3, requests.size)
        assertTrue(requests[1].prompt.messages.last().content.contains("无法解析"))
    }

    @Test
    fun twoConsecutiveUnusableRoundsFailTheTurn() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(
            listOf("<tool_call>{\"na", "<tool_call>{\"na"),
            requests = requests,
        )
        val failure = runCatching {
            engine(provider).converse(CreationSession(kind = CreationKind.Character), "写一个人物")
        }.exceptionOrNull()
        assertEquals("AI 连续两轮未返回可用的工具调用或纯文本回复；本轮未应用，请点击「重试上一轮」。", failure?.message)
    }

    @Test
    fun roundLimitClosesGracefullyKeepingWrites() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val responses = List(CreationEngine.TOOL_ROUND_LIMIT + 5) {
            "<tool_call>{\"name\":\"list_lore\",\"arguments\":{}}</tool_call>"
        }
        val provider = fakeProvider(responses, requests = requests)
        val reply = engine(provider)
            .converse(
                CreationSession(kind = CreationKind.WorldBook, lore = listOf(LoreDraft("城", listOf("城"), "城墙。").copy(id = "L1"))),
                "盘点条目",
            )
        assertTrue(reply.capped)
        assertEquals(CreationEngine.TOOL_ROUND_LIMIT, reply.rounds)
        assertEquals(CreationEngine.TOOL_ROUND_LIMIT, requests.size)
        assertTrue(reply.message.contains("继续"))
        assertEquals(1, reply.session.lore.size)
        // 第 30 轮起预告上限：第 31 轮请求里应能看到提示。
        assertTrue(requests[CreationEngine.TOOL_ROUND_WARNING_FROM].prompt.messages.any { it.content.contains("上限") })
    }

    @Test
    fun truncatedToolCallAsksForSmallerBatches() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(
            listOf(
                "先写入。<tool_call>{\"name\":\"upsert_lore\",\"arguments\":{\"entries\":[{\"title\":\"a\",\"cont",
                "已全部写完。",
            ),
            finishReasons = listOf("length", null),
            requests = requests,
        )
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.WorldBook), "导入设定")
        assertEquals("已全部写完。", reply.message)
        assertEquals(2, requests.size)
        assertTrue(requests[1].prompt.messages.last().content.contains("截断"))
        assertTrue(requests[1].prompt.messages.last().content.contains("拆成更小的批量"))
    }

    @Test
    fun reasoningOnlyLengthRoundAsksForTerseRetry() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val provider = object : ProviderAdapter {
            private var calls = 0
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) =
                kotlinx.coroutines.flow.flow {
                    requests += request
                    if (++calls == 1) {
                        emit(GenerateChunk.Completed("", reasoning = "思考".repeat(400), finishReason = "length"))
                    } else {
                        emit(GenerateChunk.Completed("先聊聊视角。"))
                    }
                }
        }
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.Character), "写一个人物")
        assertEquals("先聊聊视角。", reply.message)
        assertEquals(2, requests.size)
        // 思考耗尽输出额度、正文为空：不能直接报「模型未返回内容」，要引导精简思考重发。
        val feedback = requests[1].prompt.messages.last().content
        assertTrue(feedback.contains("推理思考耗尽了输出额度"))
        assertTrue(feedback.contains("精简思考"))
        assertEquals(CreationEngine.MAX_OUTPUT_TOKENS, requests[0].prompt.maxTokens)
        assertEquals(CreationEngine.MAX_OUTPUT_TOKENS, requests[0].preset.maxCompletionTokens)
    }

    @Test
    fun creationAgentPublishesProviderReasoningAndProseWhileStreaming() = runBlocking {
        val first = "已把主角命名"
        val response = first + "为林月。"
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
        val updates = mutableListOf<CreationStreamUpdate>()
        val reply = engine(provider)
            .converse(CreationSession(kind = CreationKind.Character), "写一位角色", updates::add)
        assertEquals(response, reply.message)
        assertTrue(updates.any { it.phase.endsWith("模型正在思考") && it.reasoning == "先确定角色目标。" })
        assertTrue(updates.any { it.assistantMessage == first })
        assertTrue(updates.any { it.output == response && it.reasoning == "先确定角色目标。" })
        assertEquals(3, updates.last().deltaCount)
        assertTrue(updates.last().firstDeltaMillis != null)
        assertTrue((updates.last().lastDeltaMillis ?: -1) >= (updates.last().firstDeltaMillis ?: 0))
    }

    @Test
    fun creationStreamShowsProseOutsideToolBlocks() {
        val plain = visibleCreationStream("普通回复", "")
        assertEquals("", plain.reasoning)
        assertEquals("普通回复", plain.assistantMessage)
        val thinking = visibleCreationStream("<think>先列出冲突", "")
        assertEquals("先列出冲突", thinking.reasoning)
        assertEquals("", thinking.output)
        val withTool = visibleCreationStream(
            "说明<tool_call>{\"name\":\"read_card\"}</tool_call>结尾", "",
        )
        assertEquals("说明结尾", withTool.assistantMessage)
    }

    // ── 提炼（单轮 JSON 保持 + 守卫加固） ─────────────────────

    @Test
    fun malformedExtractionJsonIsRepairedOnce() = runBlocking {
        val malformed = "{\"assistant_message\":\"已提取\",\"lore\":[{\"title\":\"钟楼\",\"keys\":[\"钟楼\"],\"content\":\"旧城有钟楼"
        val repaired = "{\"assistant_message\":\"已提取\",\"lore\":[{\"title\":\"钟楼\",\"keys\":[\"钟楼\"],\"content\":\"旧城有钟楼。\",\"sourceQuote\":\"旧城有钟楼\",\"sourceOffset\":-1}]}"
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(listOf(malformed, repaired), requests = requests)
        val reply = engine(provider).extractChunk(SourceChunk(0, 6, "旧城有钟楼。"), "设定.txt")
        assertEquals("旧城有钟楼。", reply.lore?.single()?.content)
        assertEquals(2, requests.size)
        assertEquals(0.0, requests.last().preset.temperature)
        assertTrue(requests.last().prompt.messages.first().content.contains("JSON 格式修复器"))
    }

    @Test
    fun truncatedExtractionRetriesCondensedInsteadOfRepairing() = runBlocking {
        val truncated = "{\"assistant_message\":\"已提取\",\"lore\":[{\"title\":\"钟楼\",\"keys\":[\"钟楼\"],\"content\":\"旧城有钟楼"
        val condensed = "{\"assistant_message\":\"已提取\",\"lore\":[{\"title\":\"钟楼\",\"keys\":[\"钟楼\"],\"content\":\"旧城有钟楼。\",\"sourceQuote\":\"旧城有钟楼\",\"sourceOffset\":-1}]}"
        val requests = mutableListOf<GenerateRequest>()
        val provider = fakeProvider(
            listOf(truncated, condensed),
            finishReasons = listOf("length", null),
            requests = requests,
        )
        val reply = engine(provider).extractChunk(SourceChunk(0, 6, "旧城有钟楼。"), "设定.txt")
        assertEquals("旧城有钟楼。", reply.lore?.single()?.content)
        assertEquals(2, requests.size)
        assertTrue(requests.last().prompt.messages.first().content.contains("两句话以内"))
    }

    @Test
    fun repeatedExtractionFailureReportsResumePoint() = runBlocking {
        val requests = mutableListOf<GenerateRequest>()
        val garbage = "{invalid"
        val provider = fakeProvider(
            listOf(garbage, garbage, garbage, garbage),
            finishReasons = listOf("length", "length", "length", "length"),
            requests = requests,
        )
        val failure = runCatching {
            engine(provider).extractChunk(SourceChunk(0, 6, "旧城有钟楼。"), "设定.txt")
        }.exceptionOrNull()
        assertTrue(failure?.message?.contains("可从已保存位置继续") == true)
    }

    // ── 导入编辑与保真 ────────────────────────────────────────

    @Test
    fun importedCharacterEditKeepsExtensionsAdvancedFieldsAndShadowedKeys() {
        val original = CharacterCard(
            id = "char_x",
            name = "林月",
            description = "描述",
            firstMessage = "开场",
            alternateGreetings = listOf("旧备选"),
            characterBook = WorldBook(
                id = "char_x",
                name = "北境",
                entries = listOf(
                    WorldBookEntry(
                        id = "7",
                        keys = listOf("钟楼"),
                        content = "旧城有钟楼。",
                        delayUntilRecursion = 2,
                        group = "地标",
                    ),
                ),
            ),
            raw = buildJsonObject {
                put("spec", "chara_card_v2")
                put("spec_version", "2.0")
                put("data", buildJsonObject {
                    put("name", "林月")
                    put("system_prompt", "原系统提示")
                    put("alternate_greetings", JsonArray(listOf(JsonPrimitive("旧备选"))))
                    put("extensions", buildJsonObject { put("regex_scripts", JsonArray(emptyList())) })
                })
            },
        )
        val session = CreationSession.fromCharacter(original)
        assertEquals("原系统提示", session.card.systemPrompt)
        assertEquals(listOf("L1"), session.lore.map { it.id })
        assertEquals(2, session.lore.single().originalEntry?.delayUntilRecursion)

        val box = CreationToolBox(session)
        assertTrue(box.execute(
            ToolCallRequest("set_card_fields", buildJsonObject {
                put("alternateGreetings", JsonArray(listOf(JsonPrimitive("新备选"))))
            }),
        ).ok)
        assertTrue(box.execute(
            ToolCallRequest("upsert_lore", buildJsonObject {
                put("entries", JsonArray(listOf(buildJsonObject {
                    put("id", "L1")
                    put("content", "旧城的钟楼每晚敲响。")
                })))
            }),
        ).ok)

        val exported = CharacterExporter().exportToJson(box.session.toCharacterCard())
        val imported = CharacterImporter().importFromJson(exported)
        assertEquals("林月", imported.name)
        // raw.data 已同步，导出器不再用旧值遮蔽编辑结果。
        assertEquals(listOf("新备选"), imported.alternateGreetings)
        val data = Json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject
        assertTrue(data["extensions"]!!.jsonObject.containsKey("regex_scripts"))
        val entry = imported.characterBook!!.entries.single()
        assertEquals("旧城的钟楼每晚敲响。", entry.content)
        assertEquals(2, entry.delayUntilRecursion)
        assertEquals("地标", entry.group)
    }

    @Test
    fun importedWorldBookEditPreservesRawTopLevelAndEntryFields() {
        val book = WorldBook(
            id = "wb_1",
            name = "北境",
            entries = listOf(
                WorldBookEntry(
                    id = "1",
                    keys = listOf("城门"),
                    content = "城门夜里关。",
                    position = 4,
                ),
            ),
            raw = buildJsonObject { put("extra_top", JsonPrimitive(1)) },
        )
        val session = CreationSession.fromWorldBook(book)
        assertEquals(listOf("L1"), session.lore.map { it.id })
        val box = CreationToolBox(session)
        assertTrue(box.execute(
            ToolCallRequest("upsert_lore", buildJsonObject {
                put("entries", JsonArray(listOf(buildJsonObject {
                    put("id", "L1")
                    put("content", "城门入夜即关。")
                })))
            }),
        ).ok)
        val serialized = WorldBookCodec.serializeWorldBook(box.session.toWorldBook())
        assertEquals(1, serialized["extra_top"]!!.jsonPrimitive.int)
        val restored = WorldBookCodec.parseWorldBookEntries(serialized).single()
        assertEquals("城门入夜即关。", restored.content)
        assertEquals(4, restored.position)
        assertEquals("wb_1", box.session.toWorldBook().id)
    }

    @Test
    fun legacySessionsWithoutLoreIdsGetBackfilled() {
        val legacy = CreationSession(
            kind = CreationKind.WorldBook,
            lore = listOf(
                LoreDraft("甲", listOf("甲"), "甲内容"),
                LoreDraft("乙", listOf("乙"), "乙内容"),
            ),
        )
        val filled = legacy.withAssignedLoreIds()
        assertEquals(listOf("L1", "L2"), filled.lore.map { it.id })
        val withExisting = legacy.copy(
            lore = legacy.lore + LoreDraft("丙", listOf("丙"), "丙内容").copy(id = "L5"),
        ).withAssignedLoreIds()
        assertEquals(listOf("L1", "L2", "L5"), withExisting.lore.map { it.id })
        val box = CreationToolBox(withExisting)
        assertTrue(box.execute(
            ToolCallRequest("upsert_lore", buildJsonObject {
                put("entries", JsonArray(listOf(buildJsonObject {
                    put("title", "丁")
                    put("keys", JsonArray(listOf(JsonPrimitive("丁"))))
                    put("content", "丁内容")
                })))
            }),
        ).ok)
        assertEquals("L6", box.session.lore.last().id)
    }

    // ── 对抗复核修复回归（保真与 uid） ────────────────────────

    private fun embeddedBookCard(entries: List<WorldBookEntry>): CharacterCard = CharacterCard(
        id = "char_x",
        name = "林月",
        description = "描述",
        firstMessage = "开场",
        characterBook = WorldBook(id = "char_x", name = "北境", entries = entries),
        raw = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", buildJsonObject { put("name", "林月") })
        },
    )

    private fun exportedEmbeddedEntries(exported: String): List<kotlinx.serialization.json.JsonObject> {
        val data = Json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject
        return data["character_book"]!!.jsonObject["entries"]!!.jsonObject.values
            .map { it.jsonObject }
            .sortedBy { it["uid"]!!.jsonPrimitive.int }
    }

    @Test
    fun importedCharacterEditKeepsUnmodeledEntryFieldsThroughRebuild() {
        val original = embeddedBookCard(listOf(
            WorldBookEntry(
                id = "7", keys = listOf("钟楼"), content = "旧城有钟楼。", useGroupScoring = true,
                raw = buildJsonObject {
                    put("uid", 7)
                    put("sticky", 5)
                    put("cooldown", 3)
                    put("delay", 1)
                    put("extensions", buildJsonObject { put("custom_flag", "keep") })
                },
            ),
        ))
        val session = CreationSession.fromCharacter(original)
        val box = CreationToolBox(session)
        assertTrue(box.execute(
            ToolCallRequest("upsert_lore", buildJsonObject {
                put("entries", JsonArray(listOf(buildJsonObject {
                    put("id", "L1")
                    put("content", "旧城的钟楼每晚敲响。")
                })))
            }),
        ).ok)

        val entry = exportedEmbeddedEntries(CharacterExporter().exportToJson(box.session.toCharacterCard())).single()
        assertEquals("旧城的钟楼每晚敲响。", entry["content"]!!.jsonPrimitive.content)
        // tellev 未建模的 ST 字段必须从 entry.raw 存活，不能被重建路径清掉。
        assertEquals(5, entry["sticky"]!!.jsonPrimitive.int)
        assertEquals(3, entry["cooldown"]!!.jsonPrimitive.int)
        assertEquals(1, entry["delay"]!!.jsonPrimitive.int)
        assertEquals("keep", entry["extensions"]!!.jsonObject["custom_flag"]!!.jsonPrimitive.content)
        assertEquals("true", entry["useGroupScoring"]!!.jsonPrimitive.content)
    }

    @Test
    fun exportedEmbeddedBookAssignsFreshUidsToNewEntries() {
        val original = embeddedBookCard(listOf(
            WorldBookEntry(id = "0", keys = listOf("a"), content = "甲"),
            WorldBookEntry(id = "1", keys = listOf("b"), content = "乙"),
            WorldBookEntry(id = "2", keys = listOf("c"), content = "丙"),
        ))
        val box = CreationToolBox(CreationSession.fromCharacter(original))
        assertTrue(box.execute(
            ToolCallRequest("remove_lore", buildJsonObject { put("ids", JsonArray(listOf(JsonPrimitive("L1")))) }),
        ).ok)
        assertTrue(box.execute(
            ToolCallRequest("upsert_lore", buildJsonObject {
                put("entries", JsonArray(listOf(buildJsonObject {
                    put("title", "新地")
                    put("keys", JsonArray(listOf(JsonPrimitive("新地"))))
                    put("content", "新地点。")
                })))
            }),
        ).ok)

        val entries = exportedEmbeddedEntries(CharacterExporter().exportToJson(box.session.toCharacterCard()))
        assertEquals(listOf(1, 2, 3), entries.map { it["uid"]!!.jsonPrimitive.int })
        // 新条目 uid 必须高于全部导入 uid，不能撞上保留下来的 1/2。
        assertEquals("新地点。", entries.last()["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun serializedWorldBookAssignsFreshUidsToNewEntries() {
        val book = WorldBook(
            id = "wb_1",
            name = "北境",
            entries = listOf(
                WorldBookEntry(id = "1", keys = listOf("a"), content = "甲", raw = buildJsonObject { put("uid", 1) }),
                WorldBookEntry(id = "n1", keys = listOf("b"), content = "乙"),
            ),
        )
        val serialized = WorldBookCodec.serializeWorldBook(book)
        val uids = serialized["entries"]!!.jsonObject.values
            .map { it.jsonObject["uid"]!!.jsonPrimitive.int }
            .sorted()
        assertEquals(listOf(1, 2), uids)
    }

    @Test
    fun editedCardAlternateGreetingsKeepFrontendInRawAndTyped() {
        val html = "<details class=\"card\"><summary>状态</summary>平静</details>"
        val original = CharacterCard(
            id = "char_y",
            name = "林月",
            description = "描述",
            firstMessage = "开场",
            alternateGreetings = listOf("旧备选"),
            raw = buildJsonObject {
                put("spec", "chara_card_v2")
                put("spec_version", "2.0")
                put("data", buildJsonObject {
                    put("name", "林月")
                    put("alternate_greetings", JsonArray(listOf(JsonPrimitive("旧备选"))))
                })
            },
        )
        val box = CreationToolBox(CreationSession.fromCharacter(original))
        assertTrue(box.execute(
            ToolCallRequest("set_card_fields", buildJsonObject {
                put("alternateGreetings", JsonArray(listOf(JsonPrimitive("新备选"))))
                put("frontendHtml", html)
            }),
        ).ok)

        val card = box.session.toCharacterCard()
        val expected = listOf("新备选\n\n$html")
        // typed 与 raw.data 必须一致：导出器让 raw 遮蔽 typed，分叉会丢前端片段。
        assertEquals(expected, card.alternateGreetings)
        val exported = CharacterExporter().exportToJson(card)
        val data = Json.parseToJsonElement(exported).jsonObject["data"]!!.jsonObject
        assertEquals(expected, data["alternate_greetings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(expected, CharacterImporter().importFromJson(exported).alternateGreetings)
    }
}
