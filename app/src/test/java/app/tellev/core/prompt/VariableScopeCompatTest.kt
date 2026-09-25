package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the ST-Prompt-Template variables.ts semantics ported to the
 * template environment: message-scope default, string-shorthand options,
 * flags (nxs/xxs), merge, results, the live cache read model, and the
 * historical-floor write isolation. template.js mirrors these in
 * tools/template-eval/test.mjs.
 */
class VariableScopeCompatTest {

    private fun processor() = DefaultPromptTemplateProcessor()

    private fun render(
        content: String,
        metadata: kotlinx.serialization.json.JsonObject = buildJsonObject { },
        messageVariables: kotlinx.serialization.json.JsonObject? = null,
    ): PromptTemplateResult = processor().process(
        PromptTemplateRequest(
            messages = listOf(PromptMessage(role = MessageRole.System, content = content)),
            context = MacroContext(),
            metadata = metadata,
            messageVariables = messageVariables,
        ),
    )

    @Test
    fun `bare setvar writes message scope while setLocalVar writes local`() {
        val result = render(
            "<% setvar('mood', 'calm') %><% setLocalVar('mood', 'tense') %>",
        )

        assertEquals("tense", result.variableUpdates.local?.get("mood")?.jsonPrimitive?.content)
        assertEquals("calm", result.variableUpdates.message?.get("mood")?.jsonPrimitive?.content)
    }

    @Test
    fun `getvar reads merged cache with message layer on top`() {
        val result = render(
            "<%= getvar('hp') %>|<%= getvar('mp', 'local') %>|<%= getvar('gp', 'global') %>",
            metadata = buildJsonObject {
                put("promptTemplateLocalVariables", buildJsonObject { put("hp", JsonPrimitive(10)); put("mp", JsonPrimitive(20)) })
                put("promptTemplateGlobalVariables", buildJsonObject { put("hp", JsonPrimitive(99)); put("gp", JsonPrimitive(7)) })
            },
            messageVariables = buildJsonObject { put("hp", JsonPrimitive(5)) },
        )

        // message(5) > local(10) > global(99) per variables.ts:52-59.
        assertEquals("5|20|7", result.messages.single().content)
    }

    @Test
    fun `string shorthand options route scope`() {
        val result = render(
            "<% setvar('a', 1, 'global') %><% setvar('b', 2, 'local') %><% setvar('c', 3) %>" +
                "<%= getvar('a', 'global') %><%= getvar('b', 'local') %><%= getvar('c', 'message') %>",
        )

        assertEquals("123", result.messages.single().content)
        assertEquals(2.0, result.variableUpdates.local?.get("b")?.jsonPrimitive?.content?.toDouble())
        assertEquals(3.0, result.variableUpdates.message?.get("c")?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `nxs flag only sets when absent`() {
        val result = render(
            "<% setvar('k', 'first', {flags: 'nxs'}) %><% setvar('k', 'second', {flags: 'nxs'}) %>" +
                "<%= getvar('k') %>",
        )

        assertEquals("first", result.messages.single().content)
    }

    @Test
    fun `xxs flag only sets when present`() {
        val result = render(
            "<% setvar('absent', 'x', {flags: 'xxs'}) %>" +
                "<% setvar('exists', 'seed') %><% setvar('exists', 'overwritten', {flags: 'xxs'}) %>" +
                "<%= getvar('absent', {defaults: 'kept'}) %>|<%= getvar('exists') %>",
        )

        assertEquals("kept|overwritten", result.messages.single().content)
    }

    @Test
    fun `merge option concatenates arrays and deep merges objects`() {
        val result = render(
            "<% setvar('list', ['a', 'b']) %><% setvar('list', ['c'], {merge: true}) %>" +
                "<%= getvar('list.2') %>|<%= getvar('obj.nested') %>",
            metadata = buildJsonObject {
                put("promptTemplateLocalVariables", buildJsonObject {
                    put("obj", buildJsonObject { put("nested", JsonPrimitive("old")); put("keep", JsonPrimitive("yes")) })
                })
            },
        )

        // list merge: message-scope write defaults, second setvar merges onto the cache.
        assertEquals("c|old", result.messages.single().content)
    }

    @Test
    fun `results old returns the previous value`() {
        val result = render(
            "<% setvar('k', 'old-value') %><%= setvar('k', 'new-value', {results: 'old'}) %>",
        )

        assertEquals("old-value", result.messages.single().content)
    }

    @Test
    fun `incvar defaults to writing message scope`() {
        val result = render(
            "<% incvar('count') %><%= getvar('count') %>",
        )

        assertEquals("1", result.messages.single().content)
        assertEquals(1.0, result.variableUpdates.message?.get("count")?.jsonPrimitive?.content?.toDouble())
        assertNull(result.variableUpdates.local)
    }

    @Test
    fun `seeded message variables are visible and unchanged seed is not persisted`() {
        val result = render(
            "<%= getvar('hp') %>",
            messageVariables = buildJsonObject { put("hp", JsonPrimitive(42)) },
        )

        assertEquals("42", result.messages.single().content)
        assertNull(result.variableUpdates.message)
    }

    @Test
    fun `changed seeded message variables persist as message update`() {
        val result = render(
            "<% setvar('hp', 50) %>",
            messageVariables = buildJsonObject { put("hp", JsonPrimitive(42)) },
        )

        assertEquals(50.0, result.variableUpdates.message?.get("hp")?.jsonPrimitive?.content?.toDouble())
    }

    @Test
    fun `historical floor writes are isolated from persistence`() {
        val processor = processor()
        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "sys"),
                    // A historical chat floor: index != 0 and != last → isolated.
                    PromptMessage(role = MessageRole.Assistant, channel = "chat", content = "<% incvar('floor', 1) %>"),
                    PromptMessage(role = MessageRole.User, channel = "chat", content = "<%= getvar('floor', {defaults: 'none'}) %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        // ST-faithful: historical floors do not execute during generate at all
        // (their effects applied once at creation), so the isolated write is
        // invisible to the current turn's read and to the persisted updates.
        assertEquals("none", result.messages[2].content)
        assertNull(result.variableUpdates.message)
        assertNull(result.variableUpdates.local)
    }

    @Test
    fun `historical floor registration does not refresh sticky`() {
        val first = processor().process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "<% injectPrompt('sticky', 'v', 100, 2) %>")),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        assertEquals("", first.messages.single().content)

        // Build 2: a historical floor re-registers (isolated → rolled back),
        // while the system prompt does not; one decay remains from build 1.
        val second = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "sys"),
                    PromptMessage(role = MessageRole.Assistant, channel = "chat", content = "<% injectPrompt('sticky', 'v2', 100, 9) %>"),
                    PromptMessage(role = MessageRole.User, channel = "chat", content = "<%= getPromptsInjected('sticky') %>"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )
        // Decay: sticky 2→1 → alive; the isolated floor's sticky=9 registration is rolled back.
        assertEquals("v", second.messages[2].content)
    }

    @Test
    fun `object literal options parse in the fallback evaluator`() {
        val result = render("<%= getvar('missing', {defaults: 'fallback'}) %>")

        assertEquals("fallback", result.messages.single().content)
    }

    @Test
    fun `data class updates carry message field for the coordinator`() {
        val updates = PromptTemplateVariableUpdates(
            local = null,
            global = null,
            message = buildJsonObject { put("hp", JsonPrimitive(5)) },
        )

        assertEquals(5, updates.message?.get("hp")?.jsonPrimitive?.content?.toInt())
        assertNull(updates.local)
    }
}
