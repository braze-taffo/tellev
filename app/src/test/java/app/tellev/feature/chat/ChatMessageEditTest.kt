package app.tellev.feature.chat

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ChatMessageEditTest {
    private val message = ChatMessage("id", MessageRole.Character, "Card", "old", 0,
        swipes = listOf("old", "alternate"))

    @Test fun `first edit repairs invalid imported swipe ids without unbounded metadata`() {
        for (index in listOf(-1, 7, Int.MAX_VALUE)) {
            val edited = editedChatMessage(message.copy(swipeIndex = index), "edited")
            assertEquals(2, edited.swipeIndex)
            assertEquals(listOf("old", "alternate", "edited"), edited.swipes)
            assertTrue(CharacterRegexApplier.isNormalProcessed(edited))
            assertEquals(edited, editedChatMessage(edited, "edited"))
        }
    }

    @Test fun `edit keeps valid alternate selection and creates first legacy swipe`() {
        val edited = editedChatMessage(message.copy(swipeIndex = 1), "edited")
        assertEquals(1, edited.swipeIndex)
        assertEquals(listOf("old", "edited"), edited.swipes)
        val legacy = editedChatMessage(message.copy(swipes = emptyList()), "edited")
        assertEquals(0, legacy.swipeIndex)
        assertEquals(listOf("edited"), legacy.swipes)
    }

    @Test fun `malformed imported normal processing marker is treated as unprocessed`() {
        val metadata = Json.parseToJsonElement("""{"tellev_regex_normal_versions":[{}]}""").jsonObject
        assertFalse(CharacterRegexApplier.isNormalProcessed(message.copy(metadata = metadata)))
        assertTrue(CharacterRegexApplier.isNormalProcessed(editedChatMessage(message.copy(metadata = metadata), "edited")))
    }
}
