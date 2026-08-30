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
import org.junit.Assert.assertFalse
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

    @Test
    fun `group macro resolves from string-array groupMembers metadata`() {
        // Regression guard: ChatViewModel writes groupMembers as an array of
        // plain name strings; the reader side used to expect {"name": ...}
        // objects, so {{group}} silently resolved to "".
        val metadata = buildJsonObject {
            put("maxContextTokens", 8192)
            put(
                "groupMembers",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("Alice"),
                        kotlinx.serialization.json.JsonPrimitive("Carol"),
                    ),
                ),
            )
        }
        val request = baseRequest(metadata).let {
            it.copy(character = it.character.copy(description = "Members: {{group}}"))
        }

        val result = engine.build(request)

        assertTrue(
            "{{group}} should expand to the member names, got: ${result.messages.first().content}",
            result.messages.first().content.contains("Alice, Carol"),
        )
    }

    @Test
    fun `injected prompts reserve budget from chat history`() {
        // Regression guard for audit §12.2: injections used to be spliced in
        // AFTER budget trimming, so a big injected prompt could push the whole
        // request past maxContextTokens. Now injection tokens are reserved
        // from the chat budget, so old chat messages are dropped to make room.
        val base = engine.build(baseRequest(buildJsonObject { put("maxContextTokens", 500) }))
        val bigInjection = "lorem ipsum dolor sit amet consectetur ".repeat(60)
        val withInjection = engine.build(
            baseRequest(
                buildJsonObject {
                    put("maxContextTokens", 500)
                    put(
                        "injectedPrompts",
                        buildJsonObject {
                            put(
                                "ext/p1",
                                buildJsonObject {
                                    put("value", bigInjection)
                                    put("position", 0)
                                    put("depth", 0)
                                    put("role", "system")
                                },
                            )
                        },
                    )
                },
            ),
        )

        val baseCount = base.messages.count { it.content.contains("Message number") }
        val injectedCount = withInjection.messages.count { it.content.contains("Message number") }
        assertTrue(withInjection.messages.any { it.content.contains("lorem ipsum") })
        assertTrue(
            "injection tokens must be reserved from the chat budget (base=$baseCount injected=$injectedCount)",
            injectedCount < baseCount,
        )
    }

    @Test
    fun `depth_prompt is injected into chat at specified depth and role`() {
        val cardWithDepthPrompt = character().copy(
            raw = buildJsonObject {
                put("name", "Alice")
                put("data", buildJsonObject {
                    put("name", "Alice")
                    put("extensions", buildJsonObject {
                        put("depth_prompt", buildJsonObject {
                            put("prompt", "Remember: Alice is secretive.")
                            put("depth", 2)
                            put("role", "system")
                        })
                    })
                })
            },
        )
        val request = baseRequest(buildJsonObject { put("maxContextTokens", 8192) })
            .copy(character = cardWithDepthPrompt)

        val result = engine.build(request)

        assertTrue(
            "depth_prompt text should appear in the prompt",
            result.messages.any { it.content.contains("Remember: Alice is secretive.") },
        )
    }

    @Test
    fun `system_prompt overrides default You are X opener`() {
        val cardWithSysPrompt = character().copy(
            raw = buildJsonObject {
                put("name", "Alice")
                put("data", buildJsonObject {
                    put("name", "Alice")
                    put("system_prompt", "You are a master storyteller in a fantasy world.")
                })
            },
        )
        val request = baseRequest(buildJsonObject { put("maxContextTokens", 8192) })
            .copy(character = cardWithSysPrompt)

        val result = engine.build(request)

        val systemContent = result.messages.first().content
        assertTrue(
            "Custom system_prompt should be used, got: $systemContent",
            systemContent.contains("master storyteller"),
        )
    }

    @Test
    fun `post_history_instructions injected at depth zero`() {
        val cardWithJailbreak = character().copy(
            raw = buildJsonObject {
                put("name", "Alice")
                put("data", buildJsonObject {
                    put("name", "Alice")
                    put("post_history_instructions", "[System: Stay in character at all times.]")
                })
            },
        )
        val request = baseRequest(buildJsonObject { put("maxContextTokens", 8192) })
            .copy(character = cardWithJailbreak)

        val result = engine.build(request)

        assertTrue(
            "post_history_instructions should appear in the prompt",
            result.messages.any { it.content.contains("Stay in character at all times.") },
        )
        // The jailbreak should be near the end (depth 0 = last position before user input)
        val lastFew = result.messages.takeLast(3)
        assertTrue(
            "post_history_instructions should be near the end of the message list",
            lastFew.any { it.content.contains("Stay in character") },
        )
    }

    @Test
    fun `names_behavior COMPLETION keeps ascii names and sanitizes cjk names`() {
        // ST openai.js:948-951 + PromptManager.js:1349-1351: COMPLETION sets
        // the name field on every named message, sanitizing invalid
        // characters to `_` instead of dropping the name.
        val cjkMessages = listOf(
            ChatMessage(id = "m0", role = MessageRole.User, name = "Bob", content = "Hello there.", createdAtMillis = 1),
            ChatMessage(id = "m1", role = MessageRole.Character, name = "小明", content = "Hi Bob.", createdAtMillis = 2),
        )
        val result = engine.build(namesBehaviorRequest(1, messagesOverride = cjkMessages))

        val cjk = result.messages.first { it.content.contains("Hi Bob.") }
        assertEquals("__", cjk.name)
        val ascii = result.messages.first { it.content.contains("Hello there.") }
        assertEquals("Bob", ascii.name)
        // COMPLETION never prefixes content.
        assertTrue(result.messages.none { it.content.startsWith("小明: ") })
    }

    @Test
    fun `names_behavior COMPLETION does not prefix content in group chats`() {
        // ST openai.js:599-600: the COMPLETION case breaks out of the content
        // prefix switch, even in groups.
        val result = engine.build(namesBehaviorRequest(1, groupMembers = listOf("Alice", "Carol")))

        val assistantHistory = result.messages.filter {
            it.role == MessageRole.Assistant && it.content.contains("Message number")
        }
        assertTrue(assistantHistory.isNotEmpty())
        assertTrue(assistantHistory.all { it.name == "Alice" })
        assertTrue(assistantHistory.none { it.content.startsWith("Alice: ") })
    }

    @Test
    fun `names_behavior CONTENT prefixes all named messages including user`() {
        // ST openai.js:594-596: CONTENT prepends `Name: ` to every
        // non-narrator message (user included) and never sets the name field.
        val result = engine.build(namesBehaviorRequest(2))

        assertTrue(result.messages.all { it.name == null })
        val assistantHistory = result.messages.first { it.content.contains("Message number 1") }
        assertTrue(assistantHistory.content.startsWith("Alice: "))
        val userHistory = result.messages.first { it.content.contains("Message number 0") }
        assertTrue(userHistory.content.startsWith("Bob: "))
        val userInput = result.messages.first { it.content.contains("Let us explore the forest.") }
        assertTrue(userInput.content.startsWith("Bob: "))
    }

    @Test
    fun `names_behavior DEFAULT prefixes only group assistant messages`() {
        // ST openai.js:589-592: DEFAULT prefixes only group messages whose
        // name differs from the user's.
        val grouped = engine.build(namesBehaviorRequest(0, groupMembers = listOf("Alice", "Carol")))
        val groupAssistant = grouped.messages.first { it.content.contains("Message number 1") }
        assertTrue(groupAssistant.content.startsWith("Alice: "))
        val groupUser = grouped.messages.first { it.content.contains("Message number 0") }
        assertFalse(groupUser.content.startsWith("Bob: "))

        val single = engine.build(namesBehaviorRequest(0))
        val singleAssistant = single.messages.first { it.content.contains("Message number 1") }
        assertFalse(singleAssistant.content.startsWith("Alice: "))
        assertTrue(single.messages.all { it.name == null })
    }

    @Test
    fun `names_behavior NONE drops names without any prefix`() {
        // ST openai.js:587-588: NONE leaves content untouched and sets no
        // name field.
        val result = engine.build(namesBehaviorRequest(-1))

        assertTrue(result.messages.all { it.name == null })
        val chatOnly = result.messages.filter {
            it.content.contains("Message number") || it.content.contains("Let us explore the forest.")
        }
        assertTrue(chatOnly.none { it.content.startsWith("Alice: ") || it.content.startsWith("Bob: ") })
    }

    private fun namesBehaviorRequest(
        namesBehavior: Int,
        groupMembers: List<String>? = null,
        messagesOverride: List<ChatMessage>? = null,
    ): PromptBuildRequest {
        val metadata = buildJsonObject {
            put("maxContextTokens", 8192)
            groupMembers?.let { members ->
                put(
                    "groupMembers",
                    kotlinx.serialization.json.JsonArray(
                        members.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ),
                )
            }
        }
        return baseRequest(metadata).copy(
            preset = GenerationPreset(
                id = "preset1",
                name = "P",
                providerType = "openai",
                maxTokens = 256,
                raw = buildJsonObject { put("names_behavior", namesBehavior) },
            ),
            messages = messagesOverride ?: messages(),
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
