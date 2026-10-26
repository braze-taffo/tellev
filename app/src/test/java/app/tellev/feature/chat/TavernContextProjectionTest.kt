package app.tellev.feature.chat

import app.tellev.core.extension.ExtensionHost
import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TavernContextProjectionTest {
    private fun host(settings: () -> JsonObject) = Proxy.newProxyInstance(
        ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java),
    ) { _, method, _ ->
        if (method.name == "snapshotExtensionSettings") settings() else error("Unexpected host call: ${method.name}")
    } as ExtensionHost

    @Test fun `identity reads never traverse worldbook entries or extension settings`() {
        val unreadable = object : AbstractList<WorldBookEntry>() {
            override val size: Int get() = error("Worldbook body must not be read")
            override fun get(index: Int): WorldBookEntry = error("Worldbook body must not be read")
        }
        val state = ChatUiState(selectedCharacter = CharacterCard("card", "角色"),
            worldBooks = listOf(WorldBook("huge", "大世界书", unreadable)))
        val context = ChatTavernAdapter.buildTavernContext(state, 7, host { error("Settings must not be read") },
            fields = setOf("characterId", "__runtimeGeneration", "worldBookNames", "unknown"))
        assertEquals(setOf("characterId", "__runtimeGeneration", "worldBookNames"), context.keys)
        assertEquals("card", context["characterId"]!!.jsonPrimitive.content)
        assertEquals("大世界书", context["worldBookNames"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(context.toString().length < 150)
    }

    @Test fun `every public field matches the full context including raw unknown fields`() {
        val unknown = buildJsonObject { put("custom", "keep"); put("scripts", "x".repeat(10000)) }
        val book = WorldBook("world", "世界书", listOf(WorldBookEntry("17", emptyList(), content = "正文", raw = unknown)))
        val state = ChatUiState(selectedCharacter = CharacterCard("card", "角色", raw = unknown, characterBook = book),
            worldBooks = listOf(book), selectedPreset = GenerationPreset("p", "预设", "openai", raw = unknown),
            messages = listOf(ChatMessage("m", MessageRole.Character, "角色", "hello", 1)))
        val host = host { buildJsonObject { put("custom_settings", true) } }
        val full = ChatTavernAdapter.buildTavernContext(state, 9, host)
        assertFalse("worldBookNames" in full)
        for ((name, value) in full) {
            val projected = ChatTavernAdapter.buildTavernContext(state, 9, host, fields = setOf(name))
            assertEquals(name, setOf(name), projected.keys)
            assertEquals(name, value, projected[name])
        }
        assertEquals(unknown, full["chatCompletionSettings"])
        assertEquals(unknown, full["oai_settings"])
        assertEquals("keep", full["character"]!!.jsonObject["data"]!!.jsonObject["custom"]!!.jsonPrimitive.content)
    }
}
