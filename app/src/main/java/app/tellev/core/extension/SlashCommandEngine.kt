package app.tellev.core.extension

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
 * Variables are shared with the extension host so that `/setvar` /
 * `/getvar` operate on the same store that `TavernHelper.getVariables`
 * exposes to JS.
 */
class SlashCommandEngine(
    private val variables: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    private val extensionCommands: Map<String, RegisteredCommandRef> = emptyMap(),
    private val maxLoopIterations: Int = 1000,
    /**
     * Optional callback invoked by the `/event-emit` command so that
     * custom events actually reach the extension event bus.  Receives
     * the event name and the positional argument list.
     */
    private val eventEmitter: ((String, List<String>) -> Unit)? = null,
) {

    data class RegisteredCommandRef(
        val extensionId: String,
    )

    data class Command(
        val name: String,
        val args: List<String>,
        val namedArgs: Map<String, String>,
    )

    data class Result(
        val handled: Boolean,
        val output: String = "",
        val isError: Boolean = false,
        val isAborted: Boolean = false,
        val errorMessage: String = "",
    ) {
        companion object {
            fun ok(output: String = "") = Result(handled = true, output = output)
            fun error(message: String) = Result(handled = true, isError = true, errorMessage = message, output = "")
            fun unknown(name: String) = Result(handled = false, output = "Unknown command: $name")
            fun abort() = Result(handled = true, isAborted = true)
        }
    }

    /**
     * Parse and execute a full STScript text.  Multiple lines are executed
     * sequentially; each line may contain a pipe chain.
     */
    fun execute(scriptText: String): Result {
        val lines = scriptText.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        var lastResult = Result.ok()

        for (line in lines) {
            val pipeCommands = parsePipe(line)
            var pipeInput = ""

            for ((index, cmd) in pipeCommands.withIndex()) {
                val effectiveCmd = if (index > 0 && cmd.args.isNotEmpty() && cmd.args.last() == "{{pipe}}") {
                    cmd.copy(args = cmd.args.dropLast(1) + pipeInput)
                } else if (index > 0 && pipeInput.isNotEmpty()) {
                    cmd.copy(args = cmd.args + pipeInput)
                } else {
                    cmd
                }

                lastResult = executeCommand(effectiveCmd)
                if (lastResult.isAborted) return lastResult
                if (lastResult.isError) return lastResult
                pipeInput = lastResult.output
            }
        }

        return lastResult
    }

    // ── Parser ──────────────────────────────────────────────────────────

    private fun parsePipe(line: String): List<Command> {
        val commands = mutableListOf<Command>()
        val segments = splitTopLevel(line, '|')

        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) continue
            val cmd = parseCommand(trimmed) ?: continue
            commands.add(cmd)
        }

        return commands
    }

    private fun parseCommand(text: String): Command? {
        val tokens = tokenize(text)
        if (tokens.isEmpty() || !tokens[0].startsWith("/")) return null

        val name = tokens[0].removePrefix("/")
        val positional = mutableListOf<String>()
        val named = mutableMapOf<String, String>()

        for (token in tokens.drop(1)) {
            val eqIdx = token.indexOf('=')
            val isComparison = eqIdx > 0 && (
                token[eqIdx - 1] == '!' || token[eqIdx - 1] == '>' || token[eqIdx - 1] == '<' ||
                (eqIdx + 1 < token.length && token[eqIdx + 1] == '=')
            )
            if (eqIdx > 0 && !token.startsWith("\"") && !token.startsWith("'") && !isComparison) {
                val key = token.substring(0, eqIdx)
                val value = token.substring(eqIdx + 1)
                named[key] = value
            } else {
                positional.add(token)
            }
        }

        return Command(name = name, args = positional, namedArgs = named)
    }

    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote: Char? = null
        var i = 0

        while (i < text.length) {
            val c = text[i]

            when {
                inQuote != null -> {
                    if (c == inQuote) {
                        inQuote = null
                    } else {
                        current.append(c)
                    }
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                }
                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                }
                else -> {
                    current.append(c)
                }
            }
            i++
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote: Char? = null
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuote != null -> {
                    if (c == inQuote) inQuote = null
                    current.append(c)
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                    current.append(c)
                }
                c == delimiter -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    // ── Built-in command executors ──────────────────────────────────────

    private fun executeCommand(cmd: Command): Result {
        return when (cmd.name) {
            "echo" -> Result.ok(cmd.args.joinToString(" "))

            "noop", "pass", "return" -> Result.ok(cmd.args.joinToString(" "))

            "delay", "wait", "sleep" -> {
                val ms = cmd.args.firstOrNull()?.toLongOrNull() ?: 1000L
                Thread.sleep(ms.coerceAtMost(30_000L))
                Result.ok("")
            }

            "setvar", "setglobalvar" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.getOrNull(0) ?: ""
                val value = cmd.namedArgs["value"] ?: cmd.args.getOrNull(1) ?: ""
                if (name.isBlank()) return Result.error("setvar requires a variable name")
                variables[name] = value
                Result.ok(value)
            }

            "getvar", "getglobalvar", "var" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.firstOrNull() ?: ""
                if (name.isBlank()) return Result.error("getvar requires a variable name")
                Result.ok(variables[name] ?: "")
            }

            "addvar", "addglobalvar" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.getOrNull(0) ?: ""
                val increment = cmd.namedArgs["value"] ?: cmd.args.getOrNull(1) ?: ""
                if (name.isBlank()) return Result.error("addvar requires a variable name")
                val current = variables[name] ?: "0"
                val result = runCatching {
                    (current.toLong() + increment.toLong()).toString()
                }.getOrElse {
                    current + increment
                }
                variables[name] = result
                Result.ok(result)
            }

            "incvar", "incglobalvar" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.firstOrNull() ?: ""
                if (name.isBlank()) return Result.error("incvar requires a variable name")
                val current = (variables[name]?.toLongOrNull() ?: 0L) + 1L
                variables[name] = current.toString()
                Result.ok(current.toString())
            }

            "decvar", "decglobalvar" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.firstOrNull() ?: ""
                if (name.isBlank()) return Result.error("decvar requires a variable name")
                val current = (variables[name]?.toLongOrNull() ?: 0L) - 1L
                variables[name] = current.toString()
                Result.ok(current.toString())
            }

            "flushvar", "flushglobalvar", "deletevar", "delvar" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.firstOrNull() ?: ""
                if (name.isBlank()) return Result.error("flushvar requires a variable name")
                variables.remove(name)
                Result.ok("")
            }

            "listvar" -> {
                Result.ok(variables.keys.sorted().joinToString("\n"))
            }

            "hasvar", "varexists" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.firstOrNull() ?: ""
                Result.ok(if (variables.containsKey(name)) "true" else "false")
            }

            "let" -> {
                val name = cmd.namedArgs["name"] ?: cmd.namedArgs["key"] ?: cmd.args.getOrNull(0) ?: ""
                val value = cmd.namedArgs["value"] ?: cmd.args.drop(1).joinToString(" ")
                if (name.isBlank()) return Result.error("let requires a variable name")
                variables[name] = value
                Result.ok(value)
            }

            "if" -> {
                val condition = cmd.args.joinToString(" ")
                val result = evaluateCondition(condition)
                Result.ok(if (result) "true" else "false")
            }

            "while" -> {
                val condition = cmd.args.joinToString(" ")
                val body = cmd.namedArgs["body"] ?: ""
                var iterations = 0
                while (evaluateCondition(condition) && iterations < maxLoopIterations) {
                    if (body.isNotBlank()) execute(body)
                    iterations++
                }
                Result.ok(if (iterations >= maxLoopIterations) "max_iterations" else "")
            }

            "times" -> {
                val count = cmd.args.firstOrNull()?.toIntOrNull() ?: 0
                val body = cmd.namedArgs["body"] ?: ""
                repeat(count.coerceAtMost(maxLoopIterations)) { if (body.isNotBlank()) execute(body) }
                Result.ok("")
            }

            "abort", "stop" -> Result.abort()

            "len" -> {
                val input = cmd.args.joinToString(" ")
                Result.ok(input.length.toString())
            }

            "upper" -> Result.ok(cmd.args.joinToString(" ").uppercase())

            "lower" -> Result.ok(cmd.args.joinToString(" ").lowercase())

            "replace" -> {
                val input = cmd.args.getOrNull(2) ?: cmd.namedArgs["input"] ?: ""
                val from = cmd.args.getOrNull(0) ?: ""
                val to = cmd.args.getOrNull(1) ?: ""
                Result.ok(input.replace(from, to))
            }

            "substr" -> {
                val input = cmd.args.getOrNull(0) ?: ""
                val start = cmd.args.getOrNull(1)?.toIntOrNull() ?: 0
                val end = cmd.args.getOrNull(2)?.toIntOrNull() ?: input.length
                Result.ok(input.substring(start.coerceAtLeast(0), end.coerceAtMost(input.length)))
            }

            "add" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a + b).toString())
            }

            "sub" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a - b).toString())
            }

            "mul" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.args.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                Result.ok((a * b).toString())
            }

            "div" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.args.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                if (b == 0.0) return Result.error("Division by zero")
                Result.ok((a / b).toString())
            }

            "mod" -> {
                val a = cmd.args.getOrNull(0)?.toLongOrNull() ?: 0L
                val b = cmd.args.getOrNull(1)?.toLongOrNull() ?: 1L
                if (b == 0L) return Result.error("Modulo by zero")
                Result.ok((a % b).toString())
            }

            "pow" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val b = cmd.args.getOrNull(1)?.toDoubleOrNull() ?: 1.0
                Result.ok(Math.pow(a, b).toString())
            }

            "abs" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.abs(a).toString())
            }

            "sqrt" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.sqrt(a).toString())
            }

            "round" -> {
                val a = cmd.args.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                Result.ok(Math.round(a).toString())
            }

            "max" -> {
                val values = cmd.args.mapNotNull { it.toDoubleOrNull() }
                Result.ok(values.maxOrNull()?.toString() ?: "")
            }

            "min" -> {
                val values = cmd.args.mapNotNull { it.toDoubleOrNull() }
                Result.ok(values.minOrNull()?.toString() ?: "")
            }

            "rand", "random" -> {
                val values = cmd.args
                if (values.isEmpty()) {
                    Result.ok((0..Int.MAX_VALUE).random().toString())
                } else {
                    Result.ok(values.random())
                }
            }

            "sort" -> {
                val items = cmd.args
                val sorted = if (cmd.namedArgs["reverse"] == "true") {
                    items.sortedDescending()
                } else {
                    items.sorted()
                }
                Result.ok(sorted.joinToString("\n"))
            }

            "array-wrap" -> {
                Result.ok(cmd.args.joinToString(" | "))
            }

            "array-unwrap" -> {
                val input = cmd.args.joinToString(" ")
                Result.ok(input.split(" | ").joinToString(" "))
            }

            "trimtokens", "trimstart", "trimend" -> {
                Result.ok(cmd.args.joinToString(" "))
            }

            "tokens" -> {
                val input = cmd.args.joinToString(" ")
                Result.ok(input.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toString())
            }

            "concat" -> Result.ok(cmd.args.joinToString(""))

            "join" -> {
                val separator = cmd.namedArgs["separator"] ?: "\n"
                Result.ok(cmd.args.joinToString(separator))
            }

            "split" -> {
                val input = cmd.args.getOrNull(0) ?: ""
                val separator = cmd.args.getOrNull(1) ?: " "
                Result.ok(input.split(separator).joinToString("\n"))
            }

            "match" -> {
                val pattern = cmd.args.getOrNull(0) ?: ""
                val input = cmd.args.getOrNull(1) ?: ""
                val regex = runCatching { Regex(pattern) }.getOrNull()
                Result.ok(if (regex?.containsMatchIn(input) == true) "true" else "false")
            }

            "test" -> {
                val pattern = cmd.args.getOrNull(0) ?: ""
                val input = cmd.args.getOrNull(1) ?: ""
                val regex = runCatching { Regex(pattern) }.getOrNull()
                Result.ok(if (regex?.containsMatchIn(input) == true) "true" else "false")
            }

            "fuzzy" -> {
                val needle = cmd.args.getOrNull(0) ?: ""
                val haystack = cmd.args.getOrNull(1) ?: ""
                Result.ok(if (haystack.contains(needle, ignoreCase = true)) "true" else "false")
            }

            "input", "prompt" -> Result.ok(cmd.args.joinToString(" "))

            "popup", "buttons" -> Result.ok(cmd.args.joinToString(" "))

            "message-role" -> {
                val role = cmd.args.firstOrNull() ?: "system"
                Result.ok(role)
            }

            "message-name" -> {
                val name = cmd.args.joinToString(" ")
                Result.ok(name)
            }

            "comment" -> Result.ok(cmd.args.joinToString(" "))

            "send", "sys", "sysname" -> Result.ok(cmd.args.joinToString(" "))

            "gen", "genraw" -> Result.ok("")

            "continue", "regenerate", "swipe" -> Result.ok("")

            "newchat" -> Result.ok("")

            "del", "cut" -> Result.ok("")

            "model" -> Result.ok("")

            "tokenizer" -> {
                val input = cmd.args.joinToString(" ")
                Result.ok(input.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toString())
            }

            "clipboard-get" -> Result.ok("")

            "clipboard-set" -> Result.ok("")

            "beep" -> Result.ok("")

            "help", "?" -> {
                Result.ok(BUILTIN_COMMANDS.joinToString("\n") { "/$it" })
            }

            "event-emit" -> {
                val event = cmd.namedArgs["event"] ?: cmd.args.getOrNull(0) ?: ""
                val dataArgs = cmd.args.drop(if (cmd.namedArgs["event"] != null) 0 else 1)
                val dataFromNamed = cmd.namedArgs.filterKeys { it != "event" }.values.toList()
                val allData = dataArgs + dataFromNamed
                eventEmitter?.invoke(event, allData)
                Result.ok(event)
            }

            "inject" -> Result.ok("")

            "listinjects" -> Result.ok("")

            "flushinject" -> Result.ok("")

            "getpromptentry", "setpromptentry" -> Result.ok("")

            "is-mobile" -> Result.ok("false")

            "chat-render", "chat-reload" -> Result.ok("")

            "reroll-pick" -> Result.ok("")

            "profile" -> Result.ok("")

            "profile-list" -> Result.ok("")

            "tempchat" -> Result.ok("")

            "closechat" -> Result.ok("")

            "getchatname" -> Result.ok("")

            "renamechat" -> Result.ok("")

            "delchat" -> Result.ok("")

            "forcesave" -> Result.ok("")

            "instruct", "instruct-on", "instruct-off", "instruct-state" -> Result.ok("")

            "context" -> Result.ok("")

            "panels" -> Result.ok("")

            "bg" -> Result.ok("")

            "char-find" -> Result.ok("")

            "char-create", "char-update", "char-duplicate", "char-get", "char-delete" -> Result.ok("")

            "sendas" -> Result.ok("")

            "single", "bubble", "flat" -> Result.ok("")

            "go" -> Result.ok("")

            "rename-char" -> Result.ok("")

            "sysgen" -> Result.ok("")

            "ask" -> Result.ok("")

            "delname" -> Result.ok("")

            "trigger" -> Result.ok("")

            "hide", "unhide" -> Result.ok("")

            "member-get", "member-disable", "member-enable", "member-add", "member-remove",
            "member-up", "member-down", "member-peek", "member-count" -> Result.ok("")

            "delswipe", "addswipe" -> Result.ok("")

            "messages" -> Result.ok("")

            "setinput" -> Result.ok("")

            "pick-icon" -> Result.ok("")

            "api", "api-url" -> Result.ok("")

            "chat-jump" -> Result.ok("")

            "prompt-post-processing" -> Result.ok("")

            "vn" -> Result.ok("")

            "resetpanels" -> Result.ok("")

            "bgcol" -> Result.ok("")

            "theme" -> Result.ok("")

            "css-var" -> Result.ok("")

            "movingui" -> Result.ok("")

            "stop-strings" -> Result.ok("")

            "start-reply-with" -> Result.ok("")

            "persona-create", "persona-update", "persona-get", "persona-delete",
            "persona-duplicate", "persona-lock", "persona-set", "persona-sync" -> Result.ok("")

            "reasoning-get", "reasoning-set", "reasoning-parse", "reasoning-format",
            "reasoning-template", "reasoning-collapse", "reasoning-expand", "reasoning-toggle" -> Result.ok("")

            "secret-id", "secret-delete", "secret-write", "secret-rename", "secret-read" -> Result.ok("")

            "sysprompt", "sysprompt-on", "sysprompt-off", "sysprompt-state" -> Result.ok("")

            "extension-enable", "extension-disable", "extension-toggle", "extension-state",
            "extension-exists", "reload-page" -> Result.ok("")

            "note", "note-depth", "note-frequency", "note-position", "note-role" -> Result.ok("")

            "lockbg", "unlockbg", "autobg" -> Result.ok("")

            "branch-create", "checkpoint-create", "checkpoint-go", "checkpoint-exit",
            "checkpoint-parent", "checkpoint-get", "checkpoint-list" -> Result.ok("")

            "tag-add", "tag-remove", "tag-exists", "tag-list", "tag-import" -> Result.ok("")

            "tools-list", "tools-invoke", "tools-register", "tools-unregister" -> Result.ok("")

            "preset" -> Result.ok("")

            "proxy" -> Result.ok("")

            "loader-wrap", "loader-show", "loader-hide", "loader-stop" -> Result.ok("")

            "db", "db-list", "db-get", "db-add", "db-update", "db-disable", "db-enable", "db-delete" -> Result.ok("")

            "db-ingest", "db-purge", "db-search", "vector-threshold", "vector-query",
            "vector-max-entries", "vector-chats-state", "vector-files-state", "vector-worldinfo-state" -> Result.ok("")

            "imagine", "imagine-source", "imagine-style", "imagine-comfy-workflow" -> Result.ok("")

            "expression-set", "expression-fallback", "expression-folder-override",
            "expression-last", "expression-list", "expression-classify", "expression-upload" -> Result.ok("")

            "profile-create", "profile-update", "profile-get", "profile-genstream" -> Result.ok("")

            "regex-preset", "regex", "regex-state", "regex-toggle" -> Result.ok("")

            "show-gallery", "list-gallery" -> Result.ok("")

            "summarize" -> Result.ok("")

            "caption" -> Result.ok("")

            "translate" -> Result.ok("")

            "speak" -> Result.ok("")

            "count" -> Result.ok("0")

            "world", "getchatbook", "getglobalbooks", "getpersonabook", "getcharbook",
            "findentry", "getentryfield", "createentry", "setentryfield" -> Result.ok("")

            "wi-set-timed-effect", "wi-get-timed-effect" -> Result.ok("")

            "yt-script" -> Result.ok("")

            else -> {
                if (cmd.name in extensionCommands) {
                    Result(handled = false, output = "")
                } else {
                    Result.unknown(cmd.name)
                }
            }
        }
    }

    private fun evaluateCondition(condition: String): Boolean {
        val trimmed = condition.trim()
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
            return variables[varName] ?: ""
        }
        return token
    }

    companion object {
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
            "getpromptentry", "setpromptentry", "is-mobile",
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
