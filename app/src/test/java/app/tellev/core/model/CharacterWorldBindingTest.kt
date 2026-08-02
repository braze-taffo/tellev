package app.tellev.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterWorldBindingTest {

    private fun card(raw: String): CharacterCard =
        CharacterCard(id = "char_1", name = "Test", raw = Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `linkedWorldBookName reads data extensions world`() {
        val c = card("""{"data":{"extensions":{"world":"Lore"}}}""")
        assertEquals("Lore", CharacterWorldBinding.linkedWorldBookName(c))
    }

    @Test
    fun `linkedWorldBookName returns null when unbound`() {
        val c = card("""{"data":{"extensions":{}}}""")
        assertNull(CharacterWorldBinding.linkedWorldBookName(c))
    }

    @Test
    fun `linkedWorldBookNames reads canonical then legacy bare world fields`() {
        // Order: data.extensions.world, then legacy data.world, then top-level world.
        val c = card("""{"data":{"extensions":{"world":"A"},"world":"B"},"world":"C"}""")
        assertEquals(listOf("A", "B", "C"), CharacterWorldBinding.linkedWorldBookNames(c))
    }

    @Test
    fun `withLinkedWorldBookName sets data extensions world and preserves siblings`() {
        val c = card("""{"data":{"extensions":{"foo":"bar"}}}""")
        val out = CharacterWorldBinding.withLinkedWorldBookName(c, "MyLore")
        assertEquals("MyLore", CharacterWorldBinding.linkedWorldBookName(out))
        assertEquals(
            "bar",
            out.raw["data"]!!.jsonObject["extensions"]!!.jsonObject["foo"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `withLinkedWorldBookName creates data and extensions when absent`() {
        val c = card("""{"spec":"chara_card_v2"}""")
        val out = CharacterWorldBinding.withLinkedWorldBookName(c, "MyLore")
        assertEquals("MyLore", CharacterWorldBinding.linkedWorldBookName(out))
        assertEquals("chara_card_v2", out.raw["spec"]!!.jsonPrimitive.content)
    }

    @Test
    fun `withLinkedWorldBookName blank clears binding and legacy fields`() {
        val c = card("""{"data":{"extensions":{"world":"A"},"world":"B"},"world":"C"}""")
        val out = CharacterWorldBinding.withLinkedWorldBookName(c, "   ")
        assertNull(CharacterWorldBinding.linkedWorldBookName(out))
        val data = out.raw["data"]!!.jsonObject
        val ext = data["extensions"]!!.jsonObject
        assertTrue("extensions.world should be removed", !ext.containsKey("world"))
        assertTrue("legacy data.world should be removed", !data.containsKey("world"))
        assertTrue("legacy top-level world should be removed", !out.raw.containsKey("world"))
    }

    @Test
    fun `withLinkedWorldBookName consolidates legacy bare world into single source`() {
        // Setting a new binding must clear legacy bare `world` fields so a book
        // cannot double-activate at chat time.
        val c = card("""{"data":{"extensions":{},"world":"Old"},"world":"OldTop"}""")
        val out = CharacterWorldBinding.withLinkedWorldBookName(c, "New")
        assertEquals("New", CharacterWorldBinding.linkedWorldBookName(out))
        assertEquals(listOf("New"), CharacterWorldBinding.linkedWorldBookNames(out))
    }
}
