package app.tellev.feature.chat

import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.CharacterImporter
import org.junit.Assert.*
import org.junit.Test

class TavernMessageRendererTest {
    private val card = CharacterImporter().importFromJson(
        requireNotNull(javaClass.getResource("/qunxing-regex.json")).readText(),
    )

    @Test fun `standard fields override RP Hub aliases and bare slash patterns work`() {
        val mixed = CharacterImporter().importFromJson("""{"name":"test","extensions":{"regex_scripts":[
            {"findRegex":"/x/g","regex":"y","flags":"bad","replaceString":"ok","replacement":"wrong","placement":[2],"markdownOnly":true},
            {"regex":"a/b","flags":"gi","replacement":"slash","placement":[2],"markdownOnly":true}
        ]}}""")
        assertEquals("ok ok slash slash", CharacterRegexApplier.applyForDisplay("x x a/b A/B", MessageRole.Character, mixed))
    }

    @Test fun `actual Qunxing regex wraps only body and retains the complete HTML panel`() {
        val segments = renderMessageParts(
            MessageReasoning.Parts("阿诺玛丽『报告』\n（日志一）\n（日志二）", "API thought"),
            MessageRole.Character, card, null, "User", 0, false,
        )
        assertEquals(TavernRenderSegment.Reasoning("API thought"), segments.first())
        assertEquals(2, segments.size)
        val html = (segments[1] as TavernRenderSegment.Frontend).html
        assertTrue(html.startsWith("<div"))
        assertTrue(html.endsWith("</div>"))
        assertEquals(2, Regex("系统底层日志").findAll(html).count())
        assertFalse(html.contains("API thought"))
        assertTrue(html.contains("「报告」"))
    }

    @Test fun `RP Hub display rules do not enter history`() {
        assertEquals("原文", CharacterRegexApplier.applyForPrompt("原文", MessageRole.Character, card, depth = 0))
    }

    @Test fun `display generated think tags remain body`() {
        val tagged = CharacterImporter().importFromJson("""{"name":"test","extensions":{"regex_scripts":[
            {"findRegex":"/^(.+)$/g","replaceString":"<think>${'$'}1</think>","placement":[2],"markdownOnly":true}
        ]}}""")
        val segments = renderMessageParts(MessageReasoning.Parts("body"), MessageRole.Character, tagged, null, "User", 0, false)
        assertEquals(listOf(TavernRenderSegment.Text("<think>body</think>")), segments)
    }

    @Test fun `reasoning placement never formats body`() {
        val separate = CharacterImporter().importFromJson("""{"name":"test","extensions":{"regex_scripts":[
            {"findRegex":"/word/g","replaceString":"thought","placement":[6],"markdownOnly":true}
        ]}}""")
        val segments = renderMessageParts(MessageReasoning.Parts("word", "word"), MessageRole.Character, separate, null, "User", 0, false)
        assertEquals(listOf(TavernRenderSegment.Reasoning("thought"), TavernRenderSegment.Text("word")), segments)
    }

    @Test fun `orphan MVU anchor is hidden from assistant display without changing source or prompt`() {
        val source = "剧情正文\n\n<StatusPlaceHolderImpl/>"
        val parts = MessageReasoning.Parts(source, "推理中的 <StatusPlaceHolderImpl/>")
        for (role in listOf(MessageRole.Character, MessageRole.Assistant)) {
            assertEquals(listOf(TavernRenderSegment.Reasoning(parts.reasoning), TavernRenderSegment.Text("剧情正文")),
                renderMessageParts(parts, role, null, null, "User", 0, false))
        }
        assertEquals(source, parts.body)
        assertEquals(source, CharacterRegexApplier.applyForPrompt(source, MessageRole.Character, null, depth = 0))
        assertTrue(renderMessageParts(MessageReasoning.Parts("<StatusPlaceHolderImpl/>"),
            MessageRole.Character, null, null, "User", 0, false).isEmpty())
    }

    @Test fun `card and preset rules consume anchor before leftover cleanup`() {
        val custom = CharacterImporter().importFromJson("""{"name":"test","extensions":{"regex_scripts":[
            {"findRegex":"<StatusPlaceHolderImpl/>","replaceString":"<div class=\"status\">状态正常</div>","placement":[2],"markdownOnly":true}
        ]}}""")
        val preset = app.tellev.core.model.GenerationPreset("test", "test", "openai-compatible",
            extensions = custom.raw["extensions"] as kotlinx.serialization.json.JsonObject)
        for ((selectedCard, selectedPreset) in listOf(custom to null, null to preset)) {
            val segments = renderMessageParts(MessageReasoning.Parts("<StatusPlaceHolderImpl/>"),
                MessageRole.Character, selectedCard, selectedPreset, "User", 0, false)
            assertEquals(listOf(TavernRenderSegment.Frontend("<div class=\"status\">状态正常</div>")), segments)
        }
    }

    @Test fun `frontend anchors and literal examples remain while orphan segment disappears`() {
        val frontend = "<div class=\"status\">\n<StatusPlaceHolderImpl/>\n</div>"
        assertEquals(listOf(TavernRenderSegment.Frontend(frontend)),
            renderMessageParts(MessageReasoning.Parts("$frontend\n\n<StatusPlaceHolderImpl/>"),
                MessageRole.Character, null, null, "User", 0, false))
        for (source in listOf(
            "说明：<StatusPlaceHolderImpl/>",
            "`<StatusPlaceHolderImpl/>`",
            "示例\n```xml\n<StatusPlaceHolderImpl/>\n```",
            "示例\n~~~~xml\n<StatusPlaceHolderImpl/>\n~~~\n<StatusPlaceHolderImpl/>\n~~~~",
            "示例\n    <StatusPlaceHolderImpl/>",
            "<OtherPlaceholder/>",
        )) {
            assertEquals(listOf(TavernRenderSegment.Text(source)),
                renderMessageParts(MessageReasoning.Parts(source), MessageRole.Character, null, null, "User", 0, false))
        }
        val userSource = "用户输入\n<StatusPlaceHolderImpl/>"
        assertEquals(listOf(TavernRenderSegment.Text(userSource)),
            renderMessageParts(MessageReasoning.Parts(userSource), MessageRole.User, null, null, "User", 0, false))
    }
}
