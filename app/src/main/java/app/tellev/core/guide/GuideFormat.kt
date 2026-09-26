package app.tellev.core.guide

/** 指引的一页：一个功能（新手引导里则是一步）——做什么、在哪找到、怎么用。 */
internal data class GuidePage(
    val title: String,
    val summary: String,
    val where: List<String>,
    val steps: List<String>,
)

internal data class GuideDocument(val pages: List<GuidePage>)

/**
 * 指引资产（`app/src/main/assets/guide` 下每语言一份）的解析器。
 *
 * 格式刻意做成语言中立：`## ` 起一页，分节用 HTML 注释标记（`<!-- where -->`、
 * `<!-- steps -->`），于是译文可以自由翻译正文，解析器不必去认「在哪找到」
 * 这类会被翻译的标签。
 *
 * 结构不完整就抛 [IllegalArgumentException]，不做静默降级：半截指引比一个
 * 明确的加载失败更难查，而资产守卫测试会保证随包发布的每份资产都解析得出来。
 */
internal object GuideFormat {
    private const val TITLE_PREFIX = "## "
    private const val SUMMARY_MARKER = "<!-- summary -->"
    private const val WHERE_MARKER = "<!-- where -->"
    private const val STEPS_MARKER = "<!-- steps -->"

    fun parse(source: String): GuideDocument {
        val pages = mutableListOf<GuidePage>()
        var builder: PageBuilder? = null
        for (rawLine in source.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("<!--") && line.endsWith("-->")) {
                when (line) {
                    SUMMARY_MARKER -> builder?.section = Section.Summary
                    WHERE_MARKER -> builder?.section = Section.Where
                    STEPS_MARKER -> builder?.section = Section.Steps
                    // 其余注释（含文件头的格式说明）忽略。
                }
                continue
            }
            if (line.startsWith(TITLE_PREFIX)) {
                builder?.let { pages += it.build() }
                builder = PageBuilder(line.removePrefix(TITLE_PREFIX).trim())
                continue
            }
            val current = builder ?: continue
            when (current.section) {
                Section.Summary -> if (line.isNotEmpty()) current.summaryLines += line
                Section.Where -> bulletText(line)?.let { current.where += it }
                Section.Steps -> stepText(line)?.let { current.steps += it }
                null -> Unit
            }
        }
        builder?.let { pages += it.build() }
        require(pages.isNotEmpty()) { "guide asset has no '## ' page" }
        return GuideDocument(pages)
    }

    private enum class Section { Summary, Where, Steps }

    private class PageBuilder(private val title: String) {
        // 标题之后默认就在摘要段：`<!-- summary -->` 标记可以写也可以省，
        // 省掉标记时也不会把摘要吞掉。
        var section: Section? = Section.Summary
        val summaryLines = mutableListOf<String>()
        val where = mutableListOf<String>()
        val steps = mutableListOf<String>()

        fun build(): GuidePage {
            require(title.isNotEmpty()) { "guide page has an empty title" }
            require(summaryLines.isNotEmpty()) { "guide page '$title' has no summary" }
            require(where.isNotEmpty()) { "guide page '$title' has no 'where' entry" }
            require(steps.isNotEmpty()) { "guide page '$title' has no 'steps' entry" }
            return GuidePage(
                title = title,
                // 摘要按段落排版时可能被折成多行，拼回一句。
                summary = summaryLines.joinToString(" "),
                where = where.toList(),
                steps = steps.toList(),
            )
        }
    }

    private fun bulletText(line: String): String? {
        val body = when {
            line.startsWith("- ") -> line.removePrefix("- ")
            line.startsWith("* ") -> line.removePrefix("* ")
            else -> return null
        }.trim()
        return body.ifEmpty { null }
    }

    private fun stepText(line: String): String? {
        val match = Regex("""^(\d+)[.)]\s+(.+)$""").find(line) ?: return null
        return match.groupValues[2].trim().ifEmpty { null }
    }
}
