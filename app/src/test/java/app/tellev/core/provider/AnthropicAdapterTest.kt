package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.GenerationPreset
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicAdapterTest {

    @Test
    fun `system messages merge and text content stays a string without attachments`() = runBlocking {
        var capturedBody = ""
        val adapter = AnthropicAdapter(client = client { chain ->
            capturedBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            response(chain, 200, """{"content":[{"type":"text","text":"ok"}]}""")
        })
        val prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.System, content = "core memory"),
                PromptMessage(MessageRole.User, content = "one"),
                PromptMessage(MessageRole.Assistant, content = "two"),
                PromptMessage(MessageRole.System, content = "jailbreak"),
                PromptMessage(MessageRole.User, content = "last"),
            ),
            stop = emptyList(),
            maxTokens = 77,
            providerType = ProviderCatalog.ANTHROPIC,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        )
        adapter.streamGenerate(
            config(),
            GenerateRequest(prompt = prompt, preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC)),
        ).toList()

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("core memory\n\njailbreak", payload["system"]!!.jsonPrimitive.content)
        val messages = payload["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("one", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("last", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stream usage from message start and delta reaches completed chunk`() = runBlocking {
        val adapter = AnthropicAdapter(client = client { chain ->
            response(
                chain,
                200,
                """
                data: {"type":"message_start","message":{"usage":{"input_tokens":80,"cache_read_input_tokens":30,"cache_creation_input_tokens":10}}}

                data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"ok"}}

                data: {"type":"message_delta","usage":{"output_tokens":20}}

                data: [DONE]

                """.trimIndent(),
                mediaType = "text/event-stream",
            )
        })
        val chunks = adapter.streamGenerate(config(), GenerateRequest(
            prompt = prompt(),
            preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC),
        )).toList()
        val completed = chunks.filterIsInstance<GenerateChunk.Completed>().single()
        assertEquals(80, completed.usage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
        assertEquals(20, completed.usage?.get("output_tokens")?.jsonPrimitive?.content?.toInt())
        assertEquals(30, completed.usage?.get("cache_read_input_tokens")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `non streaming usage reaches completed chunk`() = runBlocking {
        val adapter = AnthropicAdapter(client = client { chain ->
            response(chain, 200, """{"content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":3,"output_tokens":2}}""")
        })
        val completed = adapter.streamGenerate(
            config(), GenerateRequest(prompt = prompt(), preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC), stream = false),
        ).toList().filterIsInstance<GenerateChunk.Completed>().single()
        assertEquals(3, completed.usage?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
        assertEquals(2, completed.usage?.get("output_tokens")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `attachments become image blocks on the final user turn only`() = runBlocking {
        var capturedBody = ""
        val adapter = AnthropicAdapter(
            client = client { chain ->
                capturedBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
                response(chain, 200, """{"content":[{"type":"text","text":"ok"}]}""")
            },
            resolveAttachmentBytes = { "img-bytes".toByteArray() },
        )
        val prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.User, content = "first"),
                PromptMessage(MessageRole.User, content = "look at this"),
            ),
            stop = emptyList(),
            maxTokens = 77,
            providerType = ProviderCatalog.ANTHROPIC,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        )
        adapter.streamGenerate(
            config(),
            GenerateRequest(
                prompt = prompt,
                preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC),
                attachments = listOf(
                    app.tellev.core.model.Attachment(
                        id = "img-1", name = "img-1.png", mimeType = "image/png",
                        relativePath = "user/images/img-1.png",
                    ),
                ),
            ),
        ).toList()

        val messages = Json.parseToJsonElement(capturedBody).jsonObject["messages"]!!.jsonArray
        // 非 finally user 消息保持纯文本。
        assertEquals("first", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val content = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("look at this", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val source = content[1].jsonObject["source"]!!.jsonObject
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString("img-bytes".toByteArray()),
            source["data"]!!.jsonPrimitive.content,
        )
        assertTrue(capturedBody.contains("\"max_tokens\":77"))
    }

    @Test
    fun `fresh chat starting with a character greeting still leads with a user turn`() = runBlocking {
        var capturedBody = ""
        val adapter = AnthropicAdapter(client = client { chain ->
            capturedBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            response(chain, 200, """{"content":[{"type":"text","text":"ok"}]}""")
        })
        val prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.System, content = "card"),
                PromptMessage(MessageRole.Assistant, content = "Hello, traveller."),
                PromptMessage(MessageRole.User, content = "hi"),
            ),
            stop = emptyList(),
            maxTokens = 77,
            providerType = ProviderCatalog.ANTHROPIC,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        )
        adapter.streamGenerate(
            config(),
            GenerateRequest(prompt = prompt, preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC)),
        ).toList()

        val messages = Json.parseToJsonElement(capturedBody).jsonObject["messages"]!!.jsonArray
        // The Messages API answers 400 unless the first message is a user turn.
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello, traveller.", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non image attachments are not encoded as image blocks and instruct stops are sent`() = runBlocking {
        var capturedBody = ""
        val adapter = AnthropicAdapter(
            client = client { chain ->
                capturedBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
                response(chain, 200, """{"content":[{"type":"text","text":"ok"}]}""")
            },
            resolveAttachmentBytes = { "bytes".toByteArray() },
        )
        val prompt = PromptBuildResult(
            messages = listOf(PromptMessage(MessageRole.User, content = "see file")),
            stop = listOf("<|im_end|>"),
            maxTokens = 77,
            providerType = ProviderCatalog.ANTHROPIC,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        )
        adapter.streamGenerate(
            config(),
            GenerateRequest(
                prompt = prompt,
                preset = GenerationPreset("p", "p", ProviderCatalog.ANTHROPIC, stop = listOf("END")),
                attachments = listOf(
                    app.tellev.core.model.Attachment(
                        id = "a1", name = "notes.pdf", mimeType = "application/pdf",
                        relativePath = "user/files/a1.pdf",
                    ),
                ),
            ),
        ).toList()

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        val content = payload["messages"]!!.jsonArray[0].jsonObject["content"]!!
        // No image block: the content stays a plain string.
        assertEquals("see file", content.jsonPrimitive.content)
        val stops = payload["stop_sequences"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("END", "<|im_end|>"), stops)
    }

    private fun prompt() = PromptBuildResult(
        messages = listOf(PromptMessage(MessageRole.User, content = "hello")),
        stop = emptyList(),
        maxTokens = 77,
        providerType = ProviderCatalog.ANTHROPIC,
        diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
    )

    private fun config() = ProviderConfig(
        providerType = ProviderCatalog.ANTHROPIC,
        baseUrl = "https://anthropic.test",
        apiKey = "test-key",
        model = "claude-sonnet-4-20250514",
    )

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        mediaType: String = "application/json",
    ): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(mediaType.toMediaType()))
            .build()
}
