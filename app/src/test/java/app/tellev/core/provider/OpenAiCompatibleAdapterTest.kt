package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAdapterTest {

    @Test
    fun `deepseek status checks chat endpoint after models endpoint`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = client { chain ->
            val request = chain.request()
            paths += "${request.method} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/models" -> response(chain, 200, """{"data":[{"id":"deepseek-v4-flash"}]}""")
                "/chat/completions" -> response(chain, 400, "chat payload rejected")
                else -> response(chain, 500, "unexpected")
            }
        }
        val adapter = OpenAiCompatibleAdapter(
            client = client,
            providerId = ProviderCatalog.DEEPSEEK,
            defaultModel = "deepseek-v4-flash",
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
        )

        val status = adapter.checkStatus(
            ProviderConfig(
                providerType = ProviderCatalog.DEEPSEEK,
                baseUrl = "https://api.deepseek.test",
                apiKey = "secret",
                model = "deepseek-v4-flash",
            ),
        )

        assertFalse(status.available)
        assertEquals("chat payload rejected", status.message)
        assertEquals(listOf("GET /models", "POST /chat/completions"), paths)
    }

    @Test
    fun `checkStatus falls back to chat completions when model listing is unavailable`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = client { chain ->
            val request = chain.request()
            paths += "${request.method} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/models" -> response(chain, 404, "{}")
                "/chat/completions" -> response(chain, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
                else -> response(chain, 500, "{}")
            }
        }
        val adapter = OpenAiCompatibleAdapter(
            client = client,
            defaultModel = "fallback-model",
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
        )

        val status = adapter.checkStatus(config(model = null))

        assertTrue(status.available)
        assertEquals(listOf("GET /models", "POST /chat/completions"), paths)
    }

    @Test
    fun `listModels returns configured fallback model when model listing fails`() = runBlocking {
        val client = client { chain -> response(chain, 404, "{}") }
        val adapter = OpenAiCompatibleAdapter(
            client = client,
            defaultModel = "default-model",
            modelsPath = "/models",
        )

        val models = adapter.listModels(config(model = "configured-model"))

        assertEquals(listOf("configured-model"), models.map { it.id })
    }

    @Test
    fun `checkStatus requires model when model listing is disabled`() = runBlocking {
        val adapter = OpenAiCompatibleAdapter(
            client = client { chain -> response(chain, 500, "{}") },
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
            supportsModelListing = false,
        )

        val status = adapter.checkStatus(config(model = null))

        assertFalse(status.available)
        assertTrue(status.message.contains("model", ignoreCase = true))
    }

    @Test
    fun `checkStatus posts chat request when model listing is disabled`() = runBlocking {
        val paths = mutableListOf<String>()
        val client = client { chain ->
            val request = chain.request()
            paths += "${request.method} ${request.url.encodedPath}"
            response(chain, 200, """{"choices":[{"message":{"content":"ok"}}]}""")
        }
        val adapter = OpenAiCompatibleAdapter(
            client = client,
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
            supportsModelListing = false,
        )

        val status = adapter.checkStatus(config(model = "endpoint-id"))

        assertTrue(status.available)
        assertEquals(listOf("POST /chat/completions"), paths)
    }

    @Test
    fun `custom profile normalizes v1 and sends only enabled compatibility fields`() = runBlocking {
        var capturedPath = ""
        var capturedAuthorization: String? = null
        var capturedProxyHeader: String? = null
        var capturedBody = ""
        val client = client { chain ->
            val request = chain.request()
            capturedPath = request.url.encodedPath
            capturedAuthorization = request.header("X-API-Key")
            capturedProxyHeader = request.header("X-Proxy")
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            response(chain, 200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        }
        val adapter = OpenAiCompatibleAdapter(client = client)
        val config = ProviderConfig(
            providerType = ProviderCatalog.OPENAI_COMPATIBLE,
            baseUrl = "https://example.test/v1/",
            apiKey = "secret",
            model = "custom-model",
            headers = mapOf("X-Proxy" to "edge"),
            options = buildJsonObject {
                put("modelsPath", JsonPrimitive("/v1/models"))
                put("chatCompletionsPath", JsonPrimitive("/v1/chat/completions"))
                put("authHeader", JsonPrimitive("X-API-Key"))
                put("authScheme", JsonPrimitive(""))
                put("includeUsage", JsonPrimitive(false))
                put("supportsTopK", JsonPrimitive(false))
                put("maxTokensField", JsonPrimitive("max_completion_tokens"))
                put("extraBody", buildJsonObject {
                    put("vendor_flag", JsonPrimitive(true))
                    put("model", JsonPrimitive("must-not-override"))
                })
            },
        )

        adapter.streamGenerate(
            config,
            generateRequest(
                stream = false,
                preset = GenerationPreset(
                    id = "preset",
                    name = "preset",
                    providerType = ProviderCatalog.OPENAI_COMPATIBLE,
                    topK = 40,
                ),
            ),
        ).toList()

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("/v1/chat/completions", capturedPath)
        assertEquals("secret", capturedAuthorization)
        assertEquals("edge", capturedProxyHeader)
        assertEquals("custom-model", payload["model"]?.jsonPrimitive?.content)
        assertEquals("77", payload["max_completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("true", payload["vendor_flag"]?.jsonPrimitive?.content)
        assertNull(payload["top_k"])
        assertNull(payload["stream_options"])
    }

    @Test
    fun `deepseek stream keeps reasoning separate and accepts usage-only chunks`() = runBlocking {
        var capturedBody = ""
        var capturedPath = ""
        var capturedAuthorization: String? = null
        var capturedInjectedHeader: String? = null
        val client = client { chain ->
            val request = chain.request()
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            capturedPath = request.url.encodedPath
            capturedAuthorization = request.header("Authorization")
            capturedInjectedHeader = request.header("X-Injected")
            response(
                chain,
                200,
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\r\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\r\n" +
                    "data: {\"choices\":[],\"usage\":{\"total_tokens\":9}}\r\n" +
                    "data: [DONE]\r\n",
                "text/event-stream",
            )
        }
        val adapter = OpenAiCompatibleAdapter(
            client = client,
            providerId = ProviderCatalog.DEEPSEEK,
            defaultModel = "deepseek-v4-flash",
            modelsPath = "/models",
            chatCompletionsPath = "/chat/completions",
        )
        val preset = GenerationPreset(
            id = "deepseek",
            name = "deepseek",
            providerType = ProviderCatalog.DEEPSEEK,
            topK = 50,
            presencePenalty = 0.5,
            frequencyPenalty = 0.5,
            seed = 42,
            raw = buildJsonObject {
                put("thinking", buildJsonObject { put("type", JsonPrimitive("enabled")) })
            },
        )

        val chunks = adapter.streamGenerate(
            ProviderConfig(
                providerType = ProviderCatalog.DEEPSEEK,
                baseUrl = "https://api.deepseek.test",
                apiKey = "secret",
                model = "deepseek-v4-flash",
                headers = mapOf(
                    "Authorization" to "Bearer wrong-key",
                    "X-Injected" to "must-not-be-sent",
                ),
                options = buildJsonObject {
                    put("chatCompletionsPath", JsonPrimitive("/wrong/path"))
                    put("authHeader", JsonPrimitive("X-Wrong-Auth"))
                    put("authScheme", JsonPrimitive("Raw"))
                    put("extraBody", buildJsonObject { put("injected", JsonPrimitive(true)) })
                },
            ),
            generateRequest(stream = true, preset = preset),
        ).toList()
        val completed = chunks.filterIsInstance<GenerateChunk.Completed>().single()
        val payload = Json.parseToJsonElement(capturedBody).jsonObject

        assertEquals("<reasoning>\nthink\n</reasoning>\nanswer", completed.text)
        assertEquals("9", completed.usage?.get("total_tokens")?.jsonPrimitive?.content)
        assertTrue(payload["thinking"] is JsonObject)
        assertNull(payload["top_k"])
        assertNull(payload["stream_options"])
        assertNull(payload["presence_penalty"])
        assertNull(payload["frequency_penalty"])
        assertNull(payload["seed"])
        assertNull(payload["injected"])
        assertEquals("/chat/completions", capturedPath)
        assertEquals("Bearer secret", capturedAuthorization)
        assertNull(capturedInjectedHeader)
    }

    @Test
    fun `negative SillyTavern seed sentinel is omitted`() = runBlocking {
        var capturedBody = ""
        val client = client { chain ->
            capturedBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            response(
                chain,
                200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n" +
                    "data: [DONE]\n",
                "text/event-stream",
            )
        }

        OpenAiCompatibleAdapter(client = client).streamGenerate(
            config(model = "custom-model"),
            generateRequest(
                stream = true,
                preset = GenerationPreset("p", "p", "openai-compatible", seed = -1),
            ),
        ).toList()

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        assertNull(payload["seed"])
    }

    @Test
    fun `stream reassembles tool call deltas`() = runBlocking {
        val client = client { chain ->
            response(
                chain,
                200,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"\"}}]}}]}\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"q\\\":\\\"道渊\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n" +
                    "data: [DONE]\n",
                "text/event-stream",
            )
        }
        val completed = OpenAiCompatibleAdapter(client = client).streamGenerate(
            config(model = "custom-model"),
            generateRequest(stream = true, preset = GenerationPreset("p", "p", "openai-compatible")),
        ).toList().filterIsInstance<GenerateChunk.Completed>().single()

        val toolCalls = completed.toolCalls ?: JsonArray(emptyList())
        assertEquals("call_1", toolCalls[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(
            "{\"q\":\"道渊\"}",
            toolCalls[0].jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content,
        )
    }


    private fun generateRequest(stream: Boolean, preset: GenerationPreset) = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.System, content = "system"),
                PromptMessage(MessageRole.User, content = "hello"),
            ),
            stop = emptyList(),
            maxTokens = 77,
            providerType = preset.providerType,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        ),
        preset = preset,
        stream = stream,
    )


    private fun config(model: String?) = ProviderConfig(
        providerType = ProviderCatalog.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test",
        apiKey = "test-key",
        model = model,
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
