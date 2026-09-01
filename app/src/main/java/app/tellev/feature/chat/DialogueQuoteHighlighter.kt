package app.tellev.feature.chat

/**
 * Finds quoted dialogue using the same quote pairs and broad exclusions as
 * SillyTavern's message formatter. Matches never cross a line and incomplete
 * pairs are intentionally left untouched.
 */
object DialogueQuoteHighlighter {
    private data class QuotePair(val open: Char, val close: Char)

    private val quotePairs = listOf(
        QuotePair('"', '"'),
        QuotePair('“', '”'),
        QuotePair('«', '»'),
        QuotePair('「', '」'),
        QuotePair('『', '』'),
        QuotePair('＂', '＂'),
    )

    private val styleBlock = Regex("""<style\b[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
    private val fencedCode = Regex(
        """(?m)(^|\n)[ \t]*(`{3,}|~{3,})[^\r\n]*\r?\n[\s\S]*?\r?\n[ \t]*\2[ \t]*(?=\r?\n|$)""",
    )
    private val inlineCode = Regex("""(`+)[\s\S]*?\1""")
    private val htmlTag = Regex("""<[^>]+>""")

    fun findRanges(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()

        val excluded = BooleanArray(text.length)
        sequenceOf(styleBlock, fencedCode, inlineCode, htmlTag).forEach { regex ->
            regex.findAll(text).forEach { match ->
                for (index in match.range) excluded[index] = true
            }
        }

        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        while (cursor < text.length) {
            if (excluded[cursor]) {
                cursor++
                continue
            }

            val pair = quotePairs.firstOrNull { it.open == text[cursor] }
            if (pair == null) {
                cursor++
                continue
            }

            val lineEnd = text.indexOfAny(charArrayOf('\r', '\n'), startIndex = cursor + 1)
                .let { if (it == -1) text.length else it }
            var closing = cursor + 1
            while (closing < lineEnd && (excluded[closing] || text[closing] != pair.close)) {
                closing++
            }

            if (closing < lineEnd) {
                ranges += cursor..closing
                cursor = closing + 1
            } else {
                cursor++
            }
        }
        return ranges
    }

    /** Wrap matching ranges in q tags before CommonMark conversion. */
    fun wrapForMarkdown(text: String): String {
        val ranges = findRanges(text)
        if (ranges.isEmpty()) return text

        val result = StringBuilder(text)
        ranges.asReversed().forEach { range ->
            result.insert(range.last + 1, "</q>")
            result.insert(range.first, "<q>")
        }
        return result.toString()
    }
}
