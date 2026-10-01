package app.tellev.feature.chat

import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.*
import app.tellev.feature.chat.ChatSessionInit.withCharacterGreetingSwipes
import app.tellev.feature.chat.ChatSessionInit.withProcessedGreeting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Expectations from the locked ST script.js/regex/engine.js/world-info.js, not Tellev output. */
class TavernCompatibilityRegressionTest {
    private fun script(find: String, replacement: String, display: Boolean = false, substitute: Int = 0, trims: List<String> = emptyList()) = buildJsonObject {
        put("findRegex", find); put("replaceString", replacement); put("markdownOnly", display)
        put("placement", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
        put("substituteRegex", substitute); put("trimStrings", JsonArray(trims.map(::JsonPrimitive)))
    }
    private fun card(greeting: String = "hello", scripts: List<JsonObject> = emptyList(), alternatives: List<String> = emptyList()) =
        CharacterCard("fixture", "Alice", firstMessage = greeting, alternateGreetings = alternatives,
            raw = buildJsonObject { put("extensions", buildJsonObject { put("regex_scripts", JsonArray(scripts)) }) })
    private fun session(card: CharacterCard, messages: List<ChatMessage>? = null, vars: JsonObject = JsonObject(emptyMap())) =
        ChatSession("chat", "fixture", card.id, null, messages ?: listOf(ChatMessage("g", MessageRole.Character, card.name, card.firstMessage, 1,
            swipes = listOf(card.firstMessage) + card.alternateGreetings)), metadata = buildJsonObject { put("variables", vars) })
    private val preset = GenerationPreset("p", "Fixture", "fixture")
    private fun request(card: CharacterCard, messages: List<ChatMessage>, books: List<WorldBook> = emptyList()) =
        PromptBuildRequest(card, Persona("u", "Bob", ""), messages, books, preset, "next", "fixture")

    @Test fun `real supplied PNG greeting resolves eight variables before durable save`() = runBlocking {
        val text = requireNotNull(javaClass.getResourceAsStream("/fixtures/xiangcun-greeting.json")).bufferedReader().use { it.readText() }
        val imported = CharacterImporter().importFromJson(text)
        val root = Files.createTempDirectory("tellev-greeting-")
        try {
            val disk = FileStDataStore(StDirectoryLayout.fromRoot(root)); disk.bootstrap()
            val created = ChatSessionInit.createSessionForCharacter(imported, "Bob", disk)
            val saved = disk.readChatSession(created.id)
            assertEquals(created, saved)
            assertFalse(saved.messages.first().content.contains("{{setvar::"))
            assertFalse(saved.messages.first().content.contains("{{getvar::"))
            assertTrue(saved.messages.first().content.contains("第2295章"))
            assertEquals(8, (saved.metadata["variables"] as JsonObject).size)
            assertTrue(CharacterRegexApplier.isNormalProcessed(saved.messages.first()))
            assertEquals(saved, saved.withProcessedGreeting(imported, "Bob", DefaultPromptEngine()))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `normal greeting regex is saved once and alternative initialization is lazy`() = runBlocking {
        val c = card("{{setvar::score::10}}a", listOf(script("/a$/g", "aa")), listOf("{{setvar::score::20}}other"))
        val engine = DefaultPromptEngine()
        val first = session(c).withProcessedGreeting(c, "Bob", engine)
        assertEquals("aa", first.messages.first().content)
        assertEquals("10", (first.metadata["variables"] as JsonObject)["score"]!!.jsonPrimitive.content)
        assertEquals(2, first.withCharacterGreetingSwipes(c).messages.first().swipes.size)
        val selected = first.copy(messages = listOf(first.messages.first().copy(swipeIndex = 1, content = c.alternateGreetings.first())))
            .withProcessedGreeting(c, "Bob", engine)
        assertEquals("other", selected.messages.first().content)
        assertEquals("20", (selected.metadata["variables"] as JsonObject)["score"]!!.jsonPrimitive.content)
        assertEquals(selected, selected.withProcessedGreeting(c, "Bob", engine))
    }

    @Test fun `upgrading a raw old greeting preserves advanced variables and custom first floors`() {
        val c = card("{{setvar::score::10}}[{{getvar::score}}]")
        val old = session(c, vars = buildJsonObject { put("score", "99") }).let {
            it.copy(messages = it.messages + ChatMessage("u", MessageRole.User, "Bob", "later", 2))
        }
        val upgraded = old.withProcessedGreeting(c, "Bob", DefaultPromptEngine())
        assertEquals("[99]", upgraded.messages.first().content)
        assertEquals("99", (upgraded.metadata["variables"] as JsonObject)["score"]!!.jsonPrimitive.content)
        val custom = old.copy(messages = listOf(old.messages.first().copy(content = "custom")))
        assertEquals(custom, custom.withProcessedGreeting(c, "Bob", DefaultPromptEngine()))
    }

    @Test fun `an imported first bot floor also expands and saves its macros once`() {
        val c = card("original", listOf(script("/Bob/g", "changed")))
        val imported = session(c, listOf(ChatMessage("imported", MessageRole.Character, "Alice", "{{user}} {{incvar::count}}", 1)))
        val saved = imported.withProcessedGreeting(c, "Bob", DefaultPromptEngine())
        assertEquals("Bob 1", saved.messages.first().content)
        assertEquals("1", (saved.metadata["variables"] as JsonObject)["count"]!!.jsonPrimitive.content)
        assertEquals(saved, saved.withProcessedGreeting(c, "Bob", DefaultPromptEngine()))
    }

    @Test fun `script changes to the first floor invalidate its stored macro evaluation`() {
        val c = card("{{incvar::count}}")
        val engine = DefaultPromptEngine()
        val first = session(c).withProcessedGreeting(c, "Bob", engine)
        val scriptWrite = first.copy(messages = listOf(first.messages.first().copy(content = "new {{incvar::count}}")))
        val processed = scriptWrite.withProcessedGreeting(c, "Bob", engine)
        assertEquals("new 2", processed.messages.first().content)
        assertEquals(processed, processed.withProcessedGreeting(c, "Bob", engine))
    }

    @Test fun `explicit greeting macro references the main card greeting after selecting an alternative`() {
        val c = card("main", alternatives = listOf("alternative"))
        val selected = session(c, listOf(ChatMessage("g", MessageRole.Character, "Alice", "alternative", 1)))
        val context = ChatTextProcessing.context(c, selected, "Bob")
        assertEquals("main", DefaultPromptEngine().processChatText("{{greeting}}", MessageRole.User, c, preset, context).text)
    }

    @Test fun `prompt does not expand original greeting and shares the first floor evaluation`() {
        val state = SnapshotMacroVariables(buildJsonObject { put("score", "99"); put("count", "0") })
        val macros = DefaultMacroEngine()
        val scoped = object : MacroEngine {
            override fun expand(text: String, context: MacroContext) = macros.expand(text, context.copy(variableAccess = state))
            override fun registerCustomMacro(name: String, resolver: (MacroContext) -> String) = macros.registerCustomMacro(name, resolver)
        }
        val c = card("{{setvar::score::10}}hello")
        DefaultPromptEngine(scoped).build(request(c, listOf(ChatMessage("g", MessageRole.Character, "Alice", "{{incvar::count}}", 1))))
        assertEquals("99", state.getLocal("score")); assertEquals("1", state.getLocal("count"))
        val literal = "{{setvar::score::20}}[{{getvar::score}}]"
        val result = DefaultPromptEngine(scoped).build(request(c, listOf(
            ChatMessage("g", MessageRole.Character, "Alice", "hello", 1), ChatMessage("a", MessageRole.Character, "Alice", literal, 2))))
        assertTrue(result.messages.any { it.content == literal }); assertEquals("99", state.getLocal("score"))
    }

    @Test fun `user text normal regex precedes macros and assistant body macros stay literal`() {
        val c = card(scripts = listOf(script("/TOKEN/g", "{{user}}")))
        val ctx = ChatTextProcessing.context(c, session(c), "Bob")
        assertEquals("Bob", DefaultPromptEngine().processChatText("TOKEN", MessageRole.User, c, preset, ctx).text)
        assertEquals("Bob", DefaultPromptEngine().processChatText("{{user}}", MessageRole.User, c, preset, ctx).text)
        val raw = "{{setvar::score::20}}[{{getvar::score}}]"
        assertEquals(raw, DefaultPromptEngine().processChatText(raw, MessageRole.Character, c, preset, ctx, expandMacros = false).text)
    }

    @Test fun `regex expands find trim and replacement macros after capture insertion`() {
        val variables = SnapshotMacroVariables(buildJsonObject { put("score", "10"); put("token", "ALPHA") })
        val engine = DefaultMacroEngine(); val context = MacroContext(userName = "Bob", variableAccess = variables)
        fun apply(text: String, rule: JsonObject) = CharacterRegexApplier.applyNormal(text, MessageRole.Character, card(scripts = listOf(rule)),
            macroExpander = { engine.expand(it, context) })
        assertEquals("[10]", apply("x", script("/x/g", "[{{getvar::score}}]")))
        assertEquals("hit", apply("ALPHA", script("{{getvar::token}}", "hit", substitute = 1)))
        assertEquals("Bob", apply("{{user}}", script("/(.*)/", "\$1")))
        assertEquals("", apply("ALPHA", script("/(.*)/", "\$1", trims = listOf("{{getvar::token}}"))))
        assertEquals("1", apply("x", script("/(x)/", "\$1", trims = listOf("{{incvar::count}}"))).let { variables.getLocal("count") })
        apply("no match", script("/(x)/", "\$1", trims = listOf("{{incvar::count}}")))
        assertEquals("1", variables.getLocal("count"))
    }

    @Test fun `preset precedes scoped regex and JS replacement boundary behavior is preserved`() {
        val c = card(scripts = listOf(script("/b/g", "c")))
        val p = preset.copy(extensions = buildJsonObject { put("regex_scripts", JsonArray(listOf(script("/a/g", "b")))) })
        assertEquals("c", CharacterRegexApplier.applyNormal("a", MessageRole.Character, c, p))
        fun apply(find: String, replacement: String, text: String) = CharacterRegexApplier.applyNormal(text, MessageRole.Character, card(scripts = listOf(script(find, replacement))))
        assertEquals("", apply("/(x)/g", "\$99", "x"))
        assertEquals("\$&", apply("/x/g", "\$&", "x"))
        assertEquals("hit", apply("/\\u{1F600}/u", "hit", "😀"))
        assertEquals("😀", apply("/\\u{110000}/u", "hit", "😀"))
        assertEquals("literal", apply("/\\\\u\\{1F600\\}/u", "literal", "\\u{1F600}"))
    }

    @Test fun `embedded worldbook independent scan depth includes pending floor and survives export`() {
        val c = CharacterImporter().importFromJson("""{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Alice","character_book":{"entries":[{"id":0,"keys":["secret"],"content":"hidden lore","extensions":{"scan_depth":1}}]}}}""")
        assertEquals(1, c.characterBook!!.entries.single().scanDepth)
        val history = listOf(ChatMessage("g", MessageRole.Character, "Alice", "secret", 1), ChatMessage("u", MessageRole.User, "Bob", "recent", 2))
        val result = DefaultPromptEngine().build(request(c, history, listOf(c.characterBook!!)))
        assertEquals(0, result.diagnostics.activatedWorldEntryIds.size)
        val inherited = c.characterBook!!.copy(entries = c.characterBook!!.entries.map { it.copy(scanDepth = null) })
        assertEquals(1, DefaultPromptEngine().build(request(c, history, listOf(inherited))).diagnostics.activatedWorldEntryIds.size)
    }

    @Test fun `background display scopes and render cache keys track session variables`() {
        val c = card(scripts = listOf(script("/x/g", "[{{getvar::score}}]", display = true)))
        val parts = MessageReasoning.fromResponse("x", "")
        val a = MacroContext(userName = "Bob", localVariables = buildJsonObject { put("score", "10") })
        val b = a.copy(localVariables = buildJsonObject { put("score", "99") })
        fun render(ctx: MacroContext) = renderMessageParts(parts, MessageRole.Character, c, preset, "Bob", 0, false, ctx)
        assertEquals(listOf(TavernRenderSegment.Text("[10]")), render(a))
        assertEquals(listOf(TavernRenderSegment.Text("[99]")), render(b))
        assertNotEquals(RenderInputs(parts, MessageRole.Character, c, preset, "Bob", 0, false, a), RenderInputs(parts, MessageRole.Character, c, preset, "Bob", 0, false, b))
        assertEquals(listOf(TavernRenderSegment.Text("[10]")), render(a))
    }

    @Test fun `per entry scan depth also controls group scoring and zero depth recursion`() {
        val shallow = WorldBookEntry("shallow", listOf("secret"), content = "shallow", constant = true,
            group = "g", useGroupScoring = true, scanDepth = 1)
        val deep = shallow.copy(id = "deep", content = "deep", scanDepth = 3)
        val scanner = WorldInfoScanner(random = { 0.0 }, maxRecursionSteps = 2)
        val grouped = scanner.scan(listOf(shallow, deep), "secret recent", { _, text -> text },
            entrySearchText = { if (it.scanDepth == 1) "recent" else "secret recent" })
        assertEquals(listOf("deep"), grouped.allActivated.map { it.entry.id })
        val source = WorldBookEntry("source", emptyList(), content = "recursive secret", constant = true)
        val zero = WorldBookEntry("zero", listOf("secret"), content = "must not activate", scanDepth = 0)
        val result = scanner.scan(listOf(source, zero), "recent", { _, text -> text }, entrySearchText = { if (it.scanDepth == 0) "" else "recent" })
        assertEquals(listOf("source"), result.allActivated.map { it.entry.id })
    }

    @Test fun `worldbook scan depth round trips and explicit null overrides old raw extension`() {
        val raw = Json.parseToJsonElement("""{"name":"book","entries":{"0":{"uid":0,"key":["secret"],"content":"lore","extensions":{"scan_depth":1}}}}""").jsonObject
        val codec = app.tellev.core.storage.codec.WorldBookCodec
        val entries = codec.parseWorldBookEntries(raw)
        assertEquals(1, entries.single().scanDepth)
        val book = WorldBook("b", "book", entries, raw)
        assertEquals(1, codec.parseWorldBookEntries(codec.serializeWorldBook(book)).single().scanDepth)
        val cleared = book.copy(entries = entries.map { it.copy(scanDepth = null) })
        assertNull(codec.parseWorldBookEntries(codec.serializeWorldBook(cleared)).single().scanDepth)
    }
}
