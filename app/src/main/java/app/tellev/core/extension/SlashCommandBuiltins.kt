package app.tellev.core.extension

import app.tellev.core.extension.SlashCommandEngine.Command
import app.tellev.core.extension.SlashCommandEngine.RegisteredCommandRef
import app.tellev.core.extension.SlashCommandEngine.Result

internal class SlashCommandBuiltins(
    private val variableStore: VariableStore?,
    private val extensionCommands: Map<String, RegisteredCommandRef>,
    private val maxLoopIterations: Int,
    private val eventEmitter: ((String, List<String>) -> Unit)?,
    private val onUnimplementedCommand: ((String) -> Unit)?,
    private val reportedStubs: MutableSet<String>,
    private val executeLines: (List<List<Command>>) -> Result,
    private val executeScript: (String) -> Result,
) {

    private fun stubOk(name: String, output: String = ""): Result {
        if (reportedStubs.add(name)) onUnimplementedCommand?.invoke(name)
        return Result.ok(output)
    }

    fun execute(cmd: Command): Result {
        return when (cmd.name) {
            "echo" -> Result.ok(cmd.textArgs().joinToString(" "))

            "noop", "pass", "return" -> Result.ok(cmd.textArgs().joinToString(" "))

            "delay", "wait", "sleep" -> {
                val ms = cmd.textArgs().firstOrNull()?.toLongOrNull() ?: 1000L
                Thread.sleep(ms.coerceAtMost(30_000L))
                Result.ok("")
            }

            "setvar", "setchatvar" -> {
                val name = SlashCommandParser.varName(cmd)
                val value = SlashCommandParser.varValue(cmd)
                if (name.isBlank()) return Result.error("setvar requires a variable name")
                variableStore?.setLocal(name, value)
                Result.ok(value)
            }

            "setglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
                val value = SlashCommandParser.varValue(cmd)
                if (name.isBlank()) return Result.error("setglobalvar requires a variable name")
                variableStore?.setGlobal(name, value)
                Result.ok(value)
            }

            "getvar", "getchatvar", "var" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("getvar requires a variable name")
                Result.ok(variableStore?.getLocal(name) ?: "")
            }

            "getglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("getglobalvar requires a variable name")
                Result.ok(variableStore?.getGlobal(name) ?: "")
            }

            "addvar", "addchatvar" -> {
                val name = SlashCommandParser.varName(cmd)
                val increment = SlashCommandParser.varValue(cmd)
                if (name.isBlank()) return Result.error("addvar requires a variable name")
                Result.ok(variableStore?.addLocal(name, increment) ?: "0")
            }

            "addglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
                val increment = SlashCommandParser.varValue(cmd)
                if (name.isBlank()) return Result.error("addglobalvar requires a variable name")
                Result.ok(variableStore?.addGlobal(name, increment) ?: "0")
            }

            "incvar", "incchatvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("incvar requires a variable name")
                Result.ok(variableStore?.incLocal(name) ?: "0")
            }

            "incglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("incglobalvar requires a variable name")
                Result.ok(variableStore?.incGlobal(name) ?: "0")
            }

            "decvar", "decchatvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("decvar requires a variable name")
                Result.ok(variableStore?.decLocal(name) ?: "0")
            }

            "decglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("decglobalvar requires a variable name")
                Result.ok(variableStore?.decGlobal(name) ?: "0")
            }

            "flushvar", "flushchatvar", "deletevar", "delvar" -> {
                val name = SlashCommandParser.varName(cmd)
                if (name.isBlank()) return Result.error("flushvar requires a variable name")
                variableStore?.deleteLocal(name)
                Result.ok("")
            }

            "flushglobalvar" -> {
                val name = SlashCommandParser.varName(cmd)
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
                val name = SlashCommandParser.varName(cmd)
                Result.ok(if (variableStore?.hasLocal(name) == true) "true" else "false")
            }

            "hasglobalvar", "globalvarexists" -> {
                val name = SlashCommandParser.varName(cmd)
                Result.ok(if (variableStore?.hasGlobal(name) == true) "true" else "false")
            }

            "let" -> {
                val name = SlashCommandParser.varName(cmd)
                val value = SlashCommandParser.varValue(cmd)
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
                    return Result.ok(if (SlashCommandConditionEvaluator.evaluateCondition(condition, variableStore)) "true" else "false")
                }
                // ST form: /if left=x right=y rule=eq {:then:} else={:else:}
                val thenClosure = closures.firstOrNull()
                val elseClosure = if (cmd.namedArgs["else"] == "") closures.getOrNull(1) else null
                if (SlashCommandConditionEvaluator.evaluateIfCondition(cmd, variableStore)) {
                    when {
                        thenClosure != null -> executeLines(thenClosure.lines)
                        else -> {
                            val text = cmd.textArgs().filter { it.isNotBlank() }.joinToString(" ")
                            if (text.isBlank()) Result.ok("") else executeScript(text)
                        }
                    }
                } else {
                    val elseText = cmd.namedArgs["else"]?.takeIf { it.isNotBlank() }
                    when {
                        elseClosure != null -> executeLines(elseClosure.lines)
                        elseText != null -> executeScript(elseText)
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
                    while (SlashCommandConditionEvaluator.evaluateCondition(condition, variableStore) && iterations < maxLoopIterations) {
                        if (bodyText.isNotBlank()) {
                            val r = executeScript(bodyText)
                            if (r.isBreak) break
                            if (r.isAborted || r.isError) return r
                            output = r.output
                        }
                        iterations++
                    }
                    return Result.ok(if (iterations >= maxLoopIterations) "max_iterations" else output)
                }
                // ST form: /while left=x right=y rule=neq guard=100 {:body:}
                val guard = cmd.namedArgs["guard"]?.toIntOrNull() ?: 100
                val cap = minOf(guard, maxLoopIterations)
                var iterations = 0
                var output = ""
                while (SlashCommandConditionEvaluator.evaluateIfCondition(cmd, variableStore) && iterations < cap) {
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
                        val r = executeScript(bodyText)
                        if (r.isBreak) return@repeat
                        if (r.isAborted || r.isError) return r
                        output = r.output
                    }
                }
                Result.ok(output)
            }

            "run", "call", "exec" -> {
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
                    if (text.isBlank()) Result.ok("") else executeScript(text)
                }
            }

            ":" -> {
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
