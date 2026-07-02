package app.tellev.core.prompt

import app.tellev.core.extension.VariableStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

interface MacroEngine {
    fun expand(text: String, context: MacroContext): String
    fun registerCustomMacro(name: String, resolver: (MacroContext) -> String)
}

data class MacroContext(
    val characterName: String = "",
    val userName: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterScenario: String = "",
    val exampleMessages: String = "",
    val firstMessage: String = "",
    val lastMessage: String = "",
    val groupMemberNames: String = "",
    val maxPromptTokens: Int = 0,
    val maxContextTokens: Int = 0,
    val customVariables: Map<String, String> = emptyMap(),
    // ── SillyTavern macro parity (added in step 5 gap-fill) ──
    /** {{persona}} — current user persona description. */
    val personaDescription: String = "",
    /** {{model}} — selected model id for the current provider. */
    val modelName: String = "",
    /** {{maxResponse}} / {{maxResponseTokens}}. */
    val maxResponseTokens: Int = 0,
    /** {{input}} — text currently in the send box. */
    val inputText: String = "",
    /** {{lastUserMessage}} — most recent user-visible message content. */
    val lastUserMessage: String = "",
    /** {{lastCharMessage}} — most recent character/assistant message content. */
    val lastCharMessage: String = "",
    /** {{lastMessageId}} — index of the last message in the chat. */
    val lastMessageId: String = "",
    /** {{greeting::N}} — alternate greetings (index 1+ = alternateGreetings[N-1]). */
    val alternateGreetings: List<String> = emptyList(),
)

class DefaultMacroEngine : MacroEngine {

    // DefaultMacroEngine is shared across requests (constructed once and injected
    // into DefaultPromptEngine), so customMacros is read from build() coroutines
    // while extensions write to it. Use a ConcurrentHashMap for safe concurrent
    // access instead of a plain mutableMapOf.
    private val customMacros = ConcurrentHashMap<String, (MacroContext) -> String>()

    /**
     * Backing store for the SillyTavern variable macros (`{{getvar::}}`,
     * `{{setvar::name::value}}`, ...).  Plugged in by the extension graph;
     * when null the variable macros degrade safely (getters return "",
     * setters are no-ops).
     */
    @Volatile
    var variableStore: VariableStore? = null

    // Matches {{...}} patterns - non-greedy, handles nested colons for transforms
    private val macroPattern = Regex("""\{\{([^{}]+)\}\}""")

    override fun registerCustomMacro(name: String, resolver: (MacroContext) -> String) {
        customMacros[name] = resolver
    }

    override fun expand(text: String, context: MacroContext): String {
        return expandRecursive(text, context, depth = 0)
    }

    private fun expandRecursive(text: String, context: MacroContext, depth: Int): String {
        if (depth >= 10) return text

        val result = macroPattern.replace(text) { matchResult ->
            val macroExpression = matchResult.groupValues[1].trim()
            resolveExpression(macroExpression, context, depth)
        }

        // If expansion changed the text and result still contains macros, recurse
        if (result != text && result.contains("{{") && depth < 9) {
            return expandRecursive(result, context, depth + 1)
        }

        return result
    }

