package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the ST-Prompt-Template json-patch.ts / chat.ts families
 * (parseJSON / jsonPatch / patchVariables / getChatMessage(s) /
 * matchChatMessages); template.js mirrors these in the Node harness.
 *
 * Probes stay within the Kotlin fallback evaluator's supported grammar
 * (assign-then-member, getvar defaults) — the WebView path runs full JS.
 */
class JsonAndChatCompatTest {

    private fun processor() = DefaultPromptTemplateProcessor()

    private fun render(
        content: String,
        chat: List<PromptTemplateChatMessage> = emptyList(),
        metadata: kotlinx.serialization.json.JsonObject = buildJsonObject { },
    ) = processor().process(
        PromptTemplateRequest(
            messages = listOf(PromptMessage(role = MessageRole.System, content = content)),
            context = MacroContext(),
            metadata = metadata,
            chat = chat,
        ),
    ).messages.single().content

    private fun chatFloors() = listOf(
        PromptTemplateChatMessage(id = 0, isUser = false, isSystem = false, name = "玄泽", content = "greeting"),
        PromptTemplateChatMessage(id = 1, isUser = true, isSystem = false, content = "hello there"),
        PromptTemplateChatMessage(id = 2, isUser = false, isSystem = false, name = "玄泽", content = "reply two"),
        PromptTemplateChatMessage(id = 3, isUser = true, isSystem = false, content = "final question"),
    )

    // ── parseJSON ──────────────────────────────────────────────────────

    @Test
    fun `parseJSON strict input`() {
        assertEquals("5", render("<% const parsed = parseJSON('{\"a\": 5}') %><%= parsed.a %>"))
    }

    @Test
    fun `parseJSON repairs trailing commas`() {
        assertEquals("5", render("<% const parsed = parseJSON('{\"a\": 5,}') %><%= parsed.a %>"))
    }

    @Test
    fun `parseJSON repairs unquoted keys`() {
        assertEquals("ok", render("<% const parsed = parseJSON('{status: \"ok\"}') %><%= parsed.status %>"))
    }

    @Test
    fun `parseJSON repairs single quotes`() {
        assertEquals("ok", render("<% const parsed = parseJSON(\"{'status': 'ok'}\") %><%= parsed.status %>"))
    }

    @Test
    fun `parseJSON throws on unrepairable input`() {
        assertThrows(IllegalArgumentException::class.java) {
            render("<% const parsed = parseJSON('not json at all {') %>")
        }
    }

    // ── jsonPatch ──────────────────────────────────────────────────────

    @Test
    fun `jsonPatch replace and add with pointer paths`() {
        val result = render(
            "<% const doc = {name: 'old', phones: ['x']} %>" +
                "<% const out = jsonPatch(doc, [{op: 'replace', path: '/name', value: 'John'}]) %>" +
                "<%= out.name %>|<%= out.phones.0 %>",
        )

        assertEquals("John|x", result)
    }

    @Test
    fun `jsonPatch does not mutate the original document`() {
        val result = render(
            "<% const doc = {a: 1} %>" +
                "<% jsonPatch(doc, [{op: 'replace', path: '/a', value: 2}]) %>" +
                "<%= doc.a %>",
        )

        assertEquals("1", result)
    }

    @Test
    fun `jsonPatch remove move copy and append`() {
        val result = render(
            "<% const doc = {a: 1, b: 2, list: [\"x\"]} %>" +
                "<% const out = jsonPatch(doc, [" +
                "{op: 'remove', path: '/a'}," +
                "{op: 'move', from: '/b', path: '/c'}," +
                "{op: 'copy', from: '/c', path: '/d'}," +
                "{op: 'add', path: '/list/-', value: 'y'}" +
                "]) %>" +
                "<% setvar('probe', out) %>" +
                "<%= getvar('probe.c') %><%= getvar('probe.d') %><%= getvar('probe.list.1') %>" +
                "<%= getvar('probe.a', {defaults: 'gone'}) %>",
        )

        assertEquals("22ygone", result)
    }

