package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPromptEngineTest {
    @Test
    fun buildActivatesMatchingWorldEntry() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice", description = "A careful guide."),
                persona = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "User",
                        content = "Tell me about the academy.",
                        createdAtMillis = 1L,
                    ),
                ),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "academy",
                                keys = listOf("academy"),
                                content = "The academy is built under the old observatory.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "What is the academy?",
                providerType = "openai-compatible",
            ),
        )

        assertEquals(listOf("academy"), result.diagnostics.activatedWorldEntryIds)
        assertTrue(result.messages.first().content.contains("old observatory"))
    }

    @Test
    fun buildProcessesPromptTemplateExpressions() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "Knows <%= user %> as <%= char %>.",
                ),
                persona = Persona(id = "p1", name = "Mira", description = ""),
                messages = emptyList(),
                worldBooks = emptyList(),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        val systemPrompt = result.messages.first().content
        assertTrue(systemPrompt.contains("Knows Mira as Alice."))
        assertFalse(systemPrompt.contains("<%"))
    }

    @Test
    fun buildProcessesPromptTemplateConditionalsWithVariables() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "<% if (getvar('mode') === 'story') { %>Story mode<% } else { %>Chat mode<% } %>",
                ),
                persona = null,
                messages = emptyList(),
                worldBooks = emptyList(),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
                metadata = buildJsonObject {
                    put("promptTemplateVariables", buildJsonObject {
                        put("mode", "story")
                    })
                },
            ),
        )

        val systemPrompt = result.messages.first().content
        assertTrue(systemPrompt.contains("Story mode"))
        assertFalse(systemPrompt.contains("Chat mode"))
    }

    @Test
    fun buildCarriesPromptTemplateVariablesWithinRenderedPrompt() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "<% setvar('mood', 'calm') %>",
                ),
                persona = null,
                messages = emptyList(),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "mood",
                                keys = emptyList(),
                                constant = true,
                                content = "Mood is <%= getvar('mood') %>.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        val systemPrompt = result.messages.first().content
        assertTrue(systemPrompt.contains("Mood is calm."))
        assertFalse(systemPrompt.contains("setvar"))
    }

    @Test
    fun buildAppliesGenerateBeforeWorldEntry() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = emptyList(),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "generate-before",
                                keys = emptyList(),
                                constant = true,
                                content = "[GENERATE:BEFORE]\nPinned instruction for {{char}}.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        val systemPrompt = result.messages.first().content
        assertTrue(systemPrompt.startsWith("Pinned instruction for Alice."))
        assertFalse(systemPrompt.contains("[GENERATE"))
        assertFalse(systemPrompt.contains("World info:"))
    }

    @Test
    fun buildAppliesGenerateIndexedAfterWorldEntry() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "User",
                        content = "First history.",
                        createdAtMillis = 1L,
                    ),
                ),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "generate-index",
                                keys = emptyList(),
                                constant = true,
                                content = "[GENERATE:1:AFTER]\nHistory suffix.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        assertEquals("First history.\nHistory suffix.", result.messages[1].content)
    }

    @Test
    fun buildInjectsMessageAtPosition() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = emptyList(),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "inject-pos",
                                keys = emptyList(),
                                constant = true,
                                content = "@INJECT pos=1 role=system\nInserted system note.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        assertEquals(MessageRole.System, result.messages[1].role)
        assertEquals("Inserted system note.", result.messages[1].content)
    }

    @Test
    fun buildInjectsMessageAfterTargetText() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "User",
                        content = "Find the academy.",
                        createdAtMillis = 1L,
                    ),
                ),
                worldBooks = listOf(
                    WorldBook(
                        id = "world",
                        name = "World",
                        entries = listOf(
                            WorldBookEntry(
                                id = "inject-target",
                                keys = emptyList(),
                                constant = true,
                                content = "@INJECT target=\"academy\" at=after role=assistant\nTargeted reminder.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        assertEquals(MessageRole.Assistant, result.messages[2].role)
        assertEquals("Targeted reminder.", result.messages[2].content)
    }

    @Test
    fun buildSplicesInExtensionInjectedPromptsByDepth() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = listOf(
                    ChatMessage(id = "m1", role = MessageRole.User, name = "User", content = "Hello.", createdAtMillis = 1L),
                    ChatMessage(id = "m2", role = MessageRole.Character, name = "Alice", content = "Hi there.", createdAtMillis = 2L),
                    ChatMessage(id = "m3", role = MessageRole.User, name = "User", content = "How are you?", createdAtMillis = 3L),
                ),
                worldBooks = emptyList(),
                preset = GenerationPreset(id = "default", name = "Default", providerType = "openai-compatible"),
                userInput = "Tell me a story.",
                providerType = "openai-compatible",
                metadata = buildJsonObject {
                    put("injectedPrompts", buildJsonObject {
                        putJsonObject("ext-a/an") {
                            put("extensionId", "ext-a")
                            put("promptId", "an")
                            put("value", "Author's note: keep it short.")
                            put("position", 1)
                            put("depth", 1)
                            put("role", "system")
                        }
                        putJsonObject("ext-b/before") {
                            put("extensionId", "ext-b")
                            put("promptId", "before")
                            put("value", "Pre-context reminder.")
                            put("position", 2)
                            put("depth", 4)
                            put("role", "system")
                        }
                        putJsonObject("ext-c/after") {
                            put("extensionId", "ext-c")
                            put("promptId", "after")
                            put("value", "Right after system prompt.")
                            put("position", 0)
                            put("depth", 0)
                            put("role", "system")
                        }
                        putJsonObject("ext-d/none") {
                            put("extensionId", "ext-d")
                            put("promptId", "none")
                            put("value", "Should be skipped.")
                            put("position", -1)
                            put("depth", 0)
                            put("role", "system")
                        }
                    })
                },
            ),
        )

        val contents = result.messages.map { it.content }

        // BEFORE_PROMPT reminder should be the very first message.
        assertEquals("Pre-context reminder.", contents.first())
        // system prompt should follow the BEFORE_PROMPT reminder.
        assertTrue(contents[1].contains("Alice"))
        // IN_PROMPT entry should sit immediately after the system prompt.
        assertEquals("Right after system prompt.", contents[2])
        // IN_CHAT at depth=1 should appear right before the last user message,
        // which is the user's "Tell me a story." prompt from [request.userInput].
        assertEquals("Author's note: keep it short.", contents[contents.lastIndex - 1])
        assertEquals("Tell me a story.", contents.last())
        // NONE entry must never appear.
        assertFalse(contents.contains("Should be skipped."))
    }
}
