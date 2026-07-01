package app.tellev.feature.chat

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders message text as Markdown -> HTML on the Kotlin side, then feeds the HTML to
 * [TavernHtmlPanel] (which already renders "frontend" HTML messages via WebView). Doing
 * the markdown->HTML conversion in Kotlin rather than inside the WebView avoids per-message
 * JS execution on the hot rendering path.
 *
 * Most user messages and short plain-text AI replies never hit the WebView at all:
 * [looksLikeMarkdown] gates the conversion so plain text keeps using the native Compose
 * `Text()`.
 */
object MarkdownRenderer {
    private val extensions = listOf(TablesExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).build()

    /**
     * Heuristic check for Markdown syntax that warrants the WebView rendering path.
     * Plain text (no leading markers, no inline formatting) returns false so it stays on
     * the cheap native `Text()`.
     */
    private val feature = Regex(
        """(^|\n)\s{0,3}#{1,6}\s""" +              // ATX headings
            """|(^|\n)\s{0,3}[-*+]\s+""" +          // unordered list
            """|(^|\n)\s{0,3}\d+\.\s+""" +          // ordered list
            """|(^|\n)\s{0,3}>\s""" +              // blockquote
            """|(^|\n)\s{0,3}(-{3,}|\*{3,})\s*$""" + // thematic break
            """|```""" +                            // fenced code block
            """|`[^`]+`""" +                        // inline code
            """|\*\*[^*]+\*\*""" +                  // bold
            """|__[^_]+__""" +                      // bold (underscore)
            """|\[[^\]]+\]\([^)]+\)""" +            // link
            """|(^\s*\|.*\|\s*$)""",                // table row
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    fun looksLikeMarkdown(text: String): Boolean =
        text.length > 8 && feature.containsMatchIn(text)

    fun render(markdown: String): String = renderer.render(parser.parse(markdown))
}
