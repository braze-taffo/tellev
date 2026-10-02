package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JS-semantics alignment for the Kotlin fallback expression evaluator
 * (findings B2/B4/B6): real ST cards branch on strings like "0", so truthy
 * must follow JS ToBoolean, equality must be type-aware, and string literals
 * must unescape \xNN/\uXXXX in a single pass.
 */
class PromptTemplateExpressionSemanticsTest {

    private fun render(
        content: String,
        variables: JsonObject = buildJsonObject { },
    ) = DefaultPromptTemplateProcessor().process(
        PromptTemplateRequest(
            messages = listOf(PromptMessage(role = MessageRole.System, content = content)),
            context = MacroContext(),
            metadata = buildJsonObject {
                putJsonObject("promptTemplateVariables") { variables.forEach { (k, v) -> put(k, v) } }
            },
        ),
    ).messages.single().content

    private fun conditional(expression: String, variables: JsonObject = buildJsonObject { }): String =
        render("<% if ($expression) { %>Y<% } else { %>N<% } %>", variables)

    // ── B2: truthy follows JS ToBoolean ────────────────────────────────

    @Test
    fun `truthy follows JS ToBoolean for strings`() {
        // JS: every non-empty string is truthy — real ST cards branch on "0".
        assertEquals("Y", conditional("getvar('x')", buildJsonObject { put("x", "0") }))
        assertEquals("Y", conditional("getvar('x')", buildJsonObject { put("x", "false") }))
        assertEquals("N", conditional("getvar('x')", buildJsonObject { put("x", "") }))
        assertEquals("N", conditional("getvar('x')"))
    }

    @Test
    fun `truthy follows JS ToBoolean for numbers and empties`() {
        assertEquals("N", conditional("getvar('n')", buildJsonObject { put("n", 0) }))
        assertEquals("N", conditional("0"))
        assertEquals("Y", conditional("1"))
        // Empty arrays/objects are truthy in JS (no falsy collection rule).
        assertEquals("Y", conditional("[]"))
        assertEquals("Y", conditional("{}"))
    }

    // ── B4: ==/=== type-aware equality ─────────────────────────────────

    @Test
    fun `strict equality compares types like JS`() {
        assertEquals("N", conditional("1 === '1'"))
        assertEquals("Y", conditional("1 === 1"))
        assertEquals("Y", conditional("'a' === 'a'"))
        assertEquals("N", conditional("true === 1"))
        assertEquals("N", conditional("'yes' !== 'yes'"))
        assertEquals("Y", conditional("getvar('n') === '007'", buildJsonObject { put("n", "007") }))
    }

    @Test
    fun `loose equality coerces like JS`() {
        assertEquals("Y", conditional("1 == '1'"))
        assertEquals("Y", conditional("true == 1"))
        assertEquals("N", conditional("0 == 'abc'"))
        assertEquals("Y", conditional("'a' != 'b'"))
        assertEquals("Y", conditional("getvar('n') == 1.0", buildJsonObject { put("n", "1") }))
    }

    // ── B6: string literal escapes, single pass ────────────────────────

    @Test
    fun `string literals unescape xNN and uXXXX`() {
        assertEquals("A", render("<%= '\\x41' %>"))
        assertEquals("B", render("<%= '\\u0042' %>"))
    }

    @Test
    fun `string literal escapes are single pass`() {
        // Chained replaces used to turn backslash-backslash-n into a newline.
        assertEquals("a\\nb", render("<%= 'a\\\\nb' %>"))
        assertEquals("\n", render("<%= '\\n' %>"))
        // Unknown escapes degrade to the escaped character (JS behavior).
        assertEquals("aqb", render("<%= 'a\\qb' %>"))
    }
}
