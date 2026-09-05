package app.tellev.core.storage

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatSessionMutationTest {
    private val a = ChatMessage("a", MessageRole.Assistant, "Fixture", "old", 0)
    private val base = ChatSession("chat", "Fixture", null, null, listOf(a))

    @Test fun `different swipe snapshots merge while nested business arrays remain atomic`() {
        fun state(values: String) = base.copy(messages = listOf(a.copy(variables = Json.parseToJsonElement(values).jsonArray.toList())))
        val before = state("""[{"n":1},{"n":2}]""")
        val result = applyChatSessionMutation(before, state("""[{"n":3},{"n":2}]"""), state("""[{"n":1},{"n":4}]"""))
        assertEquals(state("""[{"n":3},{"n":4}]"""), result)
        val nested = state("""[{"items":[1,2]}]""")
        assertThrows(IllegalStateException::class.java) {
            applyChatSessionMutation(nested, state("""[{"items":[3,2]}]"""), state("""[{"items":[1,4]}]"""))
        }
        assertThrows(IllegalStateException::class.java) {
            applyChatSessionMutation(before, state("""[{"n":1}]"""), state("""[{"n":1},{"n":4}]"""))
        }
    }

    @Test fun `message edit preserves concurrently committed variables and unknown metadata`() {
        val variables = listOf(buildJsonObject { put("value", 2) })
        val current = base.copy(messages = listOf(a.copy(variables = variables)), metadata = buildJsonObject { put("future", 1) })
        val desired = base.copy(messages = listOf(a.copy(content = "edited")))
        val result = applyChatSessionMutation(base, desired, current)
        assertEquals("edited", result.messages.single().content)
        assertEquals(variables, result.messages.single().variables)
        assertEquals(current.metadata, result.metadata)
    }

    @Test fun `edit targets stable identity after another message is deleted`() {
        val b = a.copy(id = "b")
        val original = base.copy(messages = listOf(a, b))
        val desired = original.copy(messages = listOf(a, b.copy(content = "updated")))
        val result = applyChatSessionMutation(original, desired, base.copy(messages = listOf(b)))
        assertEquals(listOf(b.copy(content = "updated")), result.messages)
    }

    @Test fun `competing values and delete after edit fail without reverting the current data`() {
        val current = base.copy(messages = listOf(a.copy(content = "newer")))
        assertThrows(IllegalStateException::class.java) {
            applyChatSessionMutation(base, base.copy(messages = listOf(a.copy(content = "stale"))), current)
        }
        assertThrows(IllegalStateException::class.java) {
            applyChatSessionMutation(base, base.copy(messages = emptyList()), current)
        }
        assertEquals("newer", current.messages.single().content)
    }

    @Test fun `reapplying an accepted field replacement is idempotent and ownership cannot change`() {
        val desired = base.copy(title = "changed")
        assertEquals(desired, applyChatSessionMutation(base, desired, desired))
        assertThrows(IllegalArgumentException::class.java) { applyChatSessionMutation(base, desired.copy(id = "other"), base) }
    }
}
