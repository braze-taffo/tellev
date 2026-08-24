package app.tellev.feature.settings

import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun `quick switch exposes every named custom configuration`() {
        val configs = listOf(
            CustomProviderConfig(id = "one", name = "日常聊天"),
            CustomProviderConfig(id = "two", name = "长文模型"),
        )
        val state = SettingsUiState(
            customConfigs = configs,
            selectedProviderId = ProviderConfigPersistence.selectedIdFor("two"),
        )

        assertEquals(
            listOf("日常聊天", "长文模型"),
            providerSwitchOptions(state).map { it.label },
        )
        assertEquals("长文模型", selectedProviderLabel(state))
    }
}
