package app.tellev.core.security

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFieldScannerTest {
    @Test
    fun `sanitizes sensitive values nested inside arrays`() {
        val input = buildJsonObject {
            put("data", buildJsonObject {
                put("extensions", buildJsonArray {
                    add(buildJsonObject {
                        put("api_key", "sk-123456789012345678901234")
                        put("name", "safe")
                    })
                })
            })
        }
        val sanitized = SensitiveFieldScanner.sanitize(input)
        val extension = sanitized["data"]!!.jsonObject["extensions"]!!.jsonArray[0].jsonObject
        assertEquals("[REDACTED]", extension["api_key"]!!.jsonPrimitive.content)
        assertEquals("safe", extension["name"]!!.jsonPrimitive.content)
        assertTrue(SensitiveFieldScanner.findSensitiveFields(input).contains("data.extensions[0].api_key"))
    }
}
