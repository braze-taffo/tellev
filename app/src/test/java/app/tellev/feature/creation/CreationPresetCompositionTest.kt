package app.tellev.feature.creation

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Authoring follows the CURRENT chat selection: the chat-selected preset for
 * the provider category owns sampling, output budget and stop sequences,
 * while the authoring system instructions stay authoritative.
 */
class CreationPresetCompositionTest {

    private class RecordingProvider : ProviderAdapter {
        val requests = mutableListOf<GenerateRequest>()
        var replies = ArrayDeque(listOf("直接回答，不调用工具。"))

        override val id = "openai-compatible"
        override val displayName = "Fake"
        override val capabilities = setOf(ProviderCapability.Chat)
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
        override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
            requests += request
            return flowOf(GenerateChunk.Completed(replies.removeFirstOrNull() ?: "完成。"))
        }
    }

    private fun noSecrets(selected: String? = null): SecretStore = object : SecretStore {
        override suspend fun putSecret(id: String, value: String) = Unit
        override suspend fun readSecret(id: String): String? = selected
        override suspend fun deleteSecret(id: String) = Unit
        override suspend fun listSecretIds(): List<String> = emptyList()
    }

    @Test
    fun withoutPresetStoreFallsBackToBuiltInAgentPreset() = runBlocking {
        val provider = RecordingProvider()
        val engine = CreationEngine(noSecrets(), ProviderRegistry(listOf(provider)))
        engine.converse(CreationSession(kind = CreationKind.Character), "写一个人物")
        val preset = provider.requests.single().preset
        assertEquals("ai-creation-agent", preset.id)
        assertEquals(0.7, preset.temperature!!, 1e-9)
        assertEquals(1_000_000, preset.maxContextTokens)
        assertEquals(CreationEngine.MAX_OUTPUT_TOKENS, provider.requests.single().prompt.maxTokens)
    }

    @Test
    fun chatSelectedPresetOwnsSamplingBudgetAndStop() = runBlocking {
        val root = Files.createTempDirectory("creation-preset-").toFile()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root.toPath()))
            store.bootstrap()
            store.savePreset(GenerationPreset(
                id = "my_preset",
                name = "我的酒馆预设",
                providerType = "openai-compatible",
                category = PresetCategory.OpenAi,
                temperature = 0.33,
                topP = 0.88,
                maxContextTokens = 32_768,
                maxCompletionTokens = 7_777,
                stop = listOf("END_OF_TURN"),
            ))
            store.selectPreset(PresetCategory.OpenAi, "my_preset")

            val provider = RecordingProvider()
            val engine = CreationEngine(
                noSecrets(), ProviderRegistry(listOf(provider)), store,
            )
            engine.converse(CreationSession(kind = CreationKind.Character), "写一个人物")
            val request = provider.requests.single()
            val preset = request.preset
            // Authoring identity stays stable, values come from the chat preset.
            assertEquals("ai-creation-agent", preset.id)
            // Name round-trip semantics belong to the store; authoring only needs a non-blank label.
            assertTrue(preset.name.isNotBlank())
            assertEquals(0.33, preset.temperature!!, 1e-9)
            assertEquals(0.88, preset.topP!!, 1e-9)
            assertEquals(7_777, request.prompt.maxTokens)
            assertEquals(listOf("END_OF_TURN"), preset.stop)
            // The preset's prompt slots never enter the authoring request.
            assertTrue(preset.prompts.isEmpty())
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun repairRoundsForceZeroTemperatureEvenWithPreset() = runBlocking {
        val root = Files.createTempDirectory("creation-preset-repair-").toFile()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root.toPath()))
            store.bootstrap()
            store.savePreset(GenerationPreset(
                id = "hot", name = "hot", providerType = "openai-compatible",
                category = PresetCategory.OpenAi, temperature = 0.9,
            ))
            store.selectPreset(PresetCategory.OpenAi, "hot")

            val provider = RecordingProvider()
            provider.replies = ArrayDeque(listOf(
                "<tool_call>{\"name\":\"read_card\"", // truncated, unrecoverable → repair round
                "直接回答。",
            ))
            val engine = CreationEngine(
                noSecrets(), ProviderRegistry(listOf(provider)), store,
            )
            engine.converse(CreationSession(kind = CreationKind.WorldBook), "补一条设定")
            assertEquals(0.9, provider.requests[0].preset.temperature!!, 1e-9)
            assertEquals(0.0, provider.requests[1].preset.temperature!!, 1e-9)
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun outputBudgetBelowFloorIsClampedForTheRetryLogic() = runBlocking {
        val root = Files.createTempDirectory("creation-preset-budget-").toFile()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root.toPath()))
            store.bootstrap()
            store.savePreset(GenerationPreset(
                id = "tiny", name = "tiny", providerType = "openai-compatible",
                category = PresetCategory.OpenAi, maxCompletionTokens = 128,
            ))
            store.selectPreset(PresetCategory.OpenAi, "tiny")
            val provider = RecordingProvider()
            CreationEngine(noSecrets(), ProviderRegistry(listOf(provider)), store)
                .converse(CreationSession(kind = CreationKind.Character), "写一个人物")
            assertEquals(1_024, provider.requests.single().prompt.maxTokens)
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    @Test
    fun bootstrapDefaultPresetIsFollowedWhenUserHasNotChosenOne() = runBlocking {
        val root = Files.createTempDirectory("creation-preset-empty-").toFile()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root.toPath()))
            store.bootstrap()
            val provider = RecordingProvider()
            CreationEngine(noSecrets(), ProviderRegistry(listOf(provider)), store)
                .converse(CreationSession(kind = CreationKind.Character), "写一个人物")
            val preset = provider.requests.single().preset
            // bootstrap() seeds the ST default OpenAI preset (temperature 1);
            // authoring follows it instead of a separate default.
            assertEquals("ai-creation-agent", preset.id)
            assertEquals(1.0, preset.temperature!!, 1e-9)
        } finally {
            root.deleteRecursively()
        }
        Unit
    }
}
