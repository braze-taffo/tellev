package app.tellev.feature.creation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

class CreationToolProtocolTest {

    @Test
    fun nativeToolArgumentsAcceptObjectFromCompatibleRelay() {
        val blocks = parseNativeCreationCalls(JsonArray(listOf(buildJsonObject {
            put("function", buildJsonObject {
                put("name", "creation_tool")
                put("arguments", buildJsonObject {
                    put("name", "read_card")
                    put("arguments", buildJsonObject { })
                })
            })
        })))
        assertEquals("read_card", (blocks.single() as ToolCallBlock.Valid).call.name)
    }

    // ── 工具块解析 ──────────────────────────────────────────────

    @Test
    fun parsesSingleBlockWithSurroundingProse() {
        val result = parseToolCallBlocks("先看现状。\n<tool_call>{\"name\":\"read_card\",\"arguments\":{}}</tool_call>\n谢谢。")
        assertEquals(1, result.blocks.size)
        val call = (result.blocks.single() as ToolCallBlock.Valid).call
        assertEquals("read_card", call.name)
        assertTrue(call.arguments.isEmpty())
        assertTrue(result.prose.contains("先看现状"))
        assertTrue(result.prose.contains("谢谢"))
        assertFalse(result.prose.contains("tool_call"))
        assertFalse(result.hasUnclosedBlock)
    }

    @Test
    fun parsesMultipleBlocksAndToleratesFencedJson() {
        val text = """
            说明A
            <tool_call>```json
            {"name":"list_lore","arguments":{"keyword":"城"}}
            ```</tool_call>
            说明B
            <tool_call>{"name":"read_card"}</tool_call>
        """.trimIndent()
        val result = parseToolCallBlocks(text)
        assertEquals(2, result.blocks.size)
        val first = result.blocks[0] as ToolCallBlock.Valid
        assertEquals("list_lore", first.call.name)
        assertEquals("城", first.call.arguments["keyword"]!!.jsonPrimitive.content)
        val second = result.blocks[1] as ToolCallBlock.Valid
        assertEquals("read_card", second.call.name)
        assertTrue(second.call.arguments.isEmpty())
        assertTrue(result.prose.contains("说明A") && result.prose.contains("说明B"))
    }

