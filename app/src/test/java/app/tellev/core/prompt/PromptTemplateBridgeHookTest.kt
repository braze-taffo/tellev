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

        override fun evaluate(request: JsonObject): JsonObject {
            val template = request["template"].toString()
            evaluated += template
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
}
