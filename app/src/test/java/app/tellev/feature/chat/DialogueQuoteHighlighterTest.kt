package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueQuoteHighlighterTest {
    @Test
    fun `recognizes all SillyTavern quote pairs`() {
        val text = """"ascii" “curly” «guillemets» 「corner」 『white』 ＂fullwidth＂"""

        assertEquals(6, DialogueQuoteHighlighter.findRanges(text).size)
        assertEquals(
            listOf("\"ascii\"", "“curly”", "«guillemets»", "「corner」", "『white』", "＂fullwidth＂"),
            DialogueQuoteHighlighter.findRanges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `matches multiple runs but never crosses a line or accepts an unmatched pair`() {
        val text = "\"one\" then “two”\n“not\nclosed” and \"unfinished"

        assertEquals(
            listOf("\"one\"", "“two”"),
            DialogueQuoteHighlighter.findRanges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `skips inline fenced style and html attribute code`() {
        val text = """
            keep “dialogue”
            `"inline"`
            ```kotlin
            val text = "fenced"
            ```
            <style>.x::after { content: "style"; }</style>
            <span title="attribute">outside 「text」</span>
        """.trimIndent()

        assertEquals(
            listOf("“dialogue”", "「text」"),
            DialogueQuoteHighlighter.findRanges(text).map { text.substring(it) },
        )
    }

    @Test
    fun `markdown wrapper preserves delimiters and emits q tags`() {
        val wrapped = DialogueQuoteHighlighter.wrapForMarkdown("Before **“hello”** after")
        val rendered = MarkdownRenderer.render("Before **“hello”** after", highlightDialogue = true)
        val host = wrapTavernHtml(rendered, "#111111", "#123456")

        assertEquals("Before **<q>“hello”</q>** after", wrapped)
        assertTrue(rendered.contains("<q>“hello”</q>"))
        assertFalse(rendered.contains("&lt;q&gt;"))
        assertTrue(host.contains("q { color: #123456; }"))
        assertTrue(host.contains("q::before, q::after { content: none; }"))
    }
}
