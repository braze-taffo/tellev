package app.tellev.feature.creation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterBlueprintTest {

    private fun testSession(): CreationSession = CreationSession(
        kind = CreationKind.Character,
        card = CharacterDraft(name = "旧名", scenario = "保留的场景"),
        lore = listOf(
            LoreDraft("城市", listOf("云城"), "云城在山中。", constant = false, insertionOrder = 50, depth = 6)
                .copy(id = "L1", sourceQuote = "原始证据"),
            LoreDraft("组织", listOf("夜巡"), "夜巡守城。").copy(id = "L2"),
        ),
    )

    private fun blueprintJson(vararg overrides: Pair<String, String>): String {
        val base = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "name" to JsonPrimitive("云城守卫"),
            "description" to JsonPrimitive("一位守卫云城的年轻剑士。"),
            "personality" to JsonPrimitive("沉稳、警觉"),
            "scenario" to JsonPrimitive("云城城门"),
            "first_mes" to JsonPrimitive("城门缓缓开启。"),
            "alternate_greetings" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("雨夜的城门。"))),
            "mes_example" to JsonPrimitive("<START>"),
            "system_prompt" to JsonPrimitive(""),
            "post_history_instructions" to JsonPrimitive(""),
            "creator_notes" to JsonPrimitive("测试卡"),
            "tags" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("奇幻"), JsonPrimitive("守卫"))),
            "cover_prompt" to JsonPrimitive("1girl, guard, city gate, dusk, masterpiece"),
            "lore" to kotlinx.serialization.json.JsonArray(listOf(
                Json.parseToJsonElement("""{"title":"城市","keys":["云城"],"content":"云城重建后更高了。"}"""),
                Json.parseToJsonElement("""{"title":"新条目","keys":["塔"],"content":"高塔俯瞰全城。"}"""),
            )),
        )
        overrides.forEach { (key, value) ->
            base[key] = if (value.startsWith("[") || value.startsWith("{")) {
                Json.parseToJsonElement(value)
            } else JsonPrimitive(value)
        }
        return kotlinx.serialization.json.JsonObject(base).toString()
    }

    private fun parse(text: String) =
        CharacterBlueprintCompiler.fromArguments(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `fromArguments accepts snake_case protocol and camelCase aliases`() {
        val blueprint = parse(blueprintJson())
        assertEquals("云城守卫", blueprint.name)
        assertEquals("城门缓缓开启。", blueprint.firstMessage)
        assertEquals(listOf("雨夜的城门。"), blueprint.alternateGreetings)
        assertEquals(listOf("奇幻", "守卫"), blueprint.tags)
        assertEquals(2, blueprint.lore.size)

        val aliased = parse(blueprintJson().replace("\"first_mes\"", "\"first_message\"")
            .replace("\"creator_notes\"", "\"creatorNotes\""))
        assertEquals("城门缓缓开启。", aliased.firstMessage)
        assertEquals("测试卡", aliased.creatorNotes)
    }

    @Test
    fun `fromArguments rejects unknown top-level keys`() {
        val error = assertThrows(IllegalStateException::class.java) {
            parse(blueprintJson("creator" to "someone"))
        }
        assertTrue(error.message!!.contains("creator"))
        assertTrue(error.message!!.contains("name"))
    }

    @Test
    fun `applyToSession overwrites card fields and upserts lore by title`() {
        val session = testSession()
        val blueprint = parse(blueprintJson())
        val updated = CharacterBlueprintCompiler.applyToSession(session, blueprint)
        assertEquals("云城守卫", updated.card.name)
        assertEquals("1girl, guard, city gate, dusk, masterpiece", updated.card.coverPrompt)
        // Matched title updates in place: agent id and source provenance survive.
        val city = updated.lore.first { it.id == "L1" }
        assertEquals("云城重建后更高了。", city.content)
        assertEquals("原始证据", city.sourceQuote)
        // Unmatched blueprint entries are appended with fresh monotonic ids.
        val added = updated.lore.first { it.title == "新条目" }
        assertEquals("L3", added.id)
        // Existing entries absent from the blueprint are kept, never deleted.
        assertEquals(3, updated.lore.size)
        // Card fields are overwritten wholesale by the blueprint.
        assertEquals("云城城门", updated.card.scenario)
    }

    @Test
    fun `compile regenerates the blueprint from draft and lore`() {
        val session = testSession().let {
            CharacterBlueprintCompiler.applyToSession(it, parse(blueprintJson()))
        }
        val json = CharacterBlueprintCompiler.compile(session)
        assertEquals("云城守卫", json["name"]!!.jsonPrimitive.content)
        assertEquals("1girl, guard, city gate, dusk, masterpiece", json["cover_prompt"]!!.jsonPrimitive.content)
        val lore = json["lore"].toString()
        assertTrue(lore.contains("云城重建后更高了。"))
        assertTrue(lore.contains("夜巡守城。"))
        // Round-trip: compile → parse → apply converges on the same content.
        val again = CharacterBlueprintCompiler.applyToSession(
            testSession(), parse(json.toString()),
        )
        assertEquals(session.card, again.card)
        assertEquals(session.lore.map { it.title }, again.lore.map { it.title })
    }

    @Test
    fun `toCharacterCard embeds the standard profile into raw data extensions`() {
        val session = testSession().let {
            CharacterBlueprintCompiler.applyToSession(it, parse(blueprintJson()))
        }
        val card = session.toCharacterCard()
        val extensions = ((card.raw["data"] as? kotlinx.serialization.json.JsonObject)
            ?.get("extensions") as? kotlinx.serialization.json.JsonObject)
            ?.get(CharacterBlueprintCompiler.EXTENSION_KEY)
            as? kotlinx.serialization.json.JsonObject
        assertEquals("云城守卫", extensions?.get("name")?.jsonPrimitive?.content)
        assertEquals(
            "1girl, guard, city gate, dusk, masterpiece",
            extensions?.get("cover_prompt")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `fromCharacter recovers the cover prompt from an exported card`() {
        val session = testSession().let {
            CharacterBlueprintCompiler.applyToSession(it, parse(blueprintJson()))
        }
        val card = session.toCharacterCard()
        val reopened = CreationSession.fromCharacter(card)
        assertEquals("1girl, guard, city gate, dusk, masterpiece", reopened.card.coverPrompt)
    }

    @Test
    fun `write_blueprint tool applies and reports counts`() {
        val box = CreationToolBox(testSession())
        val result = box.execute(ToolCallRequest(
            "write_blueprint",
            Json.parseToJsonElement(blueprintJson()).jsonObject,
        ))
        assertTrue(result.ok)
        assertTrue(result.payload.toString().contains("\"lore_created\":1"))
        assertTrue(result.payload.toString().contains("\"lore_updated\":1"))
        assertTrue(result.payload.toString().contains("\"lore_kept\":1"))
        assertEquals("云城守卫", box.session.card.name)
        assertEquals(3, box.session.lore.size)
        assertNotEquals("", box.session.card.coverPrompt)
    }

    @Test
    fun `write_blueprint rejects worldbook sessions and blank names`() {
        val world = CreationSession(kind = CreationKind.WorldBook, worldName = "北境")
        val worldResult = CreationToolBox(world).execute(ToolCallRequest(
            "write_blueprint", Json.parseToJsonElement(blueprintJson()).jsonObject,
        ))
        assertTrue(!worldResult.ok)

        val blankName = CreationToolBox(testSession()).execute(ToolCallRequest(
            "write_blueprint", Json.parseToJsonElement(blueprintJson("name" to "")).jsonObject,
        ))
        assertTrue(!blankName.ok)
    }

    @Test
    fun `blueprint request prompt is non-empty`() {
        assertTrue(blueprintRequestPrompt().contains("write_blueprint"))
    }
}
