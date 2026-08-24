package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.WorldBook
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore

data class GenerationRuntimeSnapshot(
    val selectedProviderId: String,
    val providerConfig: ProviderConfig,
    val presetCategory: PresetCategory,
    val presets: List<GenerationPreset>,
    val preset: GenerationPreset,
    val personas: List<Persona>,
    val persona: Persona?,
    val worldBooks: List<WorldBook>,
    val disabledWorldIds: Set<String>,
    val activeWorldBooks: List<WorldBook>,
)

class GenerationRuntimeResolver(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
) {
    suspend fun resolve(selectedPersonaId: String? = null): GenerationRuntimeSnapshot {
        val storedProviderId = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
            ?: ProviderCatalog.OPENAI_COMPATIBLE
        val adapterId = ProviderConfigPersistence.adapterIdFor(storedProviderId)
        val selectedProviderId = if (providerRegistry.find(adapterId)?.supportsChatGeneration == true) {
            storedProviderId
        } else {
            ProviderCatalog.OPENAI_COMPATIBLE.also {
                secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, it)
            }
        }
        val providerConfig = ProviderConfigPersistence.loadProviderConfig(secretStore, selectedProviderId)
        val presetCategory = presetCategoryForProvider(providerConfig.providerType)
        val presets = dataStore.listPresets().filter { it.category == presetCategory }
        val selectedPresetName = dataStore.readSelectedPresetName(presetCategory)
        val selectedNamedPreset = presets.firstOrNull { it.id == selectedPresetName }
            ?: presets.firstOrNull()
            ?: error("没有可用于 ${presetCategory.name} 的生成预设")
        val workingPreset = if (selectedNamedPreset.id == selectedPresetName) {
            dataStore.readPreset(presetCategory, "in_use")
        } else {
            null
        }
        val preset = workingPreset?.copy(
            id = selectedNamedPreset.id,
            name = selectedNamedPreset.name,
            providerType = selectedNamedPreset.providerType,
            category = selectedNamedPreset.category,
        ) ?: selectedNamedPreset
        val personas = dataStore.listPersonas()
        val persona = personas.firstOrNull { it.id == selectedPersonaId } ?: personas.firstOrNull()
        val worldBooks = dataStore.listWorldBooks()
        val disabledWorldIds = dataStore.readDisabledWorldIds()

        return GenerationRuntimeSnapshot(
            selectedProviderId = selectedProviderId,
            providerConfig = providerConfig,
            presetCategory = presetCategory,
            presets = presets,
            preset = preset,
            personas = personas,
            persona = persona,
            worldBooks = worldBooks,
            disabledWorldIds = disabledWorldIds,
            activeWorldBooks = worldBooks.filterNot { it.id in disabledWorldIds },
        )
    }
}

fun presetCategoryForProvider(providerType: String): PresetCategory = when (providerType) {
    ProviderCatalog.TEXTGEN_WEBUI, ProviderCatalog.OLLAMA, ProviderCatalog.LLAMA_CPP ->
        PresetCategory.TextGen
    ProviderCatalog.KOBOLD, ProviderCatalog.KOBOLDCPP, ProviderCatalog.HORDE ->
        PresetCategory.Kobold
    ProviderCatalog.NOVELAI -> PresetCategory.NovelAi
    else -> PresetCategory.OpenAi
}
