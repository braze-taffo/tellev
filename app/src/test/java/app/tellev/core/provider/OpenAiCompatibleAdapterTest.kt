package app.tellev.core.provider

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAdapterTest {

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

    private fun config(model: String?) = ProviderConfig(
        providerType = ProviderCatalog.OPENAI_COMPATIBLE,
        baseUrl = "https://example.test",
        apiKey = "test-key",
        model = model,
    )

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(chain: Interceptor.Chain, code: Int, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
