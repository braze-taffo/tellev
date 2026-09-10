package app.tellev.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.ComfyUiSettings
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.AppPreferences
import app.tellev.core.storage.StDataStore
import app.tellev.feature.settings.controller.AppearanceSettingsController
import app.tellev.feature.settings.controller.BackupSettingsController
import app.tellev.feature.settings.controller.ImageGenSettingsController
import app.tellev.feature.settings.controller.PersonaSecretSettingsController
import app.tellev.feature.settings.controller.PresetSettingsController
import app.tellev.feature.settings.controller.ProviderSettingsController
import app.tellev.ui.theme.ThemeAccent
import app.tellev.ui.theme.ThemeMode
import app.tellev.ui.theme.parseThemeAccent
import app.tellev.ui.theme.parseThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

data class SettingsUiState(
    val providers: List<ProviderAdapter> = emptyList(),
    val selectedProviderId: String = "openai-compatible",
    val customConfigs: List<CustomProviderConfig> = emptyList(),
    val customConfigName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val compatibility: OpenAiCompatibilitySettings = OpenAiCompatibilitySettings(),
    val extraHeadersJson: String = "{}",
    val extraBodyJson: String = "{}",
    val providerStatus: ProviderStatus? = null,
    val isTesting: Boolean = false,
    val presets: List<GenerationPreset> = emptyList(),
    val selectedPresetNames: Map<PresetCategory, String> = emptyMap(),
    val personas: List<Persona> = emptyList(),
    val secretIds: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.System,
    val themeAccent: ThemeAccent = ThemeAccent.Warm,
    val chatBubbleAlpha: Float = 0.6f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val availableModels: List<String> = emptyList(),
    // ── 生图模型（ComfyUI）──
    val comfyBaseUrl: String = "",
    val comfyModel: String = "",
    val comfySettings: ComfyUiSettings = ComfyUiSettings(),
    val comfyStatus: ProviderStatus? = null,
    val isTestingComfy: Boolean = false,
    val comfyModels: List<String> = emptyList(),
    // ── 生图引擎选择与 NovelAI 生图（远程）──
    val imageEngine: String = ProviderCatalog.COMFYUI,
    val novelAiToken: String = "",
    val novelAiSettings: NovelAiImageSettings = NovelAiImageSettings(),
    val novelAiStatus: ProviderStatus? = null,
    val isTestingNovelAi: Boolean = false,
)

