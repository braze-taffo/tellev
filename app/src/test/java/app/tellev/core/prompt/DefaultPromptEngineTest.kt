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

        // [GENERATE:1:AFTER] addresses the first chat message; index 1 in the
        // final array is the '[Start a new Chat]' marker, which the logical
        // index space skips.
        assertEquals("First history.\nHistory suffix.", result.messages[2].content)
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

        // @INJECT pos=1 addresses the logical slot after the system prompt;
        // the '[Start a new Chat]' marker at array index 1 is skipped by the
        // logical index space, so the note lands at array index 2.
        assertEquals(MessageRole.System, result.messages[2].role)
        assertEquals("Inserted system note.", result.messages[2].content)
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

        // system(0), new-chat marker(1), targeted chat message(2), injection(3).
        assertEquals(MessageRole.Assistant, result.messages[3].role)
        assertEquals("Targeted reminder.", result.messages[3].content)
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

    // ── ST injection-parity regression tests ────────────────────────────────
    // Reference behavior: SillyTavern @51ad27fb (openai.js
    // populationInjectionPrompts, preparePromptsForChatCompletion;
    // world-info.js checkWorldInfo; authors-note.js defaults).

    private fun chatMessages(count: Int): List<ChatMessage> = (1..count).map { i ->
        ChatMessage(
            id = "m$i",
            role = if (i % 2 == 1) MessageRole.User else MessageRole.Character,
            name = if (i % 2 == 1) "User" else "Alice",
            content = "chat message $i",
            createdAtMillis = i.toLong(),
        )
    }

    private fun buildWith(
        preset: GenerationPreset = GenerationPreset(id = "d", name = "D", providerType = "openai-compatible"),
        messages: List<ChatMessage> = chatMessages(4),
        worldBooks: List<WorldBook> = emptyList(),
        metadata: kotlinx.serialization.json.JsonObject = buildJsonObject { },
        characterRaw: kotlinx.serialization.json.JsonObject = buildJsonObject { },
        userInput: String = "current input",
    ): PromptBuildResult = DefaultPromptEngine().build(
        PromptBuildRequest(
            character = CharacterCard(id = "alice", name = "Alice", description = "Alice desc", raw = characterRaw),
            persona = Persona(id = "p", name = "Mira", description = ""),
            messages = messages,
            worldBooks = worldBooks,
            preset = preset,
            userInput = userInput,
            providerType = "openai-compatible",
            metadata = metadata,
        ),
    )

    @Test
    fun `depth zero injection lands after last chat message with system closest to end`() {
        val metadata = buildJsonObject {
            putJsonObject("injectedPrompts") {
                putJsonObject("ext/a") {
                    put("value", "SYS INJECT ONE")
                    put("position", 1)
                    put("depth", 0)
                    put("role", "system")
                }
                putJsonObject("ext/b") {
                    put("value", "SYS INJECT TWO")
                    put("position", 1)
                    put("depth", 0)
                    put("role", "system")
                }
                putJsonObject("ext/c") {
                    put("value", "USER INJECT")
                    put("position", 1)
                    put("depth", 0)
                    put("role", "user")
                }
            }
        }
        val result = buildWith(metadata = metadata)
        val messages = result.messages
        val lastChatIdx = messages.indexOfLast { it.content == "current input" }
        // ST populationInjectionPrompts: same depth+role JOINED with \n into ONE
        // message; chronological role order assistant → user → system.
        assertEquals("USER INJECT", messages[lastChatIdx + 1].content)
        assertEquals(MessageRole.User, messages[lastChatIdx + 1].role)
        assertEquals("SYS INJECT ONE\nSYS INJECT TWO", messages[lastChatIdx + 2].content)
        assertEquals(MessageRole.System, messages[lastChatIdx + 2].role)
    }

    @Test
    fun `deep injection clamps to top of chat not above system prompt`() {
        val metadata = buildJsonObject {
            putJsonObject("injectedPrompts") {
                putJsonObject("ext/deep") {
                    put("value", "DEEP INJECT")
                    put("position", 1)
                    put("depth", 100)
                    put("role", "system")
                }
            }
        }
        val result = buildWith(metadata = metadata, messages = chatMessages(2))
        val messages = result.messages
        val injectIdx = messages.indexOfFirst { it.content == "DEEP INJECT" }
        val firstChatIdx = messages.indexOfFirst { it.content == "chat message 1" }
        // The injection sits at the top of the chat span, never before the
        // system prompt (index 0).
        assertTrue(injectIdx > 0)
        assertEquals(firstChatIdx, injectIdx + 1)
    }

    @Test
    fun `world info at depth entries with same depth and role merge into one message`() {
        val worldBooks = listOf(
            WorldBook(
                id = "world",
                name = "World",
                entries = listOf(
                    WorldBookEntry(
                        id = "wiA", keys = emptyList(), content = "WI ALPHA",
                        constant = true, position = 4, depth = 1, role = 0, insertionOrder = 1,
                    ),
                    WorldBookEntry(
                        id = "wiB", keys = emptyList(), content = "WI BETA",
                        constant = true, position = 4, depth = 1, role = 0, insertionOrder = 2,
                    ),
                ),
            ),
        )
        val result = buildWith(worldBooks = worldBooks)
        val merged = result.messages.filter { it.content.contains("WI ALPHA") }
        assertEquals(1, merged.size)
        assertTrue(merged.first().content.contains("WI BETA"))
        // Depth 1: one position above the end of the chat span.
        val idx = result.messages.indexOfFirst { it.content.contains("WI ALPHA") }
        assertEquals("current input", result.messages[idx + 1].content)
    }

    @Test
    fun `an anchored entries inject in chat at depth four not into system prompt`() {
        val worldBooks = listOf(
            WorldBook(
                id = "world",
                name = "World",
                entries = listOf(
                    WorldBookEntry(
                        id = "anTop", keys = emptyList(), content = "AN TOP ENTRY",
                        constant = true, position = 2,
                    ),
                    WorldBookEntry(
                        id = "anBottom", keys = emptyList(), content = "AN BOTTOM ENTRY",
                        constant = true, position = 3,
                    ),
                ),
            ),
        )
        val result = buildWith(worldBooks = worldBooks, messages = chatMessages(6))
        // Not part of the system prompt (ST anchors ↑AT/↓AT to the author's
        // note, which defaults to in-chat depth 4).
        assertFalse(result.messages.first().content.contains("AN TOP ENTRY"))
        val idx = result.messages.indexOfFirst { it.content.contains("AN TOP ENTRY") }
        assertTrue(idx > 0)
        assertEquals(MessageRole.System, result.messages[idx].role)
        assertTrue(result.messages[idx].content.contains("AN BOTTOM ENTRY"))
        // Depth 4: exactly four chat messages sit below the injection.
        val below = result.messages.drop(idx + 1)
        assertEquals(4, below.count { it.content.startsWith("chat message") || it.content == "current input" })
    }

    @Test
    fun `outlet entries activate but are not emitted without an outlet placeholder`() {
        val worldBooks = listOf(
            WorldBook(
                id = "world",
                name = "World",
                entries = listOf(
                    WorldBookEntry(
                        id = "outlet", keys = emptyList(), content = "OUTLET CONTENT",
                        constant = true, position = 7,
                    ),
                ),
            ),
        )
        val result = buildWith(worldBooks = worldBooks)
        assertTrue(result.diagnostics.activatedWorldEntryIds.contains("outlet"))
        assertFalse(result.messages.any { it.content.contains("OUTLET CONTENT") })
    }

    @Test
    fun `character system prompt overrides preset main and supports original macro`() {
        val preset = GenerationPreset(
            id = "p", name = "P", providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(identifier = "main", content = "PRESET MAIN", order = 0),
                PresetPrompt(identifier = "chatHistory", order = 1),
            ),
        )
        val characterRaw = buildJsonObject {
            putJsonObject("data") {
                put("system_prompt", "CARD OVERRIDE [{{original}}]")
            }
        }
        val result = buildWith(preset = preset, characterRaw = characterRaw)
        val main = result.messages.first { it.content.contains("CARD OVERRIDE") }
        // ST replaces the preset main content with the card override and
        // substitutes {{original}} with the replaced text (openai.js:1486-1494).
        assertEquals("CARD OVERRIDE [PRESET MAIN]", main.content)
        assertFalse(result.messages.any { it.content == "PRESET MAIN" })
    }

    @Test
    fun `post history instructions route through preset jailbreak slot`() {
        val preset = GenerationPreset(
            id = "p", name = "P", providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(identifier = "main", content = "PRESET MAIN", order = 0),
                PresetPrompt(identifier = "chatHistory", order = 1),
                PresetPrompt(identifier = "jailbreak", content = "PRESET JB", order = 2),
            ),
        )
        val characterRaw = buildJsonObject {
            putJsonObject("data") {
                put("post_history_instructions", "CARD PHI [{{original}}]")
            }
        }
        val result = buildWith(preset = preset, characterRaw = characterRaw)
        val jb = result.messages.last()
        // The override replaces the slot content in place (after chatHistory).
        assertEquals("CARD PHI [PRESET JB]", jb.content)
        assertEquals(1, result.messages.count { it.content.contains("CARD PHI") })
    }

    @Test
    fun `post history instructions dropped when preset has no jailbreak slot`() {
        val preset = GenerationPreset(
            id = "p", name = "P", providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(identifier = "main", content = "PRESET MAIN", order = 0),
                PresetPrompt(identifier = "chatHistory", order = 1),
            ),
        )
        val characterRaw = buildJsonObject {
            putJsonObject("data") {
                put("post_history_instructions", "CARD PHI")
            }
        }
        val result = buildWith(preset = preset, characterRaw = characterRaw)
        // ST only emits PHI by overriding an existing enabled jailbreak prompt.
        assertFalse(result.messages.any { it.content.contains("CARD PHI") })
    }

    @Test
    fun `post history instructions fallback lands after chat without preset`() {
        val characterRaw = buildJsonObject {
            putJsonObject("data") {
                put("post_history_instructions", "CARD PHI")
            }
        }
        val result = buildWith(characterRaw = characterRaw)
        assertEquals("CARD PHI", result.messages.last().content)
        assertEquals(MessageRole.System, result.messages.last().role)
    }

    @Test
    fun `preset absolute prompt injects into chat history at its depth`() {
        val preset = GenerationPreset(
            id = "p", name = "P", providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(identifier = "main", content = "PRESET MAIN", order = 0),
                PresetPrompt(identifier = "chatHistory", order = 1),
                PresetPrompt(
                    identifier = "styleGuide", content = "STYLE GUIDE",
                    order = 2, relative = true, depth = 2, role = "user",
                ),
            ),
        )
        val result = buildWith(preset = preset, messages = chatMessages(4))
        val idx = result.messages.indexOfFirst { it.content == "STYLE GUIDE" }
        assertTrue(idx > 0)
        assertEquals(MessageRole.User, result.messages[idx].role)
        // Depth 2 within the chat span: two chat messages remain below it.
        val below = result.messages.drop(idx + 1)
        assertEquals(2, below.count { it.content.startsWith("chat message") || it.content == "current input" })
    }

    @Test
    fun `world info scan text includes speaker names`() {
        val worldBooks = listOf(
            WorldBook(
                id = "world",
                name = "World",
                entries = listOf(
                    WorldBookEntry(id = "byName", keys = listOf("Mira"), content = "PERSONA NAME MATCHED"),
                ),
            ),
        )
        // No message content mentions "Mira" — only the speaker name prefix
        // (ST chatForWI includes names, script.js:4565).
        val result = buildWith(worldBooks = worldBooks)
        assertTrue(result.diagnostics.activatedWorldEntryIds.contains("byName"))
    }

    @Test
    fun `em entries wrap dialogue examples in preset path`() {
        val preset = GenerationPreset(
            id = "p", name = "P", providerType = "openai-compatible",
            prompts = listOf(
                PresetPrompt(identifier = "main", content = "PRESET MAIN", order = 0),
                PresetPrompt(identifier = "dialogueExamples", order = 1),
                PresetPrompt(identifier = "chatHistory", order = 2),
            ),
        )
        val worldBooks = listOf(
            WorldBook(
                id = "world",
                name = "World",
                entries = listOf(
                    WorldBookEntry(id = "emTop", keys = emptyList(), content = "EM TOP", constant = true, position = 5),
                    WorldBookEntry(id = "emBottom", keys = emptyList(), content = "EM BOTTOM", constant = true, position = 6),
                ),
            ),
        )
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = CharacterCard(
                    id = "alice", name = "Alice",
                    exampleMessages = "EXAMPLE DIALOGUE",
                ),
                persona = null,
                messages = chatMessages(2),
                worldBooks = worldBooks,
                preset = preset,
                userInput = "current input",
                providerType = "openai-compatible",
            ),
        )
        // ST emits each example chat as its own block preceded by the
        // '[Example Chat]' marker; ↑EM entries come first, ↓EM last.
        val contents = result.messages.map { it.content }
        val emTopIdx = contents.indexOf("EM TOP")
        val exampleIdx = contents.indexOf("EXAMPLE DIALOGUE")
        val emBottomIdx = contents.indexOf("EM BOTTOM")
        assertTrue(emTopIdx in 1 until exampleIdx)
        assertTrue(exampleIdx < emBottomIdx)
        assertEquals("[Example Chat]", contents[emTopIdx - 1])
        assertEquals("[Example Chat]", contents[exampleIdx - 1])
        assertEquals("[Example Chat]", contents[emBottomIdx - 1])
    }

    @Test
    fun `chat history opens with the new chat marker`() {
        val result = buildWith(messages = chatMessages(2))
        val contents = result.messages.map { it.content }
        val markerIdx = contents.indexOf("[Start a new Chat]")
        assertTrue(markerIdx > 0)
        assertEquals("chat message 1", contents[markerIdx + 1])
    }

    @Test
    fun `inclusion group activates only one member`() {
        val entries = (1..3).map { i ->
            WorldBookEntry(
                id = "g$i", keys = listOf("academy"), content = "GROUP ENTRY $i",
                group = "faction", insertionOrder = i,
            )
        }
        val result = buildWith(
            worldBooks = listOf(WorldBook("world", "World", entries)),
            userInput = "tell me about the academy",
        )
        assertEquals(1, result.diagnostics.activatedWorldEntryIds.count { it.startsWith("g") })
    }

    @Test
    fun `inclusion group prioritized entry wins by insertion order`() {
        val entries = listOf(
            WorldBookEntry(
                id = "loser", keys = listOf("academy"), content = "LOSER",
                group = "faction", insertionOrder = 5,
            ),
            WorldBookEntry(
                id = "prio", keys = listOf("academy"), content = "PRIO WINNER",
                group = "faction", groupOverride = true, insertionOrder = 1,
            ),
        )
        val result = buildWith(
            worldBooks = listOf(WorldBook("world", "World", entries)),
            userInput = "tell me about the academy",
        )
        assertEquals(listOf("prio"), result.diagnostics.activatedWorldEntryIds)
    }

    @Test
    fun `dont_activate decorator suppresses and is stripped from content`() {
        val entries = listOf(
            WorldBookEntry(
                id = "suppressed", keys = listOf("academy"),
                content = "@@dont_activate\nSUPPRESSED LORE",
            ),
            WorldBookEntry(
                id = "forced", keys = listOf("neverseen"),
                content = "@@activate\nFORCED LORE",
            ),
        )
        val result = buildWith(
            worldBooks = listOf(WorldBook("world", "World", entries)),
            userInput = "tell me about the academy",
        )
        assertEquals(listOf("forced"), result.diagnostics.activatedWorldEntryIds)
        assertTrue(result.messages.first().content.contains("FORCED LORE"))
        assertFalse(result.messages.first().content.contains("@@activate"))
    }
}
