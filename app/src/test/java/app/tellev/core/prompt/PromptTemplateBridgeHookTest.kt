package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [DefaultPromptTemplateProcessor] through a fake [PromptTemplateJsBridge]
 * so the per-build lifecycle hooks (sticky decay, outlet scan) are asserted on
 * the bridge path itself — the seam the pure-Kotlin-registry tests cannot see.
 */
class PromptTemplateBridgeHookTest {

    private class RecordingBridge : PromptTemplateJsBridge {
        val evaluated = mutableListOf<String>()
        var deactivates = 0
        val outletCalls = mutableListOf<String>()
        var lastRequest: JsonObject? = null

        override fun evaluate(request: JsonObject): JsonObject {
            val template = request["template"].toString()
            evaluated += template
            lastRequest = request
            // Minimal echo of the JS contract: report empty scopes and content.
            return buildJsonObject {
                put("content", kotlinx.serialization.json.JsonPrimitive(""))
                put("local", buildJsonObject { })
                put("global", buildJsonObject { })
                put("message", buildJsonObject { })
                put("definitions", buildJsonObject { })
            }
        }

        override fun deactivateInjectedPrompts() {
            deactivates++
        }

        override fun replaceOutletPlaceholders(content: String): String {
            outletCalls += content
            return content.replace("{{outletPromptsInjected:bridgeK}}", "BRIDGE-OUTLET")
        }
    }

    @Before
    fun resetRegistry() {
        PromptInjectedRegistry.clear()
    }

    @Test
    fun `deactivate runs exactly once per build before rendering`() {
        val bridge = RecordingBridge()
        val processor = DefaultPromptTemplateProcessor(javascriptEvaluator = bridge)

        processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "A<%= 1 %>B"),
                    PromptMessage(role = MessageRole.User, content = "C<%= 2 %>D"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals(1, bridge.deactivates)
        assertEquals(2, bridge.evaluated.size)
    }

    @Test
    fun `outlet scan routes marker messages through the bridge`() {
        val bridge = RecordingBridge()
        val processor = DefaultPromptTemplateProcessor(javascriptEvaluator = bridge)

        val result = processor.process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(role = MessageRole.System, content = "plain"),
                    PromptMessage(role = MessageRole.User, content = "X{{outletPromptsInjected:bridgeK}}Y"),
                ),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals(1, bridge.outletCalls.size)
        assertEquals("XBRIDGE-OUTLETY", result.messages[1].content)
    }

    @Test
    fun `disabled settings never touch the bridge`() {
        val bridge = RecordingBridge()
        val processor = DefaultPromptTemplateProcessor(
            ejsSettings = app.tellev.core.extension.EjsTemplateSettings(enabled = false),
            javascriptEvaluator = bridge,
        )

        processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "<%= 1 %>")),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals(0, bridge.deactivates)
        assertTrue(bridge.evaluated.isEmpty())
    }

    @Test
    fun `render request carries the active character for the getchr family`() {
        val bridge = RecordingBridge()
        val processor = DefaultPromptTemplateProcessor(javascriptEvaluator = bridge)

        processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "A<%= 1 %>B")),
                context = MacroContext(
                    characterName = "玄泽",
                    characterDescription = "描述",
                    characterPersonality = "性格",
                    characterScenario = "场景",
                    firstMessage = "开场",
                    exampleMessages = "示例",
                    alternateGreetings = listOf("g1"),
                ),
                metadata = buildJsonObject { },
            ),
        )

        val character = bridge.lastRequest?.get("character") as? JsonObject
        assertEquals("玄泽", character?.get("name")?.toString()?.trim('"'))
        assertEquals("描述", character?.get("description")?.toString()?.trim('"'))
        assertEquals("性格", character?.get("personality")?.toString()?.trim('"'))
        assertEquals("场景", character?.get("scenario")?.toString()?.trim('"'))
        assertEquals("开场", character?.get("first_mes")?.toString()?.trim('"'))
        assertEquals("示例", character?.get("mes_example")?.toString()?.trim('"'))
        val data = character?.get("data") as? JsonObject
        assertEquals("[\"g1\"]", data?.get("alternate_greetings").toString())
    }

    @Test
    fun `render request worldCatalog carries raw entry fields`() {
        val bridge = RecordingBridge()
        val processor = DefaultPromptTemplateProcessor(javascriptEvaluator = bridge)

        processor.process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "A<%= 1 %>B")),
                context = MacroContext(),
                metadata = buildJsonObject { },
                worldCatalog = listOf(
                    PromptTemplateWorldEntry(
                        id = "e1",
                        content = "正文",
                        raw = buildJsonObject { put("key", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("玄泽")))) },
                        bookId = "char-book",
                        bookName = "玄泽书",
                        comment = "自我介绍",
                    ),
                ),
            ),
        )

        val catalog = bridge.lastRequest?.get("worldCatalog") as? kotlinx.serialization.json.JsonArray
        val entry = catalog?.single() as? JsonObject
        assertEquals("玄泽书", entry?.get("bookName")?.toString()?.trim('"'))
        val raw = entry?.get("raw") as? JsonObject
        assertEquals("[\"玄泽\"]", raw?.get("key").toString())
    }
}
