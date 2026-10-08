package app.tellev.core.prompt

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PromptMacroContextTest {
    private val history = listOf(
        message("u1", MessageRole.User, "我是李三"),
        message("a1", MessageRole.Character, "李三来到了乌坦城。"),
    )
    private fun message(id: String, role: MessageRole, content: String) =
        ChatMessage(id, role, if (role == MessageRole.User) "User" else "斗破苍穹", content, 1L)

    // The incident preset's history and latest-input structure, without unrelated content/scripts.
    private val preset = GenerationPreset("dream-input", "Dream input", "openai-compatible", prompts = listOf(
        PresetPrompt("history-start", content = "<dream_history>", order = 0),
        PresetPrompt("chatHistory", order = 1),
        PresetPrompt("history-end", content = "</dream_history>", order = 2),
        PresetPrompt("writing", role = "user", content = "【最新输入】\n<dreamer_input>\n{{lastUserMessage}}\n</dreamer_input>", order = 3),
    ))
    private fun request(messages: List<ChatMessage> = history, input: String = "不，我是李四",
        macroMessages: List<ChatMessage>? = messages + message("current", MessageRole.User, input)) =
        PromptBuildRequest(CharacterCard("card", "斗破苍穹"), null, messages, emptyList(), preset,
            input, "openai-compatible", macroMessages = macroMessages)

    @Test fun `first input and correction use the current saved floor exactly once on the wire`() {
        for (prior in listOf(emptyList(), history)) {
            val req = request(prior)
            val context = PromptMacroContextBuilder.buildMacroContext(req)
            assertEquals(req.userInput, context.lastUserMessage)
            assertEquals(req.userInput, context.lastMessage)
            assertEquals(prior.size, context.lastUserMessageId)
            assertEquals(prior.size.toString(), context.lastMessageId)
            assertEquals(prior.lastOrNull()?.content.orEmpty(), context.lastCharMessage)
            val result = DefaultPromptEngine().build(req)
            assertEquals(1, result.messages.count { it.role == MessageRole.User && it.content == req.userInput })
            assertTrue(result.messages.any { it.content == "【最新输入】\n<dreamer_input>\n${req.userInput}\n</dreamer_input>" })
            assertEquals(prior, req.messages)
        }
    }

    @Test fun `same user text in separate floors is not deduplicated by content`() {
        val result = DefaultPromptEngine().build(request(input = "我是李三"))
        assertEquals(2, result.messages.count { it.content == "我是李三" })
    }

    @Test fun `next turn and EJS constants see current text and floor numbers`() {
        val prior = history + listOf(message("u2", MessageRole.User, "不，我是李四"), message("a2", MessageRole.Character, "李四走向集市。"))
        val req = request(prior, "去买东西")
        val context = PromptMacroContextBuilder.buildMacroContext(req)
        assertEquals("去买东西", context.lastUserMessage)
        val result = DefaultPromptTemplateProcessor().process(PromptTemplateRequest(
            messages = listOf(PromptMessage(MessageRole.System, content = "<%= lastUserMessage %>|<%= lastMessage %>|<%= lastMessageId %>|<%= lastUserMessageId %>|<%= lastCharMessageId %>")),
            context = context,
            metadata = JsonObject(emptyMap()),
        ))
        assertEquals("去买东西|去买东西|4|4|3", result.messages.single().content)
    }

    @Test fun `hidden floors keep saved IDs and only user character macros filter them`() {
        val snapshot = listOf(history[0], message("hidden1", MessageRole.User, "隐藏输入").copy(isHidden = true),
            history[1], message("current", MessageRole.User, "不，我是李四"),
            message("hidden2", MessageRole.Character, "隐藏回复").copy(isHidden = true))
        val context = PromptMacroContextBuilder.buildMacroContext(request(macroMessages = snapshot))
        assertEquals("不，我是李四", context.lastUserMessage)
        assertEquals("隐藏回复", context.lastMessage)
        assertEquals("4", context.lastMessageId)
        assertEquals(3, context.lastUserMessageId)
        assertEquals(2, context.lastCharMessageId)
    }

    @Test fun `generate templates can read the current input using its macro floor ID`() {
        val req = request()
        val result = DefaultPromptEngine().build(req.copy(preset = preset.copy(prompts = listOf(
            PresetPrompt("main", content = "EJS:<%= getChatMessage(lastUserMessageId) %>|<%= lastUserMessage %>", order = -1),
        ) + preset.prompts)))
        assertTrue(result.messages.any { it.content == "EJS:不，我是李四|不，我是李四" })
        assertEquals(1, result.messages.count { it.content == req.userInput })
    }

    @Test fun `quiet and independent helper input do not become saved user messages`() {
        for (quiet in listOf<String?>(null, "后台提取场景")) {
            val req = request(input = "后台指令", macroMessages = null).copy(quietPrompt = quiet)
            val context = PromptMacroContextBuilder.buildMacroContext(req)
            assertEquals("我是李三", context.lastUserMessage)
            assertEquals(history.last().content, context.lastMessage)
            assertEquals("后台指令", context.inputText)
            assertEquals(0, context.lastUserMessageId)
            val result = DefaultPromptEngine().build(req)
            assertTrue(result.messages.any { it.content.contains("<dreamer_input>\n我是李三\n</dreamer_input>") })
            if (quiet != null) assertFalse(result.messages.any { it.content == "后台指令" })
        }
    }

    @Test fun `imported duplicate message IDs do not overwrite distinct template floors`() {
        val floors = listOf(message("imported-id", MessageRole.User, "第一条"), message("imported-id", MessageRole.Character, "第二条"))
        val req = request(floors, macroMessages = null).copy(preset = preset.copy(prompts = listOf(
            PresetPrompt("main", content = "EJS:<%= getChatMessage(0) %>|<%= getChatMessage(1) %>", order = -1),
        ) + preset.prompts))
        assertTrue(DefaultPromptEngine().build(req).messages.any { it.content == "EJS:第一条|第二条" })
    }

    @Test fun `selected swipe content and variable snapshots remain scoped to macro floors`() {
        val vars = buildJsonObject { put("name", "李四") }
        val current = message("current", MessageRole.User, "旧输入").copy(swipes = listOf("旧输入", "不，我是李四"), swipeIndex = 1,
            variables = listOf(JsonObject(emptyMap()), vars))
        val context = PromptMacroContextBuilder.buildMacroContext(request(macroMessages = history + current))
        assertEquals("不，我是李四", context.lastUserMessage)
        assertEquals(vars, context.messageVariables)
    }
}
