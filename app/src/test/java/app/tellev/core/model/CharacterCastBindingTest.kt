package app.tellev.core.model

import app.tellev.core.storage.*
import app.tellev.core.provider.resolveCharacterCast
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CharacterCastBindingTest {
    private val main = CharacterCard("director", "潮港世界", raw = buildJsonObject {
        put("spec", "chara_card_v3"); put("spec_version", "3.0")
        put("data", buildJsonObject { put("extensions", buildJsonObject { put("foreign_key", "keep"); put("world", "潮港") }) })
    })
    private val sailor = CharacterCard("sailor", "舟青", description = "渡船人", personality = "谨慎",
        scenario = "欠商会人情", firstMessage = "先看潮位。", systemPrompt = "说话简短。",
        characterBook = WorldBook("tides", "潮汐", listOf(WorldBookEntry("0", listOf("港口"), content = "暗号青檐。"))))

    @Test fun `binding removes self and duplicate members and keeps unrelated extensions`() {
        val card = CharacterCastBinding.withMembers(main, listOf(main, sailor, sailor))
        assertEquals(listOf("sailor"), CharacterCastBinding.members(card).map { it.id })
        assertEquals("keep", card.raw["data"]!!.jsonObject["extensions"]!!.jsonObject["foreign_key"]!!.jsonPrimitive.content)
        assertEquals("潮港", CharacterWorldBinding.linkedWorldBookName(card))
        assertTrue(CharacterCastBinding.members(CharacterCastBinding.withMembers(card, emptyList())).isEmpty())
    }

    @Test fun `cast survives json and png exports without copying executable member extensions`() {
        val scripted = sailor.copy(raw = buildJsonObject { put("data", buildJsonObject { put("extensions", buildJsonObject {
            put("regex_scripts", JsonArray(listOf(buildJsonObject { put("scriptName", "must not run") })))
            put("world", "潮汐")
        }) }) })
        val bound = CharacterCastBinding.withMembers(main, listOf(scripted))
        val exporter = CharacterExporter()
        val outputs = listOf(exporter.exportToJson(bound).toByteArray(), exporter.exportToPng(bound, PngCardParser.createMinimalPng()))
        outputs.forEachIndexed { index, bytes ->
            val imported = CharacterImporter().importFromBytes(bytes, if (index == 0) "world.json" else "world.png")
            val member = CharacterCastBinding.members(imported).single()
            assertEquals(sailor.description, member.description)
            assertEquals(sailor.systemPrompt, member.systemPrompt)
            assertEquals("暗号青檐。", member.characterBook!!.entries.single().content)
            assertEquals("潮汐", CharacterWorldBinding.linkedWorldBookName(member))
            assertFalse(member.raw.toString().contains("regex_scripts"))
        }
    }

    @Test fun `live references refresh member settings and deleted members use snapshots`() = runBlocking {
        val root = Files.createTempDirectory("cast-resolve-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root)); store.bootstrap()
            store.saveCharacter(sailor.copy(personality = "新设定：谨慎但敢于反抗。"))
            val bound = CharacterCastBinding.withMembers(main, listOf(sailor))
            assertEquals("新设定：谨慎但敢于反抗。", resolveCharacterCast(bound, store).single().personality)
            store.deleteCharacter(sailor.id)
            assertEquals(sailor.personality, resolveCharacterCast(bound, store).single().personality)
        } finally { root.toFile().deleteRecursively() }
    }
}
