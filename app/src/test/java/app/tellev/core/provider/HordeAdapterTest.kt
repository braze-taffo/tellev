package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HordeAdapterTest {
    @Test
    fun `status cancellation ends flow while remote delete is still waiting`() = runBlocking {
        val statusStarted = CountDownLatch(1)
        val statusRelease = CountDownLatch(1)
        val deleteStarted = CountDownLatch(1)
        val deleteRelease = CountDownLatch(1)
        BlockingProviderHttpServer { exchange ->
            when {
                exchange.requestPath.endsWith("/generate/text/async") ->
                    exchange.respondJson("""{"id":"job-1"}""")
                exchange.requestMethod == "DELETE" -> exchange.stallBeforeHeaders(deleteStarted, deleteRelease)
                exchange.requestPath.endsWith("/generate/text/status/job-1") ->
                    exchange.stallBody(statusStarted, statusRelease)
                else -> exchange.respondJson("{}")
            }
        }.use { server ->
            val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    val target = chain.request().url.newBuilder()
                        .scheme("http")
                        .host("127.0.0.1")
                        .port(server.baseUrl.substringAfterLast(':').toInt())
                        .build()
                    chain.proceed(chain.request().newBuilder().url(target).build())
                })
                .build()
            val job = launch(Dispatchers.Default) {
                HordeAdapter(client = client).streamGenerate(config(), request()).toList()
            }
            try {
                assertTrue("status body was not reached", statusStarted.await(8, TimeUnit.SECONDS))
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue("remote cancel was not queued", deleteStarted.await(3, TimeUnit.SECONDS))
            } finally {
                statusRelease.countDown()
                deleteRelease.countDown()
                withTimeout(3_000) { job.cancelAndJoin() }
            }
        }
    }

    @Test
    fun `submit network failure completes flow instead of keeping guard child active`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()
        val chunks = withTimeout(2_000) {
            HordeAdapter(client = client).streamGenerate(config(), request()).toList()
        }
        assertEquals("provider_network", (chunks.single() as GenerateChunk.Failed).error.code)
    }

    private fun config() = ProviderConfig(ProviderCatalog.HORDE, "https://example.test")

    private fun request() = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(PromptMessage(MessageRole.User, content = "hello")),
            stop = emptyList(),
            maxTokens = null,
            providerType = ProviderCatalog.HORDE,
            diagnostics = PromptDiagnostics(emptyList()),
        ),
        preset = GenerationPreset("p", "p", ProviderCatalog.HORDE),
    )
}
