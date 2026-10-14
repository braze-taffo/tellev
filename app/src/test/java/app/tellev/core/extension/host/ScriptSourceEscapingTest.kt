package app.tellev.core.extension.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScriptSourceEscapingTest {
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