    @Test
    fun invalidJsonInsideBlockIsReportedNotThrown() {
        val result = parseToolCallBlocks("<tool_call>{\"name\":}</tool_call>")
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single() is ToolCallBlock.Invalid)
    }

    @Test
    fun missingNameFieldIsInvalid() {
        val result = parseToolCallBlocks("<tool_call>{\"arguments\":{}}</tool_call>")
        assertTrue(result.blocks.single() is ToolCallBlock.Invalid)
    }

    @Test
    fun unclosedTrailingBlockIsFlaggedAndCutFromProse() {
        val text = "总结一下。<tool_call>{\"name\":\"upsert_lore\",\"arg"
        val result = parseToolCallBlocks(text)
        assertTrue(result.hasUnclosedBlock)
        assertEquals(0, result.blocks.size)
        assertEquals("总结一下。", result.prose)
    }

    @Test
    fun prosePreviewCutsBothClosedAndUnclosedBlocks() {
        assertEquals("可见文本尾部", proseWithoutToolBlocks("可见文本<tool_call>{}</tool_call>尾部"))
        assertEquals("开头", proseWithoutToolBlocks("开头<tool_call>{\"partial"))
    }

    @Test
    fun parsesObservedDoubledPipeDsmlReadCard() {
        val raw = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="read_card">

            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>
        """.trimIndent()
        val parsed = parseToolCallBlocks(raw)
        val call = (parsed.blocks.single() as ToolCallBlock.Valid).call
        assertEquals("read_card", call.name)
        assertTrue(call.arguments.isEmpty())
        assertEquals("", parsed.prose)
        assertFalse(parsed.hasUnclosedBlock)
        assertTrue(CreationToolBox(testSession()).execute(call).ok)
    }

    @Test
    fun parsesCanonicalDsmlParametersAndKeepsOrder() {
        val raw = """
            准备更新。
            <｜DSML｜tool_calls>
            <｜DSML｜invoke name="list_lore">
            <｜DSML｜parameter name="keyword" string="true">云城</｜DSML｜parameter>
            <｜DSML｜parameter name="limit" string="false">5</｜DSML｜parameter>
            </｜DSML｜invoke>
            <｜DSML｜invoke name="read_card"></｜DSML｜invoke>
            </｜DSML｜tool_calls>
            已提交。
        """.trimIndent()
        val parsed = parseToolCallBlocks(raw)
        assertEquals(2, parsed.blocks.size)
        val first = (parsed.blocks[0] as ToolCallBlock.Valid).call
        assertEquals("list_lore", first.name)
        assertEquals("云城", first.arguments["keyword"]!!.jsonPrimitive.content)
        assertEquals(5, first.arguments["limit"]!!.jsonPrimitive.int)
        assertEquals("read_card", (parsed.blocks[1] as ToolCallBlock.Valid).call.name)
        assertEquals("准备更新。\n\n已提交。", parsed.prose)
        assertFalse(parsed.hasUnclosedBlock)
    }

    @Test
    fun incompleteOrInvalidDsmlCannotBecomeVisibleReply() {
        val partial = "先读取。<｜｜DSML｜｜ calls><｜｜DSML｜｜ invoke name=\"read_card\">"
        assertEquals("先读取。", proseWithoutToolBlocks(partial))
        assertTrue(parseToolCallBlocks(partial).hasUnclosedBlock)

        val invalid = "<｜DSML｜tool_calls><｜DSML｜invoke name=\"read_card\">坏内容</｜DSML｜invoke></｜DSML｜tool_calls>"
        val parsed = parseToolCallBlocks(invalid)
        assertTrue(parsed.blocks.single() is ToolCallBlock.Invalid)
        assertEquals("", parsed.prose)
        assertFalse(parsed.hasUnclosedBlock)
    }

    // ── 执行器 ──────────────────────────────────────────────────

    private fun testSession(): CreationSession = CreationSession(
        kind = CreationKind.Character,
        card = CharacterDraft(name = "旧名", scenario = "保留的场景"),
        lore = listOf(
            LoreDraft("城市", listOf("云城"), "云城在山中。", constant = false, insertionOrder = 50, depth = 6)
                .copy(id = "L1", sourceQuote = "原始证据"),
            LoreDraft("组织", listOf("夜巡"), "夜巡守城。").copy(id = "L2"),
        ),
    )

    private fun strings(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))

    @Test
    fun readCardReturnsDraftWorldNameAndCount() {
        val box = CreationToolBox(testSession().copy(worldName = "北境"))
        val result = box.execute(ToolCallRequest("read_card", buildJsonObject { }))
        assertTrue(result.ok)
        assertEquals("旧名", result.payload["card"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("北境", result.payload["world_name"]!!.jsonPrimitive.content)
        assertEquals(2, result.payload["lore_count"]!!.jsonPrimitive.int)
    }

    @Test
    fun listLorePaginatesAndFiltersByKeyword() {
        val box = CreationToolBox(testSession())
        val page = box.execute(ToolCallRequest("list_lore", buildJsonObject {
            put("offset", 1)
            put("limit", 1)
        }))
        assertTrue(page.ok)
        assertEquals(2, page.payload["total"]!!.jsonPrimitive.int)
        val entries = page.payload["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        assertEquals("L2", entries[0].jsonObject["id"]!!.jsonPrimitive.content)
        val filtered = box.execute(ToolCallRequest("list_lore", buildJsonObject { put("keyword", "云") }))
        assertEquals(1, filtered.payload["total"]!!.jsonPrimitive.int)
        assertEquals("L1", filtered.payload["entries"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun listLoreRejectsNonIntegerOffset() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("list_lore", buildJsonObject { put("offset", "x") }))
        assertFalse(result.ok)
        assertTrue(result.payload["error"]!!.jsonPrimitive.content.contains("offset"))
    }

    @Test
    fun readLoreReturnsFullFieldsAndReportsMissingIds() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("read_lore", buildJsonObject { put("ids", strings("L1", "L9")) }))
        assertTrue(result.ok)
        val found = result.payload["found"]!!.jsonArray
        assertEquals(1, found.size)
        assertEquals("云城在山中。", found[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(listOf("L9"), result.payload["not_found"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun setCardFieldsMergesAndKeepsUnmentionedFields() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("name", "新名") }))
        assertTrue(result.ok)
        assertEquals("新名", box.session.card.name)
        assertEquals("保留的场景", box.session.card.scenario)
    }

    @Test
    fun setCardFieldsRejectsUnknownFieldWithoutApplying() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("nickname", "x") }))
        assertFalse(result.ok)
        val error = result.payload["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("nickname"))
        assertTrue(error.contains("firstMessage"))
        assertEquals("旧名", box.session.card.name)
    }

    @Test
    fun setCardFieldsRejectsWrongFieldType() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("tags", "冒险") }))
        assertFalse(result.ok)
        assertTrue(box.session.card.tags.isEmpty())
    }

    @Test
    fun upsertPatchByIdKeepsUnmentionedEntryFields() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L1")
                    put("content", "云城在群山深处，只有一条栈道。")
                })
            })
        }))
        assertTrue(result.ok)
        val updated = box.session.lore.first { it.id == "L1" }
        assertEquals("云城在群山深处，只有一条栈道。", updated.content)
        // 旧协议整条覆盖时这些字段会被默认值清空。
        assertEquals(listOf("云城"), updated.keys)
        assertEquals(50, updated.insertionOrder)
        assertEquals(6, updated.depth)
        assertEquals("城市", updated.title)
    }

    @Test
    fun upsertCreatesWithSequentialIdsAndWarnsAboutMissingKeys() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("title", "钟楼")
                    put("keys", strings("钟楼"))
                    put("content", "旧城有钟楼。")
                })
                add(buildJsonObject {
                    put("title", "北门")
                    put("content", "北门封死。")
                })
            })
        }))
        assertTrue(result.ok)
        assertEquals(listOf("L1", "L2", "L3", "L4"), box.session.lore.map { it.id })
        val outcomes = result.payload["results"]!!.jsonArray
        assertEquals("created", outcomes[0].jsonObject["status"]!!.jsonPrimitive.content)
        val warnings = outcomes[1].jsonObject["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { it.contains("触发词") })
    }

    @Test
    fun upsertIgnoresSystemManagedProvenanceFields() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L1")
                    put("content", "新内容")
                    put("sourceQuote", "伪造的证据")
                })
            })
        }))
        assertTrue(result.ok)
        val updated = box.session.lore.first { it.id == "L1" }
        assertEquals("新内容", updated.content)
        assertEquals("原始证据", updated.sourceQuote)
    }

    @Test
    fun upsertReportsUnknownIdWithoutTouchingOthers() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L9")
                    put("content", "不存在")
                })
            })
        }))
        assertTrue(result.ok)
        assertEquals(listOf("L9"), result.payload["not_found"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, box.session.lore.size)
        assertTrue(box.session.lore.none { it.content == "不存在" })
    }

    @Test
    fun upsertKeepsOriginalEntryMergeBase() {
        val originalEntry = app.tellev.core.model.WorldBookEntry(
            id = "7", keys = listOf("钟楼"), content = "旧文", delayUntilRecursion = 2,
        )
        val session = testSession().copy(
            lore = listOf(LoreDraft("钟楼", listOf("钟楼"), "旧文").copy(id = "L1", originalEntry = originalEntry)),
        )
        val box = CreationToolBox(session)
        box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L1")
                    put("content", "新文")
                })
            })
        }))
        val updated = box.session.lore.single()
        assertEquals("新文", updated.content)
        assertEquals(2, updated.originalEntry?.delayUntilRecursion)
        val exported = box.session.toWorldBook().entries.single()
        assertEquals("新文", exported.content)
        assertEquals(2, exported.delayUntilRecursion)
        assertEquals("7", exported.id)
    }

    @Test
    fun removeLoreDeletesByIdAndReportsNotFound() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("remove_lore", buildJsonObject { put("ids", strings("L1", "L9")) }))
        assertTrue(result.ok)
        val removed = result.payload["removed"]!!.jsonArray
        assertEquals("城市", removed[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(listOf("L9"), result.payload["not_found"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("L2"), box.session.lore.map { it.id })
    }

    @Test
    fun removeLoreRejectsMixedTypeIdsInsteadOfSilentlySkippingThem() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("remove_lore", buildJsonObject {
            put("ids", buildJsonArray { add(JsonPrimitive("L1")); add(JsonPrimitive(7)) })
        }))
        assertFalse(result.ok)
        assertTrue(result.payload["error"]!!.jsonPrimitive.content.contains("第 2 项"))
        assertEquals(listOf("L1", "L2"), box.session.lore.map { it.id })
    }

    @Test
    fun unknownToolNameFailsWithAvailableToolsListed() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("hack", buildJsonObject { }))
        assertFalse(result.ok)
        val error = result.payload["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("read_card"))
        assertTrue(error.contains("upsert_lore"))
    }

    @Test
    fun toolResultRendersAsWrappedFeedback() {
        val result = box().execute(ToolCallRequest("read_card", buildJsonObject { }))
        val rendered = result.render()
        assertTrue(rendered.startsWith("<tool_result name=\"read_card\" ok=\"true\">"))
        assertTrue(rendered.endsWith("</tool_result>"))
        assertTrue(ToolResult(false, "bad\" name", buildJsonObject { }).render()
            .startsWith("<tool_result name=\"unknown\""))
    }

    // ── 对抗复核修复回归 ──────────────────────────────────────

    @Test
    fun nonPrimitiveNameIsInvalidBlockNotCrash() {
        val result = parseToolCallBlocks("<tool_call>{\"name\":[\"read_card\"],\"arguments\":{}}</tool_call>")
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single() is ToolCallBlock.Invalid)
    }

    @Test
    fun readAndListLoreUseWriteSideFieldNames() {
        val box = CreationToolBox(testSession())
        val read = box.execute(ToolCallRequest("read_lore", buildJsonObject { put("ids", strings("L1")) }))
        assertTrue(read.ok)
        val view = read.payload["found"]!!.jsonArray.single().jsonObject
        assertEquals(50, view["insertionOrder"]!!.jsonPrimitive.int)
        assertTrue(view.containsKey("secondaryKeys"))
        assertTrue(view.containsKey("matchWholeWords"))
        assertFalse(view.containsKey("insertion_order"))
        assertFalse(view.containsKey("secondary_keys"))
        assertFalse(view.containsKey("match_whole_words"))
        val list = box.execute(ToolCallRequest("list_lore", buildJsonObject { }))
        val row = list.payload["entries"]!!.jsonArray[0].jsonObject
        assertTrue(row.containsKey("insertionOrder"))
        assertFalse(row.containsKey("insertion_order"))
    }

    @Test
    fun upsertAcceptsStNativeAndSnakeCaseFieldNames() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L1")
                    put("insertion_order", 61)
                    put("secondary_keys", strings("夜"))
                    put("match_whole_words", true)
                })
                add(buildJsonObject {
                    put("title", "塔楼")
                    put("key", strings("塔楼"))
                    put("order", 5)
                    put("content", "塔楼俯瞰全城。")
                })
            })
        }))
        assertTrue(result.ok)
        val updated = box.session.lore.first { it.id == "L1" }
        assertEquals(61, updated.insertionOrder)
        assertEquals(listOf("夜"), updated.secondaryKeys)
        assertTrue(updated.matchWholeWords)
        // 模型照抄 ST 字段名（key/order）也不能变成静默 no-op。
        val created = box.session.lore.first { it.title == "塔楼" }
        assertEquals(listOf("塔楼"), created.keys)
        assertEquals(5, created.insertionOrder)
    }

    @Test
    fun upsertWarnsAboutUnrecognizedFields() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("id", "L1")
                    put("colour", "red")
                    put("content", "新内容")
                })
            })
        }))
        assertTrue(result.ok)
        val outcome = result.payload["results"]!!.jsonArray[0].jsonObject
        val warnings = outcome["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { it.contains("colour") })
        assertEquals("新内容", box.session.lore.first { it.id == "L1" }.content)
    }

    @Test
    fun removeThenCreateDoesNotReuseIdNumbers() {
        val session = testSession().copy(
            lore = testSession().lore + LoreDraft("高塔", listOf("高塔"), "高塔。").copy(id = "L3"),
        )
        val box = CreationToolBox(session)
        assertTrue(box.execute(ToolCallRequest("remove_lore", buildJsonObject { put("ids", strings("L3")) })).ok)
        val created = box.execute(ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("title", "新塔")
                    put("keys", strings("新塔"))
                    put("content", "新塔取代旧塔。")
                })
            })
        }))
        assertTrue(created.ok)
        // 删掉最大号 L3 后新建必须拿到 L4：id 不复用，旧 tool_result 里的 L3 不会指错条目。
        assertEquals(listOf("L1", "L2", "L4"), box.session.lore.map { it.id })
        assertEquals(4, box.session.nextLoreNumber)
    }

    @Test
    fun worldBookSessionSetNameRenamesBookAndRejectsCardFields() {
        val box = CreationToolBox(CreationSession(kind = CreationKind.WorldBook, worldName = "旧名"))
        val rename = box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("name", " 新名 ") }))
        assertTrue(rename.ok)
        assertEquals("新名", box.session.worldName)
        assertEquals("新名", rename.payload["world_name"]!!.jsonPrimitive.content)
        val rejected = box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("description", "不应生效") }))
        assertFalse(rejected.ok)
        assertTrue(rejected.payload["error"]!!.jsonPrimitive.content.contains("世界书"))
    }

    private fun box() = CreationToolBox(testSession())
}
