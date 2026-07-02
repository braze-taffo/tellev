package app.tellev.core.prompt

import app.tellev.core.extension.VariableStore
import app.tellev.core.extension.VariableStoreTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroEngineTest {

    private val engine = DefaultMacroEngine()

    private val context = MacroContext(
        characterName = "Alice",
        userName = "Bob",
        characterDescription = "A curious explorer.",
        characterPersonality = "Adventurous and witty.",
        characterScenario = "In a mysterious forest.",
        exampleMessages = "Alice: Hello!\nBob: Hi there!",
        firstMessage = "Alice greets you warmly.",
        lastMessage = "Bob says goodbye.",
        groupMemberNames = "Charlie, Diana",
        maxPromptTokens = 2048,
        maxContextTokens = 8192,
        personaDescription = "Bob is a brave traveler.",
        modelName = "claude-opus-4-8",
        maxResponseTokens = 1024,
        inputText = "Tell me a story",
        lastUserMessage = "What's next?",
        lastCharMessage = "Bob says goodbye.",
        lastMessageId = "5",
        alternateGreetings = listOf("Alt greeting one.", "Alt greeting two."),
    )

    /** A fresh in-memory VariableStore for shorthand-macro tests. */
    private fun newStore(local: MutableMap<String, String> = mutableMapOf()): VariableStore =
        VariableStoreTest.storeWith(local)

    @Test
    fun `char macro expands to character name`() {
        val result = engine.expand("Hello {{char}}!", context)
        assertEquals("Hello Alice!", result)
    }

    @Test
    fun `name2 macro expands to character name`() {
        val result = engine.expand("Hello {{name2}}!", context)
        assertEquals("Hello Alice!", result)
    }

    @Test
    fun `user macro expands to user name`() {
        val result = engine.expand("Hello {{user}}!", context)
        assertEquals("Hello Bob!", result)
    }

    @Test
    fun `name1 macro expands to user name`() {
        val result = engine.expand("Hello {{name1}}!", context)
        assertEquals("Hello Bob!", result)
    }

    @Test
    fun `description macro expands correctly`() {
        val result = engine.expand("{{description}}", context)
        assertEquals("A curious explorer.", result)
    }

    @Test
    fun `personality macro expands correctly`() {
        val result = engine.expand("{{personality}}", context)
        assertEquals("Adventurous and witty.", result)
    }

    @Test
    fun `scenario macro expands correctly`() {
        val result = engine.expand("{{scenario}}", context)
        assertEquals("In a mysterious forest.", result)
    }

    @Test
    fun `mes_example macro expands correctly`() {
        val result = engine.expand("{{mes_example}}", context)
        assertEquals("Alice: Hello!\nBob: Hi there!", result)
    }

    @Test
    fun `dialogueExamples macro expands same as mes_example`() {
        val result = engine.expand("{{dialogueExamples}}", context)
        assertEquals("Alice: Hello!\nBob: Hi there!", result)
    }

    @Test
    fun `firstMessage macro expands correctly`() {
        val result = engine.expand("{{firstMessage}}", context)
        assertEquals("Alice greets you warmly.", result)
    }

    @Test
    fun `lastMessage macro expands correctly`() {
        val result = engine.expand("{{lastMessage}}", context)
        assertEquals("Bob says goodbye.", result)
    }

    @Test
    fun `group macro expands to member names`() {
        val result = engine.expand("Members: {{group}}", context)
        assertEquals("Members: Charlie, Diana", result)
    }

    @Test
    fun `maxPrompt macro expands to token count`() {
        val result = engine.expand("{{maxPrompt}}", context)
        assertEquals("2048", result)
    }

    @Test
    fun `maxContext macro expands to token count`() {
        val result = engine.expand("{{maxContext}}", context)
        assertEquals("8192", result)
    }

    @Test
    fun `date macro produces non-empty string`() {
        val result = engine.expand("Today is {{date}}.", context)
        assertTrue(result.startsWith("Today is "))
        assertTrue(result.endsWith("."))
        assertNotEquals("Today is {{date}}.", result)
    }

    @Test
    fun `time macro produces non-empty string`() {
        val result = engine.expand("It is {{time}} now.", context)
        assertTrue(result.startsWith("It is "))
        assertTrue(result.endsWith(" now."))
        assertNotEquals("It is {{time}} now.", result)
    }

    @Test
    fun `weekday macro produces non-empty string`() {
        val result = engine.expand("{{weekday}}", context)
        assertTrue(result.isNotEmpty())
        assertNotEquals("{{weekday}}", result)
    }

    @Test
    fun `isodate macro produces ISO date format`() {
        val result = engine.expand("{{isodate}}", context)
        // ISO date format: YYYY-MM-DD
        assertTrue("Expected ISO date format, got: $result", result.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `isotime macro produces ISO time format`() {
        val result = engine.expand("{{isotime}}", context)
        // ISO time format: HH:MM:SS
        assertTrue("Expected ISO time format, got: $result", result.matches(Regex("\\d{2}:\\d{2}:\\d{2}.*")))
    }

    @Test
    fun `random with single bound produces number in range`() {
        val result = engine.expand("{{random:10}}", context)
        val number = result.toInt()
        assertTrue("Expected 0-9, got $number", number in 0..9)
    }

    @Test
    fun `random with range produces number in range`() {
        val result = engine.expand("{{random:5-15}}", context)
        val number = result.toInt()
        assertTrue("Expected 5-15, got $number", number in 5..15)
    }

    @Test
    fun `comment macro is removed entirely`() {
        val result = engine.expand("Hello {{// this is a comment}} world!", context)
        assertEquals("Hello  world!", result)
    }

    @Test
    fun `upper transform produces uppercase text`() {
        val result = engine.expand("{{upper:hello}}", context)
        assertEquals("HELLO", result)
    }

    @Test
    fun `lower transform produces lowercase text`() {
        val result = engine.expand("{{lower:HELLO WORLD}}", context)
        assertEquals("hello world", result)
    }

    @Test
    fun `capitalize transform capitalizes first letter`() {
        val result = engine.expand("{{capitalize:hello world}}", context)
        assertEquals("Hello world", result)
    }

    @Test
    fun `upper transform works with macro`() {
        val result = engine.expand("{{upper:char}}", context)
        // "char" is not a macro expression here, it's the literal text inside upper:
        // Actually, upper:char resolves "char" as a macro expression
        assertEquals("ALICE", result)
    }

    @Test
    fun `recursive expansion resolves nested macros`() {
        // Use a non-reserved variable name so the standard {{greeting}} macro
        // (added in step 5) doesn't shadow the custom variable.
        val customContext = context.copy(
            customVariables = mapOf("myGreeting" to "{{char}} says hello")
        )
        val result = engine.expand("{{myGreeting}}", customContext)
        assertEquals("Alice says hello", result)
    }

    @Test
    fun `unknown macros pass through unchanged`() {
        val result = engine.expand("Hello {{nonexistent_macro}} world!", context)
        assertEquals("Hello {{nonexistent_macro}} world!", result)
    }

    @Test
    fun `multiple macros in one string`() {
        val result = engine.expand("{{char}} and {{user}} meet.", context)
        assertEquals("Alice and Bob meet.", result)
    }

    @Test
    fun `custom macro registration works`() {
        engine.registerCustomMacro("test_macro") { "custom_value" }
        val result = engine.expand("{{test_macro}}", context)
        assertEquals("custom_value", result)
    }

    @Test
    fun `trim macro is stripped`() {
        val result = engine.expand("Hello {{trim}} world", context)
        assertEquals("Hello  world", result)
    }

    @Test
    fun `rollback macro is stripped`() {
        val result = engine.expand("Hello {{rollback}} world", context)
        assertEquals("Hello  world", result)
    }

    @Test
    fun `bias macro passes through unchanged`() {
        val result = engine.expand("{{bias \"positive\"}}", context)
        assertEquals("{{bias \"positive\"}}", result)
    }

    @Test
    fun `datetimeformat iso produces datetime string`() {
        val result = engine.expand("{{datetimeformat iso}}", context)
        assertTrue("Expected ISO datetime, got: $result", result.contains("T"))
    }

    @Test
    fun `no macros returns text unchanged`() {
        val text = "Plain text without any macros."
        val result = engine.expand(text, context)
        assertEquals(text, result)
    }

    @Test
    fun `empty text returns empty`() {
        val result = engine.expand("", context)
        assertEquals("", result)
    }

    @Test
    fun `charDescription macro expands same as description`() {
        val result = engine.expand("{{charDescription}}", context)
        assertEquals("A curious explorer.", result)
    }

    // ── Step 5 gap-fill: SillyTavern macro parity ─────────────────────────

    @Test
    fun `newline macro inserts single newline`() {
        assertEquals("a\nb", engine.expand("a{{newline}}b", context))
    }

    @Test
    fun `newline with count inserts multiple newlines`() {
        assertEquals("a\n\n\nb", engine.expand("a{{newline::3}}b", context))
    }

    @Test
    fun `space macro inserts single space`() {
        assertEquals("a b", engine.expand("a{{space}}b", context))
    }

    @Test
    fun `space with count inserts multiple spaces`() {
        assertEquals("a    b", engine.expand("a{{space::4}}b", context))
    }

    @Test
    fun `noop macro expands to empty`() {
        assertEquals("ab", engine.expand("a{{noop}}b", context))
    }

    @Test
    fun `reverse macro reverses literal text`() {
        assertEquals("olleh", engine.expand("{{reverse::hello}}", context))
    }

    @Test
    fun `reverse macro resolves inner macros first`() {
        assertEquals("ecilA", engine.expand("{{reverse::char}}", context))
    }

    @Test
    fun `random list form picks one of the items`() {
        val items = setOf("a", "b", "c")
        repeat(20) {
            assertTrue(items.contains(engine.expand("{{random::a::b::c}}", context)))
        }
    }

    @Test
    fun `roll with bare die produces value in range`() {
        repeat(20) {
            val v = engine.expand("{{roll::d6}}", context).toInt()
            assertTrue("Expected 1-6, got $v", v in 1..6)
        }
    }

    @Test
    fun `roll with count and modifier produces value in range`() {
        repeat(20) {
            val v = engine.expand("{{roll::2d6+4}}", context).toInt()
            assertTrue("Expected 6-16, got $v", v in 6..16)
        }
    }

    @Test
    fun `roll with bare number rolls one die of that size`() {
        repeat(20) {
            val v = engine.expand("{{roll::20}}", context).toInt()
            assertTrue("Expected 1-20, got $v", v in 1..20)
        }
    }

    @Test
    fun `roll with invalid spec returns zero`() {
        assertEquals("0", engine.expand("{{roll::abc}}", context))
    }

    @Test
    fun `maxResponse macro expands to token count`() {
        assertEquals("1024", engine.expand("{{maxResponse}}", context))
        assertEquals("1024", engine.expand("{{maxResponseTokens}}", context))
    }

    @Test
    fun `model macro expands to model name`() {
        assertEquals("claude-opus-4-8", engine.expand("{{model}}", context))
    }

    @Test
    fun `persona macro expands to persona description`() {
        assertEquals("Bob is a brave traveler.", engine.expand("{{persona}}", context))
    }

    @Test
    fun `input macro expands to input text`() {
        assertEquals("Tell me a story", engine.expand("{{input}}", context))
    }

    @Test
    fun `lastUserMessage and lastCharMessage macros expand`() {
        assertEquals("What's next?", engine.expand("{{lastUserMessage}}", context))
        assertEquals("Bob says goodbye.", engine.expand("{{lastCharMessage}}", context))
    }

    @Test
    fun `lastMessageId macro expands to index`() {
        assertEquals("5", engine.expand("{{lastMessageId}}", context))
    }

    @Test
    fun `isMobile macro is always true on Android`() {
        assertEquals("true", engine.expand("{{isMobile}}", context))
    }

    @Test
    fun `greeting macro expands to first message`() {
        assertEquals("Alice greets you warmly.", engine.expand("{{greeting}}", context))
        assertEquals("Alice greets you warmly.", engine.expand("{{charFirstMessage}}", context))
    }

    @Test
    fun `greeting with index picks alternate greeting`() {
        assertEquals("Alt greeting one.", engine.expand("{{greeting::1}}", context))
        assertEquals("Alt greeting two.", engine.expand("{{greeting::2}}", context))
    }

    @Test
    fun `greeting with out-of-range index returns empty`() {
        assertEquals("", engine.expand("{{greeting::99}}", context))
    }

    // ── Variable shorthand ({{.name}} / {{$name}} and operators) ─────────

    @Test
    fun `shorthand local get returns empty when unset`() {
        engine.variableStore = newStore()
        assertEquals("", engine.expand("{{.mood}}", context))
    }

    @Test
    fun `shorthand local set and get roundtrip`() {
        engine.variableStore = newStore()
        assertEquals("happy", engine.expand("{{.mood=happy}}", context))
        assertEquals("happy", engine.expand("{{.mood}}", context))
    }

    @Test
    fun `shorthand global set and get roundtrip`() {
        engine.variableStore = newStore()
        assertEquals("on", engine.expand("{{${'$'}flag=on}}", context))
        assertEquals("on", engine.expand("{{${'$'}flag}}", context))
    }

    @Test
    fun `shorthand increment and decrement`() {
        engine.variableStore = newStore(mutableMapOf("count" to "5"))
        assertEquals("6", engine.expand("{{.count++}}", context))
        assertEquals("5", engine.expand("{{.count--}}", context))
        assertEquals("5", engine.expand("{{.count}}", context))
    }

    @Test
    fun `shorthand add and subtract`() {
        engine.variableStore = newStore(mutableMapOf("score" to "10"))
        assertEquals("15", engine.expand("{{.score+=5}}", context))
        assertEquals("13", engine.expand("{{.score-=2}}", context))
    }

    @Test
    fun `shorthand equality comparison`() {
        engine.variableStore = newStore(mutableMapOf("name" to "Alice"))
        assertEquals("true", engine.expand("{{.name==Alice}}", context))
        assertEquals("false", engine.expand("{{.name==Bob}}", context))
        assertEquals("false", engine.expand("{{.name!=Alice}}", context))
        assertEquals("true", engine.expand("{{.name!=Bob}}", context))
    }

    @Test
    fun `shorthand ordered comparison`() {
        engine.variableStore = newStore(mutableMapOf("hp" to "50"))
        assertEquals("true", engine.expand("{{.hp>=50}}", context))
        assertEquals("false", engine.expand("{{.hp>50}}", context))
        assertEquals("true", engine.expand("{{.hp<100}}", context))
        assertEquals("false", engine.expand("{{.hp<=40}}", context))
    }

    @Test
    fun `shorthand logical-or default returns value when falsy`() {
        engine.variableStore = newStore()
        assertEquals("default", engine.expand("{{.missing||default}}", context))
    }

    @Test
    fun `shorthand logical-or returns current when truthy`() {
        engine.variableStore = newStore(mutableMapOf("name" to "Alice"))
        assertEquals("Alice", engine.expand("{{.name||default}}", context))
    }

    @Test
    fun `shorthand nullish-coalescing returns alt when undefined`() {
        engine.variableStore = newStore(mutableMapOf("name" to "Alice"))
        assertEquals("Alice", engine.expand("{{.name??alt}}", context))
        assertEquals("alt", engine.expand("{{.missing??alt}}", context))
    }

    @Test
    fun `shorthand conditional assignment or-equals`() {
        engine.variableStore = newStore(mutableMapOf("seen" to "0"))
        assertEquals("yes", engine.expand("{{.seen||=yes}}", context))
        assertEquals("yes", engine.expand("{{.seen}}", context))
    }

    @Test
    fun `shorthand conditional assignment nullish-equals`() {
        engine.variableStore = newStore()
        assertEquals("init", engine.expand("{{.cfg??=init}}", context))
        assertEquals("init", engine.expand("{{.cfg}}", context))
    }

    @Test
    fun `shorthand without store degrades to empty string`() {
        // No variableStore set; getters resolve to "" without crashing.
        assertEquals("", engine.expand("{{.anything}}", context))
        assertEquals("", engine.expand("{{${'$'}anything}}", context))
    }

    @Test
    fun `shorthand does not interfere with non-shorthand macros`() {
        // A literal starting with '.' that isn't a valid identifier is left alone.
        assertEquals("{{.}}", engine.expand("{{.}}", context))
    }
}
