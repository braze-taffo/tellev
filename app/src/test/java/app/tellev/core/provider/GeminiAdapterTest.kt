package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
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
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAdapterTest {

    @Test
    fun `native gemini request merges systems and consecutive roles and parses every part`() = runBlocking {
        var requestUrl = ""
        var apiKeyHeader: String? = null
        var requestBody = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requestUrl = request.url.toString()
            apiKeyHeader = request.header("x-goog-api-key")
            requestBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            response(
                chain,
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"why\"},{\"text\":\"one\"}]},\"finishReason\":\"STOP\"},{\"content\":{\"parts\":[{\"text\":\"two\"}]}}],\"usageMetadata\":{\"totalTokenCount\":12}}\n",
            )
        }.build()
        val adapter = GeminiAdapter(client = client)
        val prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.System, content = "system one"),
                PromptMessage(MessageRole.System, content = "system two"),
                PromptMessage(MessageRole.User, content = "user one"),
                PromptMessage(MessageRole.User, content = "user two"),
                PromptMessage(MessageRole.Assistant, content = "model one"),
            ),
            stop = emptyList(),
            maxTokens = 512,
            providerType = ProviderCatalog.GEMINI,
            diagnostics = PromptDiagnostics(emptyList()),
        )
        val preset = GenerationPreset(
            id = "gemini",
            name = "gemini",
            providerType = ProviderCatalog.GEMINI,
            temperature = 0.7,
            topP = 0.8,
            topK = 32,
            seed = 7,
        )

        val chunks = adapter.streamGenerate(
            ProviderConfig(
                providerType = ProviderCatalog.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.test",
                apiKey = "gemini-secret",
                model = "gemini-test",
            ),
            GenerateRequest(prompt = prompt, preset = preset, stream = true),
        ).toList()

        val payload = Json.parseToJsonElement(requestBody).jsonObject
        val contents = payload["contents"]!!.jsonArray
        val systemText = payload["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        val completed = chunks.filterIsInstance<GenerateChunk.Completed>().single()

        assertEquals("gemini-secret", apiKeyHeader)
        assertFalse(requestUrl.contains("key="))
        assertTrue(requestUrl.endsWith(":streamGenerateContent?alt=sse"))
        assertEquals("system one\n\nsystem two", systemText)
        assertEquals(2, contents.size)
        assertEquals(2, contents[0].jsonObject["parts"]!!.jsonArray.size)
        assertEquals("512", payload["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content)
        assertEquals("<reasoning>\nwhy\n</reasoning>\nonetwo", completed.text)
        assertEquals("12", completed.usage?.get("totalTokenCount")?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini safety block becomes a non retryable failure`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n")
        }.build()
        val chunks = GeminiAdapter(client).streamGenerate(
            ProviderConfig(ProviderCatalog.GEMINI, "https://gemini.test", "key", "model"),
            GenerateRequest(
                prompt = PromptBuildResult(
                    messages = listOf(PromptMessage(MessageRole.User, content = "hello")),
                    stop = emptyList(),
                    maxTokens = 32,
                    providerType = ProviderCatalog.GEMINI,
                    diagnostics = PromptDiagnostics(emptyList()),
                ),
                preset = GenerationPreset("p", "p", ProviderCatalog.GEMINI),
                stream = true,
            ),
        ).toList()

        val failure = chunks.filterIsInstance<GenerateChunk.Failed>().single()
        assertEquals("gemini_safety", failure.error.code)
        assertFalse(failure.error.retryable)
    }

    private fun response(chain: Interceptor.Chain, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType()))
            .build()
}
