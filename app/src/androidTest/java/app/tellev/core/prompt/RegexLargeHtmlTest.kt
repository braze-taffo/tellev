package app.tellev.core.prompt

import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.model.*
import app.tellev.core.storage.CharacterImporter
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RegexLargeHtmlTest {
    @Test(timeout = 8000) fun missingLiteralSuffixDoesNotBlockAndroidIcu() {
        val script = buildJsonObject {
            put("findRegex", "([\\s\\S]*)<\\/thinking>")
            put("replaceString", "")
            putJsonArray("placement") { add(JsonPrimitive(2)) }
        }
        val context = CharacterRegexApplier.RegexExecutionContext(null, role = MessageRole.Assistant,
            phase = CharacterRegexApplier.RegexPhase.Normal, globalScripts = JsonArray(listOf(script)))
        val html = "<div>large HTML without a closing tag</div>".repeat(10000)
        assertEquals(html, CharacterRegexApplier.apply(html, context))
        assertEquals("tail", CharacterRegexApplier.apply("a</thinking>b</thinking>tail", context))
    }
    @Test(timeout = 12000) fun rendersActualCardGreetingsWithPresetRegex() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val card = CharacterImporter().importFromJson(assets.open("dao.json").bufferedReader().readText())
        val raw = Json.parseToJsonElement(assets.open("jiaxu.json").bufferedReader().readText()).jsonObject
        val preset = GenerationPreset("test", "test", "openai", extensions = raw["extensions"]!!.jsonObject)
        (listOf(card.firstMessage) + card.alternateGreetings).forEach {
            assertTrue(CharacterRegexApplier.applyForDisplay(it, MessageRole.Assistant,card,preset=preset).isNotEmpty())
        }
    }
}
