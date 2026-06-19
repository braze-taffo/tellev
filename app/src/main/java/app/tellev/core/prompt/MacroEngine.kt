package app.tellev.core.prompt

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
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
)

class DefaultMacroEngine : MacroEngine {

    private val customMacros = mutableMapOf<String, (MacroContext) -> String>()

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

        // Random macros: {{random:N}} or {{random:N-M}}
        if (expression.startsWith("random:")) {
            return resolveRandom(expression.removePrefix("random:"))
        }

        // datetimeformat macro: {{datetimeformat iso}}
        if (expression.startsWith("datetimeformat")) {
            val format = expression.removePrefix("datetimeformat").trim()
            return resolveDateTimeFormat(format)
        }

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
            "maxPrompt" -> context.maxPromptTokens.toString()
            "maxContext" -> context.maxContextTokens.toString()
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
}
