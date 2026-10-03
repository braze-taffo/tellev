package app.tellev.core.regex

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A8: card-shipped regexes run untrusted patterns — the match-step budget
 *  must skip catastrophic rules fast while honest rules stay unaffected. */
class CharacterRegexApplierReDoSGuardTest {

    private fun script(find: String, replacement: String) = buildJsonObject {
        put("findRegex", find)
        put("replaceString", replacement)
        put("placement", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
    }

    private fun card(scripts: List<JsonObject>) = CharacterCard(
        "fixture", "Alice",
        raw = buildJsonObject { put("extensions", buildJsonObject { put("regex_scripts", JsonArray(scripts)) }) },
    )

    private fun apply(text: String, scripts: List<JsonObject>): Pair<String, List<CharacterRegexApplier.RegexDiagnostic>> {
        val diagnostics = mutableListOf<CharacterRegexApplier.RegexDiagnostic>()
        val output = CharacterRegexApplier.apply(
            text,
            CharacterRegexApplier.RegexExecutionContext(
                character = card(scripts),
                role = MessageRole.Character,
                phase = CharacterRegexApplier.RegexPhase.Normal,
                onDiagnostic = diagnostics::add,
            ),
        )
        return output to diagnostics
    }

    @Test
    fun `super-linear match is skipped fast with a timeout diagnostic`() {
        // Lazy dot-star rescan from every start position is quadratic on this
        // input (100k chars: >10^9 char reads) — exactly the shape that froze
        // real preset rules for ~20s per message. The read budget trips it in
        // milliseconds; desktop JDK hardening covers exponential classics, but
        // Android's ICU-backed Pattern does not, so the guard stays load-bearing.
        val input = "b".repeat(100_000)
        val started = System.nanoTime()
        val (output, diagnostics) = apply(input, listOf(script("/.*?X/s", "X")))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(input, output)
        assertEquals(1, diagnostics.size)
        assertEquals(UiStrings.get(S.cregex_diag_regex_timeout), diagnostics.single().message)
        assertTrue("guard should trip fast, took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `honest regex rules still apply within the budget`() {
        val input = "hello world ".repeat(500)
        val (output, diagnostics) = apply(input, listOf(script("/world/", "WORLD")))
        // No g flag: JavaScript String.replace replaces the first match only.
        assertEquals(input.replaceFirst("world", "WORLD"), output)
        assertTrue(diagnostics.isEmpty())
    }
}
