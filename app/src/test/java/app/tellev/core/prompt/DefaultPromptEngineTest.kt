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
}
