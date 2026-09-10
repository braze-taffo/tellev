package app.tellev.core.extension

import app.tellev.core.extension.SlashCommandEngine.Command

internal sealed interface SlashCommandToken {
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
    ) : SlashCommandToken
    data object ClosureStart : SlashCommandToken
    data object ClosureEnd : SlashCommandToken
    data object Pipe : SlashCommandToken
    data object PipeBreak : SlashCommandToken
    data object Newline : SlashCommandToken
}

internal object SlashCommandParser {

    /**
     * Tokenize the whole script into a stream. Quotes are stripped (and the
     * token marked quoted so named-arg detection can ignore quoted `=`), `{:`
     * and `:}` become closure delimiters, `|`/`||` pipe separators.
     */
    fun tokenizeScript(script: String): List<SlashCommandToken> {
        val tokens = mutableListOf<SlashCommandToken>()
        val current = StringBuilder()
        var inQuote: Char? = null
        var unquotedLen = 0
        var sawQuote = false
        var i = 0

        fun flushWord() {
            if (current.isNotEmpty() || sawQuote) {
                tokens.add(
                    SlashCommandToken.Word(
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
                    tokens.add(SlashCommandToken.ClosureStart)
                    i++
                }
                c == ':' && i + 1 < script.length && script[i + 1] == '}' -> {
                    flushWord()
                    tokens.add(SlashCommandToken.ClosureEnd)
                    i++
                }
                c == '|' -> {
                    flushWord()
                    if (i + 1 < script.length && script[i + 1] == '|') {
                        tokens.add(SlashCommandToken.PipeBreak)
                        i++
                    } else {
                        tokens.add(SlashCommandToken.Pipe)
                    }
                }
                c == '\n' -> {
                    flushWord()
                    tokens.add(SlashCommandToken.Newline)
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

    class ScriptParser(private val tokens: List<SlashCommandToken>) {
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
                    is SlashCommandToken.Newline -> {
                        pos++
                        // A newline only ends the statement when the chain is
                        // actually over. Real scripts are written one command
                        // per line with the `|` either trailing the previous
                        // line or leading the next one; treating every newline
                        // as a break severed those chains, so `{{pipe}}` was
                        // emitted literally and upstream output was dropped.
                        var lookahead = pos
                        while (lookahead < tokens.size && tokens[lookahead] is SlashCommandToken.Newline) lookahead++
                        val continuesChain = lookahead < tokens.size &&
                            (tokens[lookahead] is SlashCommandToken.Pipe || tokens[lookahead] is SlashCommandToken.PipeBreak)
                        if (!continuesChain && currentLine.isNotEmpty()) {
                            lines.add(currentLine)
                            currentLine = mutableListOf()
                            expectCommand = true
                        }
                    }
                    is SlashCommandToken.Pipe, is SlashCommandToken.PipeBreak -> {
                        pos++
                        // Skip newlines between `|` and the next command.
                        while (pos < tokens.size && tokens[pos] is SlashCommandToken.Newline) pos++
                        expectCommand = true
                    }
                    is SlashCommandToken.ClosureEnd -> {
                        pos++
                        if (stopOnClosureEnd) {
                            if (currentLine.isNotEmpty()) lines.add(currentLine)
                            return lines
                        }
                        // Stray `:}` — ignore.
                    }
                    is SlashCommandToken.Word -> {
                        if (expectCommand && token.text.startsWith("/")) {
                            pos++
                            currentLine.add(parseCommand(token.text.removePrefix("/")))
                            expectCommand = false
                        } else {
                            // Stray word outside a command — skip (ST would error).
                            pos++
                        }
                    }
                    is SlashCommandToken.ClosureStart -> {
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
                    is SlashCommandToken.Word -> {
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
                    is SlashCommandToken.ClosureStart -> {
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

    /**
     * Pipe semantics, following SlashCommandClosure.substituteUnnamedArgument
     * (:555-562): `{{pipe}}` is replaced wherever it appears in any argument,
     * and otherwise the previous output is injected **only when the command
     * carries no unnamed argument of its own**. Appending unconditionally —
     * what tellev used to do — changed the argument list of every command in a
     * chain, so `/echo hello | /echo world` printed `world hello` instead of
     * `world` and any command reading args by index was shifted.
     */
    fun applyPipe(cmd: Command, pipeInput: String): Command {
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
    fun expandMacros(cmd: Command, macroExpander: ((String) -> String)?): Command {
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
    fun varName(cmd: Command): String =
        cmd.namedArgs["key"] ?: cmd.namedArgs["name"] ?: cmd.textArgs().firstOrNull() ?: ""

    fun varValue(cmd: Command): String {
        cmd.namedArgs["value"]?.let { return it }
        val positional = cmd.textArgs()
        val nameWasNamed = cmd.namedArgs.containsKey("key") || cmd.namedArgs.containsKey("name")
        return if (nameWasNamed) {
            positional.joinToString(" ")
        } else {
            positional.drop(1).joinToString(" ")
        }
    }
}
