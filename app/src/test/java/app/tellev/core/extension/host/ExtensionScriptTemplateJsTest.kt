package app.tellev.core.extension.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8: extension settings JSON is embedded as a bare JS expression. */
class ExtensionScriptTemplateJsTest {

    @Test
    fun `settings JSON passes escape sequences through verbatim`() {
        // \\d and \\n are JSON escapes for a backslash and a newline; the old
        // backslash-doubling turned them into literal "\d"/"\n" text.
        val json = """{"pattern":"\\d+","text":"line\nbreak"}"""
        val embedded = ExtensionScriptTemplate.jsSettingsExpression(json)
        assertEquals(json, embedded)
        assertEquals(
            Json.parseToJsonElement(json),
            Json.parseToJsonElement(embedded),
        )
    }

    @Test
    fun `settings JSON cannot break out of the script block`() {
        val embedded = ExtensionScriptTemplate.jsSettingsExpression("""{"a":"</script>"}""")
        assertFalse(embedded.contains("</script", ignoreCase = true))
        assertTrue(embedded.contains("<\\/script"))
        assertEquals("</script>", Json.parseToJsonElement(embedded).jsonObject["a"]?.jsonPrimitive?.content)
    }

    @Test
    fun `settings JSON escapes U+2028 and U+2029`() {
        val embedded = ExtensionScriptTemplate.jsSettingsExpression("{\"a\":\"\u2028\u2029\"}")
        assertFalse(embedded.contains("\u2028"))
        assertFalse(embedded.contains("\u2029"))
        assertTrue(embedded.contains("\\u2028"))
        assertTrue(embedded.contains("\\u2029"))
    }
}
