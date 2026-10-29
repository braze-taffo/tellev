package app.tellev.core.regex

import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.storage.*
import app.tellev.feature.chat.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegexImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun rules(text: String) = RegexImport.parse(text)
    private fun rule(name: String = "Test", from: String = "A", to: String = "B", extra: String = "") =
        """{"id":"source-id","scriptName":"$name","findRegex":"/$from/g","replaceString":"$to","placement":[1,2,5,6]$extra}"""
    private fun store(name: String) = FileStDataStore(StDirectoryLayout.fromRoot(temporary.newFolder(name).toPath()))

    @Test fun `single array and BOM exports preserve unknown fields and disabled state`() {
        val text = rule(extra = """, "disabled":true,"vendor":{"items":[1,"x"]}""")
        assertEquals(rules(text), rules("[$text]"))
        assertEquals(rules(text), rules("\uFEFF$text"))
        val result = RegexImport.merge(JsonArray(emptyList()), rules(text)).scripts.single().jsonObject
        assertNotEquals("source-id", result["id"]?.jsonPrimitive?.content)
        assertEquals(rules(text).single().jsonObject["vendor"], result["vendor"])
        assertEquals(JsonPrimitive(true), result["disabled"])
    }

    @Test fun `same name replaces in place keeps target id and unrelated rules`() {
        val original = rules("[${rule()},${rule("Other")}]")
        val result = RegexImport.merge(original, rules("[${rule(to = "C", extra = ",\"disabled\":true")},${rule("New")} ]"))
        assertEquals(1, result.added)
        assertEquals(1, result.replaced)
        assertEquals("source-id", result.scripts[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("C", result.scripts[0].jsonObject["replaceString"]?.jsonPrimitive?.content)
        assertEquals(original[1], result.scripts[1])
        assertEquals("New", result.scripts[2].jsonObject["scriptName"]?.jsonPrimitive?.content)
        assertTrue(result.scripts[0].jsonObject["disabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `invalid batch and ambiguous names are rejected before writes`() = runBlocking {
        val store = store("invalid")
        val existing = rules(rule())
        store.saveGlobalRegexScripts(existing)
        val bad = listOf("{", "[]", "null", "[1]", "{}", rule().replace("\"Test\"", "false"),
            rule().replace("\"/A/g\"", "\"\""), rule(extra = ",\"disabled\":\"false\""),
            rule(extra = ",\"maxDepth\":1.5"), rule(extra = ",\"trimStrings\":[1]"),
            rule().replace("[1,2,5,6]", "[\"2\"]"), "[${rule()},${rule()}]")
        for (text in bad) {
            assertTrue(text, runCatching { RegexImport.importInto(store, rules(text), null) }.isFailure)
            assertEquals(existing, store.readGlobalRegexScripts())
        }
        val ambiguous = JsonArray(listOf(existing[0], existing[0]))
        store.saveGlobalRegexScripts(ambiguous)
        assertTrue(runCatching { RegexImport.importInto(store, rules(rule(to = "C")), null) }.isFailure)
        assertEquals(ambiguous, store.readGlobalRegexScripts())
    }

    @Test fun `scopes switches deletion restart notification and backup roundtrip`() = runBlocking {
        val store = store("scopes")
        val card = CharacterCard(id = "alice", name = "Alice", raw = buildJsonObject {
            put("data", buildJsonObject { put("name", "Alice"); put("extensions", buildJsonObject { put("custom", "kept") }) })
        })
        store.saveCharacter(card)
        val changed = async(start = CoroutineStart.UNDISPATCHED) { store.globalRegexChanges.first() }
        RegexImport.importInto(store, rules(rule()), null)
        changed.await()
        RegexImport.importInto(store, rules(rule("Local", to = "C")), card.id)
        assertEquals("Test", store.readGlobalRegexScripts()[0].jsonObject["scriptName"]?.jsonPrimitive?.content)
        val savedCard = store.readCharacter(card.id)
        assertEquals("Local", RegexImport.scripts(savedCard)[0].jsonObject["scriptName"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive("kept"), savedCard.raw["data"]!!.jsonObject["extensions"]!!.jsonObject["custom"])
        val globals = store.readGlobalRegexScripts()
        val id = globals[0].jsonObject["id"]!!.jsonPrimitive.content
        store.saveGlobalRegexScripts(RegexImport.toggle(globals, id))
        val reopened = FileStDataStore(store.layout)
        assertFalse(CharacterRegexApplier.summarizeScripts(reopened.readGlobalRegexScripts()).single().enabled)
        val backup = temporary.root.toPath().resolve("backup.zip")
        store.exportBackup(backup)
        val restored = store("restore")
        restored.importBackup(backup)
        assertEquals(store.readGlobalRegexScripts(), restored.readGlobalRegexScripts())
        store.saveGlobalRegexScripts(RegexImport.delete(store.readGlobalRegexScripts(), id))
        assertTrue(store.readGlobalRegexScripts().isEmpty())
        assertEquals(1, RegexImport.scripts(store.readCharacter(card.id)).size)
        val local = RegexImport.scripts(store.readCharacter(card.id))
        store.saveCharacter(RegexImport.withScripts(store.readCharacter(card.id), RegexImport.delete(local,
            CharacterRegexApplier.summarizeScripts(local).single().id)))
        assertTrue(RegexImport.scripts(store.readCharacter(card.id)).isEmpty())
    }

    @Test fun `global preset and card normal processing has deterministic order`() {
        val global = rules(rule(from = "A", to = "B"))
        val preset = GenerationPreset(id = "preset", name = "Preset", providerType = "openai-compatible", extensions = buildJsonObject {
            put("regex_scripts", rules(rule(from = "B", to = "C")))
        })
        val card = RegexImport.withScripts(CharacterCard(id = "c", name = "C"), rules(rule(from = "C", to = "D")))
        assertEquals("D", CharacterRegexApplier.applyNormal("A", MessageRole.Character, card, preset, globalScripts = global))
        assertEquals("D", DefaultPromptEngine().processChatText("A", MessageRole.User, card, preset,
            MacroContext(globalRegexScripts = global)).text)
        assertEquals("A", CharacterRegexApplier.applyNormal("A", MessageRole.User, card, preset, isEdit = true, globalScripts = global))
    }

    @Test fun `global display and reasoning refresh cache and leave saved normal text alone`() {
        val global = rules(rule(extra = ",\"markdownOnly\":true"))
        val parts = MessageReasoning.Parts(body = "A", reasoning = "A")
        val context = MacroContext(globalRegexScripts = global)
        val rendered = renderMessageParts(parts, MessageRole.Character, null, null, "U", 0, false, context)
        assertTrue(rendered.contains(TavernRenderSegment.Text("B")))
        assertTrue(rendered.contains(TavernRenderSegment.Reasoning("B")))
        val before = RenderInputs(parts, MessageRole.Character, null, null, "U", 0, false, MacroContext())
        assertNotEquals(before, before.copy(macroContext = context))
        val normal = rules(rule())
        assertEquals("A", CharacterRegexApplier.applyForDisplay("A", MessageRole.User, null, includeNormal = false, globalScripts = normal))
        assertEquals("A", CharacterRegexApplier.applyForDisplay("A", MessageRole.User, null, depth = 2,
            globalScripts = rules(rule(extra = ",\"markdownOnly\":true,\"maxDepth\":1"))))
    }

    @Test fun `global prompt rules process history pending input and world info`() {
        val global = rules(rule(extra = ",\"promptOnly\":true"))
        assertEquals("B", CharacterRegexApplier.applyWorldInfoForPrompt("A", null, globalScripts = global))
        val request = PromptBuildRequest(character = CharacterCard(id = "c", name = "C"), persona = null,
            messages = listOf(ChatMessage(id = "m", role = MessageRole.Character, name = "C", content = "A", createdAtMillis = 0)),
            worldBooks = emptyList(), preset = GenerationPreset(id = "p", name = "P", providerType = "openai-compatible"), userInput = "A", providerType = "openai-compatible",
            globalRegexScripts = global)
        val result = DefaultPromptEngine().build(request)
        assertTrue(result.messages.any { it.content == "B" && it.role == MessageRole.User })
        assertTrue(result.messages.any { it.content == "B" && it.role == MessageRole.Assistant })
    }
}