    private fun resolveExpression(expression: String, context: MacroContext, depth: Int): String {
        // Comment macro: {{// comment}} -> empty
        if (expression.startsWith("//")) {
            return ""
        }

        // SillyTavern variable shorthand: {{.name}} / {{$name}} and their operator
        // forms ({{.name++}}, {{.name=value}}, {{.name==value}}, {{$name||default}},
        // ...). Must run before the `::` variable macros and the standard `when`,
        // since these expressions contain no `::` and would otherwise fall through.
        resolveShorthand(expression)?.let { return it }

        // Text transform macros: {{upper:...}}, {{lower:...}}, {{capitalize:...}}
        if (expression.startsWith("upper:")) {
            val inner = expression.removePrefix("upper:")
            val resolved = resolveExpression(inner, context, depth)
            // If inner resolved to a known value (not pass-through), use it; otherwise use literal
            val text = if (resolved == "{{$inner}}") inner else resolved
            return text.uppercase()
        }
        if (expression.startsWith("lower:")) {
            val inner = expression.removePrefix("lower:")
            val resolved = resolveExpression(inner, context, depth)
            val text = if (resolved == "{{$inner}}") inner else resolved
            return text.lowercase()
        }
        if (expression.startsWith("capitalize:")) {
            val inner = expression.removePrefix("capitalize:")
            val resolved = resolveExpression(inner, context, depth)
            val text = if (resolved == "{{$inner}}") inner else resolved
            return text.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
        }

        // Random macros: {{random:N}} or {{random:N-M}} (numeric) and
        // {{random::a::b::c}} (list pick, SillyTavern canonical form).
        if (expression.startsWith("random::")) {
            return resolveRandomList(expression.removePrefix("random::"))
        }
        if (expression.startsWith("random:")) {
            return resolveRandom(expression.removePrefix("random:"))
        }

        // Reverse transform: {{reverse::text}} — SillyTavern's only string transform.
        if (expression.startsWith("reverse::")) {
            val inner = expression.removePrefix("reverse::")
            val resolved = resolveExpression(inner, context, depth)
            // If inner wasn't a recognized macro it resolves to its pass-through
            // form; fall back to the literal text in that case (matches upper:/lower:).
            val text = if (resolved == "{{${inner}}}") inner else resolved
            return text.reversed()
        }

        // Dice roll: {{roll::1d20}}, {{roll::6}}, {{roll::3d6+4}}.
        if (expression.startsWith("roll::")) {
            return resolveRoll(expression.removePrefix("roll::"))
        }

        // Alternate greeting by index: {{greeting::1}} = alternateGreetings[0].
        if (expression.startsWith("greeting::")) {
            val index = expression.removePrefix("greeting::").trim().toIntOrNull()
            return if (index != null && index >= 1) {
                context.alternateGreetings.getOrNull(index - 1) ?: ""
            } else {
                context.firstMessage
            }
        }

        // Whitespace macros with optional repeat count: {{newline}}, {{newline::3}},
        // {{space}}, {{space::4}}, {{noop}}.
        resolveWhitespaceMacro(expression)?.let { return it }

        // datetimeformat macro: {{datetimeformat iso}}
        if (expression.startsWith("datetimeformat")) {
            val format = expression.removePrefix("datetimeformat").trim()
            return resolveDateTimeFormat(format)
        }

        // SillyTavern variable macros: {{getvar::name}}, {{setvar::name::value}}, ...
        resolveVariableMacro(expression)?.let { return it }

        // Bias macro: {{bias "text"}} -> pass through
        if (expression.startsWith("bias ")) {
            return "{{${expression}}}"
        }

        // Trim macro: {{trim}} -> marker for whitespace trimming
        if (expression == "trim") {
            return ""
        }

        // Rollback macro: {{rollback}} -> strip
        if (expression == "rollback") {
            return ""
        }

        // Macro recursive expansion marker
        if (expression == "macro") {
            // This is a recursive expansion trigger - return empty to avoid infinite loop
            return ""
        }

        // Standard macros
        return when (expression) {
            "char", "name2" -> context.characterName
            "user", "name1" -> context.userName
            "description" -> context.characterDescription
            "personality" -> context.characterPersonality
            "scenario" -> context.characterScenario
            "mes_example", "dialogueExamples" -> context.exampleMessages
            "charPrompt" -> "" // System prompt placeholder, handled at higher level
            "charDescription" -> context.characterDescription
            "chatStart" -> "" // Chat start marker, handled at higher level
            "lastMessage" -> context.lastMessage
            "firstMessage" -> context.firstMessage
            "group" -> context.groupMemberNames
            "date" -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            "time" -> LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
            "weekday" -> LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            "isodate" -> LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            "isotime" -> LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
            "maxPrompt", "maxPromptTokens" -> context.maxPromptTokens.toString()
            "maxContext", "maxContextTokens" -> context.maxContextTokens.toString()
            "maxResponse", "maxResponseTokens" -> context.maxResponseTokens.toString()
            "model" -> context.modelName
            "persona" -> context.personaDescription
            "input" -> context.inputText
            "lastUserMessage" -> context.lastUserMessage
            "lastCharMessage" -> context.lastCharMessage
            "lastMessageId" -> context.lastMessageId
            "isMobile" -> "true"
            // Greeting / alternate greetings. {{greeting}} and {{charFirstMessage}}
            // resolve to the main first message; {{greeting::N}} (N>=1) picks the
            // Nth alternate greeting. Handled below for the ::N form.
            "greeting", "charFirstMessage" -> context.firstMessage
            else -> {
                // Check custom variables
                context.customVariables[expression]?.let { return it }
                // Check custom macros
                customMacros[expression]?.let { return it(context) }
                // Unknown macro: pass through unchanged
                "{{${expression}}}"
            }
        }
    }

    private fun resolveRandom(rangeSpec: String): String {
        val trimmed = rangeSpec.trim()
        return if (trimmed.contains("-")) {
            val parts = trimmed.split("-", limit = 2)
            val low = parts[0].trim().toIntOrNull() ?: 0
            val high = parts[1].trim().toIntOrNull() ?: 0
            if (low <= high) {
                Random.nextInt(low, high + 1).toString()
            } else {
                Random.nextInt(high, low + 1).toString()
            }
        } else {
            val max = trimmed.toIntOrNull() ?: 0
            if (max > 0) {
                Random.nextInt(0, max).toString()
            } else {
                "0"
            }
        }
    }

