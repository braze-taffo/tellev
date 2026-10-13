package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.metrics.GenerationMetrics
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.provider.ReasoningSupport
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authoring requests must carry the draft's reasoning effort and account every
 * completed provider response — including repair and tool-loop rounds.
 */
class CreationEngineUsageTest {
    private class Secrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private class RecordingProvider(
        override val id: String = ProviderCatalog.OPENAI_COMPATIBLE,
        override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.Chat),
        private val reply: suspend (Int) -> GenerateChunk = { GenerateChunk.Completed("Done.") },
    ) : ProviderAdapter {
        val captures = mutableListOf<GenerateRequest>()
        val configs = mutableListOf<ProviderConfig>()
        override val displayName = "Fixture"
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "fixture")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
            captures += request
            configs += config
            emit(reply(captures.size))
        }
    }

    private fun usageFrame(prompt: Int, completion: Int) = buildJsonObject {
        put("prompt_tokens", prompt)
        put("completion_tokens", completion)
        put("total_tokens", prompt + completion)
    }

    @Test
    fun `one record per completed response across tool loop rounds`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { call ->
            if (call == 1) {
                GenerateChunk.Completed(
                    "<tool_call>{\"name\":\"read_card\",\"arguments\":{}}</tool_call>",
                    usage = usageFrame(10, 2),
                )
            } else {
                GenerateChunk.Completed("Finished.", usage = usageFrame(20, 4))
            }
        }
        val metrics = mutableListOf<GenerationMetrics>()
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "Create.", onUsage = { metrics += it })
        assertEquals(2, provider.captures.size)
        assertEquals(2, metrics.size)
        assertEquals(listOf(10, 20), metrics.map { it.promptTokens })
        assertEquals(listOf(2, 4), metrics.map { it.completionTokens })
    }

    @Test
    fun `malformed round still counts its consumed request`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { call ->
            when (call) {
                // Unclosed tool call: the payload cannot be executed, so the loop
                // feeds the failure back and replays the round.
                1 -> GenerateChunk.Completed("<tool_call>{\"name\":\"read_card\",\"arguments\":{", usage = usageFrame(7, 1))
                else -> GenerateChunk.Completed("Done.", usage = usageFrame(8, 2))
            }
        }
        val metrics = mutableListOf<GenerationMetrics>()
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "Create.", onUsage = { metrics += it })
        assertTrue(metrics.size >= 2)
        assertEquals(7, metrics.first().promptTokens)
    }

    @Test
    fun `extraction repair and condensed retries are all accounted`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { call ->
            when (call) {
                1 -> GenerateChunk.Completed("invalid", usage = usageFrame(5, 1))
                2 -> GenerateChunk.Completed("still invalid", usage = usageFrame(6, 1))
                else -> GenerateChunk.Completed("{\"assistant_message\":\"Done\",\"lore\":[]}", usage = usageFrame(7, 1))
            }
        }
        val metrics = mutableListOf<GenerationMetrics>()
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .extractChunk(SourceChunk(0, 6, "Source"), "fixture.txt", onUsage = { metrics += it })
        assertEquals(3, provider.captures.size)
        assertEquals(3, metrics.size)
    }

    @Test
    fun `unreported usage still yields one counted sample per request`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { GenerateChunk.Completed("Finished.") }
        val metrics = mutableListOf<GenerationMetrics>()
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "Create.", onUsage = { metrics += it })
        assertEquals(1, metrics.size)
        assertTrue(metrics.single().isEstimate)
        assertNull(metrics.single().promptTokens)
    }

    @Test
    fun `draft effort travels as the request override`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { GenerateChunk.Completed("Finished.") }
        val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
        engine.converse(
            CreationSession(kind = CreationKind.Character, reasoningEffort = ReasoningEffort.High),
            "Create.",
        )
        val request = provider.captures.single()
        assertEquals(
            ReasoningEffort.High,
            request.metadata[ReasoningSupport.REQUEST_OVERRIDE_KEY]?.jsonPrimitive?.content
                ?.let(ReasoningEffort::fromStored),
        )
        assertEquals(ReasoningEffort.High, ReasoningSupport.effortFor(request))
    }

    @Test
    fun `auto effort sends no override field`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "fixture-model")
        val provider = RecordingProvider { GenerateChunk.Completed("Finished.") }
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "Create.")
        val request = provider.captures.single()
        assertNull(request.metadata[ReasoningSupport.REQUEST_OVERRIDE_KEY])
        assertEquals(ReasoningEffort.Auto, ReasoningSupport.effortFor(request))
    }

    @Test
    fun `custom endpoint keeps its identity in the accounting record`() = runBlocking {
        val secrets = Secrets()
        ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(
            CustomProviderConfig("fixture", "Fixture", "https://fixture.invalid", model = "custom-model"),
        ))
        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, "custom:fixture")
        val provider = RecordingProvider { GenerateChunk.Completed("Finished.", usage = usageFrame(3, 1)) }
        val metrics = mutableListOf<GenerationMetrics>()
        CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            .converse(CreationSession(kind = CreationKind.Character), "Create.", onUsage = { metrics += it })
        assertEquals("custom:fixture", metrics.single().providerType)
        assertEquals("custom-model", metrics.single().model)
    }
}
