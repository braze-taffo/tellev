package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaAdapterTest {

    @Test
    fun `stream with done frame completes and reports stop`() = runBlocking {
        val wire = "{\"message\":{\"content\":\"你好\"},\"done\":false}\n" +
            "{\"message\":{\"content\":\"世界\"},\"done\":false}\n" +
            "{\"done\":true,\"total_duration\":1}\n"
        val adapter = OllamaAdapter(client = client { response(it, 200, wire) })
        val chunks = adapter.streamGenerate(config(), generateRequest(true)).toList()

        val completed = chunks.filterIsInstance<GenerateChunk.Completed>().single()
        assertEquals("你好世界", completed.text)
        assertEquals("stop", completed.finishReason)
        assertEquals("你好世界", chunks.filterIsInstance<GenerateChunk.Delta>().joinToString("") { it.text })
    }

    @Test
    fun `malformed frames are skipped instead of ending the stream`() = runBlocking {
        val wire = "{\"message\":{\"content\":\"before\"},\"done\":false}\n" +
            "<html>gateway garbage</html>\n" +
            "{\"message\":{\"content\":\"after\"},\"done\":false}\n" +
            "{\"done\":true}\n"
        val adapter = OllamaAdapter(client = client { response(it, 200, wire) })
        val chunks = adapter.streamGenerate(config(), generateRequest(true)).toList()

        val completed = chunks.filterIsInstance<GenerateChunk.Completed>().single()
        assertEquals("beforeafter", completed.text)
        assertEquals("stop", completed.finishReason)
    }

    @Test
    fun `error frame fails the stream instead of saving an empty reply`() = runBlocking {
        val wire = "{\"message\":{\"content\":\"partial\"},\"done\":false}\n" +
            "{\"error\":\"model crashed\"}\n" +
            "{\"message\":{\"content\":\"must not save\"},\"done\":false}\n"
        val adapter = OllamaAdapter(client = client { response(it, 200, wire) })
        val chunks = adapter.streamGenerate(config(), generateRequest(true)).toList()

        val failed = chunks.filterIsInstance<GenerateChunk.Failed>().single()
        assertEquals("ollama_stream_error", failed.error.code)
        assertEquals("model crashed", failed.error.message)
        assertTrue(chunks.none { it is GenerateChunk.Completed })
        assertTrue(chunks.filterIsInstance<GenerateChunk.Delta>().none { it.text == "must not save" })
    }

    @Test
    fun `stream ending without done reports null finish reason`() = runBlocking {
        val wire = "{\"message\":{\"content\":\"partial\"},\"done\":false}\n"
        val adapter = OllamaAdapter(client = client { response(it, 200, wire) })
        val completed = adapter.streamGenerate(config(), generateRequest(true)).toList()
            .filterIsInstance<GenerateChunk.Completed>().single()

        assertEquals("partial", completed.text)
        assertNull(completed.finishReason)
    }

    @Test
    fun `require_stream_terminator fails a stream that ends without done`() = runBlocking {
        val wire = "{\"message\":{\"content\":\"partial\"},\"done\":false}\n"
        val adapter = OllamaAdapter(client = client { response(it, 200, wire) })
        val request = generateRequest(true).copy(metadata = buildJsonObject {
            put("require_stream_terminator", true)
        })
        val chunks = adapter.streamGenerate(config(), request).toList()

        assertEquals("provider_incomplete_stream", chunks.filterIsInstance<GenerateChunk.Failed>().single().error.code)
        assertTrue(chunks.none { it is GenerateChunk.Completed })
    }

    private fun generateRequest(stream: Boolean) = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(MessageRole.System, content = "system"),
                PromptMessage(MessageRole.User, content = "hello"),
            ),
            stop = emptyList(),
            maxTokens = 77,
            providerType = ProviderCatalog.OLLAMA,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        ),
        preset = GenerationPreset("p", "p", ProviderCatalog.OLLAMA),
        stream = stream,
    )

    private fun config() = ProviderConfig(
        providerType = ProviderCatalog.OLLAMA,
        baseUrl = "https://ollama.test",
        model = "llama3",
    )

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        mediaType: String = "application/x-ndjson",
    ): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(mediaType.toMediaType()))
            .build()
}
