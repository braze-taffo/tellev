package app.tellev.core.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SlashCommandEngineTest {

    private lateinit var variables: ConcurrentHashMap<String, String>
    private lateinit var engine: SlashCommandEngine

    @Before
    fun setUp() {
        variables = ConcurrentHashMap()
        engine = SlashCommandEngine(variables)
    }

    @Test
    fun `echo returns its arguments`() {
        val result = engine.execute("/echo hello world")
        assertTrue(result.handled)
        assertEquals("hello world", result.output)
    }

    @Test
    fun `echo with quoted args preserves spaces`() {
        val result = engine.execute("/echo \"hello world\" foo")
        assertTrue(result.handled)
        assertEquals("hello world foo", result.output)
    }

    @Test
    fun `setvar and getvar round trip`() {
        engine.execute("/setvar myvar value123")
        val result = engine.execute("/getvar myvar")
        assertEquals("value123", result.output)
    }

    @Test
    fun `setvar with named args`() {
        engine.execute("/setvar key=foo value=bar")
        val result = engine.execute("/getvar foo")
        assertEquals("bar", result.output)
    }

    @Test
    fun `incvar increments numeric value`() {
        engine.execute("/setvar count 5")
        val result = engine.execute("/incvar count")
        assertEquals("6", result.output)
    }

    @Test
    fun `decvar decrements numeric value`() {
        engine.execute("/setvar count 10")
        val result = engine.execute("/decvar count")
        assertEquals("9", result.output)
    }

    @Test
    fun `addvar adds to numeric variable`() {
        engine.execute("/setvar total 100")
        val result = engine.execute("/addvar total 50")
        assertEquals("150", result.output)
    }

    @Test
    fun `addvar concatenates for non-numeric`() {
        engine.execute("/setvar greeting hello")
        val result = engine.execute("/addvar greeting world")
        assertEquals("helloworld", result.output)
    }

    @Test
    fun `flushvar removes variable`() {
        engine.execute("/setvar temp data")
        engine.execute("/flushvar temp")
        val result = engine.execute("/getvar temp")
        assertEquals("", result.output)
    }

    @Test
    fun `listvar lists all variable names sorted`() {
        engine.execute("/setvar zebra 1")
        engine.execute("/setvar alpha 2")
        engine.execute("/setvar mango 3")
        val result = engine.execute("/listvar")
        assertEquals("alpha\nmango\nzebra", result.output)
    }

    @Test
    fun `hasvar returns true for existing variable`() {
        engine.execute("/setvar exists yes")
        val result = engine.execute("/hasvar exists")
        assertEquals("true", result.output)
    }

    @Test
    fun `hasvar returns false for missing variable`() {
        val result = engine.execute("/hasvar nonexistent")
        assertEquals("false", result.output)
    }

    @Test
    fun `let sets a variable`() {
        engine.execute("/let name John")
        val result = engine.execute("/getvar name")
        assertEquals("John", result.output)
    }

    @Test
    fun `pipe passes output to next command`() {
        val result = engine.execute("/echo hello | /echo world")
        assertEquals("world hello", result.output)
    }

    @Test
    fun `pipe appends output as last positional arg`() {
        engine.execute("/setvar name Alice")
        val result = engine.execute("/getvar name | /echo greeting:")
        assertEquals("greeting: Alice", result.output)
    }

    @Test
    fun `upper converts to uppercase`() {
        val result = engine.execute("/upper hello")
        assertEquals("HELLO", result.output)
    }

    @Test
    fun `lower converts to lowercase`() {
        val result = engine.execute("/lower HELLO")
        assertEquals("hello", result.output)
    }

    @Test
    fun `len returns string length`() {
        val result = engine.execute("/len hello")
        assertEquals("5", result.output)
    }

    @Test
    fun `add performs arithmetic`() {
        val result = engine.execute("/add 3 4")
        assertEquals("7.0", result.output)
    }

    @Test
    fun `sub performs arithmetic`() {
        val result = engine.execute("/sub 10 3")
        assertEquals("7.0", result.output)
    }

    @Test
    fun `mul performs arithmetic`() {
        val result = engine.execute("/mul 6 7")
        assertEquals("42.0", result.output)
    }

    @Test
    fun `div performs arithmetic`() {
        val result = engine.execute("/div 20 4")
        assertEquals("5.0", result.output)
    }

    @Test
    fun `div by zero returns error`() {
        val result = engine.execute("/div 10 0")
        assertTrue(result.isError)
    }

    @Test
    fun `if evaluates equality`() {
        val result = engine.execute("/if abc==abc")
        assertEquals("true", result.output)
    }

    @Test
    fun `if evaluates inequality`() {
        val result = engine.execute("/if abc!=def")
        assertEquals("true", result.output)
    }

    @Test
    fun `if evaluates numeric comparison`() {
        val result = engine.execute("/if 10>=5")
        assertEquals("true", result.output)
    }

    @Test
    fun `if with variable reference`() {
        engine.execute("/setvar status active")
        val result = engine.execute("/if {{status}}==active")
        assertEquals("true", result.output)
    }

    @Test
    fun `if returns false for non-matching condition`() {
        val result = engine.execute("/if hello==world")
        assertEquals("false", result.output)
    }

    @Test
    fun `unknown command returns not handled`() {
        val result = engine.execute("/nonexistent command")
        assertFalse(result.handled)
    }

    @Test
    fun `noop returns empty string`() {
        val result = engine.execute("/noop")
        assertTrue(result.handled)
        assertEquals("", result.output)
    }

    @Test
    fun `pass returns arguments`() {
        val result = engine.execute("/pass data here")
        assertEquals("data here", result.output)
    }

    @Test
    fun `abort returns aborted result`() {
        val result = engine.execute("/abort")
        assertTrue(result.isAborted)
    }

    @Test
    fun `concat joins arguments`() {
        val result = engine.execute("/concat hello world foo")
        assertEquals("helloworldfoo", result.output)
    }

    @Test
    fun `replace substitutes text`() {
        val result = engine.execute("/replace abc xyz abcdef")
        assertEquals("xyzdef", result.output)
    }

    @Test
    fun `max returns largest value`() {
        val result = engine.execute("/max 3 7 2 9 1")
        assertEquals("9.0", result.output)
    }

    @Test
    fun `min returns smallest value`() {
        val result = engine.execute("/min 3 7 2 9 1")
        assertEquals("1.0", result.output)
    }

    @Test
    fun `sort sorts arguments`() {
        val result = engine.execute("/sort banana apple cherry")
        assertEquals("apple\nbanana\ncherry", result.output)
    }

    @Test
    fun `round rounds to nearest integer`() {
        val result = engine.execute("/round 3.7")
        assertEquals("4", result.output)
    }

    @Test
    fun `abs returns absolute value`() {
        val result = engine.execute("/abs -5.5")
        assertEquals("5.5", result.output)
    }

    @Test
    fun `tokens counts whitespace-separated tokens`() {
        val result = engine.execute("/tokens hello world foo bar")
        assertEquals("4", result.output)
    }

    @Test
    fun `match tests regex against input`() {
        val result = engine.execute("/match ^hello hello world")
        assertEquals("true", result.output)
    }

    @Test
    fun `fuzzy tests case-insensitive contains`() {
        val result = engine.execute("/fuzzy Hello hello world")
        assertEquals("true", result.output)
    }

    @Test
    fun `comments are skipped`() {
        val result = engine.execute("// this is a comment\n/echo result")
        assertEquals("result", result.output)
    }

    @Test
    fun `multi-line script executes sequentially`() {
        val result = engine.execute("/setvar x 1\n/incvar x\n/getvar x")
        assertEquals("2", result.output)
    }

    @Test
    fun `help lists built-in commands`() {
        val result = engine.execute("/help")
        assertTrue(result.output.contains("/echo"))
        assertTrue(result.output.contains("/setvar"))
        assertTrue(result.output.contains("/getvar"))
    }

    @Test
    fun `BUILTIN_COMMANDS contains expected entries`() {
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("echo"))
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("setvar"))
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("getvar"))
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("gen"))
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("send"))
        assertTrue(SlashCommandEngine.BUILTIN_COMMANDS.contains("abort"))
    }
}
