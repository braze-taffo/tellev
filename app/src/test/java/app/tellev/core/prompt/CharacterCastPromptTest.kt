package app.tellev.core.prompt

import app.tellev.core.model.*
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CharacterCastPromptTest {
    private val member = CharacterCard("actor", "舟青", description = "{{char}}是渡船人。", personality = "谨慎，想摆脱商会。",
        scenario = "认识{{user}}，但不知道密室真相。", exampleMessages = "{{char}}：先看潮位。",
        characterBook = WorldBook("book", "暗号", listOf(WorldBookEntry("0", emptyList(), content = "{{char}}的暗号是青檐。", constant = true))))
    private fun request() = PromptBuildRequest(CharacterCastBinding.withMembers(CharacterCard("director", "导演", description = "统一叙事。"), listOf(member)),
        Persona("player", "旅人", ""), emptyList(), emptyList(), GenerationPreset("preset", "preset", "openai-compatible"), "进入旧港", "openai-compatible")

    @Test fun `main narration includes full cast and expands each actors name independently`() {
        val result = DefaultPromptEngine().build(request())
        val text = result.messages.joinToString("\n") { it.content }
        assertTrue(text.contains("舟青是渡船人。"))
        assertTrue(text.contains("认识旅人"))
        assertTrue(text.contains("谨慎，想摆脱商会。"))
        assertTrue(text.contains("舟青：先看潮位。"))
        assertTrue(text.contains("舟青的暗号是青檐。"))
        assertFalse(text.contains("导演是渡船人"))
        assertEquals("统一叙事。", request().character.description)
    }

    @Test fun `existing materialized member book is replaced once and cast cycles are not traversed`() {
        val main = request().character
        val cyclic = CharacterCastBinding.withMembers(member, listOf(main))
        val existing = member.characterBook!!.copy(id = StDataStore.embeddedCharacterBookId(member.id))
        val expanded = CharacterCastPrompt.enrich(request().copy(supportingCharacters = listOf(cyclic, cyclic), worldBooks = listOf(existing)), DefaultMacroEngine())
        assertEquals(1, expanded.worldBooks.size)
        assertEquals(1, "--- 舟青 ---".toRegex().findAll(expanded.character.description).count())
        assertFalse(expanded.character.description.contains("--- 导演 ---"))
    }

    @Test fun `raw extension generation remains raw`() {
        val raw = request().copy(metadata = buildJsonObject { put("tavernRawGeneration", true) })
        assertEquals(raw, CharacterCastPrompt.enrich(raw, DefaultMacroEngine()))
    }

    @Test fun `attaching a cast member does not replay its opening or frontend`() {
        val actor = member.copy(firstMessage = "{{setvar::cast_opened::1}}<script>opening-only</script>", alternateGreetings = listOf("unused-opening"))
        val result = DefaultPromptEngine().build(request().copy(supportingCharacters = listOf(actor)))
        val text = result.messages.joinToString("\n") { it.content }
        assertFalse(text.contains("opening-only"))
        assertFalse(text.contains("unused-opening"))
        assertFalse(text.contains("cast_opened"))
    }
}
