package app.tellev.core.prompt

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuietPromptTest {
    private val scene = "A magical girl and her familiar are walking home."
    private val quiet = "Describe the latest visible scene in English."
    private fun request() = PromptBuildRequest(
        character = CharacterCard("alice", "Alice", description = "Current scene: {{lastMessage}}", raw = buildJsonObject {
            put("post_history_instructions", "Continue the story in Chinese.")
        }),
        persona = null,
        messages = listOf(ChatMessage("scene", MessageRole.Character, "Alice", scene, 1L)),
        worldBooks = emptyList(),
        preset = GenerationPreset("default", "Default", "openai-compatible", raw = buildJsonObject {
            put("assistant_prefill", "Alice says: ")
        }),
        userInput = "", providerType = "openai-compatible", quietPrompt = quiet,
        metadata = buildJsonObject {
            putJsonObject("injectedPrompts") {
                putJsonObject("scene-state") {
                    put("value", "The familiar has silver wings.")
                    put("position", 1); put("depth", 0); put("role", "system")
                }
            }
        },
    )

    @Test fun `quiet instruction appears once after PHI and injections without creating a user turn or prefill`() {
        val result = DefaultPromptEngine().build(request())
        assertEquals(PromptMessage(MessageRole.System, content = quiet), result.messages.last())
        assertEquals(1, result.messages.count { it.content == quiet })
        assertFalse(result.messages.any { it.role == MessageRole.User })
        val text = result.messages.joinToString("\n") { it.content }
        assertTrue(text.contains("Current scene: $scene"))
        assertTrue(text.contains("Continue the story in Chinese."))
        assertTrue(text.contains("The familiar has silver wings."))
        assertFalse(text.contains("Alice says:"))
    }

    @Test fun `quiet control survives trimming with reserved budget`() {
        val base = request()
        val req = base.copy(
            character = CharacterCard("alice", "Alice"), metadata = buildJsonObject { },
            preset = base.preset.copy(maxContextTokens = 180, maxCompletionTokens = 20),
            messages = listOf(ChatMessage("old", MessageRole.User, "User", "old story ".repeat(200), 0L)) + base.messages,
            quietPrompt = "Describe only the visible scene. ".repeat(8),
        )
        val result = DefaultPromptEngine().build(req)
        assertEquals(req.quietPrompt, result.messages.last().content)
        assertTrue(result.messages.any { it.content == scene })
        assertFalse(result.messages.any { it.content.contains("old story") })
    }

    @Test fun `quiet prompt is serialized through the selected instruct format`() {
        val base = request()
        val result = DefaultPromptEngine().build(base.copy(metadata = buildJsonObject {
            putJsonObject("instructPreset") {
                put("system_sequence", "<system>"); put("system_suffix", "</system>")
                put("input_sequence", "<user>"); put("input_suffix", "</user>")
                put("output_sequence", "<assistant>"); put("output_suffix", "</assistant>")
                put("wrap", true)
            }
        }))
        val serialized = result.messages.single().content
        assertTrue(serialized.contains(scene))
        assertTrue(serialized.endsWith("<system>$quiet</system><assistant>"))
        assertFalse(serialized.contains("Alice says:"))
    }
}
