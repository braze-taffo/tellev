package app.tellev.core.extension

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    private val reportedStubs = Collections.newSetFromMap(
        ConcurrentHashMap<String, Boolean>(),
    )

    private val builtins = SlashCommandBuiltins(
        variableStore = variableStore,
        extensionCommands = extensionCommands,
        maxLoopIterations = maxLoopIterations,
        eventEmitter = eventEmitter,
        onUnimplementedCommand = onUnimplementedCommand,
        reportedStubs = reportedStubs,
        executeLines = ::executeLines,
        executeScript = ::execute,
    )

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
        val tokens = SlashCommandParser.tokenizeScript(scriptText)
        val lines = SlashCommandParser.ScriptParser(tokens).parseStatements(stopOnClosureEnd = false)
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
            val piped = if (index > 0) SlashCommandParser.applyPipe(cmd, pipeInput) else cmd
            result = builtins.execute(SlashCommandParser.expandMacros(piped, macroExpander))
            if (result.isAborted || result.isError || result.isBreak) return result
            pipeInput = result.output
        }
        return result
    }

    companion object {
        val BUILTIN_COMMANDS: Set<String> get() = SlashCommandBuiltins.BUILTIN_COMMANDS
    }
}
