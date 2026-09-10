package app.tellev.core.extension

import app.tellev.core.extension.SlashCommandEngine.Command

internal object SlashCommandConditionEvaluator {

    /**
     * ST-compatible condition evaluation for `/if` and `/while`
     * (variables.js parseBooleanOperands + evalBoolean).
     */
    fun evaluateIfCondition(cmd: Command, variableStore: VariableStore?): Boolean {
        val leftRaw = cmd.namedArgs["left"] ?: cmd.textArgs().firstOrNull { it.isNotBlank() } ?: ""
        val rightRaw = cmd.namedArgs["right"]
        val rule = cmd.namedArgs["rule"] ?: "eq"
        val left = resolveOperand(leftRaw, variableStore)
        val right = rightRaw?.let { resolveOperand(it, variableStore) }

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
    fun resolveOperand(raw: String, variableStore: VariableStore?): String {
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

    fun isTruthyValue(value: String): Boolean =
        value.isNotBlank() && value != "0" && !value.equals("false", ignoreCase = true)

    fun evaluateCondition(condition: String, variableStore: VariableStore?): Boolean {
        val trimmed = condition.trim()
        if (trimmed.isEmpty()) return false

        // Check for comparison operators
        val operators = listOf("==", "!=", ">=", "<=", ">", "<")
        for (op in operators) {
            val idx = trimmed.indexOf(op)
            if (idx > 0) {
                val left = resolveValue(trimmed.substring(0, idx).trim(), variableStore)
                val right = resolveValue(trimmed.substring(idx + op.length).trim(), variableStore)
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
        val value = resolveValue(trimmed, variableStore)
        return value.isNotBlank() && value != "false" && value != "0"
    }

    fun resolveValue(token: String, variableStore: VariableStore?): String {
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
}
