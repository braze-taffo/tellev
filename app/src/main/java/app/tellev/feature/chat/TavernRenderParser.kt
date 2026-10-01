package app.tellev.feature.chat

sealed interface TavernRenderSegment {
    data class Text(val text: String) : TavernRenderSegment
    data class Reasoning(val content: String) : TavernRenderSegment
    data class Frontend(val html: String) : TavernRenderSegment
}

object TavernRenderParser {
    private val fencedCode = Regex(
        """(?m)(^|\n)([ \t]*)(`{3,}|~{3,})([^\r\n]*)\r?\n([\s\S]*?)\r?\n[ \t]*\3[ \t]*(?=\r?\n|$)""",
    )
    private val rawHtmlBlockTags = setOf(
        "article",
        "aside",
        "details",
        "div",
        "figure",
        "main",
        "section",
        "table",
    )

    fun parse(text: String): List<TavernRenderSegment> {
        if (text.isBlank()) return emptyList()

        val parts = app.tellev.core.model.MessageReasoning.split(text)
        return buildList {
            if (parts.reasoning.isNotBlank()) add(TavernRenderSegment.Reasoning(parts.reasoning))
            addAll(parseBody(parts.body))
        }
    }

    fun parseBody(text: String): List<TavernRenderSegment> {
        if (text.isBlank()) return emptyList()

        val fenced = parseFencedFrontendBlocks(text)
        // A message can mix fenced cards, raw documents and raw fragments.
        // Parse every remaining text span instead of stopping at the first format.
        return fenced.flatMap { segment ->
            if (segment is TavernRenderSegment.Text) parseRawDocumentBlocks(segment.text)
            else listOf(segment)
        }
    }

    fun isTavernFrontendCode(content: String): Boolean =
        listOf("html>", "<head>", "<body").any { content.contains(it, ignoreCase = true) }

    private fun parseFencedFrontendBlocks(text: String): List<TavernRenderSegment> {
        val segments = mutableListOf<TavernRenderSegment>()
        var cursor = 0

        for (match in fencedCode.findAll(text)) {
            val leadingNewline = match.groups[1]?.value == "\n"
            val blockStart = if (leadingNewline) match.range.first + 1 else match.range.first
            val blockEnd = match.range.last + 1
            val code = match.groups[5]?.value ?: continue

            if (!isTavernFrontendCode(code)) continue

            appendText(segments, text.substring(cursor, blockStart))
            appendFrontend(segments, code)
            cursor = blockEnd
        }

        appendText(segments, text.substring(cursor))
        return segments.ifEmpty { listOf(TavernRenderSegment.Text(text)) }
    }

    private fun parseRawDocumentBlocks(text: String): List<TavernRenderSegment> {
        val fencedRanges = fencedCode.findAll(text).map { it.range }.toList()
        val tokens = TavernHtmlTokens.scan(text, fencedRanges)
        val headRanges = tokens.mapIndexedNotNull { index, tag ->
            if (!tag.closing && tag.name in setOf("style", "script", "head")) {
                balancedEnd(tokens, index)?.let { tag.start until it }
            } else null
        }
        val segments = mutableListOf<TavernRenderSegment>()
        var cursor = 0
        for ((index, tag) in tokens.withIndex()) {
            if (tag.start < cursor || tag.closing) continue
            if (tag.name !in rawHtmlBlockTags && tag.name !in setOf("html", "body")) continue
            val elementEnd = balancedEnd(tokens, index) ?: continue
            var start = tag.start
            // Collect the entire adjacent chain, rather than only its last block.
            for (head in headRanges.asReversed()) {
                if (head.last < start && text.substring(head.last + 1, start).isBlank()) start = head.first
            }
            if (tag.name == "html") {
                val doctype = Regex("<!doctype\\s+html[^>]*>\\s*$", RegexOption.IGNORE_CASE).find(text.substring(cursor, start))
                if (doctype != null) start = cursor + doctype.range.first
            }
            start = start.coerceAtLeast(cursor)
            var end = elementEnd
            for (tail in headRanges) {
                if (tail.first >= end && text.substring(end, tail.first).isBlank()) end = tail.last + 1
            }
            val html = text.substring(start, end)
            if (tag.name !in setOf("html", "body") && !isLikelyRawHtmlMessage(html)) continue
            appendText(segments, text.substring(cursor, start))
            appendFrontend(segments, html)
            cursor = end
        }
        appendText(segments, text.substring(cursor))
        return segments
    }

    private fun balancedEnd(tokens: List<TavernHtmlTokens.Tag>, start: Int): Int? {
        val name = tokens[start].name
        var depth = 0
        for (token in tokens.drop(start)) {
            if (token.name != name) continue
            if (token.closing) {
                depth--
                if (depth == 0) return token.end
            } else if (!token.selfClosing) depth++
        }
        return null
    }

    private fun isLikelyRawHtmlMessage(fragment: String): Boolean =
        Regex("""\b(class|style|id)\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(fragment) ||
            fragment.contains("<style", ignoreCase = true) ||
            fragment.contains("<script", ignoreCase = true) ||
            fragment.contains("<details", ignoreCase = true)

    private fun appendText(segments: MutableList<TavernRenderSegment>, text: String) {
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) segments += TavernRenderSegment.Text(cleaned)
    }

    private fun appendFrontend(segments: MutableList<TavernRenderSegment>, html: String) {
        val cleaned = html.trim()
        if (cleaned.isNotEmpty()) segments += TavernRenderSegment.Frontend(cleaned)
    }

}
