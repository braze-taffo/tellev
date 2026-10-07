package app.tellev.feature.settings

import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import app.tellev.feature.settings.controller.ImageGenSettingsController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ImageGenSettingsControllerTest {
    private class Secrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override suspend fun readSecret(id: String) = values[id]
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds() = values.keys.toList()
    }

    @Test fun `saving relay and switching back preserves each inactive key`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-novelai-image-apikey", "saved-official")
        val state = MutableStateFlow(SettingsUiState(novelAiToken = "unsaved-official", novelAiRelayToken = "relay",
            novelAiSettings = NovelAiImageSettings(useRelay = true, relayBaseUrl = "https://relay.example")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ImageGenSettingsController(secrets, ProviderRegistry(emptyList()), scope, state)
            controller.saveNovelAiImageConfig()
            assertNull(state.value.error)
            assertEquals("saved-official", secrets.readSecret("provider-novelai-image-apikey"))
            assertEquals("relay", secrets.readSecret(NovelAiImageSettings.RELAY_TOKEN_SECRET_ID))
            controller.updateNovelAiSettings { it.copy(useRelay = false) }
            controller.saveNovelAiImageConfig()
            assertEquals("unsaved-official", secrets.readSecret("provider-novelai-image-apikey"))
            assertEquals("relay", secrets.readSecret(NovelAiImageSettings.RELAY_TOKEN_SECRET_ID))
            assertEquals("unsaved-official", ProviderConfigPersistence.loadProviderConfig(secrets, ProviderCatalog.NOVELAI_IMAGE).apiKey)
        } finally { scope.cancel() }
    }

    @Test fun `invalid relay save does not replace credentials or previous saved settings`() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret(NovelAiImageSettings.RELAY_TOKEN_SECRET_ID, "old-relay")
        val before = NovelAiImageSettings()
        ProviderConfigPersistence.saveNovelAiImageSettings(secrets, before)
        val state = MutableStateFlow(SettingsUiState(novelAiRelayToken = "replacement",
            novelAiSettings = before.copy(useRelay = true)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            ImageGenSettingsController(secrets, ProviderRegistry(emptyList()), scope, state).saveNovelAiImageConfig()
            assertNotNull(state.value.error)
            assertFalse(state.value.isLoading)
            assertEquals(before, ProviderConfigPersistence.loadNovelAiImageSettings(secrets))
            assertEquals("old-relay", secrets.readSecret(NovelAiImageSettings.RELAY_TOKEN_SECRET_ID))
        } finally { scope.cancel() }
    }
}
