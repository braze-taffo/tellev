package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRenderParserTest {
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
}
