package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PromptTemplateParser {
    private val tagPattern = Regex("""<%([_=-]?)([\s\S]*?)([-_]?)%>""")
    private val generateHeader = Regex("""\[GENERATE(?::([^\]]+))?]""", RegexOption.IGNORE_CASE)
    private val injectArgPattern = Regex("""([A-Za-z_][\w-]*)=(?:"([^"]*)"|'([^']*)'|([^"\s]+))""")
    private val ifStart = Regex("""if\s*\((.*)\)\s*\{\s*""")
    private val forOfStart = Regex("""for\s*\(\s*(?:const|let|var)?\s*([A-Za-z_$][\w$]*)\s+of\s+(.+)\)\s*\{\s*""")

    fun tokenize(template: String): List<TemplateToken> {
        val tokens = mutableListOf<TemplateToken>()
        var cursor = 0
        for (match in tagPattern.findAll(template)) {
            val openingMarker = match.groupValues[1]
            var preceding = template.substring(cursor, match.range.first)
            if (openingMarker == "_") preceding = preceding.trimEnd()
            if (preceding.isNotEmpty()) tokens += TemplateToken.Text(preceding)

            val body = match.groupValues[2].trim()
            tokens += if (openingMarker == "=" || openingMarker == "-") {
                TemplateToken.Output(body)
            } else {
                TemplateToken.Code(body)
            }

            cursor = match.range.last + 1
            when (match.groupValues[3]) {
                "-" -> {
                    if (template.startsWith("\r\n", cursor)) cursor += 2
                    else if (template.getOrNull(cursor) == '\n') cursor++
                }
                "_" -> while (template.getOrNull(cursor)?.isWhitespace() == true) cursor++
            }
        }
        if (cursor < template.length) tokens += TemplateToken.Text(template.substring(cursor))
        return tokens
    }

    fun findBlock(tokens: List<TemplateToken>, start: Int, end: Int): TemplateBlock {
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

    fun parseWorldEntry(entry: PromptTemplateWorldEntry): InstructionParseResult {
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

    fun stripInstructionBlocks(text: String): String =
        parseInstructionBlocks(text).normalText

    fun parseInstructionBlocks(source: String): InstructionParseResult {
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

    fun parseInstructionHeader(header: String, body: String): InstructionBlock? {
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

    fun parseInjectArgs(raw: String): Map<String, String> {
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

    fun hasInstructionMarker(text: String): Boolean =
        text.contains("[GENERATE", ignoreCase = true) || text.contains("@INJECT", ignoreCase = true)

    fun isInstructionHeader(line: String): Boolean =
        generateHeader.matches(line) || line.startsWith("@INJECT", ignoreCase = true)

    fun normalizeCode(code: String): String =
        stripTrimMarkers(code).trim()

    fun stripTrimMarkers(code: String): String =
        code.trim().removePrefix("_").removeSuffix("_").removeSuffix("-").trim()

    fun isBlockEnd(code: String): Boolean =
        code == "}" || code == "};"

    fun isElseBlock(code: String): Boolean =
        code == "} else {" || code == "}else{" || code.startsWith("} else {")

    fun String.toPlacement(): Placement? =
        when (trim().lowercase()) {
            "before", "prepend", "start" -> Placement.Before
            "after", "append", "end" -> Placement.After
            else -> null
        }

    fun String.toMessageRole(): MessageRole? =
        when (trim().lowercase()) {
            "system" -> MessageRole.System
            "user" -> MessageRole.User
            "assistant", "char", "character" -> MessageRole.Assistant
            "tool" -> MessageRole.Tool
            else -> null
        }

    fun String.removeRegexDelimiters(): String {
        val trimmed = trim()
        if (trimmed.length >= 2 && trimmed.first() == '/') {
            val lastSlash = trimmed.lastIndexOf('/')
            if (lastSlash > 0) return trimmed.substring(1, lastSlash)
        }
        return trimmed
    }

    fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }
}
