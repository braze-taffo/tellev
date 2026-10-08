package app.tellev.core.prompt

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Goldens execute the latest three reference repositories, without Tellev bridge code. */
class ChatSnapshotUpstreamTest {
    private val golden = Json.parseToJsonElement(requireNotNull(javaClass.classLoader)
        .getResourceAsStream("fixtures/upstream-chat-snapshot.json")!!.bufferedReader().use { it.readText() }).jsonObject

    @Test fun `saved snapshots match upstream core macros template getters and variable macro`() {
        for (case in golden.getValue("cases").jsonArray) {
            val row = case.jsonObject
            val label = row.getValue("name").jsonPrimitive.content
            val messages = row.getValue("floors").jsonArray.mapIndexed { index, item ->
                val floor = item.jsonObject
                ChatMessage("floor-$index", MessageRole.valueOf(floor.getValue("role").jsonPrimitive.content),
                    "Fixture", floor.getValue("content").jsonPrimitive.content, 0L,
                    isHidden = floor.getValue("hidden").jsonPrimitive.boolean,
                    variables = floor.getValue("variables").jsonArray.toList(),
                    swipeIndex = floor.getValue("swipeIndex").jsonPrimitive.int)
            }
            val character = CharacterCard("fixture", "Fixture")
            val request = PromptBuildRequest(character, null, messages, emptyList(),
                GenerationPreset("fixture", "Fixture", "openai-compatible"), "", "openai-compatible")
            val built = PromptMacroContextBuilder.buildMacroContext(request)
            val edited = ChatTextProcessing.context(character, ChatSession("fixture", "Fixture", "fixture", null, messages), "User")
            val expected = row.getValue("expected").jsonObject
            for (context in listOf(built, edited)) {
                val core = expected.getValue("core").jsonObject
                assertEquals(label, core.getValue("lastMessage").jsonPrimitive.content, context.lastMessage)
                assertEquals(label, core.getValue("lastMessageId").jsonPrimitive.content, context.lastMessageId)
                assertEquals(label, core.getValue("lastUserMessage").jsonPrimitive.content, context.lastUserMessage)
                assertEquals(label, core.getValue("lastCharMessage").jsonPrimitive.content, context.lastCharMessage)
                // Upstream throws when no chat exists; this probe compares variable macros only on saved floors.
                expected["formattedState"]?.let { value ->
                    assertEquals(label, value.jsonPrimitive.content,
                        DefaultMacroEngine().expand("{{format_message_variable::stat_data}}", context))
                }
            }
            val template = expected.getValue("template").jsonObject
            val keys = listOf("lastUserMessage", "lastUserMessageId", "lastCharMessage", "lastCharMessageId", "lastMessageId")
            val text = keys.joinToString("|") { "<%= $it %>" }
            val actual = DefaultPromptTemplateProcessor().process(PromptTemplateRequest(
                messages = listOf(PromptMessage(MessageRole.System, content = text)),
                context = built, metadata = JsonObject(emptyMap()),
                chat = messages.mapIndexed { id, m -> PromptTemplateChatMessage(id, m.role == MessageRole.User, m.isHidden, m.name, m.content) },
            )).messages.single().content
            assertEquals(label, keys.joinToString("|") { template.getValue(it).jsonPrimitive.content }, actual)
        }
    }

    @Test fun `template current floor remains addressable past hidden history`() {
        val bridgeRequests = mutableListOf<JsonObject>()
        val bridge = object : PromptTemplateJsBridge {
            override fun evaluate(request: JsonObject): JsonObject {
                bridgeRequests += request
                return buildJsonObject { put("content", "rendered") }
            }
        }
        val messages = listOf(
            ChatMessage("old", MessageRole.User, "User", "old", 0L),
            ChatMessage("hidden", MessageRole.Character, "Fixture", "hidden", 0L, isHidden = true),
            ChatMessage("reply", MessageRole.Character, "Fixture", "reply", 0L),
            ChatMessage("current", MessageRole.User, "User", "<%= message_id %>|<%= getChatMessage(lastUserMessageId) %>", 0L),
        )
        val result = DefaultPromptEngine(promptTemplateProcessor = DefaultPromptTemplateProcessor(javascriptEvaluator = bridge)).build(PromptBuildRequest(
            CharacterCard("fixture", "Fixture"), null, messages.dropLast(1), emptyList(),
            GenerationPreset("fixture", "Fixture", "openai-compatible"), messages.last().content, "openai-compatible",
            macroMessages = messages,
        ))
        val captured = bridgeRequests.single()
        assertEquals(3, captured.getValue("messageContext").jsonObject.getValue("message_id").jsonPrimitive.int)
        assertEquals(listOf(0, 1, 2, 3), captured.getValue("chat").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int })
        assertEquals(messages.last().content, captured.getValue("chat").jsonArray[3].jsonObject.getValue("mes").jsonPrimitive.content)
        assertFalse(result.messages.any { it.content == "hidden" })
    }

    @Test fun `isolated historical template reads the same saved chat snapshot`() {
        val messages = listOf(
            ChatMessage("old", MessageRole.User, "User", "old", 0L),
            ChatMessage("hidden", MessageRole.Character, "Fixture", "hidden", 0L, isHidden = true),
            ChatMessage("reply", MessageRole.Character, "Fixture", "<%= getChatMessage(lastUserMessageId) %>", 0L),
            ChatMessage("current", MessageRole.User, "User", "current", 0L),
        )
        val result = DefaultPromptEngine().build(PromptBuildRequest(
            CharacterCard("fixture", "Fixture"), null, messages.dropLast(1), emptyList(),
            GenerationPreset("fixture", "Fixture", "openai-compatible"), "current", "openai-compatible",
            macroMessages = messages,
        ))
        assertTrue(result.messages.any { it.role == MessageRole.Assistant && it.content == "current" })
    }
}
