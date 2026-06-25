package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

interface PromptTemplateProcessor {
    fun process(request: PromptTemplateRequest): PromptTemplateResult
    fun systemPromptContentFor(entry: PromptTemplateWorldEntry): String = entry.content
}

data class PromptTemplateRequest(
    val messages: List<PromptMessage>,
    val context: MacroContext,
    val metadata: JsonObject,
    val worldEntries: List<PromptTemplateWorldEntry> = emptyList(),
)

data class PromptTemplateWorldEntry(
    val id: String,
    val content: String,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class PromptTemplateResult(
    val messages: List<PromptMessage>,
    val warnings: List<String> = emptyList(),
)

class DefaultPromptTemplateProcessor : PromptTemplateProcessor {

    override fun process(request: PromptTemplateRequest): PromptTemplateResult {
        val entryParses = request.worldEntries.map { parseWorldEntry(it) }
        if (!request.messages.any { it.content.contains("<%") || hasInstructionMarker(it.content) } &&
            entryParses.none { it.blocks.isNotEmpty() }
        ) {
            return PromptTemplateResult(messages = request.messages)
        }

        val state = TemplateState(
            context = request.context,
            variables = extractVariables(request.metadata).toMutableMap(),
        )

        val injectedMessages = applyInstructionBlocks(
            request.messages.map { message -> message.copy(content = stripInstructionBlocks(message.content)) },
            entryParses.flatMap { it.blocks },
            state,
        )

        val rendered = injectedMessages.map { message ->
            message.copy(content = renderTemplate(message.content, state))
        }

        return PromptTemplateResult(
            messages = rendered,
            warnings = state.warnings.toList(),
        )
    }

    override fun systemPromptContentFor(entry: PromptTemplateWorldEntry): String =
        parseWorldEntry(entry).normalText.trim()

    private fun renderTemplate(template: String, state: TemplateState): String {
        if (!template.contains("<%")) return template
        val tokens = tokenize(template)
        return renderTokens(tokens, 0, tokens.size, state)
    }

    private fun applyInstructionBlocks(
        messages: List<PromptMessage>,
        blocks: List<InstructionBlock>,
        state: TemplateState,
    ): List<PromptMessage> {
        if (blocks.isEmpty()) return messages
        var current = messages
        for (block in blocks) {
            if (block.body.isBlank()) continue
            current = when (block.kind) {
                InstructionKind.Generate -> applyGenerateInstruction(current, block, state)
                InstructionKind.Inject -> applyInjectInstruction(current, block, state)
            }
        }
        return current
    }

    private fun applyGenerateInstruction(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): List<PromptMessage> {
        if (messages.isEmpty()) return messages
        val target = resolveGenerateTarget(messages, block, state) ?: return messages
        val placement = block.placement ?: Placement.Before
        val body = block.body.trim()
        return messages.mapIndexed { index, message ->
            if (index != target) return@mapIndexed message
            val joined = when (placement) {
                Placement.Before -> joinPromptParts(body, message.content)
                Placement.After -> joinPromptParts(message.content, body)
            }
            message.copy(content = joined)
        }
    }

    private fun resolveGenerateTarget(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): Int? {
        block.index?.let { return it.coerceIn(0, messages.lastIndex) }
        block.regex?.let { pattern ->
            val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrElse {
                state.warn("Invalid [GENERATE:REGEX] pattern: $pattern")
                return null
            }
            return messages.indexOfFirst { regex.containsMatchIn(it.content) }.takeIf { it >= 0 }
        }
        return when (block.placement ?: Placement.Before) {
            Placement.Before -> 0
            Placement.After -> messages.lastIndex
        }
    }

    private fun applyInjectInstruction(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): List<PromptMessage> {
        val role = block.role ?: MessageRole.System
        val injected = PromptMessage(role = role, content = block.body.trim())
        val index = resolveInjectIndex(messages, block, state).coerceIn(0, messages.size)
        return messages.toMutableList().apply { add(index, injected) }
    }

    private fun resolveInjectIndex(
        messages: List<PromptMessage>,
        block: InstructionBlock,
        state: TemplateState,
    ): Int {
        block.index?.let {
            val base = it.coerceIn(0, messages.size)
            return when (block.placement ?: Placement.Before) {
                Placement.Before -> base
                Placement.After -> (base + 1).coerceAtMost(messages.size)
            }
        }

        block.target?.let { target ->
            val messageIndex = target.toIntOrNull()
                ?: messages.indexOfFirst { it.content.contains(target, ignoreCase = true) }.takeIf { it >= 0 }
            if (messageIndex != null) {
                return when (block.placement ?: Placement.After) {
                    Placement.Before -> messageIndex
                    Placement.After -> messageIndex + 1
                }
            }
        }

        block.regex?.let { pattern ->
            val regex = runCatching { Regex(pattern, RegexOption.DOT_MATCHES_ALL) }.getOrElse {
                state.warn("Invalid @INJECT regex pattern: $pattern")
                return messages.size
            }
            val messageIndex = messages.indexOfFirst { regex.containsMatchIn(it.content) }
            if (messageIndex >= 0) {
                return when (block.placement ?: Placement.Before) {
                    Placement.Before -> messageIndex
                    Placement.After -> messageIndex + 1
                }
            }
        }

        return messages.size
    }

    private fun parseWorldEntry(entry: PromptTemplateWorldEntry): InstructionParseResult {
        val contentParse = parseInstructionBlocks(entry.content)
        if (contentParse.blocks.isNotEmpty()) return contentParse

        val comment = entry.raw.stringValue("comment")
            ?: entry.raw.stringValue("memo")
            ?: entry.raw.stringValue("note")
            ?: return contentParse
        val commentParse = parseInstructionBlocks(comment)
        if (commentParse.blocks.isEmpty()) return contentParse

        return InstructionParseResult(
            normalText = "",
            blocks = commentParse.blocks.map { block ->
                if (block.body.isBlank()) block.copy(body = entry.content) else block
            },
        )
    }

    private fun stripInstructionBlocks(text: String): String =
        parseInstructionBlocks(text).normalText

    private fun parseInstructionBlocks(source: String): InstructionParseResult {
        if (!hasInstructionMarker(source)) return InstructionParseResult(source, emptyList())

        val normal = StringBuilder()
        val blocks = mutableListOf<InstructionBlock>()
        var currentHeader: String? = null
        var currentBody = mutableListOf<String>()

        fun closeCurrent() {
            val header = currentHeader ?: return
            parseInstructionHeader(header, currentBody.joinToString("\n"))?.let { blocks += it }
            currentHeader = null
            currentBody = mutableListOf()
        }

        for (line in source.lines()) {
            val trimmed = line.trim()
            if (isInstructionHeader(trimmed)) {
                closeCurrent()
                currentHeader = trimmed
            } else if (currentHeader != null) {
                currentBody += line
            } else {
                normal.appendLine(line)
            }
        }
        closeCurrent()

        return InstructionParseResult(normal.toString().trim(), blocks)
    }

    private fun parseInstructionHeader(header: String, body: String): InstructionBlock? {
        generateHeader.matchEntire(header)?.let { match ->
            val parts = match.groupValues[1]
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var placement: Placement? = null
            var index: Int? = null
            var regex: String? = null
            var i = 0
            while (i < parts.size) {
                val part = parts[i]
                when {
                    part.equals("BEFORE", ignoreCase = true) -> placement = Placement.Before
                    part.equals("AFTER", ignoreCase = true) -> placement = Placement.After
                    part.equals("REGEX", ignoreCase = true) -> {
                        regex = parts.drop(i + 1)
                            .dropLastWhile { it.equals("BEFORE", true) || it.equals("AFTER", true) }
                            .joinToString(":")
                        val last = parts.lastOrNull()
                        if (last.equals("BEFORE", true)) placement = Placement.Before
                        if (last.equals("AFTER", true)) placement = Placement.After
                        i = parts.size - 1
                    }
                    part.toIntOrNull() != null -> index = part.toInt()
                }
                i++
            }
            return InstructionBlock(
                kind = InstructionKind.Generate,
                body = body,
                placement = placement,
                index = index,
                regex = regex?.removeRegexDelimiters(),
            )
        }

        if (!header.startsWith("@INJECT", ignoreCase = true)) return null
        val args = parseInjectArgs(header.substringAfter("@INJECT", "").trim())
        return InstructionBlock(
            kind = InstructionKind.Inject,
            body = body,
            placement = args["at"]?.toPlacement() ?: args["placement"]?.toPlacement(),
            index = args["pos"]?.toIntOrNull(),
            target = args["target"],
            regex = args["regex"]?.removeRegexDelimiters(),
            role = args["role"]?.toMessageRole(),
        )
    }

    private fun parseInjectArgs(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in injectArgPattern.findAll(raw)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2]
                .ifBlank { match.groupValues[3] }
                .ifBlank { match.groupValues[4] }
            result[key.lowercase()] = value.trim().trim('"', '\'')
        }
        return result
    }

    private fun renderTokens(
        tokens: List<TemplateToken>,
        start: Int,
        end: Int,
        state: TemplateState,
    ): String {
        val out = StringBuilder()
        var index = start
        while (index < end) {
            when (val token = tokens[index]) {
                is TemplateToken.Text -> out.append(token.value)
                is TemplateToken.Output -> out.append(stringify(evaluate(token.expression, state)))
                is TemplateToken.Code -> {
                    val code = normalizeCode(token.code)
                    when {
                        code.isBlank() || code.startsWith("//") -> Unit
                        isBlockEnd(code) || isElseBlock(code) -> Unit
                        ifStart.matchEntire(code) != null -> {
                            val match = ifStart.matchEntire(code)!!
                            val block = findBlock(tokens, index + 1, end)
                            val condition = truthy(evaluate(match.groupValues[1], state))
                            out.append(
                                if (condition) {
                                    renderTokens(tokens, index + 1, block.elseIndex ?: block.endIndex, state)
                                } else if (block.elseIndex != null) {
                                    renderTokens(tokens, block.elseIndex + 1, block.endIndex, state)
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
                            val block = findBlock(tokens, index + 1, end)
                            val oldValue = state.locals[localName]
                            val hadOldValue = state.locals.containsKey(localName)
                            for (item in evaluateList(source, state)) {
                                state.locals[localName] = item
                                out.append(renderTokens(tokens, index + 1, block.endIndex, state))
                            }
                            if (hadOldValue) {
                                state.locals[localName] = oldValue
                            } else {
                                state.locals.remove(localName)
                            }
                            index = block.endIndex
                        }
                        else -> out.append(executeCode(code, state))
                    }
                }
            }
            index++
        }
        return out.toString()
    }

    private fun tokenize(template: String): List<TemplateToken> {
        val tokens = mutableListOf<TemplateToken>()
        var cursor = 0
        for (match in tagPattern.findAll(template)) {
            if (match.range.first > cursor) {
                tokens += TemplateToken.Text(template.substring(cursor, match.range.first))
            }
            val marker = match.groupValues[1]
            val body = stripTrimMarkers(match.groupValues[2])
            tokens += if (marker == "=" || marker == "-") {
                TemplateToken.Output(body)
            } else {
                TemplateToken.Code(body)
            }
            cursor = match.range.last + 1
        }
        if (cursor < template.length) {
            tokens += TemplateToken.Text(template.substring(cursor))
        }
        return tokens
    }

    private fun findBlock(tokens: List<TemplateToken>, start: Int, end: Int): TemplateBlock {
        var depth = 0
        var elseIndex: Int? = null
        for (i in start until end) {
            val code = (tokens[i] as? TemplateToken.Code)?.code?.let(::normalizeCode) ?: continue
            when {
                ifStart.matchEntire(code) != null || forOfStart.matchEntire(code) != null -> depth++
                isBlockEnd(code) -> {
                    if (depth == 0) return TemplateBlock(elseIndex, i)
                    depth--
                }
                isElseBlock(code) && depth == 0 && elseIndex == null -> elseIndex = i
            }
        }
        return TemplateBlock(elseIndex, end)
    }

    private fun executeCode(code: String, state: TemplateState): String {
        val output = StringBuilder()
        val statements = splitTopLevel(code, ';').map { it.trim() }.filter { it.isNotEmpty() }
        for (statement in statements) {
            val normalized = statement.removePrefix("await ").trim()
            val declaration = declarationPattern.matchEntire(normalized)
            if (declaration != null) {
                state.locals[declaration.groupValues[1]] = evaluate(declaration.groupValues[2], state)
                continue
            }

            val assignment = assignmentPattern.matchEntire(normalized)
            if (assignment != null && !normalized.contains("==")) {
                assign(assignment.groupValues[1], evaluate(assignment.groupValues[2], state), state)
                continue
            }

            val printCall = printPattern.matchEntire(normalized)
            if (printCall != null) {
                output.append(stringify(evaluate(printCall.groupValues[1], state)))
                continue
            }

            val value = evaluate(normalized, state)
            if (value === UnsupportedExpression) {
                state.warn("Unsupported prompt template statement: $normalized")
            }
        }
        return output.toString()
    }

    private fun evaluateList(expression: String, state: TemplateState): List<Any?> {
        return when (val value = evaluate(expression, state)) {
            is List<*> -> value
            is Array<*> -> value.toList()
            is Map<*, *> -> value.values.toList()
            is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            null -> emptyList()
            UnsupportedExpression -> emptyList()
            else -> listOf(value)
        }
    }

    private fun evaluate(rawExpression: String, state: TemplateState): Any? {
        var expression = rawExpression.trim().removeSuffix(";").trim()
        if (expression.startsWith("await ")) expression = expression.removePrefix("await ").trim()
        expression = stripBalancedParens(expression)

        if (expression.isBlank()) return ""
        if (expression == "true") return true
        if (expression == "false") return false
        if (expression == "null" || expression == "undefined") return null

        if (expression.startsWith("!")) return !truthy(evaluate(expression.drop(1), state))

        splitByOperator(expression, "??")?.let { (left, right) ->
            val leftValue = evaluate(left, state)
            return if (leftValue == null || leftValue == "") evaluate(right, state) else leftValue
        }
        splitByOperator(expression, "||")?.let { (left, right) ->
            val leftValue = evaluate(left, state)
            return if (truthy(leftValue)) leftValue else evaluate(right, state)
        }
        splitByOperator(expression, "&&")?.let { (left, right) ->
            val leftValue = evaluate(left, state)
            return if (truthy(leftValue)) evaluate(right, state) else leftValue
        }

        for (operator in comparisonOperators) {
            splitByOperator(expression, operator)?.let { (left, right) ->
                return compare(evaluate(left, state), evaluate(right, state), operator)
            }
        }

        splitTopLevelByPlus(expression)?.let { parts ->
            val values = parts.map { evaluate(it, state) }
            if (values.any { it is String }) {
                return values.joinToString("") { stringify(it) }
            }
            return values.sumOf { numeric(it) }
        }

        parseStringLiteral(expression)?.let { return it }
        expression.toLongOrNull()?.let { return it }
        expression.toDoubleOrNull()?.let { return it }

        parseArrayLiteral(expression, state)?.let { return it }

        includesPattern.matchEntire(expression)?.let { match ->
            val target = stringify(evaluate(match.groupValues[1], state))
            val needle = stringify(evaluate(match.groupValues[2], state))
            return target.contains(needle)
        }

        val functionCall = functionCallPattern.matchEntire(expression)
        if (functionCall != null) {
            return callFunction(
                functionCall.groupValues[1],
                splitArguments(functionCall.groupValues[2]).map { evaluate(it, state) },
                state,
            )
        }

        return resolveReference(expression, state)
    }

    private fun callFunction(name: String, args: List<Any?>, state: TemplateState): Any? {
        return when (name) {
            "getvar", "getchatvar", "getglobalvar" -> {
                val key = stringify(args.getOrNull(0))
                val fallback = args.getOrNull(1)
                getPath(state.variables, key) ?: fallback ?: ""
            }
            "setvar", "setchatvar", "setglobalvar" -> {
                val key = stringify(args.getOrNull(0))
                setPath(state.variables, key, args.getOrNull(1))
                ""
            }
            "incvar" -> {
                val key = stringify(args.getOrNull(0))
                val next = numeric(getPath(state.variables, key)) + 1.0
                setPath(state.variables, key, next)
                formatNumber(next)
            }
            "decvar" -> {
                val key = stringify(args.getOrNull(0))
                val next = numeric(getPath(state.variables, key)) - 1.0
                setPath(state.variables, key, next)
                formatNumber(next)
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
                if (root is MutableMap<*, *>) {
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
            trimmed.startsWith("variables.") -> setPath(state.variables, trimmed.removePrefix("variables."), value)
            trimmed.startsWith("vars.") -> setPath(state.variables, trimmed.removePrefix("vars."), value)
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

    private fun extractVariables(metadata: JsonObject): Map<String, Any?> {
        val element = metadata["promptTemplateVariables"]
            ?: metadata["variables"]
            ?: metadata["tavernVariables"]
            ?: return emptyMap()
        return (toKotlinValue(element) as? Map<*, *>)
            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
            ?.toMap()
            ?: emptyMap()
    }

    private fun toKotlinValue(element: JsonElement): Any? {
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

    private fun parseArrayLiteral(expression: String, state: TemplateState): List<Any?>? {
        if (!expression.startsWith("[") || !expression.endsWith("]")) return null
        val inner = expression.drop(1).dropLast(1)
        if (inner.isBlank()) return emptyList()
        return splitArguments(inner).map { evaluate(it, state) }
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

    private fun joinPromptParts(first: String, second: String): String {
        return listOf(first.trim(), second.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun hasInstructionMarker(text: String): Boolean =
        text.contains("[GENERATE", ignoreCase = true) || text.contains("@INJECT", ignoreCase = true)

    private fun isInstructionHeader(line: String): Boolean =
        generateHeader.matches(line) || line.startsWith("@INJECT", ignoreCase = true)

    private fun String.toPlacement(): Placement? =
        when (trim().lowercase()) {
            "before", "prepend", "start" -> Placement.Before
            "after", "append", "end" -> Placement.After
            else -> null
        }

    private fun String.toMessageRole(): MessageRole? =
        when (trim().lowercase()) {
            "system" -> MessageRole.System
            "user" -> MessageRole.User
            "assistant", "char", "character" -> MessageRole.Assistant
            "tool" -> MessageRole.Tool
            else -> null
        }

    private fun String.removeRegexDelimiters(): String {
        val trimmed = trim()
        if (trimmed.length >= 2 && trimmed.first() == '/') {
            val lastSlash = trimmed.lastIndexOf('/')
            if (lastSlash > 0) return trimmed.substring(1, lastSlash)
        }
        return trimmed
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun normalizeCode(code: String): String =
        stripTrimMarkers(code).trim()

    private fun stripTrimMarkers(code: String): String =
        code.trim().removePrefix("_").removeSuffix("_").trim()

    private fun isBlockEnd(code: String): Boolean =
        code == "}" || code == "};"

    private fun isElseBlock(code: String): Boolean =
        code == "} else {" || code == "}else{" || code.startsWith("} else {")

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

    private data class TemplateState(
        val context: MacroContext,
        val variables: MutableMap<String, Any?>,
        val locals: MutableMap<String, Any?> = mutableMapOf(),
        val warnings: LinkedHashSet<String> = linkedSetOf(),
    ) {
        fun warn(message: String) {
            warnings += message
        }
    }

    private sealed interface TemplateToken {
        data class Text(val value: String) : TemplateToken
        data class Output(val expression: String) : TemplateToken
        data class Code(val code: String) : TemplateToken
    }

    private data class TemplateBlock(val elseIndex: Int?, val endIndex: Int)

    private enum class InstructionKind { Generate, Inject }
    private enum class Placement { Before, After }

    private data class InstructionBlock(
        val kind: InstructionKind,
        val body: String,
        val placement: Placement? = null,
        val index: Int? = null,
        val target: String? = null,
        val regex: String? = null,
        val role: MessageRole? = null,
    )

    private data class InstructionParseResult(
        val normalText: String,
        val blocks: List<InstructionBlock>,
    )

    private object UnsupportedExpression

    private companion object {
        private val tagPattern = Regex("""<%([=-]?)([\s\S]*?)%>""")
        private val generateHeader = Regex("""\[GENERATE(?::([^\]]+))?]""", RegexOption.IGNORE_CASE)
        private val injectArgPattern = Regex("""([A-Za-z_][\w-]*)=(?:"([^"]*)"|'([^']*)'|([^"\s]+))""")
        private val ifStart = Regex("""if\s*\((.*)\)\s*\{\s*""")
        private val forOfStart = Regex("""for\s*\(\s*(?:const|let|var)?\s*([A-Za-z_$][\w$]*)\s+of\s+(.+)\)\s*\{\s*""")
        private val declarationPattern = Regex("""(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(.+)""")
        private val assignmentPattern = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*=\s*(.+)""")
        private val printPattern = Regex("""print\s*\((.*)\)""")
        private val functionCallPattern = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*\((.*)\)""")
        private val includesPattern = Regex("""(.+)\.includes\s*\((.*)\)""")
        private val comparisonOperators = listOf("===", "!==", ">=", "<=", "==", "!=", ">", "<")
    }
}
