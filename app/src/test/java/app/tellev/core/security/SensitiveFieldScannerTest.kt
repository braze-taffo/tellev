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

    @Test
    fun `content hashes and fingerprints survive backup sanitization`() {
        // Backup export sanitizes every .json it ships. The 40+ alphanumeric
        // shape rule used to redact SHA-256 hex digests, so a backup round-trip
        // rewrote memory `processed` maps and script-consent fingerprints to
        // "[REDACTED]" — the first prompt after restore then judged the
        // history "changed" and wiped long-term memory.
        val sha256 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        val input = buildJsonObject {
            put("processed", buildJsonObject {
                put("m1", sha256)
            })
            put("records", buildJsonArray {
                add(buildJsonObject {
                    put("id", sha256)
                    put("vector", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(0.5)) })
                })
            })
            put("consents", buildJsonObject {
                put("char-1", buildJsonObject {
                    put("approved", true)
                    put("fingerprint", sha256)
                })
            })
        }
        val sanitized = SensitiveFieldScanner.sanitize(input)
        val processed = sanitized["processed"]!!.jsonObject["m1"]!!.jsonPrimitive.content
        assertEquals(sha256, processed)
        val fingerprint = sanitized["consents"]!!.jsonObject["char-1"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content
        assertEquals(sha256, fingerprint)
        // A real key under a sensitive name is still redacted even when the
        // value is hex-shaped.
        val withKey = buildJsonObject {
            put("api_key", sha256)
        }
        assertEquals("[REDACTED]", SensitiveFieldScanner.sanitize(withKey)["api_key"]!!.jsonPrimitive.content)
    }
}
