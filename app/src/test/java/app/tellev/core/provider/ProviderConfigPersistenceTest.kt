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
    fun `migration handles brand-new user according to build default`() = runBlocking {
        val s = store()
        // No secrets at all -> fresh install.
        val result = ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)
        if (ProviderDefaults.selectedProviderId() == ProviderCatalog.OPENAI_COMPATIBLE) {
            assertEquals(ProviderConfigPersistence.selectedIdFor("cust_default"), result?.newSelectedId)
            assertEquals(1, ProviderConfigPersistence.listCustomConfigs(s).size)
        } else {
            assertNull(result)
            assertTrue(ProviderConfigPersistence.listCustomConfigs(s).isEmpty())
        }
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
}
