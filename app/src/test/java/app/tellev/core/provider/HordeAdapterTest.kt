package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class HordeAdapterTest {
    @Test
    fun `submit network failure completes flow instead of keeping guard child active`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()
        val chunks = withTimeout(2_000) {
            HordeAdapter(client = client).streamGenerate(
                ProviderConfig(ProviderCatalog.HORDE, "https://example.test"),
                GenerateRequest(
                    prompt = PromptBuildResult(
                        messages = listOf(PromptMessage(MessageRole.User, content = "hello")),
                        stop = emptyList(),
                        maxTokens = null,
                        providerType = ProviderCatalog.HORDE,
                        diagnostics = PromptDiagnostics(emptyList()),
                    ),
                    preset = GenerationPreset("p", "p", ProviderCatalog.HORDE),
                ),
            ).toList()
        }
        assertEquals("provider_network", (chunks.single() as GenerateChunk.Failed).error.code)
    }
}
