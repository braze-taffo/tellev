package app.tellev.feature.chat

/** Source offsets, without rewriting authored HTML. Comments and raw-text bodies are opaque. */
internal object TavernHtmlTokens {
    data class Tag(val start: Int, val end: Int, val name: String, val closing: Boolean, val selfClosing: Boolean)
    private val rawText = setOf("script", "style", "textarea", "title", "xmp", "iframe")

    fun scan(text: String, excluded: List<IntRange> = emptyList()): List<Tag> = buildList {
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf('<', cursor)
            if (start < 0) break
            val opaque = excluded.firstOrNull { start in it }
            if (opaque != null) { cursor = opaque.last + 1; continue }
            if (text.startsWith("<!--", start)) {
                val end = text.indexOf("-->", start + 4)
                cursor = if (end < 0) text.length else end + 3
                continue
            }
            var at = start + 1
            val closing = text.getOrNull(at) == '/'
            if (closing) at++
            val nameStart = at
            if (text.getOrNull(at)?.isLetter() != true) { cursor = at + 1; continue }
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] in ":-_")) at++
            val name = text.substring(nameStart, at).lowercase()
            if (text.getOrNull(at)?.let { !it.isWhitespace() && it != '>' && it != '/' } == true) {
                cursor = at; continue
            }
            var quote: Char? = null
            while (at < text.length) {
                val char = text[at]
                if (quote != null) { if (char == quote) quote = null }
                else if (char == '\'' || char == '"') quote = char
                else if (char == '>') break
                at++
            }
            if (at == text.length) break
            val tag = Tag(start, at + 1, name, closing, text.substring(start, at).trimEnd().endsWith('/'))
            add(tag)
            cursor = tag.end
            if (!closing && name in rawText) {
                val end = Regex("</${Regex.escape(name)}\\s*>", RegexOption.IGNORE_CASE).find(text, cursor)
                    ?: break
                add(Tag(end.range.first, end.range.last + 1, name, true, false))
                cursor = end.range.last + 1
            }
        }
    }
}
