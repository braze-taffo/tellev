package app.tellev.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun `fenced code block survives as a pre so the host can scroll it sideways`() {
        val rendered = MarkdownRenderer.render(
            "```\n[澳洲野人杰克]: \"哥们刚测试了太阳能发电机，剩余电量 97%\"\n```",
        )

        // 代码块必须以 <pre> 形态到达 WebView：宿主样式只对 pre 生效，
        // 一旦管线把它改写成折行段落，横向滚动的修复就失效了。
        assertTrue(rendered.contains("<pre>"))
        assertTrue(rendered.contains("<code"))
        // 行内长文本保持原样，不在 Kotlin 侧被截断或改写。
        assertTrue(rendered.contains("剩余电量 97%"))
        assertFalse(rendered.contains("<br"))
    }

    @Test
    fun `plain prose without markdown markers stays off the webview path`() {
        assertFalse(MarkdownRenderer.looksLikeMarkdown("今天天气不错，出门散步。"))
        assertTrue(MarkdownRenderer.looksLikeMarkdown("第一行\n\n第二行\n- 列表项"))
    }
}
