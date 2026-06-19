package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPromptEngineTest {
    @Test
    fun buildActivatesMatchingWorldEntry() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice", description = "A careful guide."),
                persona = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "User",
                        content = "Tell me about the academy.",
                        createdAtMillis = 1L,
                    ),
                ),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "academy",
                                keys = listOf("academy"),
                                content = "The academy is built under the old observatory.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "What is the academy?",
                providerType = "openai-compatible",
            ),
        )

        assertEquals(listOf("academy"), result.diagnostics.activatedWorldEntryIds)
        assertTrue(result.messages.first().content.contains("old observatory"))
    }
}

