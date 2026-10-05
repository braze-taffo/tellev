package app.tellev.feature.chat

import app.tellev.core.model.*
import app.tellev.core.prompt.PromptTemplateVariableSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatScrollResourcesTest {
    private fun key(index: Int = 0) = PanelSizeKey("$index", "content", 400, 2f, 1f, "dark")

    @Test fun `size cache is bounded and all layout inputs invalidate measurements`() {
        val sizes = ChatPanelSizes()
        repeat(401) { sizes.record(key(it), it + 1) }
        assertNull(sizes.get(key(0)))
        assertEquals(401, sizes.get(key(400)))
        val stored = key(400)
        for (changed in listOf(stored.copy(widthPx = 500), stored.copy(density = 3f),
            stored.copy(fontScale = 2f), stored.copy(theme = "light"), stored.copy(contentVersion = "edited"))) {
            assertNull(sizes.get(changed))
        }
        sizes.clear()
        assertNull(sizes.get(stored))
    }

    @Test fun `scrolling coalesces measurements and retiring a page removes its update`() {
        var busy = true
        val sizes = ChatPanelSizes { busy }
        var measured = 0
        val page = Any()
        sizes.deliver(page) { measured = 100 }
        sizes.deliver(page) { measured = 200 }
        assertFalse(sizes.flush())
        assertEquals(0, measured)
        busy = false
        assertTrue(sizes.flush())
        assertEquals(200, measured)
        busy = true
        sizes.deliver(page) { measured = 999 }
        sizes.remove(page)
        busy = false
        assertFalse(sizes.flush())
        assertEquals(200, measured)
    }

    @Test fun `frame aggregation preserves every physical delta`() {
        val deltas = ChatScrollDeltas()
        listOf(3f, 8f, -2f, Float.NaN, 1f).forEach(deltas::add)
        assertEquals(10f, deltas.drain())
        assertEquals(0f, deltas.drain())
    }

    @Test fun `precomputed regex depths match existing semantics including hidden and tool messages`() {
        val messages = listOf(MessageRole.User, MessageRole.Character, MessageRole.Tool, MessageRole.System, MessageRole.Assistant)
            .mapIndexed { i, role -> ChatMessage("$i", role, "n", "body", 1, isHidden = i == 1) }
        val depths = visibleRegexDepths(messages)
        messages.indices.forEach { assertEquals(visibleRegexDepth(messages, it), depths[it]) }
    }

    private fun obj(value: Int) = buildJsonObject { put("hp", value) }
    private fun state(): ChatUiState {
        val messages = listOf(
            ChatMessage("a", MessageRole.Character, "n", "body", 1, variables = listOf(obj(10), obj(20))),
            ChatMessage("b", MessageRole.User, "u", "body", 2, variables = listOf(obj(30))),
            ChatMessage("hidden", MessageRole.Character, "n", "body", 3, isHidden = true, variables = listOf(obj(40))))
        return ChatUiState(currentSession = ChatSession("s", "s", "c", null, messages,
            metadata = buildJsonObject { put("variables", obj(5)) }), messages = messages)
    }
    private fun read(cache: MessageFrontendVariables, state: ChatUiState, payload: String, id: String = "a",
                     snapshot: PromptTemplateVariableSnapshot = PromptTemplateVariableSnapshot()): JsonObject =
        Json.parseToJsonElement(cache.read(state, snapshot, id, payload)).jsonObject

    @Test fun `compact reads preserve latest negative index scope and prefix merge semantics`() {
        val cache = MessageFrontendVariables()
        val state = state()
        assertEquals(obj(30), read(cache, state, """{"options":{"type":"message"}}""")["value"])
        assertEquals(obj(40), read(cache, state, """{"options":{"type":"message","message_id":-1}}""")["value"])
        assertEquals(obj(5), read(cache, state, """{"options":{"type":"chat"}}""")["value"])
        assertEquals(obj(10), read(cache, state, """{"kind":"all"}""")["value"])
        assertEquals(JsonPrimitive(2), read(cache, state, """{"kind":"last"}""")["value"])
        assertEquals(JsonPrimitive(false), read(cache, state, """{"options":{"type":"message","message_id":99}}""")["ok"])
        assertEquals(JsonPrimitive(false), read(cache, state, "{}", "deleted")["ok"])
        assertEquals(1, cache.mergeCount)
    }

    @Test fun `swipe local and global writes invalidate shared cache before next read`() {
        val cache = MessageFrontendVariables()
        var state = state()
        read(cache, state, "{}")
        state = state.copy(messages = state.messages.map { if (it.id == "a") it.copy(swipeIndex = 1) else it })
        assertEquals(obj(20), read(cache, state, """{"options":{"type":"message","message_id":0}}""")["value"])
        state = state.copy(currentSession = state.currentSession!!.copy(metadata = buildJsonObject { put("variables", obj(99)) }))
        assertEquals(obj(99), read(cache, state, "{}")["value"])
        val global = PromptTemplateVariableSnapshot(global = obj(88))
        assertEquals(obj(88), read(cache, state, """{"options":{"type":"global"}}""", snapshot = global)["value"])
        assertEquals(4, cache.mergeCount)
    }
}
