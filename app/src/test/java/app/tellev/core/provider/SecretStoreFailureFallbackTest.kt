package app.tellev.core.provider

import app.tellev.core.security.SecretStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TELLER-2-4：模拟读取会抛异常的 SecretStore（主密钥丢失/被重置时的
 * AEADBadTagException、密文或 Base64 损坏），验证持久化层按条目隔离——
 * 单条不可读回落默认值，且「先读后写」的保存路径仍能覆盖写回。
 *
 * 真机上的 AndroidKeystoreSecretStore 需要 Android Context/AndroidKeyStore，
 * 本仓单测无 Robolectric，故这里以桩覆盖它面向调用方的契约部分；
 * 实现侧（readSecret 自身兜底返回 null）见 AndroidKeystoreSecretStore.kt。
 */
class SecretStoreFailureFallbackTest {

    /**
     * 可按 id 注入读取失败的桩。默认健康；[failAll] 模拟主密钥丢失（每条都解不开），
     * [failingIds] 模拟个别条目损坏。
     */
    private class ThrowingSecretStore : SecretStore {
        private val secrets = ConcurrentHashMap<String, String>()
        var failAll = false
        var failingIds: Set<String> = emptySet()
        var failure: Throwable = AEADBadTagException("tag mismatch")

        override suspend fun putSecret(id: String, value: String) { secrets[id] = value }
        override suspend fun readSecret(id: String): String? {
            if (failAll || id in failingIds) throw failure
            return secrets[id]
        }
        override suspend fun deleteSecret(id: String) { secrets.remove(id) }
        override suspend fun listSecretIds(): List<String> = secrets.keys.sorted()
    }

    /** custom-provider-configs 的 secret id（ProviderConfigPersistence 内私有）。 */
    private val customConfigsId = "custom-provider-configs"

    private fun store() = ThrowingSecretStore()

    @Test
    fun `listCustomConfigs falls back to empty when every read throws`() = runBlocking {
        val s = store().apply { failAll = true }
        assertEquals(emptyList<CustomProviderConfig>(), ProviderConfigPersistence.listCustomConfigs(s))
    }

    @Test
    fun `corrupt custom-configs entry does not block the overwrite that repairs it`() = runBlocking {
        val s = store().apply { failingIds = setOf(customConfigsId) }
        assertEquals(emptyList<CustomProviderConfig>(), ProviderConfigPersistence.listCustomConfigs(s))

        val recovered = listOf(
            CustomProviderConfig(id = "cust_1", name = "重录", baseUrl = "https://relay.example", apiKey = "k"),
        )
        ProviderConfigPersistence.saveCustomConfigs(s, recovered)

        // 存储恢复健康后必须读得回来：说明损坏条目可以被覆盖写回，而非永久失锁。
        s.failingIds = emptySet()
        assertEquals(recovered, ProviderConfigPersistence.listCustomConfigs(s))
    }

    @Test
    fun `createCustomConfig read-modify-write cycle survives a failing list read`() = runBlocking {
        val s = store().apply { failingIds = setOf(customConfigsId) }
        // ProviderSettingsController.createCustomConfig 的形状：先读列表、追加、整份写回。
        val existing = ProviderConfigPersistence.listCustomConfigs(s)
        val newConfig = CustomProviderConfig(id = "cust_new", name = "自定义配置 1")
        ProviderConfigPersistence.saveCustomConfigs(s, existing + newConfig)

        s.failingIds = emptySet()
        assertEquals(listOf(newConfig), ProviderConfigPersistence.listCustomConfigs(s))
    }

    @Test
    fun `deleteCustomConfig read-modify-write cycle survives a failing list read`() = runBlocking {
        val s = store().apply { failingIds = setOf(customConfigsId) }
        val updated = ProviderConfigPersistence.listCustomConfigs(s).filterNot { it.id == "cust_old" }
        ProviderConfigPersistence.saveCustomConfigs(s, updated)

        s.failingIds = emptySet()
        assertTrue(ProviderConfigPersistence.listCustomConfigs(s).isEmpty())
    }

    @Test
    fun `broken base64 payload also falls back instead of aborting the write`() = runBlocking {
        val s = store().apply {
            failingIds = setOf(customConfigsId)
            failure = IllegalArgumentException("Illegal base64 character 3a")
        }
        assertTrue(ProviderConfigPersistence.listCustomConfigs(s).isEmpty())
        ProviderConfigPersistence.saveCustomConfigs(
            s,
            listOf(CustomProviderConfig(id = "cust_1", name = "n")),
        )
        s.failingIds = emptySet()
        assertEquals(1, ProviderConfigPersistence.listCustomConfigs(s).size)
    }

    @Test
    fun `migration still runs when the custom-configs read fails`() = runBlocking {
        val s = store().apply { failingIds = setOf(customConfigsId) }
        val result = ProviderConfigPersistence.migrateLegacyOpenAiCompatible(s)

        // 读失败不能被当成「已有配置」而提前返回，也不能让写入半途抛出去。
        assertEquals(
            ProviderConfigPersistence.selectedIdFor("cust_default"),
            result?.newSelectedId,
        )
        s.failingIds = emptySet()
        val configs = ProviderConfigPersistence.listCustomConfigs(s)
        assertEquals(1, configs.size)
        assertEquals("cust_default", configs.single().id)
    }

    @Test
    fun `loadProviderConfig falls back to defaults when its reads throw`() = runBlocking {
        val s = store().apply { failAll = true }
        val config = ProviderConfigPersistence.loadProviderConfig(s, ProviderCatalog.OPENAI_COMPATIBLE)
        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, config.providerType)
        assertEquals(ProviderDefaults.baseUrl(ProviderCatalog.OPENAI_COMPATIBLE), config.baseUrl)
        assertNull(config.apiKey)
    }

    @Test
    fun `loadProviderConfig for a missing custom id falls back when the list read throws`() = runBlocking {
        val s = store().apply { failingIds = setOf(customConfigsId) }
        val config = ProviderConfigPersistence.loadProviderConfig(s, ProviderConfigPersistence.selectedIdFor("gone"))
        assertEquals(ProviderCatalog.OPENAI_COMPATIBLE, config.providerType)
        assertTrue(config.baseUrl.isNotBlank())
    }

    @Test
    fun `image engine and settings reads fall back to their defaults when the store throws`() = runBlocking {
        val s = store().apply { failAll = true }
        assertEquals(ComfyUiSettings(), ProviderConfigPersistence.loadComfySettings(s))
        assertFalse(ProviderConfigPersistence.isComfyImageGenerationConfigured(s))
        assertFalse(ProviderConfigPersistence.isNovelAiImageConfigured(s))
        assertEquals(NovelAiImageSettings(), ProviderConfigPersistence.loadNovelAiImageSettings(s))
        assertEquals(ProviderCatalog.COMFYUI, ProviderConfigPersistence.loadImageEngine(s))
        assertTrue(ProviderConfigPersistence.listImageProviderProfiles(s).isEmpty())
        assertTrue(ProviderConfigPersistence.loadAdvanced(s, ProviderCatalog.OPENAI_COMPATIBLE) ==
            OpenAiCompatibilitySettings.defaults(ProviderCatalog.OPENAI_COMPATIBLE))
    }
}
