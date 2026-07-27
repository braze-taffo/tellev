package app.tellev.core.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlashCommandEngineTest {

    private lateinit var variableStore: VariableStore
    private lateinit var local: MutableMap<String, String>
    private lateinit var engine: SlashCommandEngine

    @Before
    fun setUp() {
        local = java.util.concurrent.ConcurrentHashMap()
        variableStore = VariableStoreTest.storeWith(local)
        engine = SlashCommandEngine(variableStore = variableStore)
    }

    // ── Argument-shape regressions (audit 2026-07-27) ───────────────────

    @Test
    fun `named argument keeps a quoted value`() {
        // Fixing `/echo "a=b"` had split `key="value"` into an empty named arg
        // plus a stray positional one, silently breaking every quoted
        // named argument (`/replace pattern="x"`, `/if right="some text"`, ...).
        engine.execute("/setvar key=\"my var\" value=\"hello world\"")
        assertEquals("hello world", engine.execute("/getvar \"my var\"").output)
    }

    @Test
    fun `quoted positional argument is still positional`() {
        assertEquals("a=b", engine.execute("/echo \"a=b\"").output)
    }

    @Test
    fun `setvar accepts the documented key form with an unnamed value`() {
        // ST's shape: /setvar key=x <值>. tellev read the value off index 1 of
        // the positional list and stored an empty string instead.
        engine.execute("/setvar key=mood 高兴")
        assertEquals("高兴", engine.execute("/getvar mood").output)
    }

    @Test
    fun `addvar accepts the documented key form`() {
        engine.execute("/setvar key=n 5")
        engine.execute("/addvar key=n 3")
        assertEquals("8", engine.execute("/getvar n").output)
    }

    @Test
    fun `macros in arguments are expanded before execution`() {
        val withMacros = SlashCommandEngine(
            variableStore = variableStore,
            macroExpander = { it.replace("{{char}}", "Alice") },
        )
        withMacros.execute("/setvar key=who {{char}}")
        assertEquals("Alice", withMacros.execute("/getvar who").output)
        assertEquals("Alice", withMacros.execute("/echo {{char}}").output)
    }

    @Test
    fun `unimplemented commands report themselves once`() {
        val seen = mutableListOf<String>()
        val reporting = SlashCommandEngine(
            variableStore = variableStore,
            onUnimplementedCommand = { seen.add(it) },
        )
        val first = reporting.execute("/gen write something")
        assertTrue(first.handled)
        assertFalse(first.isError)
        reporting.execute("/gen write something else")
        assertEquals(listOf("gen"), seen)
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
    fun `pipe is dropped when the next command has its own argument`() {
        // ST injects the pipe only into a command with no unnamed argument
        // (SlashCommandClosure.js:555-562). Appending unconditionally shifted
        // every index-based argument in a chain.
        val result = engine.execute("/echo hello | /echo world")
        assertEquals("world", result.output)
    }

    @Test
    fun `pipe fills a command that has no argument of its own`() {
        engine.execute("/setvar name Alice")
        val result = engine.execute("/getvar name | /echo")
        assertEquals("Alice", result.output)
    }

    @Test
    fun `pipe placeholder works in positional and named arguments`() {
        engine.execute("/setvar name Alice")
        assertEquals("greeting: Alice", engine.execute("/getvar name | /echo \"greeting: {{pipe}}\"").output)
        engine.execute("/getvar name | /setvar key=copy value={{pipe}}")
        assertEquals("Alice", engine.execute("/getvar copy").output)
    }

    @Test
    fun `a pipe chain survives being split across lines`() {
        engine.execute("/setvar name Alice")
        val trailing = engine.execute("/getvar name |\n/echo \"hi {{pipe}}\"")
        assertEquals("hi Alice", trailing.output)
        val leading = engine.execute("/getvar name\n| /echo \"hi {{pipe}}\"")
        assertEquals("hi Alice", leading.output)
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
    fun `known but unimplemented commands return no-op success instead of aborting`() {
        // Commands that can't be fully implemented on mobile (UI, clipboard,
        // file system, etc.) return Result.ok so scripts with incidental
        // unsupported commands continue running (audit S2 fix). They must NOT
        // return hard errors that abort the entire script.
        listOf("gen", "continue", "inject", "char-delete", "newchat").forEach { name ->
            val result = engine.execute("/$name test")

            assertTrue("/$name must be recognized", result.handled)
            assertFalse("/$name must not abort the script with an error", result.isError)
        }
    }

    @Test
    fun `implemented commands remain successful`() {
        val result = engine.execute("/echo still works")
        assertTrue(result.handled)
        assertFalse(result.isError)
        assertEquals("still works", result.output)
    }

    @Test
    fun `is-mobile reports the Android runtime truthfully`() {
        val result = engine.execute("/is-mobile")

        assertTrue(result.handled)
        assertFalse(result.isError)
        assertEquals("true", result.output)
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

    // ── truthy semantics (guard for the resolveValue change) ──
    // resolveValue now strips surrounding quotes before the truthy test, so
    // STScript-style unquoted evaluation holds: the literal "0" / "false" are
    // falsy, any other non-empty value is true.

    @Test
    fun `if truthy treats zero as falsy`() {
        val result = engine.execute("/if 0")
        assertEquals("false", result.output)
    }

    @Test
    fun `if truthy treats false as falsy`() {
        val result = engine.execute("/if false")
        assertEquals("false", result.output)
    }

    @Test
    fun `if truthy treats a non-empty string as true`() {
        val result = engine.execute("/if hello")
        assertEquals("true", result.output)
    }

    @Test
    fun `if equality compares a variable to a bare literal`() {
        engine.execute("/setvar status active")
        val result = engine.execute("/if {{status}}==active")
        assertEquals("true", result.output)
    }

    // ── per-scope isolation (local vs global) ─────────────────────────────

    @Test
    fun `setvar writes local and does not leak to global`() {
        engine.execute("/setvar k local-value")
        assertEquals("local-value", engine.execute("/getvar k").output)
        assertEquals("", engine.execute("/getglobalvar k").output)
    }

    @Test
    fun `setglobalvar writes global and does not leak to local`() {
        engine.execute("/setglobalvar k global-value")
        assertEquals("global-value", engine.execute("/getglobalvar k").output)
        assertEquals("", engine.execute("/getvar k").output)
    }

    @Test
    fun `incvar and incglobalvar are independent`() {
        engine.execute("/setvar n 1")
        engine.execute("/setglobalvar n 100")
        assertEquals("2", engine.execute("/incvar n").output)
        assertEquals("100", engine.execute("/getglobalvar n").output)
        assertEquals("2", engine.execute("/getvar n").output)
    }

    @Test
    fun `listvar scope filter`() {
        engine.execute("/setvar onlyLocal a")
        engine.execute("/setglobalvar onlyGlobal b")
        assertEquals("onlyLocal", engine.execute("/listvar scope=local").output)
        assertEquals("onlyGlobal", engine.execute("/listvar scope=global").output)
        assertEquals("onlyGlobal\nonlyLocal", engine.execute("/listvar scope=all").output)
    }

    @Test
    fun `hasglobalvar distinguishes scopes`() {
        engine.execute("/setglobalvar g present")
        assertEquals("true", engine.execute("/hasglobalvar g").output)
        assertEquals("false", engine.execute("/hasvar g").output)
    }

    // ── closures ({: ... :}) and structured control flow ─────────────────

    @Test
    fun `closure executes when if condition is true`() {
        val result = engine.execute("/if left=1 right=1 rule=eq {: /echo yes :}")
        assertEquals("yes", result.output)
    }

    @Test
    fun `closure does not execute when if condition is false`() {
        val result = engine.execute("/if left=1 right=2 rule=eq {: /echo yes :}")
        assertEquals("", result.output)
    }

    @Test
    fun `if else executes else closure when condition is false`() {
        val result = engine.execute("/if left=1 right=2 rule=eq {: /echo yes :} else={: /echo no :}")
        assertEquals("no", result.output)
    }

    @Test
    fun `if supports ST comparison rules`() {
        assertEquals("yes", engine.execute("/if left=10 right=5 rule=gte {: /echo yes :}").output)
        assertEquals("yes", engine.execute("/if left=abcdef right=bcd rule=in {: /echo yes :}").output)
        assertEquals("", engine.execute("/if left=abc right=def rule=in {: /echo yes :}").output)
        assertEquals("yes", engine.execute("/if left=0 rule=not {: /echo yes :}").output)
    }

    @Test
    fun `if resolves variables in operands`() {
        engine.execute("/setvar status active")
        assertEquals("yes", engine.execute("/if left=status right=active rule=eq {: /echo yes :}").output)
    }

    @Test
    fun `if executes unnamed text body when condition is true`() {
        val result = engine.execute("/if left=1 right=1 \"/echo textbody\"")
        assertEquals("textbody", result.output)
    }

    @Test
    fun `while loop with closure body iterates until condition fails`() {
        engine.execute("/setvar i 0")
        engine.execute("/while left=i right=3 rule=lt {: /incvar i :}")
        assertEquals("3", engine.execute("/getvar i").output)
    }

    @Test
    fun `while guard caps iterations`() {
        engine.execute("/setvar i 0")
        engine.execute("/while left=1 rule= guard=5 {: /incvar i :}")
        assertEquals("5", engine.execute("/getvar i").output)
    }

    @Test
    fun `break exits the enclosing while loop`() {
        engine.execute("/setvar i 0")
        engine.execute("/while left=1 guard=100 {: /incvar i | /if left=i right=3 rule=eq {: /break :} :}")
        assertEquals("3", engine.execute("/getvar i").output)
    }

    @Test
    fun `run executes closure`() {
        assertEquals("hello", engine.execute("/run {: /echo hello :}").output)
    }

    @Test
    fun `run executes command string`() {
        assertEquals("hello", engine.execute("/run \"/echo hello\"").output)
    }

    @Test
    fun `closure spanning multiple lines executes sequentially`() {
        val result = engine.execute(
            "/if left=1 right=1 rule=eq {:\n/echo line1\n/echo line2\n:}",
        )
        assertEquals("line2", result.output)
    }

    @Test
    fun `closure with pipe chain inside`() {
        assertEquals("A", engine.execute("/if left=1 right=1 {: /echo a | /upper :}").output)
    }

    @Test
    fun `nested closures work`() {
        engine.execute("/setvar outer 1")
        val result = engine.execute(
            "/if left=1 right=1 {: /if left=outer right=1 rule=eq {: /echo deep :} :}",
        )
        assertEquals("deep", result.output)
    }

    @Test
    fun `quoted equals stays positional instead of becoming a named arg`() {
        // Regression: /echo "a=b" must output a=b, not "".
        assertEquals("a=b", engine.execute("/echo \"a=b\"").output)
    }

    @Test
    fun `pipe placeholder is replaced at any argument position`() {
        assertEquals("hello world!", engine.execute("/echo world | /echo hello {{pipe}}!").output)
    }

    @Test
    fun `inline comment is stripped`() {
        assertEquals("hi", engine.execute("/echo hi // comment").output)
    }
}
