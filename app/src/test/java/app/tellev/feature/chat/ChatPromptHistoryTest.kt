package app.tellev.feature.chat

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPromptHistoryTest {
    @Test
    fun `current saved user message is removed from prompt history`() {
        val previous = ChatMessage(
            id = "previous",
            role = MessageRole.Character,
            name = "Alice",
            content = "Hello",
            createdAtMillis = 1L,
        )
        val current = ChatMessage(
            id = "current",
            role = MessageRole.User,
            name = "User",
            content = "Same text is allowed",
            createdAtMillis = 2L,
        )

        assertEquals(
            listOf(previous),
            promptHistoryBeforeCurrentMessage(listOf(previous, current), current.id),
        )
    }

    @Test
    fun `history is unchanged when the current id is not the final message`() {
        val earlier = ChatMessage(
            id = "earlier",
            role = MessageRole.User,
            name = "User",
            content = "Same text is allowed",
            createdAtMillis = 1L,
        )
        val assistant = ChatMessage(
            id = "assistant",
            role = MessageRole.Character,
            name = "Alice",
            content = "Reply",
            createdAtMillis = 2L,
        )
        val messages = listOf(earlier, assistant)

        assertEquals(
            messages,
            promptHistoryBeforeCurrentMessage(messages, earlier.id),
        )
    }

    @Test
    fun `structured prompt variables remain structured in session metadata`() {
        val variables = buildJsonObject {
            put("profile", buildJsonObject { put("name", "Alice") })
        }

        val updated = ChatSession(
            id = "chat-1",
            characterId = "char-1",
            groupId = null,
            title = "Chat",
            messages = emptyList(),
        ).withPromptTemplateVariables(variables)

        assertEquals(
            "Alice",
            updated.metadata["variables"]!!.jsonObject["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }
}
