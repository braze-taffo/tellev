package app.tellev.core.prompt

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
    )

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
        val customContext = context.copy(
            customVariables = mapOf("greeting" to "{{char}} says hello")
        )
        val result = engine.expand("{{greeting}}", customContext)
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
}