    @Test
    fun `jsonPatch test failure returns the original document`() {
        val result = render(
            "<% const doc = {a: 1} %>" +
                "<% const out = jsonPatch(doc, [{op: 'test', path: '/a', value: 999}, {op: 'replace', path: '/a', value: 2}]) %>" +
                "<%= out.a %>",
        )

        assertEquals("1", result)
    }

    @Test
    fun `patchVariables writes patched document to message scope`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(
                    PromptMessage(
                        role = MessageRole.System,
                        content = "<% patchVariables('stat_data', [{op: 'replace', path: '/hp', value: 88}]) %>",
                    ),
                ),
                context = MacroContext(),
                metadata = buildJsonObject {
                    put("promptTemplateLocalVariables", buildJsonObject {
                        put("stat_data", buildJsonObject { put("hp", JsonPrimitive(10)) })
                    })
                },
            ),
        )

        // The write defaults to the message layer (ST setVariable default) and
        // carries the patched document.
        val statData = result.variableUpdates.message?.get("stat_data")?.jsonObject
        assertEquals(88, statData?.get("hp")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `patchVariables accepts a JSON string change document`() {
        val result = render(
            "<% patchVariables('stat', '[{\"op\": \"replace\", \"path\": \"/mp\", \"value\": 7}]', 'local') %>" +
                "<%= getvar('stat.mp', 'local') %>",
            metadata = buildJsonObject {
                put("promptTemplateLocalVariables", buildJsonObject {
                    put("stat", buildJsonObject { put("mp", JsonPrimitive(1)) })
                })
            },
        )

        assertEquals("7", result)
    }

    // ── getChatMessage(s) / matchChatMessages ──────────────────────────

    @Test
    fun `getChatMessage indexes with role filter and negative offset`() {
        val result = render(
            "<%= getChatMessage(0, 'user') %>|<%= getChatMessage(-1, 'assistant') %>",
            chat = chatFloors(),
        )

        assertEquals("hello there|reply two", result)
    }

    @Test
    fun `getChatMessages slices counts and ranges`() {
        val result = render(
            "<%= getChatMessages(2) %>|<%= getChatMessages(-2, 'user') %>",
            chat = chatFloors(),
        )

        // Lists render comma-joined like Array.toString; start=0 yields empty
        // in ST too (only positive/negative counts are valid).
        assertEquals("greeting,hello there|hello there,final question", result)
    }

    @Test
    fun `matchChatMessages scans the default last-two window`() {
        val result = render(
            "<%= matchChatMessages('final') %>|<%= matchChatMessages('greeting') %>",
            chat = chatFloors(),
        )

        // Default window is the last 2 messages (start=-2): "reply two"/"final question".
        assertEquals("true|false", result)
    }

    @Test
    fun `matchChatMessages and-mode requires every pattern`() {
        val result = render(
            "<%= matchChatMessages(['final', 'question'], {start: -1}) %>|<%= matchChatMessages(['final', 'reply'], {start: -1, and: true}) %>",
            chat = chatFloors(),
        )

        assertEquals("true|false", result)
    }

    @Test
    fun `chat content is macro-expanded before exposure through the engine`() {
        val result = DefaultPromptEngine().build(
            PromptBuildRequest(
                character = app.tellev.core.model.CharacterCard(id = "alice", name = "Alice"),
                persona = null,
                messages = listOf(
                    app.tellev.core.model.ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "旅人",
                        content = "talk to {{char}}",
                        createdAtMillis = 0L,
                    ),
                ),
                worldBooks = emptyList(),
                preset = app.tellev.core.model.GenerationPreset(
                    id = "default", name = "Default", providerType = "openai-compatible",
                ),
                userInput = "Hello",
                providerType = "openai-compatible",
                metadata = buildJsonObject { },
            ),
        )

        val prompt = result.messages.joinToString("\n") { it.content }
        assertTrue(prompt.contains("talk to Alice"))
    }
}