    private fun resolveDateTimeFormat(format: String): String {
        return when (format.lowercase()) {
            "iso" -> {
                val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                val time = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
                "${date}T${time}"
            }
            else -> {
                try {
                    val now = java.time.LocalDateTime.now()
                    now.format(DateTimeFormatter.ofPattern(format))
                } catch (_: Exception) {
                    val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val time = LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME)
                    "${date}T${time}"
                }
            }
        }
    }

    /**
     * SillyTavern variable macros. Local scope: getvar/setvar/addvar/incvar/
     * decvar/deletevar/hasvar (and the getchatvar/setchatvar aliases).
     * Global scope: the `*globalvar` variants. Returns null if [expression]
     * is not a variable macro, so the caller can continue resolution.
     */
    private fun resolveVariableMacro(expression: String): String? {
        val separator = "::"
        val firstSep = expression.indexOf(separator)
        if (firstSep <= 0) return null
        val head = expression.substring(0, firstSep).lowercase()
        val rest = expression.substring(firstSep + separator.length)

        // Commands that take only a name.
        return when (head) {
            "getvar", "getchatvar" -> {
                variableStore?.getLocal(rest.trim()) ?: ""
            }
            "getglobalvar" -> {
                variableStore?.getGlobal(rest.trim()) ?: ""
            }
            "incvar", "incchatvar" -> {
                variableStore?.incLocal(rest.trim()) ?: "0"
            }
            "incglobalvar" -> {
                variableStore?.incGlobal(rest.trim()) ?: "0"
            }
            "decvar", "decchatvar" -> {
                variableStore?.decLocal(rest.trim()) ?: "0"
            }
            "decglobalvar" -> {
                variableStore?.decGlobal(rest.trim()) ?: "0"
            }
            "deletevar", "delvar" -> {
                variableStore?.deleteLocal(rest.trim()); ""
            }
            "deleteglobalvar", "delglobalvar" -> {
                variableStore?.deleteGlobal(rest.trim()); ""
            }
            "hasvar", "varexists" -> {
                if (variableStore?.hasLocal(rest.trim()) == true) "true" else "false"
            }
            "hasglobalvar", "globalvarexists" -> {
                if (variableStore?.hasGlobal(rest.trim()) == true) "true" else "false"
            }
            "setvar", "setchatvar", "setglobalvar",
            "addvar", "addchatvar", "addglobalvar" -> {
                // name::value — value may itself contain "::", so split only once.
                val nameEnd = rest.indexOf(separator)
                val name: String
                val value: String
                if (nameEnd < 0) {
                    name = rest.trim()
                    value = ""
                } else {
                    name = rest.substring(0, nameEnd).trim()
                    value = rest.substring(nameEnd + separator.length)
                }
                when (head) {
                    "setvar", "setchatvar" -> { variableStore?.setLocal(name, value); "" }
                    "setglobalvar" -> { variableStore?.setGlobal(name, value); "" }
                    "addvar", "addchatvar" -> variableStore?.addLocal(name, value) ?: "0"
                    "addglobalvar" -> variableStore?.addGlobal(name, value) ?: "0"
                    else -> ""
                }
            }
            else -> null
        }
    }

    /**
     * SillyTavern variable shorthand. `.` prefixes local scope, `$` prefixes
     * global scope. Supports get, `++`/`--`, `=`/`+=`/`-=` assignment, `==`/`!=`
     * and `>`/`<`/`>=`/`<=` comparison, `||`/`??` defaulting, and `||=`/`??=`
     * conditional assignment. Returns null when [expression] is not a shorthand
     * so the caller continues resolution.
     */
    private fun resolveShorthand(expression: String): String? {
        if (expression.isEmpty()) return null
        val scopeChar = expression[0]
        if (scopeChar != '.' && scopeChar != '$') return null
        val scopeIsGlobal = scopeChar == '$'
        val body = expression.substring(1)
        // Name = leading identifier; everything after is operator + operand.
        val nameMatch = Regex("""^[A-Za-z_][A-Za-z0-9_]*""").find(body) ?: return null
        val name = nameMatch.value
        if (name.isEmpty()) return null
        val op = body.substring(nameMatch.range.last + 1)

        fun get(): String =
            if (scopeIsGlobal) variableStore?.getGlobal(name) ?: "" else variableStore?.getLocal(name) ?: ""

        fun set(value: String): String {
            if (scopeIsGlobal) variableStore?.setGlobal(name, value) else variableStore?.setLocal(name, value)
            return value
        }

        fun add(delta: String): String =
            if (scopeIsGlobal) variableStore?.addGlobal(name, delta) ?: "0"
            else variableStore?.addLocal(name, delta) ?: "0"

        return when {
            op.isEmpty() -> get()
            op == "++" -> if (scopeIsGlobal) variableStore?.incGlobal(name) ?: "0" else variableStore?.incLocal(name) ?: "0"
            op == "--" -> if (scopeIsGlobal) variableStore?.decGlobal(name) ?: "0" else variableStore?.decLocal(name) ?: "0"
            // Multi-char operators must precede their single-char prefixes so
            // e.g. `==` is not swallowed by the bare `=` assignment branch.
            op.startsWith("+=") -> add(op.substring(2))
            op.startsWith("-=") -> add("-" + op.substring(2))
            op.startsWith("==") -> compareEq(get(), op.substring(2))
            op.startsWith("!=") -> if (compareEq(get(), op.substring(2)) == "true") "false" else "true"
            op.startsWith(">=") -> compareCmp(get(), op.substring(2), ">=")
            op.startsWith("<=") -> compareCmp(get(), op.substring(2), "<=")
            op.startsWith("||=") -> {
                val operand = op.substring(3)
                if (isFalsy(get())) set(operand) else get()
            }
            op.startsWith("??=") -> {
                val operand = op.substring(3)
                if (variableStore == null || !has(name, scopeIsGlobal)) set(operand) else get()
            }
            op.startsWith("||") -> {
                val operand = op.substring(2)
                val current = get()
                if (isFalsy(current)) operand else current
            }
            op.startsWith("??") -> {
                val operand = op.substring(2)
                if (variableStore == null || !has(name, scopeIsGlobal)) operand else get()
            }
            op.startsWith(">") -> compareCmp(get(), op.substring(1), ">")
            op.startsWith("<") -> compareCmp(get(), op.substring(1), "<")
            op.startsWith("=") -> set(op.substring(1))
            else -> null
        }
    }

    private fun has(name: String, global: Boolean): Boolean =
        if (global) variableStore?.hasGlobal(name) == true else variableStore?.hasLocal(name) == true

    private fun isFalsy(value: String): Boolean =
        value.isEmpty() || value == "0" || value.equals("false", ignoreCase = true) || value == "null"

    private fun compareEq(a: String, b: String): String {
        val la = a.toLongOrNull()
        val lb = b.toLongOrNull()
        val equal = if (la != null && lb != null) la == lb else a == b
        return if (equal) "true" else "false"
    }

    /**
     * Ordered comparison. Numeric when both sides parse as Long; lexicographic
     * when neither does; mixed (one numeric, one not) is not comparable → false.
     */
    private fun compareCmp(a: String, b: String, op: String): String {
        val la = a.toLongOrNull()
        val lb = b.toLongOrNull()
        val result = when {
            la != null && lb != null -> when (op) {
                ">=" -> la >= lb; "<=" -> la <= lb; ">" -> la > lb; "<" -> la < lb; else -> false
            }
            la == null && lb == null -> {
                val c = a.compareTo(b)
                when (op) {
                    ">=" -> c >= 0; "<=" -> c <= 0; ">" -> c > 0; "<" -> c < 0; else -> false
                }
            }
            else -> false
        }
        return if (result) "true" else "false"
    }

    private fun resolveRandomList(spec: String): String {
        // {{random::a::b::c}} — pick one item, splitting on `::`.
        val items = spec.split("::").filter { it.isNotEmpty() }
        if (items.isEmpty()) return ""
        return items[Random.nextInt(items.size)]
    }

    private fun resolveRoll(spec: String): String {
        // Supports: `d20`, `1d20`, `3d6`, `3d6+4`, `6` (bare count = 1dN).
        val trimmed = spec.trim()
        val dice = Regex("""^(\d*)d(\d+)([+-]\d+)?$""", RegexOption.IGNORE_CASE)
        val bare = trimmed.toIntOrNull()
        return when {
            dice.matches(trimmed) -> {
                val g = dice.find(trimmed)!!.groupValues
                val count = g[1].ifEmpty { "1" }.toInt()
                val sides = g[2].toInt()
                val mod = g[3].ifEmpty { "0" }.toInt()
                var total = 0
                repeat(count) { total += Random.nextInt(1, sides + 1) }
                (total + mod).toString()
            }
            bare != null && bare > 0 -> Random.nextInt(1, bare + 1).toString()
            else -> "0"
        }
    }

    private fun resolveWhitespaceMacro(expression: String): String? {
        return when {
            expression == "newline" -> "\n"
            expression.startsWith("newline::") -> {
                val n = expression.removePrefix("newline::").trim().toIntOrNull() ?: 1
                "\n".repeat(n.coerceAtLeast(0))
            }
            expression == "space" -> " "
            expression.startsWith("space::") -> {
                val n = expression.removePrefix("space::").trim().toIntOrNull() ?: 1
                " ".repeat(n.coerceAtLeast(0))
            }
            expression == "noop" -> ""
            else -> null
        }
    }
}
