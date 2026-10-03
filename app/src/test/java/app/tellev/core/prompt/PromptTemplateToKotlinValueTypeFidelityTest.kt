package app.tellev.core.prompt

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves that JSON *strings* that look like numbers/booleans keep their type
 * when crossing toKotlinValue (the Kotlin template-render path and the
 * bridge-return path both funnel through it).
 *
 * Expected (correct) behaviour: a quoted JSON string stays a String.
 * Current behaviour: kotlinx-serialization's booleanOrNull/longOrNull parse
 * `content` without an isString check, so quoted "007" becomes Long 7 and
 * "true" becomes Boolean true. Verified against kotlinx-serialization 1.8.1
 * bytecode (JsonElementKt.parseLongImpl / getBooleanOrNull).
 */
class PromptTemplateToKotlinValueTypeFidelityTest {

    private fun toKotlin(json: String): Any? =
        PromptTemplateExpressionEvaluator.toKotlinValue(Json.parseToJsonElement(json))

    @Test
    fun `quoted numeric-looking strings must stay strings`() {
        // "007" currently becomes Long 7 -> leading zero lost on write-back.
        assertEquals("007", toKotlin("\"007\""))
        // "1e10" currently becomes Double 1.0E10 -> reformatted to 10000000000.
        assertEquals("1e10", toKotlin("\"1e10\""))
        // Large integers beyond Long range currently collapse into Double.
        assertEquals("12345678901234567890123", toKotlin("\"12345678901234567890123\""))
    }

    @Test
    fun `quoted boolean-looking strings must stay strings`() {
        // "true" currently becomes Boolean true.
        assertEquals("true", toKotlin("\"true\""))
    }

    @Test
    fun `unquoted JSON scalars keep numeric and boolean types`() {
        assertEquals(7L, toKotlin("7"))
        assertEquals(true, toKotlin("true"))
        assertEquals(null, toKotlin("null"))
    }
}
