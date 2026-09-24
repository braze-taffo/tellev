package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Coverage for the ST-Prompt-Template injection family (injectPrompt /
 * getPromptsInjected / hasPromptsInjected, sticky decay, outlet placeholders)
 * through the Kotlin fallback path; template.js gets the same scenarios in
 * tools/template-eval/test.mjs against the real WebView implementation.
 */
class PromptInjectionCompatTest {

    @Before
    fun resetRegistry() {
        PromptInjectedRegistry.clear()
    }

    private fun processor() = DefaultPromptTemplateProcessor()

    private fun renderOne(content: String) = processor().process(
        PromptTemplateRequest(
            messages = listOf(PromptMessage(role = MessageRole.System, content = content)),
            context = MacroContext(),
            metadata = buildJsonObject { },
        ),
    ).messages.single().content

    @Test
    fun `injectPrompt registers and getPromptsInjected collects in order`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "<% injectPrompt('cot', 'second', 200) %><% injectPrompt('cot', 'first', 100) %>",
                    ),
                    PromptMessage(role = MessageRole.User, content = "<%= getPromptsInjected('cot') %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("first\nsecond", result.messages[1].content)
        assertTrue(PromptInjectedRegistry.has("cot"))
    }

    @Test
    fun `injectPrompt deduplicates identical content by uid`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "<% injectPrompt('k', 'same') %><% injectPrompt('k', 'same') %>",
                    ),
                    PromptMessage(role = MessageRole.User, content = "<%= getPromptsInjected('k') %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("same", result.messages[1].content)
    }

    @Test
    fun `explicit uid overrides content hash dedup`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "<% injectPrompt('k', 'a', 100, 0, 'u1') %><% injectPrompt('k', 'b', 100, 0, 'u1') %>",
                    ),
                    PromptMessage(role = MessageRole.User, content = "<%= getPromptsInjected('k') %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("b", result.messages[1].content)
    }

    @Test
    fun `sticky injection survives one decay then expires`() {
        // Build 1: register with sticky=1 (no consumption).
        renderOne("<% injectPrompt('sticky', 'persist', 100, 1) %>")

        // Build 2: start-of-build decay 1→0, entry still alive.
        assertEquals("persist", renderOne("<%= getPromptsInjected('sticky') %>"))

        // Build 3: decay 0→-1, entry gone.
        assertEquals("", renderOne("<%= getPromptsInjected('sticky') %>"))
    }

    @Test
    fun `sticky zero entry is dropped by the next build decay`() {
        renderOne("<% injectPrompt('once', 'v') %>")

        assertEquals("", renderOne("<%= getPromptsInjected('once') %>"))
    }

    @Test
    fun `outlet placeholder in plain message resolves after render pass`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "<% injectPrompt('cot', 'chain') %>"),
                    // No `<%` — this message never renders, the placeholder must
                    // still resolve in the end-of-build pass (ST handler.ts:382).
                    PromptMessage(role = MessageRole.User, content = "{{outletPromptsInjected:cot}}"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("chain", result.messages[1].content)
    }

    @Test
    fun `outlet flag returns placeholder that the end pass resolves`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "<% injectPrompt('cot', 'chained') %>"),
                    PromptMessage(role = MessageRole.User, content = "<%= getPromptsInjected('cot', [], true) %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("chained", result.messages[1].content)
    }

    @Test
    fun `outlet placeholder without registry entries resolves to empty`() {
        assertEquals("AB", renderOne("A{{outletPromptsInjected:missing}}B"))
    }

    @Test
    fun `hasPromptsInjected reflects registry state`() {
        assertFalse(PromptInjectedRegistry.has("x"))
        renderOne("<% injectPrompt('x', 'v') %>")
        assertTrue(PromptInjectedRegistry.has("x"))
    }

    @Test
    fun `isolated floor self-collection survives the registry rollback`() {
        // A historical floor that injects AND collects within itself: ST keeps
        // registrations alive until the end-of-pass scan, so the isolation must
        // resolve the floor's own placeholders before rolling back.
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "sys"),
                    PromptMessage(
                        role = MessageRole.Assistant,
                        channel = "chat",
                        content = "<% injectPrompt('mid', 'V_FROM_MID') %>[<%= getPromptsInjected('mid') %>]",
                    ),
                    PromptMessage(role = MessageRole.User, channel = "chat", content = "tail"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("[V_FROM_MID]", result.messages[1].content)
        // The rollback still happened: nothing leaked to the persistent scope.
        assertFalse(PromptInjectedRegistry.has("mid"))
    }

    @Test
    fun `disabled settings skip decay entirely`() {
        renderOne("<% injectPrompt('keep', 'v') %>")
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = app.tellev.core.extension.EjsTemplateSettings(enabled = false),
        )
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "plain")),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("plain", result.messages.single().content)
        // Disabled processing must not decay the registry (ST returns before cleanup).
        assertTrue(PromptInjectedRegistry.has("keep"))
    }
}
