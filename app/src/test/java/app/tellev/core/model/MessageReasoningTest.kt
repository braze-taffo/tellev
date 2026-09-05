package app.tellev.core.model

import app.tellev.feature.chat.withRegeneratedSwipe
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageReasoningTest {
    private fun message(text: String) = ChatMessage("m", MessageRole.Character, "C", text, 1L)

    @Test fun `legacy timestamp-only swipe info keeps imported active reasoning`() {
        val imported = message("first").copy(
            swipes = listOf("first", "second"),
            metadata = buildJsonObject { put("reasoning", "imported") },
            raw = buildJsonObject { put("swipe_id", 0) },
            swipeInfo = listOf(buildJsonObject { put("send_date", "date") }),
        )
        assertEquals("imported", imported.reasoningParts().reasoning)
        val switched = imported.selectReasoningSwipe(1)
        assertEquals("", switched.reasoningParts().reasoning)
        assertEquals("imported", switched.selectReasoningSwipe(0).reasoningParts().reasoning)
        assertEquals("date", (switched.swipeInfo[0] as JsonObject)["send_date"]!!.jsonPrimitive.content)
    }

    @Test fun `default diagnostics omit response content`() {
        val saved = message("body").withGenerationReasoning(
            MessageReasoning.Parts("body", "thought"), "body", "thought", "stop", false,
        )
        assertFalse(saved.generationDiagnostics()!!.containsKey("response"))
        assertTrue(saved.generationDiagnostics(includeResponse = true)!!.containsKey("response"))
    }

    @Test fun `only a complete same-name leading block is parsed`() {
        assertEquals(MessageReasoning.Parts("body", "thought", "parsed"), MessageReasoning.split(" \n<ThInK>thought</THINK>\nbody"))
        listOf(
            "<think>unclosed body", "<think>thought</reasoning>body",
            "<reasoning><think>nested</think>body</reasoning>",
            "body <think>example</think>", "```html\n<think>example</think>\n```",
            "<html><body><think>example</think></body></html>",
        ).forEach { original ->
            val result = MessageReasoning.split(original)
            assertEquals(original, result.body)
            assertEquals("", result.reasoning)
        }
    }

    @Test fun `API reasoning cannot consume body even with delimiter text`() {
        val result = MessageReasoning.fromResponse("body", "example </reasoning><think>unfinished")
        assertEquals("body", result.body)
        assertEquals("example </reasoning><think>unfinished", result.reasoning)
    }

    @Test fun `retry and interrupted variants retain their own reasoning including empty slots`() {
        val old = message("<reasoning>old</reasoning>first")
        val second = old.withRegeneratedSwipe("second").withGenerationReasoning(
            MessageReasoning.Parts("second", "new"), "second", "new", "stop", true,
        )
        val third = second.withRegeneratedSwipe("").withGenerationReasoning(
            MessageReasoning.Parts("", "partial"), "", "partial", "interrupted", true,
        )
        val fourth = third.withRegeneratedSwipe("fourth").withGenerationReasoning(
            MessageReasoning.Parts("fourth"), "fourth", "", "stop", true,
        )
        val restored = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), fourth))
        assertEquals(listOf("old", "new", "partial", ""), (0..3).map { restored.selectReasoningSwipe(it).reasoningParts().reasoning })
        assertEquals(listOf("first", "second", "", "fourth"), (0..3).map { restored.selectReasoningSwipe(it).reasoningParts().body })
        assertEquals("<reasoning>old</reasoning>first", restored.swipes[0])
        assertEquals("", restored.metadata["reasoning"]?.jsonPrimitive?.content)
    }

    @Test fun `imported Tavern extra is retained when adding first retry`() {
        val old = message("first").copy(metadata = buildJsonObject { put("reasoning", "imported") })
        val next = old.withRegeneratedSwipe("next").withGenerationReasoning(MessageReasoning.Parts("next"), "next", "", "stop", true)
        assertEquals("imported", next.selectReasoningSwipe(0).reasoningParts().reasoning)
        assertEquals("", next.reasoningParts().reasoning)
    }

    @Test fun `user tags are never removed`() {
        val user = message("<think>literal</think>question").copy(role = MessageRole.User)
        assertEquals(user.content, user.reasoningParts().body)
    }
}
