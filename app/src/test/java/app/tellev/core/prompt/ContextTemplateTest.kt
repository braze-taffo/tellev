package app.tellev.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/** P1/P2/P3: story_string conditionals, engine reuse, and trim-marker hygiene. */
class ContextTemplateTest {

    @Test
    fun `nested conditional blocks resolve without leaking closers`() {
        val context = MacroContext(customVariables = mapOf("a" to "x", "b" to "y"))
        val template = "{{#if a}}A{{#if b}}B{{/if}}C{{/if}}"
        assertEquals("ABC", ContextTemplate.buildStoryString(template, context))
        // Inner false keeps the outer content but drops the nested block.
        assertEquals("AC", ContextTemplate.buildStoryString(template, context.copy(customVariables = mapOf("a" to "x", "b" to ""))))
        // Outer false drops everything, including the nested block.
        assertEquals("", ContextTemplate.buildStoryString(template, context.copy(customVariables = mapOf("a" to "", "b" to "y"))))
        // No stray {{/if}} markers leak into the prompt.
        assertEquals("XY", ContextTemplate.buildStoryString("{{#if a}}X{{/if}}{{#if b}}Y{{/if}}", context))
    }

    @Test
    fun `story string expansion reuses the caller engine and its custom macros`() {
        val engine = DefaultMacroEngine()
        engine.registerCustomMacro("storytoken") { "TOKEN" }
        val out = ContextTemplate.buildStoryString(
            "{{#if description}}{{description}} {{storytoken}}{{/if}}",
            MacroContext(characterDescription = "Desc"),
            macroEngine = engine,
        )
        assertEquals("Desc TOKEN", out)
    }

    @Test
    fun `underscore-prefixed code bodies are not renamed`() {
        assertEquals("_foo = 1", PromptTemplateParser.normalizeCode("_foo = 1"))
        assertEquals("__proto.x", PromptTemplateParser.normalizeCode(" __proto.x "))
        assertEquals("", PromptTemplateParser.normalizeCode("_"))
    }
}
