package app.tellev.core.provider

import app.tellev.core.security.SecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ProviderConfigPersistenceTest {

    private class InMemorySecretStore : SecretStore {
        private val secrets = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { secrets[id] = value }
        override suspend fun readSecret(id: String): String? = secrets[id]
        override suspend fun deleteSecret(id: String) { secrets.remove(id) }
        override suspend fun listSecretIds(): List<String> = secrets.keys.sorted()
    }

    private fun store() = InMemorySecretStore()

    @Test
    fun `listCustomConfigs is empty when none stored`() = runBlocking {
        assertTrue(ProviderConfigPersistence.listCustomConfigs(store()).isEmpty())
    }

    @Test
    fun `saveCustomConfigs round-trips`() = runBlocking {
        val s = store()
        val configs = listOf(
            CustomProviderConfig(id = "a", name = "First", baseUrl = "https://a", apiKey = "k1", model = "m1"),
            CustomProviderConfig(id = "b", name = "Second", baseUrl = "https://b", advanced = OpenAiCompatibilitySettings(supportsTools = true)),
        )
        ProviderConfigPersistence.saveCustomConfigs(s, configs)
        assertEquals(configs, ProviderConfigPersistence.listCustomConfigs(s))
    }

    @Test
    fun `loadProviderConfig resolves custom id to openai-compatible`() = runBlocking {
        val s = store()
        ProviderConfigPersistence.saveCustomConfigs(s, listOf(
            CustomProviderConfig(id = "x", name = "X", baseUrl = "https://x", apiKey = "key", model = "m",
                advanced = OpenAiCompatibilitySettings(supportsVision = true)),
        ))
        val config = ProviderConfigPersistence.loadProviderConfig(s, ProviderConfigPersistence.selectedIdFor("x"))
        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, config.providerType)
        assertEquals("https://x", config.baseUrl)
        assertEquals("key", config.apiKey)
        assertEquals("m", config.model)
        // Advanced settings flow into options/headers.
        assertEquals("true", config.options["supportsVision"]?.toString())
    }

    @Test
    fun `loadProviderConfig custom id falls back when missing`() = runBlocking {
        val config = ProviderConfigPersistence.loadProviderConfig(store(), ProviderConfigPersistence.selectedIdFor("nope"))
        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, config.providerType)
        // Falls back to the built-in default base url (not crash, not blank).
        assertTrue(config.baseUrl.isNotBlank())
    }

    @Test
    fun `adapterIdFor maps custom to openai-compatible and identity otherwise`() {
        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, ProviderConfigPersistence.adapterIdFor("custom:abc"))
        assertEquals(ProviderCatalog.ANTHROPIC, ProviderConfigPersistence.adapterIdFor(ProviderCatalog.ANTHROPIC))
    }

    @Test
    fun `migration materialises legacy openai-compatible slot and repoints selection`() = runBlocking {
        val s = store()
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-baseurl", "https://legacy")
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-apikey", "legacykey")
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-model", "legacy-model")
        s.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.OPENAI_COMPATIBLE)
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-advanced",
            """{"supportsTools":true,"chatCompletionsPath":"/v2/chat"}""")

        val result = ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)

        assertEquals(ProviderConfigPersistence.selectedIdFor("cust_legacy"), result?.newSelectedId)
        val configs = ProviderConfigPersistence.listCustomConfigs(s)
        assertEquals(1, configs.size)
        val migrated = configs.single()
        assertEquals("默认 OpenAI 兼容", migrated.name)
        assertEquals("https://legacy", migrated.baseUrl)
        assertEquals("legacykey", migrated.apiKey)
        assertEquals("legacy-model", migrated.model)
        assertTrue(migrated.advanced.supportsTools)
        // Selection repointed to the custom config.
        assertEquals(
            ProviderConfigPersistence.selectedIdFor("cust_legacy"),
            s.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID),
        )
        // Loading via the new selected id yields the migrated endpoint.
        val loaded = ProviderConfigPersistence.loadProviderConfig(s, result!!.newSelectedId!!)
        assertEquals("https://legacy", loaded.baseUrl)
    }

    @Test
    fun `migration creates starter config for brand-new user defaulting to openai-compatible`() = runBlocking {
        val s = store()
        // No secrets at all -> fresh install.
        val result = ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)
        assertEquals(ProviderConfigPersistence.selectedIdFor("cust_default"), result?.newSelectedId)
        assertEquals(1, ProviderConfigPersistence.listCustomConfigs(s).size)
    }

    @Test
    fun `migration leaves non-openai users untouched`() = runBlocking {
        val s = store()
        s.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.ANTHROPIC)
        // No legacy openai-compatible data either.
        assertNull(ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s))
        assertTrue(ProviderConfigPersistence.listCustomConfigs(s).isEmpty())
        assertEquals(ProviderCatalog.ANTHROPIC, s.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID))
    }

    @Test
    fun `migration preserves legacy data but keeps selection when user is on another provider`() = runBlocking {
        val s = store()
        s.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.ANTHROPIC)
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-baseurl", "https://legacy")
        val result = ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)
        // Legacy data is preserved as a custom config...
        assertEquals(1, ProviderConfigPersistence.listCustomConfigs(s).size)
        // ...but the user's selection stays on anthropic.
        assertNull(result?.newSelectedId)
        assertEquals(ProviderCatalog.ANTHROPIC, s.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID))
    }

    @Test
    fun `migration is idempotent`() = runBlocking {
        val s = store()
        s.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-baseurl", "https://legacy")
        s.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.OPENAI_COMPATIBLE)
        ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)
        // Second call must be a no-op.
        assertNull(ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s))
        assertEquals(1, ProviderConfigPersistence.listCustomConfigs(s).size)
    }

    // ── ComfyUI 生图设置 ──────────────────────────────────────────────────

    @Test
    fun `comfy settings default to fresh install state`() = runBlocking {
        val s = store()
        assertEquals(ComfyUiSettings(), ProviderConfigPersistence.loadComfySettings(s))
        assertTrue(!ProviderConfigPersistence.isComfyImageGenerationConfigured(s))
    }

    @Test
    fun `comfy settings round-trip and enable availability`() = runBlocking {
        val s = store()
        val settings = ComfyUiSettings(
            workflowJson = """{"4": {"class_type": "CLIPTextEncode", "inputs": {"text": "%prompt%"}}}""",
            negativePrompt = "lowres",
            steps = 30,
        )
        ProviderConfigPersistence.saveComfySettings(s, settings)
        assertEquals(settings, ProviderConfigPersistence.loadComfySettings(s))
        assertTrue(ProviderConfigPersistence.isComfyImageGenerationConfigured(s))
    }

    @Test
    fun `comfy settings corrupted json falls back to defaults`() = runBlocking {
        val s = store()
        s.putSecret("provider-comfyui-settings", "not json at all")
        assertEquals(ComfyUiSettings(), ProviderConfigPersistence.loadComfySettings(s))
    }

    @Test
    fun `comfy availability false for invalid workflow`() = runBlocking {
        val s = store()
        ProviderConfigPersistence.saveComfySettings(s, ComfyUiSettings(workflowJson = "{\"broken\": "))
        assertTrue(!ProviderConfigPersistence.isComfyImageGenerationConfigured(s))
    }

    @Test
    fun `comfy availability false when workflow only whitespace`() = runBlocking {
        val s = store()
        ProviderConfigPersistence.saveComfySettings(s, ComfyUiSettings(workflowJson = "   "))
        assertTrue(!ProviderConfigPersistence.isComfyImageGenerationConfigured(s))
    }

    @Test
    fun `loadProviderConfig reads comfy url and model slots`() = runBlocking {
        val s = store()
        s.putSecret("provider-${ProviderCatalog.COMFYUI}-baseurl", "http://192.168.1.50:8188")
        s.putSecret("provider-${ProviderCatalog.COMFYUI}-model", "sd_xl.safetensors")
        val config = ProviderConfigPersistence.loadProviderConfig(s, ProviderCatalog.COMFYUI)
        assertEquals(ProviderCatalog.COMFYUI, config.providerType)
        assertEquals("http://192.168.1.50:8188", config.baseUrl)
        assertEquals("sd_xl.safetensors", config.model)
    }

    fun `image engine legacy local-diffusion value falls back to comfy`() = runBlocking {
        val s = store()
        s.putSecret(ProviderConfigPersistence.IMAGE_ENGINE_SECRET_ID, "local-diffusion")
        assertEquals(ProviderCatalog.COMFYUI, ProviderConfigPersistence.loadImageEngine(s))
    }

    @Test
    fun `image engine unknown stored value falls back to comfy`() = runBlocking {
        val s = store()
        s.putSecret(ProviderConfigPersistence.IMAGE_ENGINE_SECRET_ID, "does-not-exist")
        assertEquals(ProviderCatalog.COMFYUI, ProviderConfigPersistence.loadImageEngine(s))
    }

    // ── NovelAI 生图设置 ─────────────────────────────────────────────────

    @Test
    fun `novelai image settings default to fresh install state`() = runBlocking {
        val s = store()
        assertEquals(NovelAiImageSettings(), ProviderConfigPersistence.loadNovelAiImageSettings(s))
        assertTrue(!ProviderConfigPersistence.isNovelAiImageConfigured(s))
    }

    @Test
    fun `novelai image settings round-trip and availability follows the token`() = runBlocking {
        val s = store()
        val settings = NovelAiImageSettings(
            model = "nai-diffusion-4-5-curated",
            sampler = "k_dpmpp_2m",
            steps = 28,
            scale = 9.0,
            width = 832,
            height = 1216,
            varietyBoost = true,
        )
        ProviderConfigPersistence.saveNovelAiImageSettings(s, settings)
        assertEquals(settings, ProviderConfigPersistence.loadNovelAiImageSettings(s))
        // Availability is driven by the saved token, not the settings blob.
        assertTrue(!ProviderConfigPersistence.isNovelAiImageConfigured(s))
        s.putSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey", "pst-token")
        assertTrue(ProviderConfigPersistence.isNovelAiImageConfigured(s))
        s.deleteSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey")
        assertTrue(!ProviderConfigPersistence.isNovelAiImageConfigured(s))
    }

    @Test
    fun `novelai image corrupted json falls back to defaults`() = runBlocking {
        val s = store()
        s.putSecret("provider-novelai-image-settings", "not json at all")
        assertEquals(NovelAiImageSettings(), ProviderConfigPersistence.loadNovelAiImageSettings(s))
    }

    @Test
    fun `image engine round-trips novelai-image`() = runBlocking {
        val s = store()
        ProviderConfigPersistence.saveImageEngine(s, ProviderCatalog.NOVELAI_IMAGE)
        assertEquals(ProviderCatalog.NOVELAI_IMAGE, ProviderConfigPersistence.loadImageEngine(s))
    }

    @Test
    fun `loadProviderConfig reads the novelai image token slot`() = runBlocking {
        val s = store()
        s.putSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey", "pst-token")
        val config = ProviderConfigPersistence.loadProviderConfig(s, ProviderCatalog.NOVELAI_IMAGE)
        assertEquals(ProviderCatalog.NOVELAI_IMAGE, config.providerType)
        assertEquals("pst-token", config.apiKey)
        assertEquals("https://image.novelai.net", config.baseUrl)
    }
}
