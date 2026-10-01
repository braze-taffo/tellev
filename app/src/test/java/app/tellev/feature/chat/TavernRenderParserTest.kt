package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRenderParserTest {
    @Test fun `script strings and quoted attributes do not terminate the card`() {
        val html = "<div class=\"card\" title=\"a > b\"><script>const closing='</div>';</script><span>Visible</span></div>"
        assertEquals(listOf(TavernRenderSegment.Frontend(html)), TavernRenderParser.parseBody(html))
    }
    @Test fun `commented card content is never promoted into a frontend`() {
        val html = "<!-- <div class='obsolete'>hidden</div> -->"
        assertEquals(listOf(TavernRenderSegment.Text(html)), TavernRenderParser.parseBody(html))
    }
    @Test fun `all adjacent style and script blocks belong to the same card`() {
        for (html in listOf(
            "<style>.card{color:red}</style><script>window.seed=7</script><div class='card'>Hello</div>",
            "<style>body{color:red}</style><script>window.seed=7</script><body>Hello</body><script>window.loaded=true</script><style>body{padding:2px}</style>",
        )) assertEquals(listOf(TavernRenderSegment.Frontend(html)), TavernRenderParser.parseBody(html))
    }
    @Test
    fun `fenced card does not leave the following MVU log as source text`() {
        val card = "<body><div>梦境大讨论</div></body>"
        val log = "<div style=\"width: 80%; margin: 20px auto;\"><details class=\"mvu-update-done\"><summary>变量更新</summary><div><Analysis>初始化</Analysis></div></details></div>"
        val text = "正文\n```html\n$card\n```\n$log\n后文"
        assertEquals(listOf(TavernRenderSegment.Text("正文"), TavernRenderSegment.Frontend(card),
            TavernRenderSegment.Frontend(log), TavernRenderSegment.Text("后文")), TavernRenderParser.parseBody(text))
    }

    @Test
    fun `raw document and fragments are rendered on both sides`() {
        val first = "<details><summary>before</summary>one</details>"
        val card = "<html><body>card</body></html>"
        val last = "<div class='log'><details>after</details></div>"
        assertEquals(listOf(TavernRenderSegment.Frontend(first), TavernRenderSegment.Frontend(card),
            TavernRenderSegment.Frontend(last)), TavernRenderParser.parseBody("$first\n$card\n$last"))
    }

    @Test
    fun `literal fenced fragments stay text beside a rendered card`() {
        val sample = "```text\n<div class='log'><details>literal sample</details></div>\n```"
        val card = "<body>rendered card</body>"
        assertEquals(listOf(TavernRenderSegment.Text(sample), TavernRenderSegment.Frontend(card)),
            TavernRenderParser.parseBody("$sample\n```html\n$card\n```"))
    }

    @Test
    fun `keeps dialogue around frontend fenced block`() {
        val text = """
            Character: here is the panel.
            ```html
            <!doctype html>
            <html><body><main>ready</main></body></html>
            ```
            Character: panel is above.
        """.trimIndent()

        val segments = TavernRenderParser.parse(text)

        assertEquals(3, segments.size)
        assertEquals(TavernRenderSegment.Text("Character: here is the panel."), segments[0])
        assertTrue(segments[1] is TavernRenderSegment.Frontend)
        assertEquals(TavernRenderSegment.Text("Character: panel is above."), segments[2])
    }

    @Test
    fun `uses TavernHelper frontend detection for code fences`() {
        val divOnly = """
            ```html
            <div>not enough for TavernHelper rendering</div>
            ```
        """.trimIndent()
        val bodyPage = """
            ```html
            <body><div>render me</div></body>
            ```
        """.trimIndent()

        assertFalse(TavernRenderParser.parse(divOnly).any { it is TavernRenderSegment.Frontend })
        assertTrue(TavernRenderParser.parse(bodyPage).single() is TavernRenderSegment.Frontend)
    }

    @Test
    fun `supports multiple frontend fenced blocks`() {
        val text = """
            ```html
            <html><body>one</body></html>
            ```
            middle
            ```html
            <html><body>two</body></html>
            ```
        """.trimIndent()

        val segments = TavernRenderParser.parse(text)

        assertEquals(3, segments.size)
        assertTrue(segments[0] is TavernRenderSegment.Frontend)
        assertEquals(TavernRenderSegment.Text("middle"), segments[1])
        assertTrue(segments[2] is TavernRenderSegment.Frontend)
    }

    @Test
    fun `keeps raw html document fallback for existing project behavior`() {
        val text = "before\n<html><body>raw</body></html>\nafter"

        val segments = TavernRenderParser.parse(text)

        assertEquals(
            listOf(
                TavernRenderSegment.Text("before"),
                TavernRenderSegment.Frontend("<html><body>raw</body></html>"),
                TavernRenderSegment.Text("after"),
            ),
            segments,
        )
    }

    @Test
    fun `renders raw SillyTavern html fragments outside code fences`() {
        val text = """
            Contract completed.

            <div class="matte-black-log" style="width: 98%">
              <details><summary>Variable update log</summary><div>updated</div></details>
            </div>

            <style>
              .matte-black-log summary { color: #a9b1d6; }
            </style>
        """.trimIndent()

        val segments = TavernRenderParser.parse(text)

        assertEquals(2, segments.size)
        assertEquals(TavernRenderSegment.Text("Contract completed."), segments[0])
        assertTrue(segments[1] is TavernRenderSegment.Frontend)
        val html = (segments[1] as TavernRenderSegment.Frontend).html
        assertTrue(html.contains("<div class=\"matte-black-log\""))
        assertTrue(html.contains("<style>"))
    }

    @Test
    fun `does not render div fragments inside non frontend code fences`() {
        val text = """
            ```html
            <div class="matte-black-log"><details>code sample</details></div>
            ```
        """.trimIndent()

        val segments = TavernRenderParser.parse(text)

        assertEquals(listOf(TavernRenderSegment.Text(text)), segments)
    }

    @Test
    fun `keeps adjacent style with raw body fallback`() {
        val text = "before\n<style>body{margin:0}</style>\n<body>raw</body>\nafter"

        val segments = TavernRenderParser.parse(text)

        assertEquals(3, segments.size)
        assertEquals(TavernRenderSegment.Text("before"), segments[0])
        assertEquals(
            TavernRenderSegment.Frontend("<style>body{margin:0}</style>\n<body>raw</body>"),
            segments[1],
        )
        assertEquals(TavernRenderSegment.Text("after"), segments[2])
    }

    @Test
    fun `splits reasoning tag into a separate segment`() {
        val text = "<reasoning>let me think\nstep by step</reasoning>\nfinal answer"

        val segments = TavernRenderParser.parse(text)

        assertEquals(2, segments.size)
        assertEquals(TavernRenderSegment.Reasoning("let me think\nstep by step"), segments[0])
        assertEquals(TavernRenderSegment.Text("final answer"), segments[1])
    }

    @Test
    fun `preserves think tags occurring inside body`() {
        val openThink = "<" + "think" + ">"
        val closeThink = "</" + "think" + ">"
        val text = "preamble\n${openThink}hidden reasoning${closeThink}\nreply"

        val segments = TavernRenderParser.parse(text)

        assertEquals(listOf(TavernRenderSegment.Text(text)), segments)
    }

    @Test
    fun `reasoning block can sit before a frontend block`() {
        val text = "<reasoning>plan</reasoning>\n```html\n<html><body>x</body></html>\n```"

        val segments = TavernRenderParser.parse(text)

        assertEquals(2, segments.size)
        assertEquals(TavernRenderSegment.Reasoning("plan"), segments[0])
        assertTrue(segments[1] is TavernRenderSegment.Frontend)
    }

    @Test
    fun `reasoning only message collapses body to a single reasoning segment`() {
        val text = "<reasoning>just thinking</reasoning>"

        val segments = TavernRenderParser.parse(text)

        assertEquals(1, segments.size)
        assertEquals(TavernRenderSegment.Reasoning("just thinking"), segments[0])
    }
    @Test fun `literal unclosed script in a code fence does not swallow the following card`() {
        val text = "```javascript\nconst example = '<script>';\n```\n<div class='card'>visible</div>"
        val parts = TavernRenderParser.parseBody(text)
        assertEquals(2, parts.size)
        assertEquals("<div class='card'>visible</div>", (parts.last() as TavernRenderSegment.Frontend).html)
        assertTrue((parts.first() as TavernRenderSegment.Text).text.contains("const example"))
    }

    @Test fun `raw head body and doctype document preserve document boundaries`() {
        val document = "<!DOCTYPE html><html><head><style>.card{color:red}</style></head><body>visible</body></html>"
        assertEquals(listOf(TavernRenderSegment.Frontend(document)), TavernRenderParser.parseBody(document))
        val headBody = "<head><style>.card{color:red}</style></head><body><div class='card'>visible</div></body>"
        assertEquals(listOf(TavernRenderSegment.Frontend(headBody)), TavernRenderParser.parseBody(headBody))
    }

}
