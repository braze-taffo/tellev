package app.tellev.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole

class TavernMessageCompatTest {
    @Test
    fun `message webview reloads only when rendered html changes`() {
        val tracker = TavernMessageLoadTracker()

        assertTrue(tracker.shouldLoad("<html>first</html>"))
        assertTrue(!tracker.shouldLoad("<html>first</html>"))
        assertTrue(tracker.shouldLoad("<html>second</html>"))
    }

    @Test
    fun `webview hands drag to chat list only at its scroll edge`() {
        assertTrue(!shouldForwardWebViewDragToChat(canScrollInDirection = true))
        assertTrue(shouldForwardWebViewDragToChat(canScrollInDirection = false))
        assertEquals(0f, chatScrollDeltaAtWebViewEdge(true, 25f))
        assertEquals(-25f, chatScrollDeltaAtWebViewEdge(false, 25f))
        assertEquals(25f, chatScrollDeltaAtWebViewEdge(false, -25f))
    }

    @Test
    fun `routes status panel send and trigger command`() {
        val action = parseTavernMessageSlashCommand("/send 传讯玉简回复 瑶汐：「收到」 || /trigger")

        assertEquals("传讯玉简回复 瑶汐：「收到」", action.sendText)
        assertNull(action.systemText)
    }

    @Test
    fun `routes character creator system cut and trigger command`() {
        val action = parseTavernMessageSlashCommand(
            "/sys [仙缘已定]\n**道号**: 清玄 | /cut 3 | /trigger",
        )

        assertEquals("[仙缘已定]\n**道号**: 清玄", action.systemText)
        assertEquals(3, action.deleteMessageIndex)
    }

    @Test
    fun `parses raw prompt pipe used by daoyuan start button`() {
        val action = parseTavernMessageSlashCommand(
            "[角色创建信息]\\n姓名：沈砚\\n[仙途开启中...] | /cut 0 | /trigger",
        )

        assertEquals("[角色创建信息]\\n姓名：沈砚\\n[仙途开启中...]", action.systemText)
        assertEquals(0, action.deleteMessageIndex)
    }

    @Test
    fun `parses standalone cut command`() {
        val action = parseTavernMessageSlashCommand("/cut 17")

        assertEquals(17, action.deleteMessageIndex)
        assertNull(action.sendText)
        assertNull(action.systemText)
    }

    @Test
    fun `routes setinput command without generating`() {
        val action = parseTavernMessageSlashCommand("/setinput 查看当前机遇")

        assertEquals("查看当前机遇", action.setInputText)
        assertNull(action.sendText)
    }

    @Test
    fun `decodes stringified MVU stat data into an object`() {
        val raw = Json.parseToJsonElement(
            """{"stat_data":"{\"主角\":{\"神识\":42}}"}""",
        )

        val decoded = decodeEmbeddedJsonValues(raw).jsonObject

        assertEquals(
            42,
            decoded["stat_data"]!!.jsonObject["主角"]!!.jsonObject["神识"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `maps creator world entry fields`() {
        val raw = Json.parseToJsonElement(
            """{
                "type":"constant",
                "position":"before_character_definition",
                "order":100,
                "exclude_recursion":true,
                "prevent_recursion":true,
                "comment":"功法设定",
                "content":"太虚玄经"
            }""".trimIndent(),
        ) as JsonObject

        val entry = tavernWorldBookEntry(raw, "0")

        assertTrue(entry.constant)
        assertEquals(0, entry.position)
        assertEquals(100, entry.insertionOrder)
        assertTrue(entry.excludeRecursion)
        assertTrue(entry.preventRecursion)
        assertEquals("太虚玄经", entry.content)
    }

    @Test
    fun `compat script exposes APIs used by Dao Yuan frontend`() {
        val script = tavernMessageCompatScript()

        listOf(
            "window.getAllVariables",
            "window.triggerSlash",
            "window.getLorebooks",
            "window.createLorebook",
            "window.createLorebookEntry",
            "window.getChatMessages",
            "window.setChatMessage",
            "window.TavernHelper.getChatMessages",
            "window.TavernHelper.setChatMessage",
            "window.errorCatched",
            "window.$",
            "window._",
        ).forEach { symbol -> assertTrue("missing $symbol", script.contains(symbol)) }
    }

    @Test
    fun `host html uses theme colors auto contrast and details resize`() {
        val wrapped = wrapTavernHtml("<body style='color:white'>hello<details><summary>x</summary>y</details></body>", "#FAFAFA", "#111111")

        assertTrue(wrapped.contains("color: #111111"))
        assertFalse(wrapped.contains("color: #f7f7f7"))
        assertTrue(wrapped.contains("tellev-auto-contrast"))
        assertTrue(wrapped.contains("#000000"))
        assertTrue(wrapped.contains("#ffffff"))
        assertTrue(tavernResizeScript().contains("document.addEventListener('toggle'"))
    }

    @Test
    fun `setChatMessage switches welcome message to requested alternate swipe`() {
        val welcome = ChatMessage(
            id = "0", role = MessageRole.Character, name = "道渊", content = "欢迎页",
            createdAtMillis = 0, swipes = listOf("欢迎页", "[重塑仙缘]"), swipeIndex = 0,
        )

        val updated = setTavernMessageSwipe(welcome, "[重塑仙缘]", requestedSwipe = 1)

        assertEquals(1, updated.swipeIndex)
        assertEquals("[重塑仙缘]", updated.content)
        assertEquals(listOf("欢迎页", "[重塑仙缘]"), updated.swipes)
    }

    @Test
    fun `layout script preserves nested card scroll and bridges only its edges`() {
        val script = tavernMessageLayoutScript(462)

        assertTrue(script.contains("window.scrollTo(0, 0)"))
        assertTrue(script.contains("hasOversizedFlowChild"))
        assertTrue(script.contains("justify-content', 'flex-start"))
        assertTrue(script.contains("findNestedScrollOwner"))
        assertTrue(script.contains("overflowY === 'auto' || overflowY === 'scroll'"))
        assertTrue(script.contains("TellevBridge.setNestedScrollGesture(!!owner)"))
        assertTrue(script.contains("if (!canScrollInDirection)"))
        assertTrue(script.contains("TellevBridge.forwardBoundaryDrag(-fingerDeltaY)"))
        assertTrue(script.contains("activeScreen.style.setProperty('overflow-y', 'auto'"))
        assertTrue(script.contains("activeScreen.scrollTop = 0"))
        assertTrue(script.contains("activeScreenChanged"))
        assertTrue(script.contains("if (activeScreenChanged)"))
        assertFalse(script.contains("position: relative !important"))
        assertFalse(script.contains("overflow-y: visible !important"))
        assertTrue(script.contains("document.fonts.ready"))
        assertTrue(script.contains("nativeViewportHeight = 462"))
        assertTrue(script.contains("nativeViewportHeight > 0"))
        assertTrue(script.contains("new MutationObserver(resetMessageViewport)"))
        assertTrue(script.contains("setTimeout(resetMessageViewport, 1000)"))
    }
}
