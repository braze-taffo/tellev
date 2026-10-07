package app.tellev.feature.creation

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CreationMultiReferenceTest {
    @Test fun `native function schema exposes all new read tools`() {
        val names = creationNativeTools().single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject[
            "properties"]!!.jsonObject["name"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("list_reference_books", "list_cast", "read_cast")))
    }
    private fun book(id: String, name: String, fact: String) = WorldBook(id, name, listOf(
        WorldBookEntry("0", listOf("旧港"), content = fact, comment = "设定", raw = buildJsonObject { put("sticky", 7) })))
    private val primary = book("main", "主世界", "旧港只在退潮开放。")
    private val auxiliary = book("aux", "商会", "商会控制航运。")
    private fun draft() = CreationSession(kind = CreationKind.WorldBook, worldName = "新势力", referenceBook = primary,
        additionalReferenceBooks = listOf(auxiliary))

    @Test fun `reference books distinguish primary and same uid entries from separate books`() {
        val box = CreationToolBox(draft())
        val books = box.execute(ToolCallRequest("list_reference_books", JsonObject(emptyMap()))).payload["books"]!!.jsonArray
        assertTrue(books[0].jsonObject["primary"]!!.jsonPrimitive.boolean)
        assertFalse(books[1].jsonObject["primary"]!!.jsonPrimitive.boolean)
        val index = box.execute(ToolCallRequest("list_lore", buildJsonObject { put("source", "reference") })).payload["entries"]!!.jsonArray
        assertEquals(listOf("R1", "R2_1"), index.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val found = box.execute(ToolCallRequest("read_lore", buildJsonObject {
            put("source", "reference"); put("ids", JsonArray(listOf(JsonPrimitive("R2_1"))))
        })).payload["found"]!!.jsonArray.single().jsonObject
        assertEquals(auxiliary.id, found["book_id"]!!.jsonPrimitive.content)
        assertEquals(auxiliary.entries.single().content, found["content"]!!.jsonPrimitive.content)
        val filtered = box.execute(ToolCallRequest("list_lore", buildJsonObject { put("source", "reference"); put("book_id", "aux") }))
        assertEquals(1, filtered.payload["total"]!!.jsonPrimitive.int)
        assertEquals(primary, box.session.referenceBook)
    }

    @Test fun `faction draft preserves references without overwriting source or copying entries`() {
        val source = draft().copy(savedArtifactId = "old", lore = listOf(primary.entries.single().toLoreDraft()))
        val faction = source.factionDraft()
        assertNotEquals(source.id, faction.id)
        assertEquals("", faction.savedArtifactId)
        assertTrue(faction.lore.isEmpty())
        assertEquals(listOf(primary, auxiliary), faction.referenceBooks())
        assertTrue(creationConversationContext(faction).contains("冲突"))
        assertEquals(1, source.lore.size)
    }

    @Test fun `explicit multi book merge remaps ids preserves metadata and is idempotent`() {
        val card = draft().copy(kind = CreationKind.Character, card = CharacterDraft(name = "导演"))
        val merged = card.embedReferenceBooks()
        assertEquals(2, merged.lore.size)
        assertEquals(2, merged.lore.map { it.id }.distinct().size)
        assertTrue(merged.lore.all { it.originalEntry!!.raw["sticky"]!!.jsonPrimitive.int == 7 })
        assertEquals(merged.lore, merged.embedReferenceBooks().lore)
        assertEquals(primary, merged.referenceBook)
        assertEquals(listOf(auxiliary), merged.additionalReferenceBooks)
    }
}
