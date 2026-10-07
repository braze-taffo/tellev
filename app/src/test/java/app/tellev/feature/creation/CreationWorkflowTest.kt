package app.tellev.feature.creation

import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CreationWorkflowTest {
    private fun book(name: String = "海港") = WorldBook(
        id = "harbor", name = name,
        entries = listOf(WorldBookEntry(id = "0", keys = listOf("潮汐"), content = "涨潮时才能进入内港。",
            comment = "航路", raw = buildJsonObject { put("uid", 0); put("sticky", 7) })),
    )

    @Test
    fun worldBookStartsCardAsReadableReferenceWithoutEmbeddingEntries() {
        val source = CreationSession.fromWorldBook(book())
        val card = source.relatedDraft(CreationKind.Character)
        assertNotEquals(source.id, card.id)
        assertEquals("", card.savedArtifactId)
        assertTrue(card.lore.isEmpty())
        assertEquals("海港", card.referenceBook!!.name)
        assertFalse(card.allowAgentLoreEdits)
        val box = CreationToolBox(card)
        val index = box.execute(ToolCallRequest("list_lore", buildJsonObject { put("source", "reference") }))
        assertTrue(index.ok)
        assertEquals("R1", index.payload["entries"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val result = box.execute(ToolCallRequest("read_lore", buildJsonObject {
            put("source", "reference"); put("ids", JsonArray(listOf(JsonPrimitive("R1"))))
        }))
        assertTrue(result.ok)
        assertEquals("涨潮时才能进入内港。", result.payload["found"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(card, box.session)
        assertTrue(creationConversationContext(card).contains("source=reference"))
        assertEquals(source.lore.size, 1)
        val exported = CharacterImporter().importFromJson(CharacterExporter().exportToJson(
            card.copy(card = CharacterDraft(name = "海港导演")).toCharacterCard()))
        assertEquals(null, exported.characterBook)
    }

    @Test
    fun referenceIndexSupportsPaginationAndRejectsUnknownSources() {
        val reference = book().copy(entries = (1..23).map {
            WorldBookEntry(id = "$it", keys = listOf("港口$it"), content = "第 $it 个港口", comment = "港口$it")
        })
        val box = CreationToolBox(CreationSession(kind = CreationKind.Character, referenceBook = reference))
        val result = box.execute(ToolCallRequest("list_lore", buildJsonObject {
            put("source", "reference"); put("offset", 20); put("limit", 3)
        }))
        assertTrue(result.ok)
        assertEquals(23, result.payload["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("R21", "R22", "R23"), result.payload["entries"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        })
        assertFalse(box.execute(ToolCallRequest("list_lore", buildJsonObject { put("source", "other") })).ok)
    }

    @Test
    fun lockedCardRejectsLoreWritesButStillAllowsCharacterFields() {
        val initial = CreationSession(kind = CreationKind.Character, card = CharacterDraft(name = "旅人"),
            lore = listOf(LoreDraft("旧设定", listOf("旧城"), "既有内容", id = "L1")),
            referenceBook = book(), allowAgentLoreEdits = false)
        val box = CreationToolBox(initial)
        val write = ToolCallRequest("upsert_lore", buildJsonObject {
            put("entries", JsonArray(listOf(buildJsonObject { put("title", "新条目"); put("content", "私自增加") })))
        })
        assertFalse(box.execute(write).ok)
        assertFalse(box.execute(ToolCallRequest("remove_lore", buildJsonObject {
            put("ids", JsonArray(listOf(JsonPrimitive("L1"))))
        })).ok)
        assertEquals(initial, box.session)
        assertTrue(box.execute(ToolCallRequest("set_card_fields", buildJsonObject { put("description", "旅人来自海港。") })).ok)
        assertEquals(initial.lore, box.session.lore)
        assertEquals(initial.referenceBook, box.session.referenceBook)
        val unlocked = CreationToolBox(initial.copy(allowAgentLoreEdits = true))
        assertTrue(unlocked.execute(write).ok)
        // The source book remains read-only even when editing embedded draft entries is enabled.
        assertEquals(initial.referenceBook, unlocked.session.referenceBook)
    }

    @Test
    fun explicitMergeKeepsExistingEntriesAndMetadataAndExportsUniqueUids() {
        val existing = book("旧海港").entries.single().copy(content = "旧航路。")
        val initial = CreationSession(kind = CreationKind.Character, card = CharacterDraft(name = "海港导演", firstMessage = "欢迎来到海港。"),
            lore = listOf(existing.toLoreDraft().copy(id = "L1")), referenceBook = book(), allowAgentLoreEdits = false)
        val merged = initial.embedReferenceBook()
        assertEquals(2, merged.lore.size)
        assertEquals(initial.lore.first(), merged.lore.first())
        assertEquals(listOf("L1", "L2"), merged.lore.map { it.id })
        assertEquals(merged.lore, merged.embedReferenceBook().lore)
        assertFalse(merged.allowAgentLoreEdits)
        val exporter = CharacterExporter()
        val json = exporter.exportToJson(merged.toCharacterCard())
        val entries = Json.parseToJsonElement(json).jsonObject["data"]!!.jsonObject["character_book"]!!.jsonObject["entries"]!!.jsonObject.values
        assertEquals(2, entries.map { it.jsonObject["uid"]!!.jsonPrimitive.int }.distinct().size)
        assertTrue(entries.all { it.jsonObject["sticky"]!!.jsonPrimitive.int == 7 })
        val importedJson = CharacterImporter().importFromJson(json)
        val importedPng = CharacterImporter().importFromBytes(
            exporter.exportToPng(merged.toCharacterCard(), PngCardParser.createMinimalPng()), "card.png")
        assertEquals(listOf("旧航路。", "涨潮时才能进入内港。"), importedJson.characterBook!!.entries.map { it.content })
        assertEquals(importedJson.characterBook!!.entries.map { it.content }, importedPng.characterBook!!.entries.map { it.content })
        assertEquals("欢迎来到海港。", importedPng.firstMessage)
        assertEquals(1, initial.lore.size)
    }

    @Test
    fun reverseWorkflowRetainsCardFieldsAndDoesNotOverwriteItsSavedId() {
        val original = CreationSession(kind = CreationKind.Character, card = CharacterDraft(name = "旅人", description = "不识字的水手。",
            firstMessage = "潮水要来了！", frontendHtml = "<div>潮汐表</div>"), savedArtifactId = "saved-traveler")
        val world = original.relatedDraft(CreationKind.WorldBook)
        assertNotEquals(original.id, world.id)
        assertEquals("", world.savedArtifactId)
        assertEquals("旅人", world.originalCard!!.name)
        val updatedWorld = world.copy(lore = book().entries.map { it.toLoreDraft() })
        val returned = updatedWorld.relatedDraft(CreationKind.Character)
        assertEquals("", returned.savedArtifactId)
        assertEquals(original.card.description, returned.card.description)
        assertEquals(original.toCharacterCard().firstMessage, returned.toCharacterCard().firstMessage)
        assertTrue(returned.lore.isEmpty())
        assertEquals("涨潮时才能进入内港。", returned.referenceBook!!.entries.single().content)
        assertEquals("saved-traveler", original.savedArtifactId)
    }

    @Test
    fun reopeningDraftKeepsItsReferenceAndWritePermission() = runBlocking {
        val root = Files.createTempDirectory("creation-linked-reference").toFile()
        try {
            val repository = CreationRepository(root)
            val draft = CreationSession(kind = CreationKind.Character, card = CharacterDraft(name = "旅人"), referenceBook = book(), allowAgentLoreEdits = false)
            repository.save(draft)
            assertEquals(draft, repository.load(draft.id))
            assertEquals(0, repository.list().single().loreCount)
        } finally { root.deleteRecursively() }
        Unit
    }
}