class SettingsViewModel(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val appPreferences: AppPreferences,
    private val themeModeFlow: MutableStateFlow<ThemeMode>,
    private val themeAccentFlow: MutableStateFlow<ThemeAccent>,
    private val chatBubbleAlphaFlow: MutableStateFlow<Float>,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Domain Controllers
    private val providerController = ProviderSettingsController(
        secretStore = secretStore,
        providerRegistry = providerRegistry,
        json = json,
        scope = viewModelScope,
        stateFlow = _uiState,
    )

    private val imageGenController = ImageGenSettingsController(
        secretStore = secretStore,
        providerRegistry = providerRegistry,
        scope = viewModelScope,
        stateFlow = _uiState,
    )

    private val presetController = PresetSettingsController(
        dataStore = dataStore,
        json = json,
        scope = viewModelScope,
        stateFlow = _uiState,
    )

    private val personaSecretController = PersonaSecretSettingsController(
        dataStore = dataStore,
        secretStore = secretStore,
        scope = viewModelScope,
        stateFlow = _uiState,
    )

    private val appearanceController = AppearanceSettingsController(
        appPreferences = appPreferences,
        themeModeFlow = themeModeFlow,
        themeAccentFlow = themeAccentFlow,
        chatBubbleAlphaFlow = chatBubbleAlphaFlow,
        stateFlow = _uiState,
    )

    private val backupController = BackupSettingsController(
        dataStore = dataStore,
        scope = viewModelScope,
        stateFlow = _uiState,
    )

    init {
        observePresetChanges()
        loadInitialData()
    }

    private fun observePresetChanges() {
        viewModelScope.launch {
            dataStore.presetChanges.collect {
                runCatching {
                    val presets = dataStore.listPresets()
                    val selected = PresetCategory.entries.mapNotNull { category ->
                        dataStore.readSelectedPresetName(category)?.let { category to it }
                    }.toMap()
                    presets to selected
                }.onSuccess { (presets, selected) ->
                    _uiState.update { it.copy(presets = presets, selectedPresetNames = selected) }
                }
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                ProviderConfigPersistence.migrateLegacyOpenAiCompatible(secretStore)
                val customConfigs = ProviderConfigPersistence.listCustomConfigs(secretStore)

                val providers = providerRegistry.chatAdapters()
                val presets = dataStore.listPresets()
                val selectedPresetNames = PresetCategory.entries.mapNotNull { category ->
                    dataStore.readSelectedPresetName(category)?.let { category to it }
                }.toMap()
                val personas = dataStore.listPersonas()
                val secretIds = secretStore.listSecretIds()
                val storedSelectedId = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: _uiState.value.selectedProviderId
                val selectedId = if (
                    providerRegistry.find(ProviderConfigPersistence.adapterIdFor(storedSelectedId))
                        ?.supportsChatGeneration == true
                ) {
                    storedSelectedId
                } else {
                    ProviderCatalog.OPENAI_COMPATIBLE.also {
                        secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, it)
                    }
                }

                val fields = providerController.loadConfigFields(selectedId, customConfigs)

                val comfySettings = ProviderConfigPersistence.loadComfySettings(secretStore)
                val comfyBaseUrl = secretStore.readSecret("provider-${ProviderCatalog.COMFYUI}-baseurl")
                    ?: ProviderDefaults.baseUrl(ProviderCatalog.COMFYUI)
                val comfyModel = secretStore.readSecret("provider-${ProviderCatalog.COMFYUI}-model") ?: ""

                val imageEngine = ProviderConfigPersistence.loadImageEngine(secretStore)
                val novelAiSettings = ProviderConfigPersistence.loadNovelAiImageSettings(secretStore)
                val novelAiToken =
                    secretStore.readSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey") ?: ""

                _uiState.update {
                    it.copy(
                        providers = providers,
                        selectedProviderId = selectedId,
                        customConfigs = customConfigs,
                        customConfigName = fields.customConfigName,
                        presets = presets,
                        selectedPresetNames = selectedPresetNames,
                        personas = personas,
                        secretIds = secretIds,
                        themeMode = parseThemeMode(appPreferences.themeModeName),
                        themeAccent = parseThemeAccent(appPreferences.themeAccentName),
                        chatBubbleAlpha = appPreferences.chatBubbleAlpha,
                        baseUrl = fields.baseUrl,
                        apiKey = fields.apiKey,
                        model = fields.model,
                        compatibility = fields.compatibility,
                        extraHeadersJson = json.encodeToString(fields.compatibility.headers),
                        extraBodyJson = json.encodeToString(JsonObject.serializer(), fields.compatibility.extraBody),
                        isLoading = false,
                        comfyBaseUrl = comfyBaseUrl,
                        comfyModel = comfyModel,
                        comfySettings = comfySettings,
                        imageEngine = imageEngine,
                        novelAiToken = novelAiToken,
                        novelAiSettings = novelAiSettings,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载设置失败：${e.message}",
                    )
                }
            }
        }
    }

    // ── Provider Actions ──

    fun selectProvider(id: String) = providerController.selectProvider(id)
    fun activateProvider(id: String) = providerController.activateProvider(id)
    fun updateBaseUrl(url: String) = providerController.updateBaseUrl(url)
    fun updateApiKey(key: String) = providerController.updateApiKey(key)
    fun updateModel(model: String) = providerController.updateModel(model)
    fun updateModelsPath(value: String) = providerController.updateModelsPath(value)
    fun updateChatCompletionsPath(value: String) = providerController.updateChatCompletionsPath(value)
    fun updateAuthHeader(value: String) = providerController.updateAuthHeader(value)
    fun updateAuthScheme(value: String) = providerController.updateAuthScheme(value)
    fun updateIncludeUsage(value: Boolean) = providerController.updateIncludeUsage(value)
    fun updateSupportsModelListing(value: Boolean) = providerController.updateSupportsModelListing(value)
    fun updateSupportsTopK(value: Boolean) = providerController.updateSupportsTopK(value)
    fun updateSupportsTools(value: Boolean) = providerController.updateSupportsTools(value)
    fun updateSupportsReasoning(value: Boolean) = providerController.updateSupportsReasoning(value)
    fun updateSupportsVision(value: Boolean) = providerController.updateSupportsVision(value)
    fun updateMaxTokensField(value: String) = providerController.updateMaxTokensField(value)
    fun updateExtraHeadersJson(value: String) = providerController.updateExtraHeadersJson(value)
    fun updateExtraBodyJson(value: String) = providerController.updateExtraBodyJson(value)
    fun updateCustomConfigName(name: String) = providerController.updateCustomConfigName(name)
    fun createCustomConfig() = providerController.createCustomConfig()
    fun deleteCustomConfig(rawId: String) = providerController.deleteCustomConfig(rawId)
    fun testConnection() = providerController.testConnection()
    fun saveProviderConfig() = providerController.saveProviderConfig()

    // ── Image Generation Actions ──

    fun updateComfyBaseUrl(value: String) = imageGenController.updateComfyBaseUrl(value)
    fun updateComfyModel(value: String) = imageGenController.updateComfyModel(value)
    fun updateComfySettings(transform: (ComfyUiSettings) -> ComfyUiSettings) = imageGenController.updateComfySettings(transform)
    fun testComfyConnection() = imageGenController.testComfyConnection()
    fun saveComfyConfig() = imageGenController.saveComfyConfig()
    fun selectImageEngine(engine: String) = imageGenController.selectImageEngine(engine)
    fun updateNovelAiToken(value: String) = imageGenController.updateNovelAiToken(value)
    fun updateNovelAiSettings(transform: (NovelAiImageSettings) -> NovelAiImageSettings) = imageGenController.updateNovelAiSettings(transform)
    fun testNovelAiImage() = imageGenController.testNovelAiImage()
    fun saveNovelAiImageConfig() = imageGenController.saveNovelAiImageConfig()

    // ── Preset Actions ──

    fun loadPresets() = presetController.loadPresets()
    fun savePreset(preset: GenerationPreset) = presetController.savePreset(preset)
    fun selectPreset(preset: GenerationPreset) = presetController.selectPreset(preset)
    fun copyPreset(preset: GenerationPreset, requestedName: String) = presetController.copyPreset(preset, requestedName)
    fun renamePreset(preset: GenerationPreset, requestedName: String) = presetController.renamePreset(preset, requestedName)
    fun exportPreset(context: Context, uri: Uri, preset: GenerationPreset) = presetController.exportPreset(context, uri, preset)
    fun deletePreset(id: String, providerType: String? = null) = presetController.deletePreset(id, providerType)
    fun importPreset(context: Context, uri: Uri, providerCategory: String) = presetController.importPreset(context, uri, providerCategory)

    // ── Persona & Secret Actions ──

    fun addPersona(name: String, description: String) = personaSecretController.addPersona(name, description)
    fun updatePersona(id: String, name: String, description: String) = personaSecretController.updatePersona(id, name, description)
    fun deletePersona(id: String) = personaSecretController.deletePersona(id)
    fun addSecret(key: String, value: String) = personaSecretController.addSecret(key, value)
    fun deleteSecret(key: String) = personaSecretController.deleteSecret(key)

    // ── Appearance Actions ──

    fun setThemeMode(mode: ThemeMode) = appearanceController.setThemeMode(mode)
    fun setThemeAccent(accent: ThemeAccent) = appearanceController.setThemeAccent(accent)
    fun setChatBubbleAlpha(alpha: Float) = appearanceController.setChatBubbleAlpha(alpha)

    // ── Backup Actions ──

    fun exportBackup(context: Context, targetUri: Uri) = backupController.exportBackup(context, targetUri)
    fun importBackup(context: Context, uri: Uri) = backupController.importBackup(context, uri)

    // ── Notice Actions ──

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(info = null) }
    }
}

class SettingsViewModelFactory(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val appPreferences: AppPreferences,
    private val themeModeFlow: MutableStateFlow<ThemeMode>,
    private val themeAccentFlow: MutableStateFlow<ThemeAccent>,
    private val chatBubbleAlphaFlow: MutableStateFlow<Float>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                secretStore = secretStore,
                appPreferences = appPreferences,
                themeModeFlow = themeModeFlow,
                themeAccentFlow = themeAccentFlow,
                chatBubbleAlphaFlow = chatBubbleAlphaFlow,
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
