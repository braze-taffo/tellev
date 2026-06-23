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
    // Provider-emitted reasoning blocks. OpenAiCompatibleAdapter wraps model
    // reasoning as <reasoning>...</reasoning>; many models (Qwen, DeepSeek
    // variants) emit <think>...</think>. Both are stripped out of the message
    // body and rendered as a separate collapsible Reasoning segment.
    private val reasoningBlock = Regex(
        """<(?:reasoning|think)>([\s\S]*?)</(?:reasoning|think)>""",
        RegexOption.IGNORE_CASE,
    )
    private val htmlDocument = Regex("""<html\b[\s\S]*?</html>""", RegexOption.IGNORE_CASE)
    private val bodyDocument = Regex("""<body\b[\s\S]*?</body>""", RegexOption.IGNORE_CASE)
    private val styleBlock = Regex("""<style\b[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
    private val scriptBlock = Regex("""<script\b[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
    private val rawHtmlStartTag = Regex(
        """<([a-z][a-z0-9:-]*)(?:\s[^>]*)?>""",
        RegexOption.IGNORE_CASE,
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

        // Split out reasoning blocks first so they never leak into the message
        // body, then run the normal frontend/text parsing on each gap. Gaps are
        // trimmed so a trailing "\n" left after a reasoning block does not
        // render as a leading blank line.
        if (reasoningBlock.containsMatchIn(text)) {
            val segments = mutableListOf<TavernRenderSegment>()
            var cursor = 0
            for (match in reasoningBlock.findAll(text)) {
                segments += parseWithoutReasoning(text.substring(cursor, match.range.first).trim())
                val reasoning = match.groupValues[1].trim()
                if (reasoning.isNotEmpty()) segments += TavernRenderSegment.Reasoning(reasoning)
                cursor = match.range.last + 1
            }
            segments += parseWithoutReasoning(text.substring(cursor).trim())
            return segments
        }

        return parseWithoutReasoning(text)
    }

    private fun parseWithoutReasoning(text: String): List<TavernRenderSegment> {
        if (text.isBlank()) return emptyList()

        val fenced = parseFencedFrontendBlocks(text)
        if (fenced.any { it is TavernRenderSegment.Frontend }) {
            return fenced
        }

        return parseRawDocumentBlocks(text)
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
        val documentMatches = (htmlDocument.findAll(text) + bodyDocument.findAll(text))
            .sortedBy { it.range.first }
            .fold(mutableListOf<MatchResult>()) { kept, match ->
                if (kept.none { rangesOverlap(it.range, match.range) }) kept += match
                kept
            }

        if (documentMatches.isEmpty()) return parseRawHtmlFragments(text)

        val segments = mutableListOf<TavernRenderSegment>()
        var cursor = 0
        for (match in documentMatches) {
            if (match.range.first < cursor) continue
            val frontendStart = adjacentHeadStart(text, match)
            appendText(segments, text.substring(cursor, frontendStart))
            appendFrontend(segments, includeAdjacentHeadBlocks(text, match))
            cursor = match.range.last + 1
        }
        appendText(segments, text.substring(cursor))
        return segments
    }

    private fun parseRawHtmlFragments(text: String): List<TavernRenderSegment> {
        val fragmentRanges = rawHtmlFragmentRanges(text)
        if (fragmentRanges.isEmpty()) return listOf(TavernRenderSegment.Text(text))

        val segments = mutableListOf<TavernRenderSegment>()
        var cursor = 0
        for (range in fragmentRanges) {
            if (range.first < cursor) continue
            appendText(segments, text.substring(cursor, range.first))
            appendFrontend(segments, text.substring(range.first, range.last + 1))
            cursor = range.last + 1
        }
        appendText(segments, text.substring(cursor))
        return segments
    }

    private fun rawHtmlFragmentRanges(text: String): List<IntRange> {
        val fencedRanges = fencedCode.findAll(text).map { it.range }.toList()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0

        while (cursor < text.length) {
            val match = rawHtmlStartTag.find(text, cursor) ?: break
            val tagName = match.groupValues[1].lowercase()

            if (tagName !in rawHtmlBlockTags || fencedRanges.any { match.range.first in it }) {
                cursor = match.range.last + 1
                continue
            }

            val elementEnd = balancedElementEnd(text, match.range.first, tagName)
            if (elementEnd == null) {
                cursor = match.range.last + 1
                continue
            }

            val start = adjacentHeadStart(text, match.range.first)
            val end = includeAdjacentTailBlocks(text, elementEnd)
            val fragment = text.substring(start, end)

            if (isLikelyRawHtmlMessage(fragment)) {
                ranges += start until end
                cursor = end
            } else {
                cursor = match.range.last + 1
            }
        }

        return ranges
    }

    private fun balancedElementEnd(text: String, startIndex: Int, tagName: String): Int? {
        val tagPattern = Regex("""</?\s*${Regex.escape(tagName)}\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 0

        for (match in tagPattern.findAll(text, startIndex)) {
            val value = match.value
            when {
                value.startsWith("</") -> {
                    depth--
                    if (depth == 0) return match.range.last + 1
                }
                !value.endsWith("/>") -> depth++
            }
        }

        return null
    }

    private fun adjacentHeadStart(text: String, blockStart: Int): Int {
        val prefix = text.substring(0, blockStart)
        return (styleBlock.findAll(prefix) + scriptBlock.findAll(prefix))
            .filter { prefix.substring(it.range.last + 1).isBlank() }
            .minOfOrNull { it.range.first }
            ?: blockStart
    }

    private fun includeAdjacentTailBlocks(text: String, blockEndExclusive: Int): Int {
        var cursor = blockEndExclusive

        while (cursor < text.length) {
            val leadingWhitespace = text
                .substring(cursor)
                .takeWhile { it.isWhitespace() }
            val nextStart = cursor + leadingWhitespace.length
            val style = styleBlock.find(text, nextStart)?.takeIf { it.range.first == nextStart }
            val script = scriptBlock.find(text, nextStart)?.takeIf { it.range.first == nextStart }
            val next = listOfNotNull(style, script).minByOrNull { it.range.first } ?: break
            cursor = next.range.last + 1
        }

        return cursor
    }

    private fun isLikelyRawHtmlMessage(fragment: String): Boolean =
        Regex("""\b(class|style|id)\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(fragment) ||
            fragment.contains("<style", ignoreCase = true) ||
            fragment.contains("<script", ignoreCase = true) ||
            fragment.contains("<details", ignoreCase = true)

    private fun includeAdjacentHeadBlocks(text: String, match: MatchResult): String {
        if (match.value.startsWith("<html", ignoreCase = true)) return match.value.trim()

        val prefix = text.substring(0, match.range.first)
        val adjacentHead = (styleBlock.findAll(prefix) + scriptBlock.findAll(prefix))
            .filter { prefix.substring(it.range.last + 1).isBlank() }
            .joinToString("\n") { it.value.trim() }

        return listOf(adjacentHead, match.value.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun adjacentHeadStart(text: String, match: MatchResult): Int {
        if (match.value.startsWith("<html", ignoreCase = true)) return match.range.first

        val prefix = text.substring(0, match.range.first)
        return (styleBlock.findAll(prefix) + scriptBlock.findAll(prefix))
            .filter { prefix.substring(it.range.last + 1).isBlank() }
            .minOfOrNull { it.range.first }
            ?: match.range.first
    }

    private fun appendText(segments: MutableList<TavernRenderSegment>, text: String) {
        val cleaned = text.trim()
        if (cleaned.isNotEmpty()) segments += TavernRenderSegment.Text(cleaned)
    }

    private fun appendFrontend(segments: MutableList<TavernRenderSegment>, html: String) {
        val cleaned = html.trim()
        if (cleaned.isNotEmpty()) segments += TavernRenderSegment.Frontend(cleaned)
    }

    private fun rangesOverlap(lhs: IntRange, rhs: IntRange): Boolean =
        lhs.first <= rhs.last && rhs.first <= lhs.last
}
