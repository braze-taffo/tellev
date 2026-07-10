package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
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

    // ── Settings-controlled behavior ──────────────────────────────────

    @Test
    fun `process skips all template processing when disabled`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(enabled = false),
        )
        val template = "<% for (const item of ['red', 'blue']) { %><%= item %> <% } %>"
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = template),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        // Template should be returned unchanged
        assertEquals(template, result.messages.single().content)
    }

    @Test
    fun `process skips generate instruction blocks when generateLoaderEnabled is false`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(generateLoaderEnabled = false),
        )
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
        // The GENERATE instruction should NOT be applied; the user message
        // should remain unchanged.
        assertEquals("Ask about academy.", result.messages[1].content)
    }

    @Test
    fun `process skips EJS rendering when renderEnabled is false`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(renderEnabled = false),
        )
        val template = "<%= 'hello' %>"
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = template),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        // EJS should NOT be rendered; the template should be returned as-is
        assertEquals(template, result.messages.single().content)
    }

    @Test
    fun `process applies instruction blocks even when renderEnabled is false`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(renderEnabled = false),
        )
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
        // Instruction blocks should still be applied (generate phase)
        assertEquals("Regex note.\nAsk about academy.", result.messages[1].content)
    }

    @Test
    fun `ejsSettings can be updated at runtime`() {
        val processor = DefaultPromptTemplateProcessor()
        // Initially enabled - template should render
        val template = "<%= 'hello' %>"
        val result1 = processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = template)),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        assertEquals("hello", result1.messages.single().content)

        // Disable rendering
        processor.ejsSettings = EjsTemplateSettings(renderEnabled = false)
        val result2 = processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = template)),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        assertEquals(template, result2.messages.single().content)

        // Re-enable rendering
        processor.ejsSettings = EjsTemplateSettings.DEFAULT
        val result3 = processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = template)),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        assertEquals("hello", result3.messages.single().content)
    }

    @Test
    fun `systemPromptContentFor returns raw content when disabled`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(enabled = false),
        )
        val entry = PromptTemplateWorldEntry(
            id = "test",
            content = "Normal text.\n[GENERATE:BEFORE]\nInstruction body.",
        )
        // When disabled, the raw content should be returned unchanged
        assertEquals("Normal text.\n[GENERATE:BEFORE]\nInstruction body.", processor.systemPromptContentFor(entry))
    }

    @Test
    fun `systemPromptContentFor strips instructions when generateLoaderEnabled`() {
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = EjsTemplateSettings(enabled = true, generateLoaderEnabled = true),
        )
        val entry = PromptTemplateWorldEntry(
            id = "test",
            content = "Normal text.\n[GENERATE:BEFORE]\nInstruction body.",
        )
        // When enabled, the instruction marker and its body are stripped,
        // leaving only the normal text portion.
        assertEquals("Normal text.", processor.systemPromptContentFor(entry))
    }
}
