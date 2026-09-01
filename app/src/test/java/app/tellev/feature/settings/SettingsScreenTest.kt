package app.tellev.feature.settings

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetPrompt
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `preset editor includes unused prompts and preserves active order on save`() {
        val preset = GenerationPreset(
            id = "story",
            name = "Story",
            providerType = "OpenAI Settings",
            prompts = listOf(
                PresetPrompt(identifier = "second", order = 2),
                PresetPrompt(identifier = "first", order = 1),
            ),
            promptsUnused = listOf(
                PresetPrompt(identifier = "unused", enabled = true),
            ),
        )

        val entries = editablePresetPrompts(preset)

        assertEquals(listOf("first", "second", "unused"), entries.map { it.prompt.identifier })
        assertFalse(entries[0].isUnused)
        assertTrue(entries[2].isUnused)

        val enabledUnused = entries.map { entry ->
            if (entry.prompt.identifier == "unused") {
                entry.copy(prompt = entry.prompt.copy(enabled = true), isUnused = false)
            } else {
                entry
            }
        }
        val saved = presetWithEditablePrompts(preset, enabledUnused)

        assertEquals(listOf("first", "second", "unused"), saved.prompts.map { it.identifier })
        assertEquals(listOf(0, 1, 2), saved.prompts.map { it.order })
        assertTrue(saved.promptsUnused.isEmpty())
    }

    @Test
    fun `preset editor keeps disabled ordered prompts separate from unused prompts`() {
        val preset = GenerationPreset(
            id = "story",
            name = "Story",
            providerType = "OpenAI Settings",
        )
        val entries = listOf(
            EditablePresetPrompt(PresetPrompt(identifier = "disabled", enabled = false), isUnused = false),
            EditablePresetPrompt(PresetPrompt(identifier = "unused", enabled = false), isUnused = true),
        )

        val saved = presetWithEditablePrompts(preset, entries)

        assertEquals(listOf("disabled"), saved.prompts.map { it.identifier })
        assertFalse(saved.prompts.single().enabled)
        assertEquals(listOf("unused"), saved.promptsUnused.map { it.identifier })
    }
}
