package app.tellev.feature.chat

import app.tellev.core.extension.*
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ChatCompletionEventsTest {
    private val prompt = PromptBuildResult(listOf(
        PromptMessage(MessageRole.System, content = "system", channel = "main"),
        PromptMessage(MessageRole.Character, name = "Alice", content = "story\n<StatusPlaceHolderImpl/>", channel = "chat"),
        PromptMessage(MessageRole.Tool, content = "tool result"),
    ), listOf("stop"), 256, "fixture", PromptDiagnostics(listOf("world")))

    @Test fun `unchanged events preserve original prompt and role metadata`() = runBlocking {
        val names = mutableListOf<String>()
        val result = ChatCompletionEvents.prepare(host { event ->
            names += event.name
            val data = event.payload.getValue("args").jsonArray.first().jsonObject
            val key = when(event.name) { StEventCatalog.CHAT_COMPLETION_PROMPT_READY -> "chat"; StEventCatalog.GENERATE_AFTER_DATA -> "prompt"; else -> "messages" }
            assertEquals(listOf("system", "assistant", "tool"), data.getValue(key).jsonArray.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            event.payload
        }, prompt)
        assertSame(prompt, result)
        assertEquals(listOf(StEventCatalog.CHAT_COMPLETION_PROMPT_READY, StEventCatalog.GENERATE_AFTER_DATA, StEventCatalog.CHAT_COMPLETION_SETTINGS_READY), names)
    }

    @Test fun `invalid listener output stops the request instead of silently discarding script edits`() = runBlocking {
        for (invalid in listOf(JsonNull, JsonPrimitive("bad"), JsonArray(listOf(buildJsonObject {
            put("role", "unknown"); put("content", "wrong")
        })), JsonArray(listOf(buildJsonObject { put("role", "assistant"); put("content", 42) })))) {
            try { ChatCompletionEvents.prepare(host { event ->
                val key = when(event.name) { StEventCatalog.CHAT_COMPLETION_PROMPT_READY -> "chat"; StEventCatalog.GENERATE_AFTER_DATA -> "prompt"; else -> "messages" }
                buildJsonObject { put("args", JsonArray(listOf(buildJsonObject { put(key, invalid) }))) }
            }, prompt); fail("Invalid listener result was accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `request filters change outgoing text while preserving prompt settings and source`() = runBlocking {
        val result = ChatCompletionEvents.prepare(host { event ->
            if (event.name != StEventCatalog.CHAT_COMPLETION_SETTINGS_READY) event.payload else {
                val data = event.payload.getValue("args").jsonArray.first().jsonObject
                val messages = data.getValue("messages").jsonArray.map { element ->
                    val message = element.jsonObject
                    JsonObject(message + ("content" to JsonPrimitive(message.getValue("content").jsonPrimitive.content.replace("\n<StatusPlaceHolderImpl/>", ""))))
                }
                buildJsonObject { put("args", JsonArray(listOf(JsonObject(data + ("messages" to JsonArray(messages)))))) }
            }
        }, prompt)
        assertEquals("story", result.messages[1].content)
        assertEquals(MessageRole.Character, result.messages[1].role)
        assertEquals("chat", result.messages[1].channel)
        assertEquals("story\n<StatusPlaceHolderImpl/>", prompt.messages[1].content)
        assertEquals(MessageRole.Tool, result.messages[2].role)
        assertEquals(prompt.stop, result.stop)
        assertEquals(prompt.maxTokens, result.maxTokens)
        assertSame(prompt.diagnostics, result.diagnostics)
    }

    private fun host(transform: (ExtensionEvent) -> JsonObject): ExtensionHost =
        Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
            check(method.name == "emitMutable")
            transform(args[0] as ExtensionEvent)
        } as ExtensionHost
}
