package app.tellev.core.prompt

import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.extension.VariableStoreTest
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetPrompt
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
                                position = 1, // AFTER char defs so setvar in description runs first
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
    fun buildReturnsScopedPromptTemplateVariableUpdates() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "<% setvar('mood', 'calm') %><% setglobalvar('chapter', 'two') %>",
                ),
                persona = null,
                messages = emptyList(),
                worldBooks = emptyList(),
                preset = GenerationPreset(
                    id = "default",
                    name = "Default",
                    providerType = "openai-compatible",
                ),
                userInput = "Hello",
                providerType = "openai-compatible",
                metadata = buildJsonObject {
                    putJsonObject("promptTemplateLocalVariables") {
                        put("mood", "tense")
                    }
                    putJsonObject("promptTemplateGlobalVariables") {
                        put("chapter", "one")
                    }
                },
            ),
        )

        assertEquals(
            "calm",
            result.promptTemplateVariableUpdates.local?.get("mood")?.jsonPrimitive?.content,
        )
        assertEquals(
            "two",
            result.promptTemplateVariableUpdates.global?.get("chapter")?.jsonPrimitive?.content,
        )
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
                        putJsonObject("ext-e/filtered") {
                            put("extensionId", "ext-e")
                            put("promptId", "filtered")
                            put("value", "Filtered content must not be sent.")
                            put("position", 0)
                            put("depth", 0)
                            put("role", "system")
                            put("filter", false)
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
        assertFalse(contents.contains("Filtered content must not be sent."))
    }

    @Test
    fun `should scan injections activate world info even at none position`() {
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
                                id = "hidden-key",
                                keys = listOf("moon-key"),
                                content = "The moon archive is active.",
                            ),
                        ),
                    ),
                ),
                preset = GenerationPreset(
                    id = "default",
                    name = "Default",
                    providerType = "openai-compatible",
                ),
                userInput = "Hello",
                providerType = "openai-compatible",
                metadata = buildJsonObject {
                    putJsonObject("injectedPrompts") {
                        putJsonObject("ext/scan-only") {
                            put("value", "moon-key")
                            put("position", -1)
                            put("shouldScan", true)
                        }
                    }
                },
            ),
        )

        assertEquals(listOf("hidden-key"), result.diagnostics.activatedWorldEntryIds)
        assertTrue(result.messages.first().content.contains("moon archive"))
    }

    @Test
    fun `EJS sees local variable mutations performed by earlier macros`() {
        val activeChat = mutableMapOf("x" to "active")
        val scoped = mutableMapOf("x" to "0")
        val variableStore = VariableStoreTest.storeWith(activeChat)
        val macroEngine = DefaultMacroEngine().apply { this.variableStore = variableStore }
        val engine = DefaultPromptEngine(macroEngine = macroEngine)
        val backend = object : LocalVariableBackend {
            override fun snapshot(): Map<String, String> = scoped.toMap()
            override fun update(transform: (MutableMap<String, String>) -> Unit): Map<String, String> {
                transform(scoped)
                return scoped.toMap()
            }
        }

        val result = engine.buildWithLocalVariableBackend(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "{{setvar::x::1}}<%= incvar('x') %>",
                ),
                persona = null,
                messages = emptyList(),
                worldBooks = emptyList(),
                preset = GenerationPreset(
                    id = "default",
                    name = "Default",
                    providerType = "openai-compatible",
                ),
                userInput = "Hello",
                providerType = "openai-compatible",
                metadata = buildJsonObject {
                    put("promptTemplateLocalVariables", buildJsonObject { put("x", "0") })
                },
            ),
            backend,
        )

        assertTrue(result.messages.first().content.contains("2"))
        assertEquals("1", scoped["x"])
        assertEquals(JsonPrimitive(2.0), result.promptTemplateVariableUpdates.local?.get("x"))
        assertEquals("active", activeChat["x"])
    }
    @Test
    fun `openai preset prompt order controls components and disabled markers`() {
        val preset = GenerationPreset(
            id = "ordered",
            name = "Ordered",
            providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(
                    identifier = "main",
                    name = "Main",
                    content = "MAIN INSTRUCTION",
                    enabled = true,
                    order = 0,
                ),
                PresetPrompt(
                    identifier = "charDescription",
                    name = "Description",
                    enabled = false,
                    order = 1,
                ),
                PresetPrompt(
                    identifier = "scenario",
                    name = "Scenario",
                    enabled = true,
                    order = 2,
                ),
                PresetPrompt(
                    identifier = "chatHistory",
                    name = "History",
                    enabled = false,
                    order = 3,
                ),
            ),
        )
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice",
                    name = "Alice",
                    description = "DESCRIPTION MUST BE OMITTED",
                    scenario = "SCENARIO INCLUDED",
                ),
                persona = null,
                messages = listOf(
                    ChatMessage(
                        id = "old",
                        role = MessageRole.User,
                        name = "User",
                        content = "HISTORY MUST BE OMITTED",
                        createdAtMillis = 1,
                    ),
                ),
                worldBooks = emptyList(),
                preset = preset,
                userInput = "CURRENT INPUT MUST BE OMITTED",
                providerType = "openai-compatible",
            ),
        )
        val combined = result.messages.joinToString("\n") { it.content }

        assertTrue(combined.contains("MAIN INSTRUCTION"))
        assertTrue(combined.contains("SCENARIO INCLUDED"))
        assertFalse(combined.contains("DESCRIPTION MUST BE OMITTED"))
        assertFalse(combined.contains("HISTORY MUST BE OMITTED"))
        assertFalse(combined.contains("CURRENT INPUT MUST BE OMITTED"))
    }

    @Test
    fun `missing context setting uses safe default for world info budget`() {
        val oversizedLore = WorldBookEntry(
            id = "oversized",
            keys = emptyList(),
            content = "界".repeat(2_000),
            constant = true,
        )
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = emptyList(),
                worldBooks = listOf(WorldBook("world", "World", listOf(oversizedLore))),
                preset = GenerationPreset(
                    id = "default",
                    name = "Default",
                    providerType = "openai-compatible",
                ),
                userInput = "Hello",
                providerType = "openai-compatible",
            ),
        )

        assertTrue(result.diagnostics.activatedWorldEntryIds.isEmpty())
        assertTrue((result.diagnostics.estimatedTokenCount ?: Int.MAX_VALUE) < 8192)
    }


}
