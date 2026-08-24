package app.tellev.core.provider

import app.tellev.core.model.PresetCategory
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GenerationRuntimeResolverTest {
    private lateinit var tempDir: Path
    private lateinit var store: FileStDataStore
    private lateinit var secrets: MemorySecretStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tellev-runtime-")
        store = FileStDataStore(StDirectoryLayout.fromRoot(tempDir))
        secrets = MemorySecretStore()
        runBlocking { store.bootstrap() }
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `resolver selects preset category that matches latest provider`() = runBlocking {
        val registry = ProviderRegistry(
            listOf(
                adapter(ProviderCatalog.OPENAI_COMPATIBLE, ProviderCapability.Chat),
                adapter(ProviderCatalog.TEXTGEN_WEBUI, ProviderCapability.Text),
            ),
        )
        val resolver = GenerationRuntimeResolver(store, registry, secrets)

        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.OPENAI_COMPATIBLE)
        assertEquals(PresetCategory.OpenAi, resolver.resolve().presetCategory)

        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.TEXTGEN_WEBUI)
        val textGen = resolver.resolve()
        assertEquals(ProviderCatalog.TEXTGEN_WEBUI, textGen.selectedProviderId)
        assertEquals(PresetCategory.TextGen, textGen.presetCategory)
        assertTrue(textGen.presets.all { it.category == PresetCategory.TextGen })
        assertEquals(PresetCategory.TextGen, textGen.preset.category)
    }

    @Test
    fun `resolver replaces a non-chat provider selection without deleting its config`() = runBlocking {
        val imageId = "image-only"
        val registry = ProviderRegistry(
            listOf(
                adapter(ProviderCatalog.OPENAI_COMPATIBLE, ProviderCapability.Chat),
                adapter(imageId, ProviderCapability.Images),
            ),
        )
        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, imageId)
        secrets.putSecret("provider-$imageId-apikey", "keep-me")

        val result = GenerationRuntimeResolver(store, registry, secrets).resolve()

        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, result.selectedProviderId)
        assertEquals(
            ProviderCatalog.OPENAI_COMPATIBLE,
            secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID),
        )
        assertEquals("keep-me", secrets.readSecret("provider-$imageId-apikey"))
    }

    @Test
    fun `chat adapter filter excludes media and translation adapters`() {
        val chat = adapter("chat", ProviderCapability.Chat)
        val text = adapter("text", ProviderCapability.Text)
        val image = adapter("image", ProviderCapability.Images)
        val speech = adapter("speech", ProviderCapability.TextToSpeech)
        val translation = adapter("translation", ProviderCapability.Translation)

        val visibleIds = ProviderRegistry(listOf(chat, text, image, speech, translation))
            .chatAdapters()
            .map { it.id }

        assertTrue("chat" in visibleIds)
        assertTrue("text" in visibleIds)
        assertFalse("image" in visibleIds)
        assertFalse("speech" in visibleIds)
        assertFalse("translation" in visibleIds)
    }

    private fun adapter(id: String, vararg capabilities: ProviderCapability): ProviderAdapter =
        object : ProviderAdapter {
            override val id = id
            override val displayName = id
            override val capabilities = capabilities.toSet()
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> =
                emptyFlow()
        }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) {
            values[id] = value
        }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) {
            values.remove(id)
        }
        override suspend fun listSecretIds(): List<String> = values.keys.sorted()
    }
}
