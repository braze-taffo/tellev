package app.tellev.core.extension.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScriptSourceEscapingTest {
    @Test fun `single buffer API responses preserve exact escaping for arbitrary JSON and Unicode`() {
        val body = """{"quote":"'", "slash":"\\", "unicode":"中文"}""" + "\n\r\u2028\u2029"
        fun previousEscape(raw: String) = raw.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val id = "request'\\\n"
        assertEquals(previousEscape(body), ExtensionScriptTemplate.jsEscape(body))
        assertEquals("if(window.Tellev&&window.Tellev.onApiResponse){window.Tellev.onApiResponse('" +
            previousEscape(id) + "',200,'" + previousEscape(body) + "');}",
            ExtensionScriptTemplate.apiResponseScript(id, 200, body))
        val large = body.repeat(20000)
        assertEquals(previousEscape(large), ExtensionScriptTemplate.jsEscape(large))
    }
    @Test
    fun `script closer is escaped without changing its string value`() {
        val source = "\"</script>\""
        val safe = ExtensionScriptTemplate.sanitizeScriptSource(source)
        assertFalse(safe.contains("</script", ignoreCase = true))
        assertEquals("</script>", Json.parseToJsonElement(safe).jsonPrimitive.content)
    }

    @Test
    fun `ordinary comparisons regex and escapes remain untouched`() {
        val source = "const re = /foo/; const s = '\\n'; const ok = a < /b/.test(c);"
        assertEquals(source, ExtensionScriptTemplate.sanitizeScriptSource(source))
    }

    @Test
    fun `mixed case and repeated closers cannot terminate script element`() {
        val safe = ExtensionScriptTemplate.sanitizeScriptSource("'</ScRiPt> </SCRIPT> </script>'")
        assertFalse(safe.contains("</script", ignoreCase = true))
        assertEquals("'<\\/script> <\\/script> <\\/script>'", safe)
    }
}
