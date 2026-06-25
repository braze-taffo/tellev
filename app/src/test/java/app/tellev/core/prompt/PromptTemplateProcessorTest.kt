package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PromptTemplateProcessorTest {

    @Test
    fun `process renders for-of loops`() {
        val processor = DefaultPromptTemplateProcessor()
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "<% for (const item of ['red', 'blue']) { %><%= item %> <% } %>",
                    ),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("red blue ", result.messages.single().content)
    }

    @Test
    fun `process reports unsupported helper calls`() {
        val processor = DefaultPromptTemplateProcessor()
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "Before <% activateRegex(/x/g, '') %> after",
                    ),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("Before  after", result.messages.single().content)
        assertTrue(result.warnings.any { it.contains("activateRegex") })
    }

    @Test
    fun `process applies generate regex before matched message`() {
        val processor = DefaultPromptTemplateProcessor()
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "System"),
                    PromptMessage(role = MessageRole.User, content = "Ask about academy."),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
                worldEntries = listOf(
                    PromptTemplateWorldEntry(
                        id = "regex",
                        content = "[GENERATE:REGEX:academy:BEFORE]\nRegex note.",
                    ),
                ),
            ),
        )

        assertEquals("Regex note.\nAsk about academy.", result.messages[1].content)
    }

    @Test
    fun `process uses comment directive with entry content as body`() {
        val processor = DefaultPromptTemplateProcessor()
        val entry = PromptTemplateWorldEntry(
            id = "comment-inject",
            content = "Comment body.",
            raw = buildJsonObject {
                put("comment", JsonPrimitive("@INJECT pos=1 role=system"))
            },
        )

        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "System"),
                    PromptMessage(role = MessageRole.User, content = "User"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
                worldEntries = listOf(entry),
            ),
        )

        assertEquals("", processor.systemPromptContentFor(entry))
        assertEquals("Comment body.", result.messages[1].content)
        assertEquals(MessageRole.System, result.messages[1].role)
    }
}
