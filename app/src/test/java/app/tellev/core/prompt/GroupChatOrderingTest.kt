package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** H1: unnamed group-chat assistant messages rotate through the member list. */
class GroupChatOrderingTest {

    private fun metadata(vararg members: String) = buildJsonObject {
        putJsonArray("groupMembers") { members.forEach { add(JsonPrimitive(it)) } }
    }

    @Test
    fun `unnamed assistants rotate resuming after the last named speaker`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.Assistant, content = "opening", name = "Bob"),
            PromptMessage(role = MessageRole.Assistant, content = "unnamed one"),
            PromptMessage(role = MessageRole.Assistant, content = "unnamed two"),
            PromptMessage(role = MessageRole.User, content = "hi"),
            PromptMessage(role = MessageRole.Assistant, content = "unnamed three"),
        )
        val ordered = PromptOrderProcessor.applyGroupChatOrdering(messages, metadata("Alice", "Bob", "Cara"))
        assertEquals("Bob", ordered[0].name)
        assertEquals("Cara", ordered[1].name)
        assertEquals("Alice", ordered[2].name)
        assertNull(ordered[3].name) // user messages stay untouched
        assertEquals("Bob", ordered[4].name) // rotation wraps around
    }

    @Test
    fun `without a named anchor attribution starts at the first member`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.Assistant, content = "a"),
            PromptMessage(role = MessageRole.Assistant, content = "b"),
        )
        val ordered = PromptOrderProcessor.applyGroupChatOrdering(messages, metadata("Alice", "Bob"))
        assertEquals("Alice", ordered[0].name)
        assertEquals("Bob", ordered[1].name)
    }

    @Test
    fun `known named speakers keep their names and unknown ones anchor the rotation at the start`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.Assistant, content = "imported", name = "Zoe"),
            PromptMessage(role = MessageRole.Assistant, content = "a"),
            PromptMessage(role = MessageRole.Assistant, content = "b"),
        )
        val ordered = PromptOrderProcessor.applyGroupChatOrdering(messages, metadata("Alice", "Bob"))
        assertEquals("Zoe", ordered[0].name)
        assertEquals("Alice", ordered[1].name)
        assertEquals("Bob", ordered[2].name)
    }

    @Test
    fun `single member and empty member lists leave messages untouched`() {
        val messages = listOf(
            PromptMessage(role = MessageRole.Assistant, content = "a", name = null),
        )
        assertEquals(messages, PromptOrderProcessor.applyGroupChatOrdering(messages, metadata("Alice")))
        assertEquals(messages, PromptOrderProcessor.applyGroupChatOrdering(messages, buildJsonObject { }))
    }
}
