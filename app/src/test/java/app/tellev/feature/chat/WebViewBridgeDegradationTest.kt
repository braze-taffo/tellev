package app.tellev.feature.chat

import app.tellev.core.extension.RuntimeToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TELLER-1-8：会话 retirement 到 UI 状态切换的窗口内，旧消息面板仍在渲染、桥接口未摘除，
 * JS 的同步读会撞上 requireMessageRuntime 的 check（ChatSessionRuntime.kt:235）。
 * 读入口必须给出降级载荷，并且 getContext 的载荷必须结构完整——message.js 读
 * c.chat（:19/:30/:35/:37）与 c.variableScopes[type]（:17）。
 */
class WebViewBridgeDegradationTest {
    /** ChatSessionRuntime.requireMessageRuntime 在 token 失配时抛的就是这个。 */
    private val retired = IllegalStateException("消息前端所属会话已失效")

    private fun bridge(
        contextJson: () -> String = { error("未预期的 contextJson 调用") },
        variablesJson: () -> String = { error("未预期的 variablesJson 调用") },
        readVariables: ((String) -> String)? = null,
    ): TavernMessageBridge = TavernMessageBridge(
        onHeightChanged = {},
        onBoundaryDrag = {},
        runtime = TavernMessageRuntime(
            token = RuntimeToken("session-1", 7, "native"),
            messageIndex = 4,
            variablesJson = variablesJson,
            contextJson = contextJson,
            request = { _, _, _ -> },
            readVariables = readVariables,
        ),
        documentId = "document-1",
    )

    @Test
    fun `retired session read degrades instead of throwing back to JS`() {
        val page = bridge(contextJson = { throw retired }, variablesJson = { throw retired })

        val context = Json.parseToJsonElement(page.getContext()).jsonObject
        assertTrue(context.getValue("chat").jsonArray.isEmpty())
        assertEquals(
            setOf("global", "chat", "character", "preset"),
            context.getValue("variableScopes").jsonObject.keys,
        )
        context.getValue("variableScopes").jsonObject.forEach { (_, scope) ->
            assertTrue(scope.jsonObject.isEmpty())
        }
        assertEquals("{}", page.getAllVariables())
    }

    @Test
    fun `readVariables degrades to the no compact channel marker`() {
        assertEquals("null", bridge(readVariables = { throw retired }).readVariables("""{"kind":"all"}"""))
        assertEquals("null", bridge().readVariables("""{"kind":"all"}"""))
    }

    @Test
    fun `healthy runtime reads are passed through untouched`() {
        val context = """{"chat":[{"mes":"hi"}],"variableScopes":{"global":{"a":1}}}"""
        val variables = """{"a":1}"""
        val page = bridge(
            contextJson = { context },
            variablesJson = { variables },
            readVariables = { """{"ok":true,"value":{"a":1}}""" },
        )

        assertEquals(context, page.getContext())
        assertEquals(variables, page.getAllVariables())
        assertEquals("""{"ok":true,"value":{"a":1}}""", page.readVariables("""{"kind":"all"}"""))
    }

    @Test
    fun `closed bridge still answers with degraded payloads`() {
        val page = bridge(
            contextJson = { throw retired },
            variablesJson = { throw retired },
            readVariables = { throw retired },
        )
        // JVM 单测没有主 Looper：close() 清主线程队列会抛，但 closed 在此之前已置位；
        // 这里关心的正是置位之后的读取行为。
        runCatching { page.close() }

        assertTrue(page.isClosed())
        assertEquals(ChatTavernAdapter.emptyMessageContextJson(), page.getContext())
        assertEquals("{}", page.getAllVariables())
        assertEquals("""{"ok":false,"error":"Page retired"}""", page.readVariables("""{"kind":"all"}"""))
    }

    @Test
    fun `degraded context mirrors the scopes the live context builder emits`() {
        val payload = Json.parseToJsonElement(ChatTavernAdapter.emptyMessageContextJson()).jsonObject

        assertEquals(setOf("chat", "variableScopes"), payload.keys)
        val scopes = payload.getValue("variableScopes").jsonObject
        assertEquals(setOf("global", "chat", "character", "preset"), scopes.keys)
        scopes.forEach { (_, scope) -> assertTrue(scope.jsonObject.isEmpty()) }
    }
}
