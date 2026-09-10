package app.tellev.core.prompt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object PromptTemplateExpressionEvaluator {
    private val ifStart = Regex("""if\s*\((.*)\)\s*\{\s*""")
    private val forOfStart = Regex("""for\s*\(\s*(?:const|let|var)?\s*([A-Za-z_$][\w$]*)\s+of\s+(.+)\)\s*\{\s*""")
    private val declarationPattern = Regex("""(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(.+)""")
    private val assignmentPattern = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*(.+)""")
    private val printPattern = Regex("""print\s*\((.*)\)""")
    private val functionCallPattern = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\((.*)\)""")
    private val includesPattern = Regex("""(.+)\.includes\s*\((.*)\)""")
    private val comparisonOperators = listOf("===", "!==", ">=", "<=", "==", "!=", ">", "<")

    fun renderTemplate(
        template: String,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): String {
        if (!template.contains("<%")) return template
        javascriptEvaluator?.let { evaluate ->
            val request = buildJsonObject {
                put("template", JsonPrimitive(template))
                put("local", toJsonObject(state.localVariables))
                put("global", toJsonObject(state.globalVariables))
                put("definitions", toJsonObject(state.locals))
                put("context", toJsonObject(mapOf(
                    "user" to state.context.userName, "char" to state.context.characterName,
                    "name1" to state.context.userName, "name2" to state.context.characterName,
                )))
                put("currentWorldBookId", state.currentWorldBookId?.let(::JsonPrimitive) ?: JsonNull)
                put("worldCatalog", JsonArray(state.worldCatalog.map { entry ->
                    toJsonObject(mapOf("id" to entry.id, "comment" to entry.comment,
                        "title" to entry.title, "content" to entry.content, "bookId" to entry.bookId, "bookName" to entry.bookName))
                }))
            }
            val result = evaluate(request)
            fun replace(target: MutableMap<String, Any?>, field: String) {
                val values = result[field] as? JsonObject ?: return
                target.clear()
                values.forEach { (key, value) -> target[key] = toKotlinValue(value) }
            }
            replace(state.localVariables, "local")
            replace(state.globalVariables, "global")
            replace(state.locals, "definitions")
            refreshMergedVariables(state)
            return (result["content"] as? JsonPrimitive)?.content.orEmpty()
        }
        val tokens = PromptTemplateParser.tokenize(template)
        return renderTokens(tokens, 0, tokens.size, state, javascriptEvaluator)
    }

    fun renderTokens(
        tokens: List<TemplateToken>,
        start: Int,
        end: Int,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): String {
        val out = StringBuilder()
        var index = start
        while (index < end) {
            when (val token = tokens[index]) {
                is TemplateToken.Text -> out.append(token.value)
                is TemplateToken.Output -> out.append(stringify(evaluate(token.expression, state, javascriptEvaluator)))
                is TemplateToken.Code -> {
                    val code = PromptTemplateParser.normalizeCode(token.code)
                    when {
                        code.isBlank() || code.startsWith("//") -> Unit
                        PromptTemplateParser.isBlockEnd(code) || PromptTemplateParser.isElseBlock(code) -> Unit
                        ifStart.matchEntire(code) != null -> {
                            val match = ifStart.matchEntire(code)!!
                            val block = PromptTemplateParser.findBlock(tokens, index + 1, end)
                            val condition = truthy(evaluate(match.groupValues[1], state, javascriptEvaluator))
                            out.append(
                                if (condition) {
                                    renderTokens(tokens, index + 1, block.elseIndex ?: block.endIndex, state, javascriptEvaluator)
                                } else if (block.elseIndex != null) {
                                    renderTokens(tokens, block.elseIndex + 1, block.endIndex, state, javascriptEvaluator)
                                } else {
                                    ""
                                },
                            )
                            index = block.endIndex
                        }
                        forOfStart.matchEntire(code) != null -> {
                            val match = forOfStart.matchEntire(code)!!
                            val localName = match.groupValues[1]
                            val source = match.groupValues[2]
                            val block = PromptTemplateParser.findBlock(tokens, index + 1, end)
                            val oldValue = state.locals[localName]
                            val hadOldValue = state.locals.containsKey(localName)
                            for (item in evaluateList(source, state, javascriptEvaluator)) {
                                state.locals[localName] = item
                                out.append(renderTokens(tokens, index + 1, block.endIndex, state, javascriptEvaluator))
                            }
                            if (hadOldValue) {
                                state.locals[localName] = oldValue
                            } else {
                                state.locals.remove(localName)
                            }
                            index = block.endIndex
                        }
                        else -> out.append(executeCode(code, state, javascriptEvaluator))
                    }
                }
            }
            index++
        }
        return out.toString()
    }

    private fun executeCode(
        code: String,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): String {
        val output = StringBuilder()
        val statements = splitTopLevel(code, ';').map { it.trim() }.filter { it.isNotEmpty() }
        for (statement in statements) {
            val normalized = statement.removePrefix("await ").trim()
            val declaration = declarationPattern.matchEntire(normalized)
            if (declaration != null) {
                state.locals[declaration.groupValues[1]] = evaluate(declaration.groupValues[2], state, javascriptEvaluator)
                continue
            }

            val assignment = assignmentPattern.matchEntire(normalized)
            if (assignment != null && !normalized.contains("==")) {
                assign(assignment.groupValues[1], evaluate(assignment.groupValues[2], state, javascriptEvaluator), state)
                continue
            }

            val printCall = printPattern.matchEntire(normalized)
            if (printCall != null) {
                output.append(stringify(evaluate(printCall.groupValues[1], state, javascriptEvaluator)))
                continue
            }

            val value = evaluate(normalized, state, javascriptEvaluator)
            if (value === UnsupportedExpression) {
                state.warn("Unsupported prompt template statement: $normalized")
            }
        }
        return output.toString()
    }

    private fun evaluateList(
        expression: String,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): List<Any?> {
        return when (val value = evaluate(expression, state, javascriptEvaluator)) {
            is List<*> -> value
            is Array<*> -> value.toList()
            is Map<*, *> -> value.values.toList()
            is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            null -> emptyList()
            UnsupportedExpression -> emptyList()
            else -> listOf(value)
        }
    }

    fun evaluate(
        rawExpression: String,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): Any? {
        var expression = rawExpression.trim().removeSuffix(";").trim()
        if (expression.startsWith("await ")) expression = expression.removePrefix("await ").trim()
        expression = stripBalancedParens(expression)

        if (expression.isBlank()) return ""
        if (expression == "true") return true
        if (expression == "false") return false
        if (expression == "null" || expression == "undefined") return null

        if (expression.startsWith("!")) return !truthy(evaluate(expression.drop(1), state, javascriptEvaluator))

        splitByOperator(expression, "??")?.let { (left, right) ->
            val leftValue = evaluate(left, state, javascriptEvaluator)
            return if (leftValue == null || leftValue == "") evaluate(right, state, javascriptEvaluator) else leftValue
        }
        splitByOperator(expression, "||")?.let { (left, right) ->
            val leftValue = evaluate(left, state, javascriptEvaluator)
            return if (truthy(leftValue)) leftValue else evaluate(right, state, javascriptEvaluator)
        }
        splitByOperator(expression, "&&")?.let { (left, right) ->
            val leftValue = evaluate(left, state, javascriptEvaluator)
            return if (truthy(leftValue)) evaluate(right, state, javascriptEvaluator) else leftValue
        }

        for (operator in comparisonOperators) {
            splitByOperator(expression, operator)?.let { (left, right) ->
                return compare(evaluate(left, state, javascriptEvaluator), evaluate(right, state, javascriptEvaluator), operator)
            }
        }

        splitTopLevelByPlus(expression)?.let { parts ->
            val values = parts.map { evaluate(it, state, javascriptEvaluator) }
            if (values.any { it is String }) {
                return values.joinToString("") { stringify(it) }
            }
            return values.sumOf { numeric(it) }
        }

        parseStringLiteral(expression)?.let { return it }
        expression.toLongOrNull()?.let { return it }
        expression.toDoubleOrNull()?.let { return it }

        parseArrayLiteral(expression, state, javascriptEvaluator)?.let { return it }

        includesPattern.matchEntire(expression)?.let { match ->
            val target = stringify(evaluate(match.groupValues[1], state, javascriptEvaluator))
            val needle = stringify(evaluate(match.groupValues[2], state, javascriptEvaluator))
            return target.contains(needle)
        }

        val functionCall = functionCallPattern.matchEntire(expression)
        if (functionCall != null) {
            return callFunction(
                functionCall.groupValues[1],
                splitArguments(functionCall.groupValues[2]).map { evaluate(it, state, javascriptEvaluator) },
                state,
                javascriptEvaluator,
            )
        }

        return resolveReference(expression, state)
    }

    private fun callFunction(
        name: String,
        args: List<Any?>,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): Any? {
        return when (name) {
            "getwi", "getWorldInfo" -> resolveWorldInfo(args, state, javascriptEvaluator)
            // ST-Prompt-Template exposes both lowercase aliases (legacy) and the
            // camelCase scope-explicit family (ejs.ts:279-307); accept both.
            "getvar", "getchatvar", "getLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val fallback = args.getOrNull(1)
                getPath(state.localVariables, key) ?: fallback ?: ""
            }
            "getglobalvar", "getGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val fallback = args.getOrNull(1)
                getPath(state.globalVariables, key) ?: fallback ?: ""
            }
            "setvar", "setchatvar", "setLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                setLocalVariable(state, key, args.getOrNull(1))
                ""
            }
            "setglobalvar", "setGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                setGlobalVariable(state, key, args.getOrNull(1))
                ""
            }
            "incvar", "incLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                val next = numeric(getPath(state.localVariables, key)) + amount
                setLocalVariable(state, key, next)
                formatNumber(next)
            }
            "decvar", "decLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                val next = numeric(getPath(state.localVariables, key)) - amount
                setLocalVariable(state, key, next)
                formatNumber(next)
            }
            "incglobalvar", "incGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                val next = numeric(getPath(state.globalVariables, key)) + amount
                setGlobalVariable(state, key, next)
                formatNumber(next)
            }
            "decglobalvar", "decGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                val next = numeric(getPath(state.globalVariables, key)) - amount
                setGlobalVariable(state, key, next)
                formatNumber(next)
            }
            "delvar", "delLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                deletePath(state.localVariables, key)
                refreshMergedVariables(state)
                ""
            }
            "delGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                deletePath(state.globalVariables, key)
                refreshMergedVariables(state)
                ""
            }
            // ST insertVariable (variables.ts:559): array -> push / splice at
            // index (negative counts from the end); otherwise assign.
            "insvar", "insertLocalVar" -> {
                insertVariable(state.localVariables, args) { path, value -> setLocalVariable(state, path, value) }
            }
            "insertGlobalVar" -> {
                insertVariable(state.globalVariables, args) { path, value -> setGlobalVariable(state, path, value) }
            }
            "String" -> stringify(args.getOrNull(0))
            "Number" -> numeric(args.getOrNull(0))
            "Boolean" -> truthy(args.getOrNull(0))
            "JSON.stringify" -> stringify(args.getOrNull(0))
            "_.get" -> {
                val root = args.getOrNull(0)
                val path = stringify(args.getOrNull(1))
                val fallback = args.getOrNull(2)
                getPath(root, path) ?: fallback ?: ""
            }
            "_.set" -> {
                val root = args.getOrNull(0)
                val path = stringify(args.getOrNull(1))
                if (root === state.variables) {
                    setLocalVariable(state, path, args.getOrNull(2))
                } else if (root is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    setPath(root as MutableMap<String, Any?>, path, args.getOrNull(2))
                }
                root
            }
            "_.has" -> getPath(args.getOrNull(0), stringify(args.getOrNull(1))) != null
            else -> {
                state.warn("Unsupported prompt template function: $name")
                UnsupportedExpression
            }
        }
    }

    private fun resolveWorldInfo(
        args: List<Any?>,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): String {
        val requestedBook = args.getOrNull(0)?.let(::stringify)?.takeIf { it.isNotBlank() }
        val query = args.getOrNull(1)?.let(::stringify)?.trim().orEmpty()
        if (query.isEmpty()) {
            state.warn("getwi requires an entry uid/id/comment/title")
            return ""
        }

        val bookId = requestedBook ?: state.currentWorldBookId
        val candidates = state.worldCatalog.filter { entry ->
            if (bookId == null) true
            else entry.bookId == bookId || entry.bookName == bookId
        }
        val entry = candidates.firstOrNull { candidate ->
            candidate.id == query ||
                candidate.comment == query ||
                candidate.title == query ||
                candidate.raw.stringContent("uid") == query ||
                candidate.raw.stringContent("id") == query ||
                candidate.raw.stringContent("comment") == query ||
                candidate.raw.stringContent("title") == query
        }
        if (entry == null) {
            state.warn("getwi entry not found: book=${bookId ?: "<current>"}, entry=$query")
            return ""
        }

        val recursionKey = "${entry.bookId.orEmpty()}:${entry.id}"
        if (state.worldInfoStack.size >= 16 || recursionKey in state.worldInfoStack) {
            state.warn("getwi recursion stopped: $recursionKey")
            return ""
        }
        state.worldInfoStack += recursionKey
        return try {
            renderTemplate(entry.content, state, javascriptEvaluator)
        } finally {
            state.worldInfoStack.removeAt(state.worldInfoStack.lastIndex)
        }
    }

    private fun JsonObject.stringContent(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun resolveReference(expression: String, state: TemplateState): Any? {
        state.locals[expression]?.let { return it }
        if (state.locals.containsKey(expression)) return state.locals[expression]
        state.variables[expression]?.let { return it }

        return when (expression) {
            "char", "name2", "charName", "characterName" -> state.context.characterName
            "user", "name1", "userName" -> state.context.userName
            "description", "charDescription" -> state.context.characterDescription
            "personality" -> state.context.characterPersonality
            "scenario" -> state.context.characterScenario
            "mes_example", "dialogueExamples" -> state.context.exampleMessages
            "firstMessage" -> state.context.firstMessage
            "lastMessage" -> state.context.lastMessage
            "group" -> state.context.groupMemberNames
            "variables", "vars" -> state.variables
            else -> {
                if (expression.contains('.')) {
                    val rootName = expression.substringBefore('.')
                    val path = expression.substringAfter('.')
                    val root = resolveReference(rootName, state)
                    getPath(root, path) ?: ""
                } else {
                    state.warn("Unsupported prompt template expression: $expression")
                    UnsupportedExpression
                }
            }
        }
    }

    private fun assign(target: String, value: Any?, state: TemplateState) {
        val trimmed = target.trim()
        when {
            trimmed.startsWith("variables.") ->
                setLocalVariable(state, trimmed.removePrefix("variables."), value)
            trimmed.startsWith("vars.") ->
                setLocalVariable(state, trimmed.removePrefix("vars."), value)
            trimmed.contains('.') -> {
                val rootName = trimmed.substringBefore('.')
                val root = resolveReference(rootName, state)
                if (root is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    setPath(root as MutableMap<String, Any?>, trimmed.substringAfter('.'), value)
                }
            }
            else -> state.locals[trimmed] = value
        }
    }

    fun extractVariableScopes(metadata: JsonObject): VariableScopes {
        val localElement = metadata["promptTemplateLocalVariables"]
            ?: metadata["promptTemplateVariables"]
            ?: metadata["variables"]
            ?: metadata["tavernVariables"]
        val globalElement = metadata["promptTemplateGlobalVariables"]

        return VariableScopes(
            local = variableMap(localElement),
            global = variableMap(globalElement),
        )
    }

    private fun variableMap(element: JsonElement?): Map<String, Any?> {
        return (element?.let(::toKotlinValue) as? Map<*, *>)
            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
            ?.toMap()
            ?: emptyMap()
    }

    fun refreshMergedVariables(state: TemplateState) {
        state.variables.clear()
        state.variables.putAll(deepCopyMap(state.globalVariables))
        state.variables.putAll(deepCopyMap(state.localVariables))
    }

    private fun setLocalVariable(state: TemplateState, path: String, value: Any?) {
        setPath(state.localVariables, path, value)
        refreshMergedVariables(state)
    }

    private fun setGlobalVariable(state: TemplateState, path: String, value: Any?) {
        setPath(state.globalVariables, path, value)
        refreshMergedVariables(state)
    }

    fun deepCopyMap(source: Map<String, Any?>): MutableMap<String, Any?> =
        source.mapValuesTo(linkedMapOf()) { (_, value) -> deepCopyValue(value) }

    private fun deepCopyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.mapNotNull { (key, nested) ->
            (key as? String)?.let { it to deepCopyValue(nested) }
        }.toMap(linkedMapOf())
        is List<*> -> value.map(::deepCopyValue)
        else -> value
    }

    fun toJsonObject(values: Map<String, Any?>): JsonObject =
        JsonObject(values.mapValues { (_, value) -> toJsonElement(value) })

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(
            value.entries.mapNotNull { (key, nested) ->
                (key as? String)?.let { it to toJsonElement(nested) }
            }.toMap(),
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        else -> JsonPrimitive(value.toString())
    }

    fun toKotlinValue(element: JsonElement): Any? {
        return when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValues { toKotlinValue(it.value) }.toMutableMap()
            is JsonArray -> element.map { toKotlinValue(it) }
            is JsonPrimitive -> {
                element.booleanOrNull
                    ?: element.longOrNull
                    ?: element.doubleOrNull
                    ?: runCatching { element.content }.getOrNull()
            }
        }
    }

    private fun parseArrayLiteral(
        expression: String,
        state: TemplateState,
        javascriptEvaluator: ((JsonObject) -> JsonObject)? = null,
    ): List<Any?>? {
        if (!expression.startsWith("[") || !expression.endsWith("]")) return null
        val inner = expression.drop(1).dropLast(1)
        if (inner.isBlank()) return emptyList()
        return splitArguments(inner).map { evaluate(it, state, javascriptEvaluator) }
    }

    private fun getPath(root: Any?, path: String): Any? {
        if (path.isBlank()) return root
        var current: Any? = root
        for (part in path.split('.').filter { it.isNotBlank() }) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is List<*> -> part.toIntOrNull()?.let { current.getOrNull(it) }
                else -> return null
            }
        }
        return current
    }

    private fun setPath(root: MutableMap<String, Any?>, path: String, value: Any?) {
        val parts = path.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return
        var current = root
        for (part in parts.dropLast(1)) {
            val next = current[part]
            if (next is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                current = next as MutableMap<String, Any?>
            } else {
                val created = mutableMapOf<String, Any?>()
                current[part] = created
                current = created
            }
        }
        current[parts.last()] = value
    }

    private fun deletePath(root: MutableMap<String, Any?>, path: String): Boolean {
        val parts = path.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        var current: Any? = root
        for (part in parts.dropLast(1)) {
            current = (current as? Map<*, *>)?.get(part) ?: return false
        }
        @Suppress("UNCHECKED_CAST")
        val parent = current as? MutableMap<String, Any?> ?: return false
        return parent.remove(parts.last()) != null
    }

    private fun insertVariable(
        scope: MutableMap<String, Any?>,
        args: List<Any?>,
        setter: (String, Any?) -> Unit,
    ): String {
        val key = stringify(args.getOrNull(0))
        val value = args.getOrNull(1)
        val index = args.getOrNull(2)?.let { stringify(it) }?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val current = getPath(scope, key)
        val list = (current as? List<*>)?.toMutableList()
        if (list != null) {
            if (index == null) {
                list.add(value)
            } else {
                val at = if (index < 0) (list.size + index).coerceAtLeast(0) else index.coerceAtMost(list.size)
                list.add(at, value)
            }
            setter(key, list)
        } else {
            setter(key, value)
        }
        return ""
    }

    private fun compare(left: Any?, right: Any?, operator: String): Boolean {
        return when (operator) {
            "==", "===" -> stringify(left) == stringify(right)
            "!=", "!==" -> stringify(left) != stringify(right)
            ">=" -> numeric(left) >= numeric(right)
            "<=" -> numeric(left) <= numeric(right)
            ">" -> numeric(left) > numeric(right)
            "<" -> numeric(left) < numeric(right)
            else -> false
        }
    }

    private fun truthy(value: Any?): Boolean {
        return when (value) {
            null, UnsupportedExpression -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotBlank() && value != "false" && value != "0"
            is Collection<*> -> value.isNotEmpty()
            is Map<*, *> -> value.isNotEmpty()
            else -> true
        }
    }

    private fun numeric(value: Any?): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            is Boolean -> if (value) 1.0 else 0.0
            else -> 0.0
        }
    }

    private fun stringify(value: Any?): String {
        return when (value) {
            null, UnsupportedExpression -> ""
            is Double -> formatNumber(value)
            is Float -> formatNumber(value.toDouble())
            is Map<*, *> -> value.entries.joinToString(",") { "${it.key}:${stringify(it.value)}" }
            is List<*> -> value.joinToString(",") { stringify(it) }
            else -> value.toString()
        }
    }

    private fun formatNumber(value: Double): String {
        val longValue = value.toLong()
        return if (value == longValue.toDouble()) longValue.toString() else value.toString()
    }

    private fun splitByOperator(expression: String, operator: String): Pair<String, String>? {
        val index = findTopLevelOperator(expression, operator)
        if (index < 0) return null
        return expression.substring(0, index).trim() to expression.substring(index + operator.length).trim()
    }

    private fun splitTopLevelByPlus(expression: String): List<String>? {
        val parts = splitTopLevel(expression, '+')
        return if (parts.size > 1) parts else null
    }

    private fun splitTopLevel(expression: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in expression.indices) {
            val ch = expression[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    quote = null
                }
                continue
            }
            when (ch) {
                '\'', '"', '`' -> quote = ch
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                delimiter -> if (depth == 0) {
                    parts += expression.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += expression.substring(start)
        return parts
    }

    private fun findTopLevelOperator(expression: String, operator: String): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var i = 0
        while (i <= expression.length - operator.length) {
            val ch = expression[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    quote = null
                }
                i++
                continue
            }
            when (ch) {
                '\'', '"', '`' -> quote = ch
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
            if (depth == 0 && expression.startsWith(operator, i)) return i
            i++
        }
        return -1
    }

    private fun splitArguments(arguments: String): List<String> {
        if (arguments.isBlank()) return emptyList()
        return splitTopLevel(arguments, ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseStringLiteral(expression: String): String? {
        if (expression.length < 2) return null
        val quote = expression.first()
        if (quote !in setOf('\'', '"', '`') || expression.last() != quote) return null
        return expression.substring(1, expression.length - 1)
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\`", "`")
            .replace("\\\\", "\\")
    }

    private fun stripBalancedParens(expression: String): String {
        var result = expression
        while (result.startsWith("(") && result.endsWith(")") && wrapsWholeExpression(result)) {
            result = result.drop(1).dropLast(1).trim()
        }
        return result
    }

    private fun wrapsWholeExpression(expression: String): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in expression.indices) {
            val ch = expression[i]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    quote = null
                }
                continue
            }
            when (ch) {
                '\'', '"', '`' -> quote = ch
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i != expression.lastIndex) return false
                }
            }
        }
        return depth == 0
    }
}
