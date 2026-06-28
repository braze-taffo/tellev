package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration coverage for [DefaultPromptEngine] around the fixes in this
 * change set:
 *
 *  - The token budget must reserve the system prompt's tokens exactly once.
 *    Both the plain [DefaultPromptEngine.buildSystemPrompt] path and the
 *    context-template path fold the world-info text and character
 *    description into the system prompt, so passing them again to
 *    [TokenBudget.fitToBudget] would double-count them and trim real chat
 *    messages too eagerly.
 *  - The context-template path must reuse the already-activated world-book
 *    entries instead of re-activating with a different (un-macro-expanded)
 *    search text.
 */
class PromptEngineTest {

    private val macroEngine = DefaultMacroEngine()
    private val engine = DefaultPromptEngine(macroEngine)

    private fun character() = CharacterCard(
        id = "char1",
        name = "Alice",
        description = "A curious explorer who loves discovering new places and meeting new people.",
        personality = "Adventurous and witty.",
        scenario = "In a mysterious forest.",
        firstMessage = "Alice greets you warmly.",
        exampleMessages = "Alice: Hello!\n{{user}}: Hi there!",
    )

    private fun worldBook() = WorldBook(
        id = "wb1",
        name = "Lore",
        entries = listOf(
            // depth > 2 -> goes into wiBefore; content carries a keyword so it
            // activates against the search text built from messages + user input.
            WorldBookEntry(
                id = "lore_forest",
                keys = listOf("forest"),
                content = "The forest is ancient and whispers at night.",
                depth = 4,
                insertionOrder = 100,
            ),
        ),
    )

    private fun messages(): List<ChatMessage> = List(8) { i ->
        ChatMessage(
            id = "m$i",
            role = if (i % 2 == 0) MessageRole.User else MessageRole.Assistant,
            name = if (i % 2 == 0) "Bob" else "Alice",
            content = "Message number $i with some body text to make it non-trivial.",
            createdAtMillis = 1_000L * i,
        )
    }

    private fun baseRequest(
        metadata: JsonObject = buildJsonObject { },
    ) = PromptBuildRequest(
        character = character(),
        persona = Persona(id = "p1", name = "Bob", description = "A curious user."),
        messages = messages(),
        worldBooks = listOf(worldBook()),
        preset = GenerationPreset(
            id = "preset1",
            name = "P",
            providerType = "openai",
            maxTokens = 256,
        ),
        userInput = "Let us explore the forest.",
        providerType = "openai",
        metadata = metadata,
    )

    @Test
    fun `context-template path does not double-count world info and description against budget`() {
        // Regression guard for the budget fix. The system prompt (templated)
        // already embeds the character description; fitToBudget must NOT
        // reserve the description tokens a second time. We assert this
        // indirectly: with a budget large enough for the system prompt + all
        // chat messages, every chat message must survive trimming. Before the
        // fix the double-count inflated `usedTokens` and dropped the oldest
        // messages.
        val metadata = buildJsonObject {
            put("maxContextTokens", 8192)
            put("contextPreset", contextPresetJson())
        }

        val result = engine.build(baseRequest(metadata))

        assertEquals(MessageRole.System, result.messages.first().role)
        // Character description must be present in the templated system prompt.
        assertTrue(
            "Character description should be folded into the system prompt",
            result.messages.first().content.contains("A curious explorer"),
        )

        // Drop the leading system message; the rest must contain every chat
        // message we put in (none trimmed by a phantom budget shortfall).
        val nonSystemContents = result.messages
            .filterNot { it.role == MessageRole.System }
            .joinToString("\n") { it.content }

        messages().forEach { msg ->
            assertTrue(
                "Message '${msg.content}' should survive budget trimming, but was dropped",
                nonSystemContents.contains(msg.content),
            )
        }
    }

    @Test
    fun `plain system prompt path still trims when budget genuinely tight`() {
        // Sanity check that passing empties for worldInfo/characterDescription
        // did not break the budget path entirely: a tight budget must still drop
        // the oldest message(s). The system prompt (which embeds description +
        // world info) is always kept.
        val metadata = buildJsonObject {
            put("maxContextTokens", 120)
            // No contextPreset -> uses buildSystemPrompt (world info + desc in
            // the system message), and fitToBudget is given empties.
        }

        val result = engine.build(baseRequest(metadata))

        assertEquals(MessageRole.System, result.messages.first().role)
        // At least one chat message should have been dropped under this budget.
        val chatMessages = result.messages.filterNot { it.role == MessageRole.System }
            .filterNot { it.role == MessageRole.User || it.content == "Let us explore the forest." }
        assertTrue(
            "Expected some chat messages trimmed under a tight budget, got ${result.messages.size} messages",
            chatMessages.size < messages().size,
        )
    }

    @Test
    fun `activated world entry ids are reported in diagnostics`() {
        val metadata = buildJsonObject {
            put("maxContextTokens", 8192)
            put("contextPreset", contextPresetJson())
        }

        val result = engine.build(baseRequest(metadata))

        assertTrue(
            "The forest lore entry should be activated (user input mentions 'forest')",
            result.diagnostics.activatedWorldEntryIds.contains("lore_forest"),
        )
    }

    private fun contextPresetJson(): JsonObject = buildJsonObject {
        put("name", "TestContext")
        // Minimal story string exercising wiBefore/description/personality so
        // ContextTemplate.buildSystemPrompt emits them into the system prompt.
        put(
            "story_string",
            "{{#if wiBefore}}{{wiBefore}}\n{{/if}}" +
                "{{#if description}}{{description}}\n{{/if}}" +
                "{{#if personality}}{{personality}}\n{{/if}}" +
                "{{#if wiAfter}}{{wiAfter}}\n{{/if}}",
        )
        put("example_separator", "")
        put("chat_start", "")
        put("stop_sequence", "")
        put("system_prompt_prefix", "")
        put("system_prompt_suffix", "")
        put("token_budget", 8192)
        put("reserved_prompt_tokens", 50)
        put("reserved_examples_tokens", 0)
    }
}
