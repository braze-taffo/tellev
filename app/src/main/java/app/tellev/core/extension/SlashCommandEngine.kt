package app.tellev.core.extension

/**
 * Minimal STScript parser and executor for built-in slash commands.
 *
 * Supports a practical subset of SillyTavern's STScript syntax:
 * - `/command arg1 arg2 key=value "quoted arg"`
 * - Pipe: `|` passes the previous command's output as the next command's
 *   input (appended to its arguments).
 * - `|>` injects pipe input at the `{{pipe}}` placeholder in the next
 *   command's argument list.
 * - Line comments: `// ...`
 * - Named arguments: `key=value` (parsed into [Command.namedArgs]).
 *
 * Implemented built-in commands cover the most commonly used entries from
 * SillyTavern's 267-command built-in set.  Unknown commands return an
 * error result instead of silently succeeding.
 *
 * Variables are split into LOCAL (chat_metadata) and GLOBAL scopes by
 * [VariableStore], mirroring SillyTavern's model: unqualified commands
 * (`/setvar`, `/getvar`, ...) touch the local scope while `*globalvar`
 * variants touch the global scope.
 */
class SlashCommandEngine(
    private val variableStore: VariableStore? = null,
    private val extensionCommands: Map<String, RegisteredCommandRef> = emptyMap(),
    private val maxLoopIterations: Int = 1000,
    /**
     * Optional callback invoked by the `/event-emit` command so that
     * custom events actually reach the extension event bus.  Receives
     * the event name and the positional argument list.
     */
    private val eventEmitter: ((String, List<String>) -> Unit)? = null,
    /**
     * Expands `{{...}}` macros in command arguments. SillyTavern runs
     * `substituteParams` over every named and unnamed argument right before a
     * command executes (SlashCommandClosure.js:544,582); without it
     * `/setvar key=n {{char}}` stores the literal `{{char}}` and
     * `/times {{getvar::n}}` loops zero times, both silently.
     */
    private val macroExpander: ((String) -> String)? = null,
    /**
     * Called the first time a command that exists only as a no-op is executed.
     * Dropping the old hard-error gate stopped scripts from aborting on an
     * incidental unsupported command, but it also made those commands
     * indistinguishable from success — `/gen | /setvar key=reply` quietly
     * stored an empty string. Reporting them keeps the script running *and*
     * leaves a trace.
     */
    private val onUnimplementedCommand: ((String) -> Unit)? = null,
) {

    private val reportedStubs = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** A command that parses and "succeeds" but has no effect in tellev. */
    private fun stubOk(name: String, output: String = ""): Result {
        if (reportedStubs.add(name)) onUnimplementedCommand?.invoke(name)
        return Result.ok(output)
    }

    data class RegisteredCommandRef(
        val extensionId: String,
    )

    data class Command(
        val name: String,
        val args: List<Arg>,
        val namedArgs: Map<String, String>,
    ) {
        /** A command argument: plain text or a parsed `{: ... :}` closure. */
        sealed interface Arg {
            val text: String

            data class Text(val value: String, val quoted: Boolean = false) : Arg {
                override val text: String get() = value
            }

            data class Closure(val lines: List<List<Command>>) : Arg {
                /** Closures render empty when coerced to text (ST never stringifies them). */
                override val text: String get() = ""
            }
        }

        /** Positional args coerced to text (closures become ""). */
        fun textArgs(): List<String> = args.map { it.text }

        /** Positional closure args in order of appearance. */
        fun closureArgs(): List<Arg.Closure> = args.filterIsInstance<Arg.Closure>()
    }

    data class Result(
        val handled: Boolean,
        val output: String = "",
        val isError: Boolean = false,
        val isAborted: Boolean = false,
        val isBreak: Boolean = false,
        val errorMessage: String = "",
    ) {
        companion object {
            fun ok(output: String = "") = Result(handled = true, output = output)
            fun error(message: String) = Result(handled = true, isError = true, errorMessage = message, output = "")
            fun unknown(name: String) = Result(handled = false, output = "Unknown command: $name")
            fun unsupported(name: String) = Result(
                handled = true,
                isError = true,
                errorMessage = "Unsupported command in Tellev: /$name",
                output = "",
            )
            fun abort() = Result(handled = true, isAborted = true)

            /** `/break` — unwinds to the nearest enclosing `/while` loop. */
            fun breakResult() = Result(handled = true, isBreak = true)
        }
    }

    /**
     * Parse and execute a full STScript text. Multiple lines are executed
     * sequentially; each line may contain a pipe chain. `{: ... :}` closures
     * (possibly spanning lines) are parsed as first-class arguments.
     */
    fun execute(scriptText: String): Result {
        val tokens = tokenizeScript(scriptText)
        val lines = ScriptParser(tokens).parseStatements(stopOnClosureEnd = false)
        // Nothing parsed as a command (empty text, or plain prose): report it
        // as unhandled so callers can fall back instead of reading an empty
        // success. Matters now that message-embedded triggerSlash falls
        // through to this engine.
        if (lines.isEmpty()) return Result(handled = false)
        val result = executeLines(lines)
        // A /break that escapes every loop aborts the script (ST behavior).
        return if (result.isBreak) Result.abort() else result
    }

    private fun executeLines(lines: List<List<Command>>): Result {
        var lastResult = Result.ok()
        for (line in lines) {
            lastResult = executeLine(line)
            if (lastResult.isAborted || lastResult.isError || lastResult.isBreak) return lastResult
        }
        return lastResult
    }

    private fun executeLine(line: List<Command>): Result {
        var pipeInput = ""
        var result = Result.ok()
        for ((index, cmd) in line.withIndex()) {
            val piped = if (index > 0) applyPipe(cmd, pipeInput) else cmd
            result = executeCommand(expandMacros(piped))
            if (result.isAborted || result.isError || result.isBreak) return result
            pipeInput = result.output
        }
        return result
    }

    /**
     * Pipe semantics, following SlashCommandClosure.substituteUnnamedArgument
     * (:555-562): `{{pipe}}` is replaced wherever it appears in any argument,
     * and otherwise the previous output is injected **only when the command
     * carries no unnamed argument of its own**. Appending unconditionally —
     * what tellev used to do — changed the argument list of every command in a
     * chain, so `/echo hello | /echo world` printed `world hello` instead of
     * `world` and any command reading args by index was shifted.
     */
    private fun applyPipe(cmd: Command, pipeInput: String): Command {
        val args = cmd.args.toMutableList()
        var replaced = false
        for (i in args.indices) {
            val arg = args[i]
            if (arg is Command.Arg.Text && arg.value.contains("{{pipe}}")) {
                args[i] = Command.Arg.Text(arg.value.replace("{{pipe}}", pipeInput), arg.quoted)
                replaced = true
            }
        }
        val named = cmd.namedArgs.mapValues { (_, v) ->
            if (v.contains("{{pipe}}")) {
                replaced = true
                v.replace("{{pipe}}", pipeInput)
            } else {
                v
            }
        }
        if (!replaced && args.isEmpty() && pipeInput.isNotEmpty()) {
            args.add(Command.Arg.Text(pipeInput))
        }
        return cmd.copy(args = args, namedArgs = named)
    }

    /**
     * Expand macros in every argument, matching ST's per-argument
     * `substituteParams` pass. Closure arguments are left alone — their bodies
     * are expanded when the closure's own commands run.
     */
    private fun expandMacros(cmd: Command): Command {
        val expand = macroExpander ?: return cmd
        if (cmd.args.none { it is Command.Arg.Text && it.value.contains("{{") } &&
            cmd.namedArgs.none { it.value.contains("{{") }
        ) {
            return cmd
        }
        return cmd.copy(
            args = cmd.args.map { arg ->
                if (arg is Command.Arg.Text && arg.value.contains("{{")) {
                    Command.Arg.Text(expand(arg.value), arg.quoted)
                } else {
                    arg
                }
            },
            namedArgs = cmd.namedArgs.mapValues { (_, v) ->
                if (v.contains("{{")) expand(v) else v
            },
        )
    }

    /**
     * ST's variable commands take the name from `key=` and the value from the
     * unnamed argument (variables.js:933-963), i.e. `/setvar key=x 值`. tellev
     * historically only understood `/setvar x 值` and `value=`, so the
     * documented form read the value off index 1 of a one-element list and
     * silently stored an empty string. Both shapes are accepted now.
     */
    private fun Command.varName(): String =
        namedArgs["key"] ?: namedArgs["name"] ?: textArgs().firstOrNull() ?: ""

    private fun Command.varValue(): String {
        namedArgs["value"]?.let { return it }
        val positional = textArgs()
        val nameWasNamed = namedArgs.containsKey("key") || namedArgs.containsKey("name")
        return if (nameWasNamed) {
            positional.joinToString(" ")
        } else {
            positional.drop(1).joinToString(" ")
        }
    }

    // ── Parser ──────────────────────────────────────────────────────────

    private sealed interface Token {
        /**
         * [unquotedPrefixLen] is how many leading characters of [text] came
         * from outside quotes. Named-argument detection only searches for `=`
         * inside that prefix, so `key="a=b"` binds `key` while `"a=b"` stays a
         * positional argument.
         */
        data class Word(
            val text: String,
            val quoted: Boolean,
            val unquotedPrefixLen: Int = text.length,
        ) : Token
        data object ClosureStart : Token
        data object ClosureEnd : Token
        data object Pipe : Token
        data object PipeBreak : Token
        data object Newline : Token
    }

    /**
     * Tokenize the whole script into a stream. Quotes are stripped (and the
     * token marked quoted so named-arg detection can ignore quoted `=`), `{:`
     * and `:}` become closure delimiters, `|`/`||` pipe separators.
     */
    private fun tokenizeScript(script: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var inQuote: Char? = null
        var unquotedLen = 0
        var sawQuote = false
        var i = 0

        fun flushWord() {
            if (current.isNotEmpty() || sawQuote) {
                tokens.add(
                    Token.Word(
                        text = current.toString(),
                        quoted = sawQuote && unquotedLen == 0,
                        unquotedPrefixLen = unquotedLen,
                    ),
                )
                current.clear()
                unquotedLen = 0
                sawQuote = false
            }
        }

        while (i < script.length) {
            val c = script[i]
            when {
                inQuote != null -> {
                    if (c == inQuote) {
                        // A closing quote ends the quoted run, not the word:
                        // `key="value"` has to stay one token or the `key=`
                        // prefix is emitted as a named arg with an empty value
                        // and the value becomes a stray positional argument.
                        inQuote = null
                    } else {
                        current.append(c)
                    }
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                    sawQuote = true
                }
                c == '{' && i + 1 < script.length && script[i + 1] == ':' -> {
                    flushWord()
                    tokens.add(Token.ClosureStart)
                    i++
                }
                c == ':' && i + 1 < script.length && script[i + 1] == '}' -> {
                    flushWord()
                    tokens.add(Token.ClosureEnd)
                    i++
                }
                c == '|' -> {
                    flushWord()
                    if (i + 1 < script.length && script[i + 1] == '|') {
                        tokens.add(Token.PipeBreak)
                        i++
                    } else {
                        tokens.add(Token.Pipe)
                    }
                }
                c == '\n' -> {
                    flushWord()
                    tokens.add(Token.Newline)
                }
                c.isWhitespace() -> flushWord()
                else -> {
                    // Inline comments: `//` (and `/#`) to end of line, but only
                    // outside quotes and when starting a token.
                    if (c == '/' && current.isEmpty() && i + 1 < script.length &&
                        (script[i + 1] == '/' || script[i + 1] == '#')
                    ) {
                        while (i < script.length && script[i] != '\n' && script[i] != '|') i++
                        continue
                    }
                    current.append(c)
                    unquotedLen++
                }
            }
            i++
        }
        flushWord()
        return tokens
    }

    private inner class ScriptParser(private val tokens: List<Token>) {
        private var pos = 0

        /**
         * Parse statements (lines of pipe-chained commands). With
         * [stopOnClosureEnd] the parser returns at the matching `:}` (which it
         * consumes), producing the closure body.
         */
        fun parseStatements(stopOnClosureEnd: Boolean): List<List<Command>> {
            val lines = mutableListOf<List<Command>>()
            var currentLine = mutableListOf<Command>()
            var expectCommand = true

            while (pos < tokens.size) {
                when (val token = tokens[pos]) {
                    is Token.Newline -> {
                        pos++
                        // A newline only ends the statement when the chain is
                        // actually over. Real scripts are written one command
                        // per line with the `|` either trailing the previous
                        // line or leading the next one; treating every newline
                        // as a break severed those chains, so `{{pipe}}` was
                        // emitted literally and upstream output was dropped.
                        var lookahead = pos
                        while (lookahead < tokens.size && tokens[lookahead] is Token.Newline) lookahead++
                        val continuesChain = lookahead < tokens.size &&
                            (tokens[lookahead] is Token.Pipe || tokens[lookahead] is Token.PipeBreak)
                        if (!continuesChain && currentLine.isNotEmpty()) {
                            lines.add(currentLine)
                            currentLine = mutableListOf()
                            expectCommand = true
                        }
                    }
                    is Token.Pipe, is Token.PipeBreak -> {
                        pos++
                        // Skip newlines between `|` and the next command.
                        while (pos < tokens.size && tokens[pos] is Token.Newline) pos++
                        expectCommand = true
                    }
                    is Token.ClosureEnd -> {
                        pos++
                        if (stopOnClosureEnd) {
                            if (currentLine.isNotEmpty()) lines.add(currentLine)
                            return lines
                        }
                        // Stray `:}` — ignore.
                    }
                    is Token.Word -> {
                        if (expectCommand && token.text.startsWith("/")) {
                            pos++
                            currentLine.add(parseCommand(token.text.removePrefix("/")))
                            expectCommand = false
                        } else {
                            // Stray word outside a command — skip (ST would error).
                            pos++
                        }
                    }
                    is Token.ClosureStart -> {
                        // Stray closure outside a command — parse and discard.
                        pos++
                        parseStatements(stopOnClosureEnd = true)
                    }
                }
            }
            if (currentLine.isNotEmpty()) lines.add(currentLine)
            return lines
        }

        private fun parseCommand(name: String): Command {
            val args = mutableListOf<Command.Arg>()
            val named = mutableMapOf<String, String>()

            while (pos < tokens.size) {
                when (val token = tokens[pos]) {
                    is Token.Word -> {
                        pos++
                        // Only the unquoted prefix can carry the `key=` separator.
                        val prefixLen = token.unquotedPrefixLen.coerceIn(0, token.text.length)
                        val eqIdx = token.text.take(prefixLen).indexOf('=')
                        val isComparison = eqIdx > 0 && (
                            token.text[eqIdx - 1] == '!' || token.text[eqIdx - 1] == '>' ||
                                token.text[eqIdx - 1] == '<' ||
                                (eqIdx + 1 < token.text.length && token.text[eqIdx + 1] == '=')
                            )
                        if (eqIdx > 0 && !token.quoted && !isComparison) {
                            named[token.text.substring(0, eqIdx)] = token.text.substring(eqIdx + 1)
                        } else {
                            args.add(Command.Arg.Text(token.text, token.quoted))
                        }
                    }
                    is Token.ClosureStart -> {
                        pos++
                        val body = parseStatements(stopOnClosureEnd = true)
                        args.add(Command.Arg.Closure(body))
                    }
                    else -> return Command(name, args, named)
                }
            }
            return Command(name, args, named)
        }
    }

    // ── Built-in command executors ──────────────────────────────────────

    private fun executeCommand(cmd: Command): Result {
        // Commands that can't be fully implemented on mobile (UI, clipboard,
        // file system, etc.) are handled as no-ops (Result.ok("")) in the
        // when-block below.  Previously they were in an UNSUPPORTED_COMMANDS
        // set that returned a hard error, which aborted the entire script
        // (audit S2).  Removing the gate lets scripts with incidental
        // unsupported commands continue running.

        return when (cmd.name) {
            "echo" -> Result.ok(cmd.textArgs().joinToString(" "))

            "noop", "pass", "return" -> Result.ok(cmd.textArgs().joinToString(" "))

            "delay", "wait", "sleep" -> {
                val ms = cmd.textArgs().firstOrNull()?.toLongOrNull() ?: 1000L
                Thread.sleep(ms.coerceAtMost(30_000L))
                Result.ok("")
            }

            "setvar", "setchatvar" -> {
                val name = cmd.varName()
                val value = cmd.varValue()
                if (name.isBlank()) return Result.error("setvar requires a variable name")
                variableStore?.setLocal(name, value)
                Result.ok(value)
            }

            "setglobalvar" -> {
                val name = cmd.varName()
                val value = cmd.varValue()
                if (name.isBlank()) return Result.error("setglobalvar requires a variable name")
                variableStore?.setGlobal(name, value)
                Result.ok(value)
            }

            "getvar", "getchatvar", "var" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("getvar requires a variable name")
                Result.ok(variableStore?.getLocal(name) ?: "")
            }

            "getglobalvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("getglobalvar requires a variable name")
                Result.ok(variableStore?.getGlobal(name) ?: "")
            }

            "addvar", "addchatvar" -> {
                val name = cmd.varName()
                val increment = cmd.varValue()
                if (name.isBlank()) return Result.error("addvar requires a variable name")
                Result.ok(variableStore?.addLocal(name, increment) ?: "0")
            }

            "addglobalvar" -> {
                val name = cmd.varName()
                val increment = cmd.varValue()
                if (name.isBlank()) return Result.error("addglobalvar requires a variable name")
                Result.ok(variableStore?.addGlobal(name, increment) ?: "0")
            }

            "incvar", "incchatvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("incvar requires a variable name")
                Result.ok(variableStore?.incLocal(name) ?: "0")
            }

            "incglobalvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("incglobalvar requires a variable name")
                Result.ok(variableStore?.incGlobal(name) ?: "0")
            }

            "decvar", "decchatvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("decvar requires a variable name")
                Result.ok(variableStore?.decLocal(name) ?: "0")
            }

            "decglobalvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("decglobalvar requires a variable name")
                Result.ok(variableStore?.decGlobal(name) ?: "0")
            }

            "flushvar", "flushchatvar", "deletevar", "delvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("flushvar requires a variable name")
                variableStore?.deleteLocal(name)
                Result.ok("")
            }

            "flushglobalvar" -> {
                val name = cmd.varName()
                if (name.isBlank()) return Result.error("flushglobalvar requires a variable name")
                variableStore?.deleteGlobal(name)
                Result.ok("")
            }

            "listvar", "listchatvar" -> {
                val scopeFilter = (cmd.namedArgs["scope"] ?: "all").lowercase()
                val keys = when (scopeFilter) {
                    "local" -> variableStore?.listLocal() ?: emptyList()
                    "global" -> variableStore?.listGlobal() ?: emptyList()
                    else -> ((variableStore?.listLocal() ?: emptyList()) +
                        (variableStore?.listGlobal() ?: emptyList())).distinct().sorted()
                }
                Result.ok(keys.joinToString("\n"))
            }

            "hasvar", "varexists" -> {
                val name = cmd.varName()
                Result.ok(if (variableStore?.hasLocal(name) == true) "true" else "false")
            }

            "hasglobalvar", "globalvarexists" -> {
                val name = cmd.varName()
                Result.ok(if (variableStore?.hasGlobal(name) == true) "true" else "false")
            }

            "let" -> {
                val name = cmd.varName()
                val value = cmd.varValue()
                if (name.isBlank()) return Result.error("let requires a variable name")
                variableStore?.setLocal(name, value)
                Result.ok(value)
            }

            "if" -> {
                val closures = cmd.closureArgs()
                val hasNamedOperands = cmd.namedArgs.containsKey("left") || cmd.namedArgs.containsKey("rule")
                if (!hasNamedOperands && closures.isEmpty()) {
                    // tellev legacy form: /if <condition string> -> "true"/"false"
                    val condition = cmd.textArgs().joinToString(" ")
                    return Result.ok(if (evaluateCondition(condition)) "true" else "false")
                }
                // ST form: /if left=x right=y rule=eq {:then:} else={:else:}
                // (variables.js ifCallback). `else={:...:}` tokenizes as an empty
                // named arg followed by a positional closure, so a second closure
                // after an empty `else` is treated as the else branch.
                val thenClosure = closures.firstOrNull()
                val elseClosure = if (cmd.namedArgs["else"] == "") closures.getOrNull(1) else null
                if (evaluateIfCondition(cmd)) {
                    when {
                        thenClosure != null -> executeLines(thenClosure.lines)
                        else -> {
                            val text = cmd.textArgs().filter { it.isNotBlank() }.joinToString(" ")
                            if (text.isBlank()) Result.ok("") else execute(text)
                        }
                    }
                } else {
                    val elseText = cmd.namedArgs["else"]?.takeIf { it.isNotBlank() }
                    when {
                        elseClosure != null -> executeLines(elseClosure.lines)
                        elseText != null -> execute(elseText)
                        else -> Result.ok("")
                    }
                }
            }

            "while" -> {
                val body = cmd.closureArgs().firstOrNull()
                if (body == null) {
                    // tellev legacy: /while <condition> body=<commands>
                    val condition = cmd.textArgs().joinToString(" ")
                    val bodyText = cmd.namedArgs["body"] ?: ""
                    var iterations = 0
                    var output = ""
                    while (evaluateCondition(condition) && iterations < maxLoopIterations) {
                        if (bodyText.isNotBlank()) {
                            val r = execute(bodyText)
                            if (r.isBreak) break
                            if (r.isAborted || r.isError) return r
                            output = r.output
                        }
                        iterations++
                    }
                    return Result.ok(if (iterations >= maxLoopIterations) "max_iterations" else output)
                }
                // ST form: /while left=x right=y rule=neq guard=100 {:body:}
                // (variables.js whileCallback; guard defaults to 100).
                val guard = cmd.namedArgs["guard"]?.toIntOrNull() ?: 100
                val cap = minOf(guard, maxLoopIterations)
                var iterations = 0
                var output = ""
                while (evaluateIfCondition(cmd) && iterations < cap) {
                    val r = executeLines(body.lines)
                    if (r.isBreak) break
                    if (r.isAborted || r.isError) return r
                    output = r.output
                    iterations++
                }
                Result.ok(output)
            }

            "times" -> {
                val count = cmd.textArgs().firstOrNull()?.toIntOrNull() ?: 0
                val bodyClosure = cmd.closureArgs().firstOrNull()
                val bodyText = cmd.namedArgs["body"] ?: ""
                var output = ""
                repeat(count.coerceAtMost(maxLoopIterations)) {
                    if (bodyClosure != null) {
                        val r = executeLines(bodyClosure.lines)
                        if (r.isBreak) return@repeat
                        if (r.isAborted || r.isError) return r
                        output = r.output
                    } else if (bodyText.isNotBlank()) {
                        val r = execute(bodyText)
                        if (r.isBreak) return@repeat
                        if (r.isAborted || r.isError) return r
                        output = r.output
                    }
                }
                Result.ok(output)
            }

            "run", "call", "exec" -> {
                // ST /run (aliases call/exec): execute closure(s) or a command string.
                val closures = cmd.closureArgs()
                if (closures.isNotEmpty()) {
                    var output = ""
                    for (closure in closures) {
                        val r = executeLines(closure.lines)
                        if (r.isAborted || r.isError || r.isBreak) return r
                        output = r.output
                    }
                    Result.ok(output)
                } else {
                    val text = cmd.textArgs().joinToString(" ")
                    if (text.isBlank()) Result.ok("") else execute(text)
                }
            }

            ":" -> {
                // ST /: — immediately execute the closure argument(s).
                var output = ""
                for (closure in cmd.closureArgs()) {
                    val r = executeLines(closure.lines)
                    if (r.isAborted || r.isError || r.isBreak) return r
                    output = r.output
                }
                Result.ok(output)
            }

            "break" -> Result.breakResult()

            "abort", "stop" -> Result.abort()

            "len" -> {
                val input = cmd.textArgs().joinToString(" ")
                Result.ok(input.length.toString())
            }

            "upper" -> Result.ok(cmd.textArgs().joinToString(" ").uppercase())

            "lower" -> Result.ok(cmd.textArgs().joinToString(" ").lowercase())

            "replace" -> {
                val input = cmd.textArgs().getOrNull(2) ?: cmd.namedArgs["input"] ?: ""
                val from = cmd.textArgs().getOrNull(0) ?: ""
                val to = cmd.textArgs().getOrNull(1) ?: ""
                Result.ok(input.replace(from, to))
            }

            "substr" -> {
                val input = cmd.textArgs().getOrNull(0) ?: ""
                val start = cmd.textArgs().getOrNull(1)?.toIntOrNull() ?: 0
                val end = cmd.textArgs().getOrNull(2)?.toIntOrNull() ?: input.length
                Result.ok(input.substring(start.coerceAtLeast(0), end.coerceAtMost(input.length)))
            }

            "add" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.textArgs().getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a + b).toString())
            }

            "sub" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.textArgs().getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a - b).toString())
            }

            "mul" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.textArgs().getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a * b).toString())
            }

            "div" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.textArgs().getOrNull(1)?.toDoubleOrNull() ?: 1.0
                if (b == 0.0) return Result.error("Division by zero")
                Result.ok((a / b).toString())
            }

            "mod" -> {
                val a = cmd.textArgs().getOrNull(0)?.toLongOrNull() ?: 0L
                val b = cmd.textArgs().getOrNull(1)?.toLongOrNull() ?: 1L
                if (b == 0L) return Result.error("Modulo by zero")
                Result.ok((a % b).toString())
            }

            "pow" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.textArgs().getOrNull(1)?.toDoubleOrNull() ?: 1.0
                Result.ok(Math.pow(a, b).toString())
            }

            "abs" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.abs(a).toString())
            }

            "sqrt" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.sqrt(a).toString())
            }

            "round" -> {
                val a = cmd.textArgs().getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.round(a).toString())
            }

            "max" -> {
                val values = cmd.textArgs().mapNotNull { it.toDoubleOrNull() }
                Result.ok(values.maxOrNull()?.toString() ?: "")
            }

            "min" -> {
                val values = cmd.textArgs().mapNotNull { it.toDoubleOrNull() }
                Result.ok(values.minOrNull()?.toString() ?: "")
            }

            "rand", "random" -> {
                val values = cmd.textArgs()
                if (values.isEmpty()) {
                    Result.ok((0..Int.MAX_VALUE).random().toString())
                } else {
                    Result.ok(values.random())
                }
            }

            "sort" -> {
                val items = cmd.textArgs()
                val sorted = if (cmd.namedArgs["reverse"] == "true") {
                    items.sortedDescending()
                } else {
                    items.sorted()
                }
                Result.ok(sorted.joinToString("\n"))
            }

            "array-wrap" -> {
                Result.ok(cmd.textArgs().joinToString(" | "))
            }

            "array-unwrap" -> {
                val input = cmd.textArgs().joinToString(" ")
                Result.ok(input.split(" | ").joinToString(" "))
            }

            "trimtokens", "trimstart", "trimend" -> {
                Result.ok(cmd.textArgs().joinToString(" "))
            }

            "tokens" -> {
                val input = cmd.textArgs().joinToString(" ")
                Result.ok(input.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toString())
            }

            "concat" -> Result.ok(cmd.textArgs().joinToString(""))

            "join" -> {
                val separator = cmd.namedArgs["separator"] ?: "\n"
                Result.ok(cmd.textArgs().joinToString(separator))
            }

            "split" -> {
                val input = cmd.textArgs().getOrNull(0) ?: ""
                val separator = cmd.textArgs().getOrNull(1) ?: " "
                Result.ok(input.split(separator).joinToString("\n"))
            }

            "match" -> {
                val pattern = cmd.textArgs().getOrNull(0) ?: ""
                val input = cmd.textArgs().getOrNull(1) ?: ""
                val regex = runCatching { Regex(pattern) }.getOrNull()
                Result.ok(if (regex?.containsMatchIn(input) == true) "true" else "false")
            }

            "test" -> {
                val pattern = cmd.textArgs().getOrNull(0) ?: ""
                val input = cmd.textArgs().getOrNull(1) ?: ""
                val regex = runCatching { Regex(pattern) }.getOrNull()
                Result.ok(if (regex?.containsMatchIn(input) == true) "true" else "false")
            }

            "fuzzy" -> {
                val needle = cmd.textArgs().getOrNull(0) ?: ""
                val haystack = cmd.textArgs().getOrNull(1) ?: ""
                Result.ok(if (haystack.contains(needle, ignoreCase = true)) "true" else "false")
            }

            "input", "prompt" -> Result.ok(cmd.textArgs().joinToString(" "))

            "popup", "buttons" -> Result.ok(cmd.textArgs().joinToString(" "))

            "message-role" -> {
                val role = cmd.textArgs().firstOrNull() ?: "system"
                Result.ok(role)
            }

            "message-name" -> {
                val name = cmd.textArgs().joinToString(" ")
                Result.ok(name)
            }

            "comment" -> Result.ok(cmd.textArgs().joinToString(" "))

            "send", "sys", "sysname" -> Result.ok(cmd.textArgs().joinToString(" "))

            "gen", "genraw" -> stubOk(cmd.name)

            "continue", "regenerate", "swipe" -> stubOk(cmd.name)

            "newchat" -> stubOk(cmd.name)

            "del", "cut" -> stubOk(cmd.name)

            "model" -> stubOk(cmd.name)

            "tokenizer" -> {
                val input = cmd.textArgs().joinToString(" ")
                Result.ok(input.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toString())
            }

            "clipboard-get" -> stubOk(cmd.name)

            "clipboard-set" -> stubOk(cmd.name)

            "beep" -> stubOk(cmd.name)

            "help", "?" -> {
                Result.ok(BUILTIN_COMMANDS.joinToString("\n") { "/$it" })
            }

            "event-emit" -> {
                val event = cmd.namedArgs["event"] ?: cmd.textArgs().getOrNull(0) ?: ""
                val dataArgs = cmd.textArgs().drop(if (cmd.namedArgs["event"] != null) 0 else 1)
                val dataFromNamed = cmd.namedArgs.filterKeys { it != "event" }.values.toList()
                val allData = dataArgs + dataFromNamed
                eventEmitter?.invoke(event, allData)
                Result.ok(event)
            }

            "inject" -> stubOk(cmd.name)

            "listinjects" -> stubOk(cmd.name)

            "flushinject" -> stubOk(cmd.name)

            "getpromptentry", "setpromptentry" -> stubOk(cmd.name)

            "is-mobile" -> stubOk(cmd.name, "true")

            "chat-render", "chat-reload" -> stubOk(cmd.name)

            "reroll-pick" -> stubOk(cmd.name)

            "profile" -> stubOk(cmd.name)

            "profile-list" -> stubOk(cmd.name)

            "tempchat" -> stubOk(cmd.name)

            "closechat" -> stubOk(cmd.name)

            "getchatname" -> stubOk(cmd.name)

            "renamechat" -> stubOk(cmd.name)

            "delchat" -> stubOk(cmd.name)

            "forcesave" -> stubOk(cmd.name)

            "instruct", "instruct-on", "instruct-off", "instruct-state" -> stubOk(cmd.name)

            "context" -> stubOk(cmd.name)

            "panels" -> stubOk(cmd.name)

            "bg" -> stubOk(cmd.name)

            "char-find" -> stubOk(cmd.name)

            "char-create", "char-update", "char-duplicate", "char-get", "char-delete" -> stubOk(cmd.name)

            "sendas" -> stubOk(cmd.name)

            "single", "bubble", "flat" -> stubOk(cmd.name)

            "go" -> stubOk(cmd.name)

            "rename-char" -> stubOk(cmd.name)

            "sysgen" -> stubOk(cmd.name)

            "ask" -> stubOk(cmd.name)

            "delname" -> stubOk(cmd.name)

            "trigger" -> stubOk(cmd.name)

            "hide", "unhide" -> stubOk(cmd.name)

            "member-get", "member-disable", "member-enable", "member-add", "member-remove",
            "member-up", "member-down", "member-peek", "member-count" -> stubOk(cmd.name)

            "delswipe", "addswipe" -> stubOk(cmd.name)

            "messages" -> stubOk(cmd.name)

            "setinput" -> stubOk(cmd.name)

            "pick-icon" -> stubOk(cmd.name)

            "api", "api-url" -> stubOk(cmd.name)

            "chat-jump" -> stubOk(cmd.name)

            "prompt-post-processing" -> stubOk(cmd.name)

            "vn" -> stubOk(cmd.name)

            "resetpanels" -> stubOk(cmd.name)

            "bgcol" -> stubOk(cmd.name)

            "theme" -> stubOk(cmd.name)

            "css-var" -> stubOk(cmd.name)

            "movingui" -> stubOk(cmd.name)

            "stop-strings" -> stubOk(cmd.name)

            "start-reply-with" -> stubOk(cmd.name)

            "persona-create", "persona-update", "persona-get", "persona-delete",
            "persona-duplicate", "persona-lock", "persona-set", "persona-sync" -> stubOk(cmd.name)

            "reasoning-get", "reasoning-set", "reasoning-parse", "reasoning-format",
            "reasoning-template", "reasoning-collapse", "reasoning-expand", "reasoning-toggle" -> stubOk(cmd.name)

            "secret-id", "secret-delete", "secret-write", "secret-rename", "secret-read" -> stubOk(cmd.name)

            "sysprompt", "sysprompt-on", "sysprompt-off", "sysprompt-state" -> stubOk(cmd.name)

            "extension-enable", "extension-disable", "extension-toggle", "extension-state",
            "extension-exists", "reload-page" -> stubOk(cmd.name)

            "note", "note-depth", "note-frequency", "note-position", "note-role" -> stubOk(cmd.name)

            "lockbg", "unlockbg", "autobg" -> stubOk(cmd.name)

            "branch-create", "checkpoint-create", "checkpoint-go", "checkpoint-exit",
            "checkpoint-parent", "checkpoint-get", "checkpoint-list" -> stubOk(cmd.name)

            "tag-add", "tag-remove", "tag-exists", "tag-list", "tag-import" -> stubOk(cmd.name)

            "tools-list", "tools-invoke", "tools-register", "tools-unregister" -> stubOk(cmd.name)

            "preset" -> stubOk(cmd.name)

            "proxy" -> stubOk(cmd.name)

            "loader-wrap", "loader-show", "loader-hide", "loader-stop" -> stubOk(cmd.name)

            "db", "db-list", "db-get", "db-add", "db-update", "db-disable", "db-enable", "db-delete" -> stubOk(cmd.name)

            "db-ingest", "db-purge", "db-search", "vector-threshold", "vector-query",
            "vector-max-entries", "vector-chats-state", "vector-files-state", "vector-worldinfo-state" -> stubOk(cmd.name)

            "imagine", "imagine-source", "imagine-style", "imagine-comfy-workflow" -> stubOk(cmd.name)

            "expression-set", "expression-fallback", "expression-folder-override",
            "expression-last", "expression-list", "expression-classify", "expression-upload" -> stubOk(cmd.name)

            "profile-create", "profile-update", "profile-get", "profile-genstream" -> stubOk(cmd.name)

            "regex-preset", "regex", "regex-state", "regex-toggle" -> stubOk(cmd.name)

            "show-gallery", "list-gallery" -> stubOk(cmd.name)

            "summarize" -> stubOk(cmd.name)

            "caption" -> stubOk(cmd.name)

            "translate" -> stubOk(cmd.name)

            "speak" -> stubOk(cmd.name)

            "count" -> stubOk(cmd.name, "0")

            "world", "getchatbook", "getglobalbooks", "getpersonabook", "getcharbook",
            "findentry", "getentryfield", "createentry", "setentryfield" -> stubOk(cmd.name)

            "wi-set-timed-effect", "wi-get-timed-effect" -> stubOk(cmd.name)

            "yt-script" -> stubOk(cmd.name)

            else -> {
                if (cmd.name in extensionCommands) {
                    Result(handled = false, output = "")
                } else {
                    Result.unknown(cmd.name)
                }
            }
        }
    }

    /**
     * ST-compatible condition evaluation for `/if` and `/while`
     * (variables.js parseBooleanOperands + evalBoolean).
     */
    private fun evaluateIfCondition(cmd: Command): Boolean {
        val leftRaw = cmd.namedArgs["left"] ?: cmd.textArgs().firstOrNull { it.isNotBlank() } ?: ""
        val rightRaw = cmd.namedArgs["right"]
        val rule = cmd.namedArgs["rule"] ?: "eq"
        val left = resolveOperand(leftRaw)
        val right = rightRaw?.let { resolveOperand(it) }

        if (right == null) {
            // No right operand: truthy check; 'not' inverts.
            val truthy = isTruthyValue(left)
            return if (rule == "not") !truthy else truthy
        }
        val aNum = left.toDoubleOrNull()
        val bNum = right.toDoubleOrNull()
        return when (rule) {
            "eq", "==" -> if (aNum != null && bNum != null) aNum == bNum else left == right
            "neq", "!=" -> if (aNum != null && bNum != null) aNum != bNum else left != right
            "in" -> left.contains(right)
            "nin" -> !left.contains(right)
            "gt" -> (aNum ?: 0.0) > (bNum ?: 0.0)
            "gte" -> (aNum ?: 0.0) >= (bNum ?: 0.0)
            "lt" -> (aNum ?: 0.0) < (bNum ?: 0.0)
            "lte" -> (aNum ?: 0.0) <= (bNum ?: 0.0)
            "not" -> !isTruthyValue(left)
            else -> left == right
        }
    }

    /**
     * ST operand resolution order (variables.js:440-480): numeric literal,
     * local variable, global variable, string literal. The tellev legacy
     * `{{name}}` / `{{getvar::name}}` forms are also resolved.
     */
    private fun resolveOperand(raw: String): String {
        if (raw.toDoubleOrNull() != null) return raw
        variableStore?.getLocal(raw)?.let { return it }
        variableStore?.getGlobal(raw)?.let { return it }
        val macroMatch = Regex("^\\{\\{(?:getvar::)?([^}]+)\\}\\}$").matchEntire(raw.trim())
        if (macroMatch != null) {
            val name = macroMatch.groupValues[1].trim()
            variableStore?.getLocal(name)?.let { return it }
            variableStore?.getGlobal(name)?.let { return it }
        }
        return raw
    }

    private fun isTruthyValue(value: String): Boolean =
        value.isNotBlank() && value != "0" && !value.equals("false", ignoreCase = true)

    private fun evaluateCondition(condition: String): Boolean {        val trimmed = condition.trim()
        if (trimmed.isEmpty()) return false

        // Check for comparison operators
        val operators = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in operators) {
            val idx = trimmed.indexOf(op)
            if (idx > 0) {
                val left = resolveValue(trimmed.substring(0, idx).trim())
                val right = resolveValue(trimmed.substring(idx + op.length).trim())
                return when (op) {
                    "==" -> left == right
                    "!=" -> left != right
                    ">=" -> (left.toLongOrNull() ?: 0L) >= (right.toLongOrNull() ?: 0L)
                    "<=" -> (left.toLongOrNull() ?: 0L) <= (right.toLongOrNull() ?: 0L)
                    ">" -> (left.toLongOrNull() ?: 0L) > (right.toLongOrNull() ?: 0L)
                    "<" -> (left.toLongOrNull() ?: 0L) < (right.toLongOrNull() ?: 0L)
                    else -> false
                }
            }
        }

        // No operator: truthy check
        val value = resolveValue(trimmed)
        return value.isNotBlank() && value != "false" && value != "0"
    }

    private fun resolveValue(token: String): String {
        if (token.startsWith("{{") && token.endsWith("}}")) {
            val varName = token.removeSurrounding("{{", "}}")
            return variableStore?.getLocal(varName)
                ?: variableStore?.getGlobal(varName)
                ?: ""
        }
        // Strip matching surrounding quotes so quoted literals compare equal to
        // bare/macro-expanded values (STScript compares the unquoted value).
        return token.removeSurrounding("\"").removeSurrounding("'")
    }

    companion object {
        /**
         * Commands advertised by SillyTavern but not implemented by the
         * native engine. Keep them distinguishable from unknown extension
         * commands: callers get an explicit error and never a fake success.
         */

        val BUILTIN_COMMANDS: Set<String> = setOf(
            "echo", "noop", "pass", "return", "delay", "wait", "sleep",
            "setvar", "setglobalvar", "getvar", "getglobalvar", "var",
            "addvar", "addglobalvar", "incvar", "incglobalvar",
            "decvar", "decglobalvar", "flushvar", "flushglobalvar",
            "deletevar", "delvar", "listvar", "hasvar", "varexists", "let",
            "if", "while", "times", "abort", "stop",
            "len", "upper", "lower", "replace", "substr",
            "add", "sub", "mul", "div", "mod", "pow", "abs", "sqrt",
            "round", "max", "min", "rand", "random", "sort",
            "array-wrap", "array-unwrap", "trimtokens", "trimstart", "trimend",
            "tokens", "concat", "join", "split", "match", "test", "fuzzy",
            "input", "prompt", "popup", "buttons",
            "message-role", "message-name", "comment",
            "send", "sys", "sysname", "gen", "genraw",
            "continue", "regenerate", "swipe", "newchat", "del", "cut",
            "model", "tokenizer", "clipboard-get", "clipboard-set", "beep",
            "help", "?", "event-emit", "inject", "listinjects", "flushinject",
            "getpromptentry", "setpromptentry",
            "chat-render", "chat-reload", "reroll-pick",
            "profile", "profile-list", "tempchat", "closechat",
            "getchatname", "renamechat", "delchat", "forcesave",
            "instruct", "instruct-on", "instruct-off", "instruct-state",
            "context", "panels", "bg",
            "char-find", "char-create", "char-update", "char-duplicate",
            "char-get", "char-delete", "sendas",
            "single", "bubble", "flat", "go", "rename-char", "sysgen",
            "ask", "delname", "trigger", "hide", "unhide",
            "member-get", "member-disable", "member-enable", "member-add",
            "member-remove", "member-up", "member-down", "member-peek",
            "member-count", "delswipe", "addswipe", "messages", "setinput",
            "pick-icon", "api", "api-url", "chat-jump", "prompt-post-processing",
            "vn", "resetpanels", "bgcol", "theme", "css-var", "movingui",
            "stop-strings", "start-reply-with",
            "persona-create", "persona-update", "persona-get", "persona-delete",
            "persona-duplicate", "persona-lock", "persona-set", "persona-sync",
            "reasoning-get", "reasoning-set", "reasoning-parse", "reasoning-format",
            "reasoning-template", "reasoning-collapse", "reasoning-expand", "reasoning-toggle",
            "secret-id", "secret-delete", "secret-write", "secret-rename", "secret-read",
            "sysprompt", "sysprompt-on", "sysprompt-off", "sysprompt-state",
            "extension-enable", "extension-disable", "extension-toggle",
            "extension-state", "extension-exists", "reload-page",
            "note", "note-depth", "note-frequency", "note-position", "note-role",
            "lockbg", "unlockbg", "autobg",
            "branch-create", "checkpoint-create", "checkpoint-go", "checkpoint-exit",
            "checkpoint-parent", "checkpoint-get", "checkpoint-list",
            "tag-add", "tag-remove", "tag-exists", "tag-list", "tag-import",
            "tools-list", "tools-invoke", "tools-register", "tools-unregister",
            "preset", "proxy",
            "loader-wrap", "loader-show", "loader-hide", "loader-stop",
            "db", "db-list", "db-get", "db-add", "db-update",
            "db-disable", "db-enable", "db-delete",
            "db-ingest", "db-purge", "db-search", "vector-threshold",
            "vector-query", "vector-max-entries", "vector-chats-state",
            "vector-files-state", "vector-worldinfo-state",
            "imagine", "imagine-source", "imagine-style", "imagine-comfy-workflow",
            "expression-set", "expression-fallback", "expression-folder-override",
            "expression-last", "expression-list", "expression-classify", "expression-upload",
            "profile-create", "profile-update", "profile-get", "profile-genstream",
            "regex-preset", "regex", "regex-state", "regex-toggle",
            "show-gallery", "list-gallery", "summarize", "caption", "translate",
            "speak", "count",
            "world", "getchatbook", "getglobalbooks", "getpersonabook", "getcharbook",
            "findentry", "getentryfield", "createentry", "setentryfield",
            "wi-set-timed-effect", "wi-get-timed-effect", "yt-script",
        )
    }
}
