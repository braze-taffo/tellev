package app.tellev.core.prompt

import kotlinx.serialization.json.Json
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
        isolated: Boolean = false,
    ): String {
        if (!template.contains("<%")) return template
        javascriptEvaluator?.let { bridge ->
            val request = buildJsonObject {
                put("template", JsonPrimitive(template))
                put("local", toJsonObject(state.localVariables))
                put("global", toJsonObject(state.globalVariables))
                put("messageVariables", toJsonObject(state.messageVariables))
                put("definitions", toJsonObject(state.locals))
                put("context", toJsonObject(templateContextMap(state)))
                put("currentWorldBookId", state.currentWorldBookId?.let(::JsonPrimitive) ?: JsonNull)
                put("isolated", JsonPrimitive(isolated))
                put("chat", JsonArray(state.chatMessages.map { message ->
                    buildJsonObject {
                        put("id", JsonPrimitive(message.id))
                        put("is_user", JsonPrimitive(message.isUser))
                        put("is_system", JsonPrimitive(message.isSystem))
                        message.name?.let { put("name", JsonPrimitive(it)) }
                        put("mes", JsonPrimitive(message.content))
                    }
                }))
                put("worldCatalog", JsonArray(state.worldCatalog.map { entry ->
                    toJsonObject(mapOf("id" to entry.id, "comment" to entry.comment,
                        "title" to entry.title, "content" to entry.content, "bookId" to entry.bookId, "bookName" to entry.bookName))
                }))
            }
            val result = bridge.evaluate(request)
            fun replace(target: MutableMap<String, Any?>, field: String) {
                val values = result[field] as? JsonObject ?: return
                target.clear()
                values.forEach { (key, value) -> target[key] = toKotlinValue(value) }
            }
            replace(state.localVariables, "local")
            replace(state.globalVariables, "global")
            replace(state.messageVariables, "message")
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
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
            // JS ?? only falls through on null/undefined — empty strings pass.
            return if (leftValue == null) evaluate(right, state, javascriptEvaluator) else leftValue
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
        parseObjectLiteral(expression, state, javascriptEvaluator)?.let { return it }

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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
    ): Any? {
        return when (name) {
            "getwi", "getWorldInfo" -> resolveWorldInfo(args, state, javascriptEvaluator)
            // ST-Prompt-Template variables.ts family. Second arguments are
            // OPTIONS (string shorthand / map), not default values — ST only
            // accepts defaults via {defaults: ...}. Scope defaults follow ST:
            // reads default to the merged cache, writes default to message.
            "getvar", "getchatvar" -> {
                val key = stringify(args.getOrNull(0))
                val hints = varOptionHints(args.getOrNull(1))
                getPath(scopeMap(state, hints.scope), key) ?: hints.defaults
            }
            "getLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val hints = varOptionHints(args.getOrNull(1))
                getPath(state.localVariables, key) ?: hints.defaults
            }
            "getglobalvar", "getGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val hints = varOptionHints(args.getOrNull(1))
                getPath(state.globalVariables, key) ?: hints.defaults
            }
            "getMessageVar" -> {
                val key = stringify(args.getOrNull(0))
                val hints = varOptionHints(args.getOrNull(1))
                getPath(state.messageVariables, key) ?: hints.defaults
            }
            "setvar" -> {
                val key = stringify(args.getOrNull(0))
                setVariableScoped(state, key, args.getOrNull(1), varOptionHints(args.getOrNull(2)))
            }
            "setchatvar", "setLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                setVariableScoped(state, key, args.getOrNull(1), varOptionHints(args.getOrNull(2)), forcedScope = "local")
            }
            "setglobalvar", "setGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                setVariableScoped(state, key, args.getOrNull(1), varOptionHints(args.getOrNull(2)), forcedScope = "global")
            }
            "setMessageVar" -> {
                val key = stringify(args.getOrNull(0))
                setVariableScoped(state, key, args.getOrNull(1), varOptionHints(args.getOrNull(2)), forcedScope = "message")
            }
            "incvar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)))
            }
            "incLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "local")
            }
            "incglobalvar", "incGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "global")
            }
            "incMessageVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "message")
            }
            "decvar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), decrease = true)
            }
            "decLocalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "local", decrease = true)
            }
            "decglobalvar", "decGlobalVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "global", decrease = true)
            }
            "decMessageVar" -> {
                val key = stringify(args.getOrNull(0))
                val amount = args.getOrNull(1)?.let { numeric(it) } ?: 1.0
                changeVariableScoped(state, key, amount, varOptionHints(args.getOrNull(2)), forcedOutscope = "message", decrease = true)
            }
            "delvar" -> {
                deleteVariableScoped(state, stringify(args.getOrNull(0)), varOptionHints(args.getOrNull(1)))
                ""
            }
            "delLocalVar" -> {
                deleteVariableScoped(state, stringify(args.getOrNull(0)), varOptionHints(args.getOrNull(1)), forcedScope = "local")
                ""
            }
            "delGlobalVar" -> {
                deleteVariableScoped(state, stringify(args.getOrNull(0)), varOptionHints(args.getOrNull(1)), forcedScope = "global")
                ""
            }
            "delMessageVar" -> {
                deleteVariableScoped(state, stringify(args.getOrNull(0)), varOptionHints(args.getOrNull(1)), forcedScope = "message")
                ""
            }
            // ST insertVariable (variables.ts:559): array -> push / splice at
            // index (negative counts from the end); otherwise assign.
            "insvar" -> {
                val hints = varOptionHints(args.getOrNull(3))
                insertVariable(scopeMap(state, hints.scope ?: "message"), args) { path, value ->
                    setVariableScoped(state, path, value, VarOptionHints(scope = hints.scope ?: "message"))
                }
            }
            "insertLocalVar" -> {
                insertVariable(state.localVariables, args) { path, value ->
                    setVariableScoped(state, path, value, VarOptionHints(scope = "local"))
                }
            }
            "insertGlobalVar" -> {
                insertVariable(state.globalVariables, args) { path, value ->
                    setVariableScoped(state, path, value, VarOptionHints(scope = "global"))
                }
            }
            "insertMessageVar" -> {
                insertVariable(state.messageVariables, args) { path, value ->
                    setVariableScoped(state, path, value, VarOptionHints(scope = "message"))
                }
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
            // ST-Prompt-Template inject.ts parity; the WebView path implements
            // the same trio in template.js (see PromptInjectedRegistry docs).
            "injectPrompt" -> {
                val key = stringify(args.getOrNull(0))
                val prompt = stringify(args.getOrNull(1))
                // Coerce numeric strings like JS arithmetic does ("2" → 2).
                val order = args.getOrNull(2)?.let { numeric(it).toInt() } ?: 100
                val sticky = args.getOrNull(3)?.let { numeric(it).toInt() } ?: 0
                val uid = args.getOrNull(4)?.let { stringify(it) } ?: ""
                PromptInjectedRegistry.inject(key, prompt, order, sticky, uid)
                ""
            }
            "getPromptsInjected" -> {
                val key = stringify(args.getOrNull(0))
                // ST generate-phase default (inject.ts:56 + handler.ts:247):
                // outlet on, collected by the end-of-build scan.
                val outlet = args.getOrNull(2)?.let { truthy(it) } ?: true
                if (outlet) {
                    "{{outletPromptsInjected:${key}}}"
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val postprocess = (args.getOrNull(1) as? List<Any?>)?.mapNotNull { pp ->
                        (pp as? Map<*, *>)?.let { m ->
                            val search = m["search"]
                            val replace = stringify(m["replace"] ?: "")
                            when (search) {
                                is Regex -> search to replace
                                null -> null
                                else -> stringify(search) to replace
                            }
                        }
                    } ?: emptyList()
                    PromptInjectedRegistry.get(key, postprocess)
                }
            }
            "hasPromptsInjected" -> PromptInjectedRegistry.has(stringify(args.getOrNull(0)))
            "SillyTavern.getContext" -> emptyMap<String, Any?>()
            "execute" -> ""
            // ST-Prompt-Template json-patch.ts / chat.ts parity; the WebView
            // path implements the same functions in template.js.
            "parseJSON" -> parseJsonTolerant(stringify(args.getOrNull(0)))
            "jsonPatch" -> applyJsonPatch(args.getOrNull(0), args.getOrNull(1))
            "patchVariables" -> {
                val key = stringify(args.getOrNull(0))
                val hints = varOptionHints(args.getOrNull(2))
                val doc = getPath(scopeMap(state, hints.scope), key) ?: linkedMapOf<String, Any?>()
                val change = args.getOrNull(1)
                val patch = if (change is String) parseJsonTolerant(change) else change
                setVariableScoped(state, key, applyJsonPatch(doc, patch), hints)
            }
            "getChatMessage" -> {
                val index = args.getOrNull(0)?.let { numeric(it).toInt() } ?: 0
                val role = args.getOrNull(1)?.let { stringify(it) }
                chatMessages(state, role).getOrNull(if (index > -1) index else chatMessages(state, role).size + index)
                    ?.content.orEmpty()
            }
            "getChatMessages" -> {
                // ST overloads: (count) / (count, role) / (start, end) / (start, end, role).
                // Returns a list of contents; <%= %> renders it comma-joined
                // like Array.toString.
                val role = (args.getOrNull(1) as? String) ?: args.getOrNull(2)?.let { stringify(it) }
                val messages = chatMessages(state, role)
                val first = args.getOrNull(0)?.let { numeric(it).toInt() } ?: messages.size
                val end = args.getOrNull(1) as? Number
                sliceChat(messages, first, end?.toInt()).map { it.content }
            }
            "matchChatMessages" -> {
                val patterns = when (val pattern = args.getOrNull(0)) {
                    is List<*> -> pattern.map { stringify(it) }
                    null -> emptyList()
                    else -> listOf(stringify(pattern))
                }
                val options = args.getOrNull(1) as? Map<*, *>
                val role = options?.get("role")?.let { stringify(it) }
                val messages = chatMessages(state, role)
                val window = sliceChat(
                    messages,
                    (options?.get("start") as? Number)?.toInt() ?: -2,
                    (options?.get("end") as? Number)?.toInt(),
                ).map { it.content }
                val and = options?.get("and") == true
                window.any { message ->
                    if (and) patterns.all { message.contains(it) }
                    else patterns.any { message.contains(it) }
                }
            }
            else -> {
                state.warn("Unsupported prompt template function: $name")
                UnsupportedExpression
            }
        }
    }

    private fun resolveWorldInfo(
        args: List<Any?>,
        state: TemplateState,
        javascriptEvaluator: PromptTemplateJsBridge? = null,
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

    /**
     * ST-Prompt-Template prepareContext (ejs.ts:211) constants that Tellev can
     * source from existing data. These become bare identifiers inside EJS
     * (`_with` scope); a missing name is a hard ReferenceError, so every name
     * a card might reference is declared even when the value has to be null or
     * empty. Names Tellev cannot source meaningfully (faker, SillyTavern
     * internals, the execute STscript runner) are stubbed JS-side in
     * template.js.
     */
    private fun templateContextMap(state: TemplateState): Map<String, Any?> = mapOf(
        "user" to state.context.userName,
        "name1" to state.context.userName,
        "userName" to state.context.userName,
        "char" to state.context.characterName,
        "name2" to state.context.characterName,
        "charName" to state.context.characterName,
        "assistantName" to state.context.characterName,
        "lastMessage" to state.context.lastMessage,
        "lastUserMessage" to state.context.lastUserMessage,
        "lastCharMessage" to state.context.lastCharMessage,
        "lastMessageId" to (state.context.lastMessageId.toIntOrNull() ?: state.context.lastMessageId),
        "lastUserMessageId" to state.context.lastUserMessageId,
        "lastCharMessageId" to state.context.lastCharMessageId,
        "characterId" to state.context.characterId,
        "chatId" to "",
        "charAvatar" to "",
        "userAvatar" to "",
        "model" to state.context.modelName,
        "runType" to "generate",
        "generateType" to "normal",
        // ST binds the char-embedded lorebook name; Tellev's currentWorldBookId
        // is the same book — but only when the card actually ships one
        // (embeddedCharacterBookId synthesizes an id even for bookless cards).
        "charLoreBook" to state.currentWorldBookId
            ?.takeIf { id -> state.worldCatalog.any { it.bookId == id } },
        "userLoreBook" to null,
        "chatLoreBook" to null,
        "groups" to emptyList<Any?>(),
        "groupId" to "",
    )

    private fun JsonObject.stringContent(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun resolveReference(expression: String, state: TemplateState): Any? {
        state.locals[expression]?.let { return it }
        if (state.locals.containsKey(expression)) return state.locals[expression]
        state.variables[expression]?.let { return it }

        return when (expression) {
            "char", "name2", "charName", "characterName" -> state.context.characterName
            "user", "name1", "userName" -> state.context.userName
            "assistantName" -> state.context.characterName
            "description", "charDescription" -> state.context.characterDescription
            "personality" -> state.context.characterPersonality
            "scenario" -> state.context.characterScenario
            "mes_example", "dialogueExamples" -> state.context.exampleMessages
            "firstMessage" -> state.context.firstMessage
            "lastMessage" -> state.context.lastMessage
            "lastUserMessage" -> state.context.lastUserMessage
            "lastCharMessage" -> state.context.lastCharMessage
            "lastMessageId" -> (state.context.lastMessageId.toIntOrNull() ?: state.context.lastMessageId)
            "lastUserMessageId" -> state.context.lastUserMessageId
            "lastCharMessageId" -> state.context.lastCharMessageId
            "characterId" -> state.context.characterId
            "chatId", "charAvatar", "userAvatar" -> ""
            "faker" -> null
            "model" -> state.context.modelName
            "runType" -> "generate"
            "generateType" -> "normal"
            "charLoreBook" -> state.currentWorldBookId
                ?.takeIf { id -> state.worldCatalog.any { it.bookId == id } }
            "userLoreBook", "chatLoreBook" -> null
            "groups" -> emptyList<Any?>()
            "groupId" -> ""
            // ST exposes SillyTavern.getContext(); Tellev stubs it to a
            // permissive empty context (template.js does the same on WebView).
            "SillyTavern" -> emptyMap<String, Any?>()
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

    /** Initial message-scope variables for the current generation. */
    fun messageVariableMap(element: JsonObject?): Map<String, Any?> = variableMap(element)

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
        // ST merges the message layer on top (variables.ts:52-59): message >
        // local > global. Floors' own snapshots are not tracked per message.
        state.variables.putAll(deepCopyMap(state.messageVariables))
    }

    // ── ST variables.ts option/scope model ───────────────────────────────

    private fun chatMessages(state: TemplateState, role: String?): List<PromptTemplateChatMessage> =
        state.chatMessages.filter { message ->
            when (role) {
                null -> true
                "user" -> message.isUser
                "system" -> message.isSystem
                "assistant" -> !message.isUser && !message.isSystem
                else -> true
            }
        }

    /** ST slice semantics: positive = from start, negative = from end; (start, end) range. */
    private fun sliceChat(
        messages: List<PromptTemplateChatMessage>,
        startOrCount: Int,
        end: Int?,
    ): List<PromptTemplateChatMessage> = when {
        startOrCount > 0 && end != null -> messages.sliceSafe(startOrCount, end)
        startOrCount > 0 -> messages.take(startOrCount)
        startOrCount < 0 && end != null -> messages.sliceSafe(messages.size + startOrCount, messages.size + end)
        startOrCount < 0 -> messages.takeLast(-startOrCount)
        else -> emptyList()
    }

    private fun <T> List<T>.sliceSafe(start: Int, end: Int): List<T> {
        val from = start.coerceIn(0, size)
        val to = end.coerceIn(from, size)
        return subList(from, to)
    }

    /**
     * Tolerant JSON parse (ST uses the jsonrepair lib): strict first, then
     * trailing-comma / unquoted-key / single-quote repairs, each validated by
     * a full reparse before acceptance.
     */
    private fun parseJsonTolerant(text: String): Any? {
        val trimmed = text.trim()
        try {
            return toKotlinValue(Json.parseToJsonElement(trimmed))
        } catch (_: Exception) {}
        val noTrailing = trimmed.replace(Regex(",\\s*([}\\]])"), "$1")
        val unquotedKeys = noTrailing.replace(Regex("([{,]\\s*)([A-Za-z_$][\\w$]*)\\s*:"), "$1\"$2\":")
        val singleQuoted = unquotedKeys.replace(Regex("'((?:[^'\"\\\\]|\\\\.)*)'"), "\"$1\"")
        for (attempt in listOf(noTrailing, unquotedKeys, singleQuoted)) {
            try {
                return toKotlinValue(Json.parseToJsonElement(attempt))
            } catch (_: Exception) {}
        }
        throw IllegalArgumentException("parseJSON: unable to repair input")
    }

    /** RFC 6902 JSON Patch over Kotlin maps/lists (ST jsonPatch, lodash-backed). */
    private fun applyJsonPatch(doc: Any?, patches: Any?): Any? {
        val working = deepCopyValue(doc)
        val operations = patches as? List<*> ?: return working
        for (operation in operations) {
            val patch = operation as? Map<*, *> ?: continue
            val op = patch["op"] as? String ?: continue
            val path = patch["path"]?.toString() ?: continue
            val fromPath = patch["from"]?.toString()
            when (op) {
                "add", "replace", "set", "assign" -> setPointer(working, path, patch["value"])
                "remove" -> removePointer(working, path)
                "move" -> {
                    val from = fromPath ?: continue
                    val moved = getPointer(working, from)
                    if (moved != null) {
                        removePointer(working, from)
                        setPointer(working, path, moved)
                    }
                }
                "copy" -> {
                    val from = fromPath ?: continue
                    getPointer(working, from)?.let { setPointer(working, path, it) }
                }
                "test" -> {
                    // ST: test failure aborts and returns the ORIGINAL document.
                    if (!jsonEquals(getPointer(working, path), patch["value"])) return doc
                }
            }
        }
        return working
    }

    private fun pointerSegments(pointer: String): List<String> {
        if (pointer.isEmpty()) return emptyList()
        if (!pointer.startsWith("/")) throw IllegalArgumentException("Invalid JSON Pointer: must start with \"/\".")
        return pointer.substring(1).split("/").map { it.replace("~1", "/").replace("~0", "~") }
    }

    private fun getPointer(doc: Any?, pointer: String): Any? {
        var current: Any? = doc
        for (segment in pointerSegments(pointer)) {
            current = when (current) {
                is Map<*, *> -> current[segment]
                is List<*> -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> return null
            }
        }
        return current
    }

    private fun setPointer(doc: Any?, pointer: String, value: Any?) {
        val segments = pointerSegments(pointer)
        if (segments.isEmpty()) return
        if (segments.last() == "-") {
            val parent = getPointer(doc, parentPointer(segments))
            if (parent is MutableList<*>) {
                @Suppress("UNCHECKED_CAST")
                (parent as MutableList<Any?>).add(value)
            }
            return
        }
        val parent = getPointer(doc, parentPointer(segments)) ?: return
        when (parent) {
            is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") (parent as MutableMap<String, Any?>)[segments.last()] = value
            is MutableList<*> -> segments.last().toIntOrNull()?.let { index ->
                @Suppress("UNCHECKED_CAST")
                val list = parent as MutableList<Any?>
                while (list.size < index) list.add(null)
                if (index == list.size) list.add(value) else list[index] = value
            }
        }
    }

    private fun removePointer(doc: Any?, pointer: String): Boolean {
        val segments = pointerSegments(pointer)
        if (segments.isEmpty()) return false
        val parent = getPointer(doc, parentPointer(segments)) ?: return false
        return when (parent) {
            is MutableMap<*, *> -> @Suppress("UNCHECKED_CAST") (parent as MutableMap<String, Any?>).remove(segments.last()) != null
            is MutableList<*> -> segments.last().toIntOrNull()?.let { index ->
                if (index in parent.indices) { @Suppress("UNCHECKED_CAST") (parent as MutableList<Any?>).removeAt(index); true } else false
            } ?: false
            else -> false
        }
    }

    private fun parentPointer(segments: List<String>): String {
        val parent = segments.dropLast(1)
        // Empty pointer = the whole document (root-level key).
        if (parent.isEmpty()) return ""
        return "/" + parent.joinToString("/")
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean {
        val leftNormalized = if (left is Number) left.toDouble() else left
        val rightNormalized = if (right is Number) right.toDouble() else right
        return leftNormalized == rightNormalized
    }

    private class VarOptionHints(
        val scope: String? = null,
        val inscope: String? = null,
        val outscope: String? = null,
        val flags: String? = null,
        val results: String? = null,
        val merge: Boolean = false,
        val defaults: Any? = null,
    )

    /** ST optionsConverter (variables.ts:744): string shorthand / boolean / map. */
    private fun varOptionHints(arg: Any?): VarOptionHints = when (arg) {
        null -> VarOptionHints()
        is String -> when (arg) {
            "old", "new", "fullcache" -> VarOptionHints(results = arg)
            "nx", "xx", "nxs", "xxs", "n" -> VarOptionHints(flags = arg)
            "cache", "global", "local", "message", "initial" ->
                VarOptionHints(scope = arg, inscope = arg, outscope = arg)
            else -> VarOptionHints()
        }
        // ST: dryRun=true permits writing during the preparation phase; there
        // is no separate preparation phase here, so it is a no-op.
        is Boolean -> VarOptionHints()
        is Map<*, *> -> VarOptionHints(
            scope = arg["scope"] as? String,
            inscope = (arg["inscope"] ?: arg["scope"]) as? String,
            outscope = (arg["outscope"] ?: arg["scope"]) as? String,
            flags = arg["flags"] as? String,
            results = arg["results"] as? String,
            merge = arg["merge"] == true,
            defaults = arg["defaults"],
        )
        else -> VarOptionHints()
    }

    /** Read scope resolution: null/'cache' → merged snapshot (ST default 'cache'). */
    private fun scopeMap(state: TemplateState, scope: String?): MutableMap<String, Any?> = when (scope) {
        "global" -> state.globalVariables
        "local" -> state.localVariables
        "message" -> state.messageVariables
        else -> state.variables
    }

    private fun setVariableScoped(
        state: TemplateState,
        key: String,
        value: Any?,
        hints: VarOptionHints,
        forcedScope: String? = null,
    ): Any? {
        // ST setVariable: switch (scope || 'message') — bare setvar defaults
        // to the message layer (variables.ts:307).
        val scope = forcedScope ?: hints.scope ?: "message"
        val target = when (scope) {
            "global" -> state.globalVariables
            "local" -> state.localVariables
            "message" -> state.messageVariables
            else -> null
        }
        // ST checks nxs/xxs via getVariable with the same options: the write
        // scope when explicit, the cache for the default scope.
        val checkSource = target ?: state.variables
        val oldValue = if (hints.results == "old" || hints.merge) getPath(state.variables, key) else null
        if (hints.flags == "nxs" && getPath(checkSource, key) != null) {
            return if (hints.results == "old") oldValue else null
        }
        if (hints.flags == "xxs" && getPath(checkSource, key) == null) {
            return if (hints.results == "old") oldValue else null
        }
        var newValue = value
        if (hints.merge) newValue = mergeValues(oldValue, value)
        if (target != null) {
            if (newValue == null) deletePath(target, key) else setPath(target, key, newValue)
            refreshMergedVariables(state)
        } else if (newValue != null) {
            // scope 'cache'/'initial': cache-sync only (ST has no write case).
            setPath(state.variables, key, newValue)
        }
        return when (hints.results) {
            "old" -> oldValue
            "fullcache" -> state.variables
            else -> newValue
        }
    }

    /** ST increaseVariable/decreaseVariable: reads inscope (default cache), writes outscope (default message). */
    private fun changeVariableScoped(
        state: TemplateState,
        key: String,
        amount: Double,
        hints: VarOptionHints,
        forcedOutscope: String? = null,
        decrease: Boolean = false,
    ): String {
        val source = scopeMap(state, hints.inscope)
        val target = scopeMap(state, forcedOutscope ?: hints.outscope ?: "message")
        val current = numeric(getPath(source, key))
        val next = if (decrease) current - amount else current + amount
        setPath(target, key, next)
        refreshMergedVariables(state)
        return formatNumber(next)
    }

    private fun deleteVariableScoped(
        state: TemplateState,
        key: String,
        hints: VarOptionHints,
        forcedScope: String? = null,
    ) {
        val scope = forcedScope ?: hints.scope ?: "message"
        val target = scopeMap(state, scope)
        deletePath(target, key)
        if (scope != "cache") refreshMergedVariables(state)
    }

    /** ST merge option: arrays concat onto arrays, otherwise lodash-style deep merge with arrays replaced. */
    private fun mergeValues(oldValue: Any?, value: Any?): Any? = when {
        (oldValue == null || oldValue is List<*>) && value is List<*> -> {
            val list = (oldValue as? List<*>)?.toMutableList() ?: mutableListOf()
            list.addAll(value)
            list
        }
        oldValue is MutableMap<*, *> && value is Map<*, *> -> deepMergeMaps(oldValue, value)
        else -> value
    }

    private fun deepMergeMaps(dst: MutableMap<*, *>, src: Map<*, *>): Any? {
        @Suppress("UNCHECKED_CAST")
        val out = deepCopyMap(dst as Map<String, Any?>)
        src.forEach { (key, srcValue) ->
            val dstValue = out[key.toString()]
            out[key.toString()] = if (dstValue is MutableMap<*, *> && srcValue is Map<*, *>) {
                deepMergeMaps(dstValue, srcValue)
            } else {
                srcValue
            }
        }
        return out
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
        javascriptEvaluator: PromptTemplateJsBridge? = null,
    ): List<Any?>? {
        if (!expression.startsWith("[") || !expression.endsWith("]")) return null
        val inner = expression.drop(1).dropLast(1)
        if (inner.isBlank()) return emptyList()
        return splitArguments(inner).map { evaluate(it, state, javascriptEvaluator) }
    }

    /**
     * Simple `{key: value}` literals — enough for ST variable options
     * (`{defaults: 0}`, `{scope: 'global'}`, `{flags: 'nxs'}`). Keys are bare
     * identifiers or quoted strings; values are full expressions.
     */
    private fun parseObjectLiteral(
        expression: String,
        state: TemplateState,
        javascriptEvaluator: PromptTemplateJsBridge? = null,
    ): Map<String, Any?>? {
        if (!expression.startsWith("{") || !expression.endsWith("}")) return null
        val inner = expression.drop(1).dropLast(1).trim()
        val out = linkedMapOf<String, Any?>()
        if (inner.isEmpty()) return out
        for (part in splitTopLevel(inner, ',')) {
            val pair = splitTopLevel(part.trim(), ':')
            if (pair.size != 2) return null
            val rawKey = pair[0].trim()
            val key = parseStringLiteral(rawKey)
                ?: rawKey.takeIf { Regex("[A-Za-z_$][\\w$]*").matches(it) }
                ?: return null
            out[key] = evaluate(pair[1].trim(), state, javascriptEvaluator)
        }
        return out
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
