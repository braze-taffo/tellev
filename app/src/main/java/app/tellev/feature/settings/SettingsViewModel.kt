package app.tellev.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.AppPreferences
import app.tellev.core.storage.StDataStore
import app.tellev.ui.theme.ThemeMode
import app.tellev.ui.theme.parseThemeMode
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

data class SettingsUiState(
    val providers: List<ProviderAdapter> = emptyList(),
    val selectedProviderId: String = "openai-compatible",
    // User-defined named OpenAI-compatible endpoints. The selectedProviderId may
    // be `custom:{id}` to address one of these.
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val availableModels: List<String> = emptyList(),
)

class SettingsViewModel(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val appPreferences: AppPreferences,
    private val themeModeFlow: MutableStateFlow<ThemeMode>,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
                // Migrate the legacy single openai-compatible slot into named
                // custom configs (idempotent), then load the current list.
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

                val fields = loadConfigFields(selectedId, customConfigs)

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
                        baseUrl = fields.baseUrl,
                        apiKey = fields.apiKey,
                        model = fields.model,
                        compatibility = fields.compatibility,
                        extraHeadersJson = json.encodeToString(fields.compatibility.headers),
                        extraBodyJson = json.encodeToString(JsonObject.serializer(), fields.compatibility.extraBody),
                        isLoading = false,
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

    private data class ConfigFields(
        val customConfigName: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val compatibility: OpenAiCompatibilitySettings,
    )

    /** Loads the form fields for a selected id (custom config or built-in slot). */
    private suspend fun loadConfigFields(
        selectedId: String,
        customConfigs: List<CustomProviderConfig>,
    ): ConfigFields {
        if (ProviderConfigPersistence.isCustomConfigId(selectedId)) {
            val config = customConfigs.firstOrNull { it.id == ProviderConfigPersistence.customIdFrom(selectedId) }
            return ConfigFields(
                customConfigName = config?.name ?: "",
                baseUrl = config?.baseUrl ?: "",
                apiKey = config?.apiKey ?: "",
                model = config?.model ?: "",
                compatibility = config?.advanced ?: OpenAiCompatibilitySettings(),
            )
        }
        return ConfigFields(
            customConfigName = "",
            baseUrl = secretStore.readSecret("provider-$selectedId-baseurl")
                ?: ProviderDefaults.baseUrl(selectedId),
            apiKey = secretStore.readSecret("provider-$selectedId-apikey") ?: "",
            model = secretStore.readSecret("provider-$selectedId-model")
                ?: ProviderDefaults.model(selectedId),
            compatibility = ProviderConfigPersistence.loadAdvanced(secretStore, selectedId),
        )
    }

    fun selectProvider(id: String) = loadProvider(id, activate = false)

    /**
     * Switches the active generation configuration without rewriting any of
     * its editable fields. This is used by the first-level quick switcher, so
     * the next chat generation sees the new selection immediately.
     */
    fun activateProvider(id: String) = loadProvider(id, activate = true)

    private fun loadProvider(id: String, activate: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedProviderId = id, providerStatus = null) }
            try {
                val customConfigs = ProviderConfigPersistence.listCustomConfigs(secretStore)
                val fields = loadConfigFields(id, customConfigs)
                if (activate) {
                    secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, id)
                }
                _uiState.update {
                    it.copy(
                        customConfigs = customConfigs,
                        customConfigName = fields.customConfigName,
                        baseUrl = fields.baseUrl,
                        apiKey = fields.apiKey,
                        model = fields.model,
                        compatibility = fields.compatibility,
                        extraHeadersJson = json.encodeToString(fields.compatibility.headers),
                        extraBodyJson = json.encodeToString(JsonObject.serializer(), fields.compatibility.extraBody),
                        isLoading = false,
                        availableModels = emptyList(),
                        info = if (activate) "已切换到“${providerDisplayName(id, customConfigs)}”。" else it.info,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "加载服务商配置失败：${e.message}",
                    )
                }
            }
        }
    }

    private fun providerDisplayName(id: String, customConfigs: List<CustomProviderConfig>): String =
        if (ProviderConfigPersistence.isCustomConfigId(id)) {
            customConfigs.firstOrNull { it.id == ProviderConfigPersistence.customIdFrom(id) }?.name
                ?: "自定义配置"
        } else {
            providerRegistry.find(id)?.displayName ?: id
        }

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun updateModel(model: String) {
        _uiState.update { it.copy(model = model) }
    }

    fun updateModelsPath(value: String) = updateCompatibility { copy(modelsPath = value) }
    fun updateChatCompletionsPath(value: String) = updateCompatibility { copy(chatCompletionsPath = value) }
    fun updateAuthHeader(value: String) = updateCompatibility { copy(authHeader = value) }
    fun updateAuthScheme(value: String) = updateCompatibility { copy(authScheme = value) }
    fun updateIncludeUsage(value: Boolean) = updateCompatibility { copy(includeUsage = value) }
    fun updateSupportsModelListing(value: Boolean) = updateCompatibility { copy(supportsModelListing = value) }
    fun updateSupportsTopK(value: Boolean) = updateCompatibility { copy(supportsTopK = value) }
    fun updateSupportsTools(value: Boolean) = updateCompatibility { copy(supportsTools = value) }
    fun updateSupportsReasoning(value: Boolean) = updateCompatibility { copy(supportsReasoning = value) }
    fun updateSupportsVision(value: Boolean) = updateCompatibility { copy(supportsVision = value) }
    fun updateMaxTokensField(value: String) = updateCompatibility { copy(maxTokensField = value) }
    fun updateExtraHeadersJson(value: String) = _uiState.update { it.copy(extraHeadersJson = value) }
    fun updateExtraBodyJson(value: String) = _uiState.update { it.copy(extraBodyJson = value) }

    private fun updateCompatibility(transform: OpenAiCompatibilitySettings.() -> OpenAiCompatibilitySettings) {
        _uiState.update { it.copy(compatibility = it.compatibility.transform()) }
    }

    fun testConnection() {
        val state = _uiState.value
        val config = runCatching { providerConfigFromState(state) }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message ?: "高级配置格式错误") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, providerStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(
                    ProviderConfigPersistence.adapterIdFor(state.selectedProviderId)
                )
                val status = withContext(Dispatchers.IO) {
                    adapter.checkStatus(config)
                }
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        providerStatus = status,
                    )
                }

                // Also try to fetch available models
                if (status.available) {
                    try {
                        val models = withContext(Dispatchers.IO) {
                            adapter.listModels(config)
                        }
                        _uiState.update {
                            it.copy(availableModels = models.map { m -> m.id })
                        }
                    } catch (_: Exception) {
                        // Model listing may not be supported
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTesting = false,
                        error = "连接测试失败：${e.message}",
                    )
                }
            }
        }
    }

    fun saveProviderConfig() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val providerId = state.selectedProviderId
                secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, providerId)

                if (ProviderConfigPersistence.isCustomConfigId(providerId)) {
                    val rawId = ProviderConfigPersistence.customIdFrom(providerId)
                    val advanced = compatibilitySettingsFromState(state)
                    val configs = ProviderConfigPersistence.listCustomConfigs(secretStore)
                    val existing = configs.firstOrNull { it.id == rawId }
                    val savedName = state.customConfigName.trim().ifBlank { existing?.name ?: "自定义配置" }
                    val updated = configs.map { c ->
                        if (c.id == rawId) c.copy(
                            name = savedName,
                            baseUrl = state.baseUrl.trim(),
                            apiKey = state.apiKey.trim(),
                            model = state.model.trim(),
                            advanced = advanced,
                        ) else c
                    }
                    ProviderConfigPersistence.saveCustomConfigs(secretStore, updated)
                    _uiState.update {
                        it.copy(
                            customConfigs = updated,
                            customConfigName = savedName,
                            isLoading = false,
                            info = "模型服务配置已保存。",
                        )
                    }
                } else {
                    if (state.baseUrl.isNotBlank()) {
                        secretStore.putSecret("provider-$providerId-baseurl", state.baseUrl)
                    }
                    if (state.apiKey.isNotBlank()) {
                        secretStore.putSecret("provider-$providerId-apikey", state.apiKey)
                    } else {
                        secretStore.deleteSecret("provider-$providerId-apikey")
                    }
                    if (state.model.isNotBlank()) {
                        secretStore.putSecret("provider-$providerId-model", state.model)
                    } else {
                        secretStore.deleteSecret("provider-$providerId-model")
                    }
                    if (ProviderConfigPersistence.hasAdvancedSettings(providerId)) {
                        ProviderConfigPersistence.saveAdvanced(
                            secretStore,
                            providerId,
                            compatibilitySettingsFromState(state),
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            info = "模型服务配置已保存。",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "保存服务商配置失败：${e.message}",
                    )
                }
            }
        }
    }

    fun updateCustomConfigName(name: String) {
        _uiState.update { it.copy(customConfigName = name) }
    }

    fun createCustomConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val configs = ProviderConfigPersistence.listCustomConfigs(secretStore)
                val name = "自定义配置 ${configs.size + 1}"
                val id = "cust_${UUID.randomUUID()}"
                val newConfig = CustomProviderConfig(
                    id = id,
                    name = name,
                    baseUrl = "",
                    apiKey = "",
                    model = "",
                    advanced = OpenAiCompatibilitySettings(),
                )
                val updated = configs + newConfig
                ProviderConfigPersistence.saveCustomConfigs(secretStore, updated)
                val selectedId = ProviderConfigPersistence.selectedIdFor(id)
                secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, selectedId)
                _uiState.update {
                    it.copy(
                        customConfigs = updated,
                        selectedProviderId = selectedId,
                        customConfigName = name,
                        baseUrl = "",
                        apiKey = "",
                        model = "",
                        compatibility = OpenAiCompatibilitySettings(),
                        extraHeadersJson = "{}",
                        extraBodyJson = "{}",
                        availableModels = emptyList(),
                        isLoading = false,
                        info = "已创建自定义配置“$name”。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "创建自定义配置失败：${e.message}") }
            }
        }
    }

    fun deleteCustomConfig(rawId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val wasSelected = _uiState.value.selectedProviderId ==
                    ProviderConfigPersistence.selectedIdFor(rawId)
                val configs = ProviderConfigPersistence.listCustomConfigs(secretStore)
                val updated = configs.filterNot { it.id == rawId }
                ProviderConfigPersistence.saveCustomConfigs(secretStore, updated)

                if (wasSelected) {
                    val fallback = updated.firstOrNull()
                        ?.let { ProviderConfigPersistence.selectedIdFor(it.id) }
                        ?: "openai-compatible"
                    secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, fallback)
                    val fields = loadConfigFields(fallback, updated)
                    _uiState.update {
                        it.copy(
                            customConfigs = updated,
                            selectedProviderId = fallback,
                            customConfigName = fields.customConfigName,
                            baseUrl = fields.baseUrl,
                            apiKey = fields.apiKey,
                            model = fields.model,
                            compatibility = fields.compatibility,
                            extraHeadersJson = json.encodeToString(fields.compatibility.headers),
                            extraBodyJson = json.encodeToString(JsonObject.serializer(), fields.compatibility.extraBody),
                            availableModels = emptyList(),
                            isLoading = false,
                            info = "自定义配置已删除。",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            customConfigs = updated,
                            isLoading = false,
                            info = "自定义配置已删除。",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "删除自定义配置失败：${e.message}") }
            }
        }
    }

    fun loadPresets() {
        viewModelScope.launch {
            try {
                val presets = dataStore.listPresets()
                _uiState.update { it.copy(presets = presets) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "加载预设失败：${e.message}")
                }
            }
        }
    }

    fun savePreset(preset: GenerationPreset) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.savePreset(preset)
                if (dataStore.readSelectedPresetName(preset.category) == preset.id) {
                    dataStore.selectPreset(preset.category, preset.id)
                }
                val presets = dataStore.listPresets()
                _uiState.update {
                    it.copy(
                        presets = presets,
                        isLoading = false,
                        info = "预设“${preset.name}”已保存。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "保存预设失败：${e.message}",
                    )
                }
            }
        }
    }

    fun selectPreset(preset: GenerationPreset) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.selectPreset(preset.category, preset.id)
                _uiState.update {
                    it.copy(
                        selectedPresetNames = it.selectedPresetNames + (preset.category to preset.id),
                        isLoading = false,
                        info = "已切换到预设“${preset.name}”。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "切换预设失败：${e.message}")
                }
            }
        }
    }

    fun copyPreset(preset: GenerationPreset, requestedName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val id = presetId(requestedName)
                require(_uiState.value.presets.none { it.category == preset.category && it.id == id }) {
                    "同分类已存在预设“$id”"
                }
                dataStore.savePreset(preset.copy(id = id, name = requestedName.trim()))
                val presets = dataStore.listPresets()
                _uiState.update {
                    it.copy(
                        presets = presets,
                        isLoading = false,
                        info = "预设已另存为“${requestedName.trim()}”。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "另存为失败：${e.message}") }
            }
        }
    }

    fun renamePreset(preset: GenerationPreset, requestedName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val id = presetId(requestedName)
                require(id != preset.id) { "新名称与原名称相同" }
                require(_uiState.value.presets.none { it.category == preset.category && it.id == id }) {
                    "同分类已存在预设“$id”"
                }
                val wasSelected = dataStore.readSelectedPresetName(preset.category) == preset.id
                dataStore.savePreset(preset.copy(id = id, name = requestedName.trim()))
                dataStore.deletePreset(preset.id, preset.category.name)
                if (wasSelected) dataStore.selectPreset(preset.category, id)
                val selected = dataStore.readSelectedPresetName(preset.category)
                val presets = dataStore.listPresets()
                _uiState.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = if (selected == null) it.selectedPresetNames - preset.category
                            else it.selectedPresetNames + (preset.category to selected),
                        isLoading = false,
                        info = "预设已重命名为“${requestedName.trim()}”。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "重命名失败：${e.message}") }
            }
        }
    }

    fun exportPreset(context: Context, uri: Uri, preset: GenerationPreset) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val raw = dataStore.readPreset(preset.category, preset.id)?.raw ?: preset.raw
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.encodeToString(JsonObject.serializer(), raw).encodeToByteArray())
                    } ?: error("无法创建导出文件")
                }
                _uiState.update { it.copy(isLoading = false, info = "预设“${preset.name}”已导出。") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "导出预设失败：${e.message}") }
            }
        }
    }

    fun deletePreset(id: String, providerType: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val deletedPreset = _uiState.value.presets.firstOrNull {
                    it.id == id && (providerType == null || it.providerType == providerType)
                }
                dataStore.deletePreset(id, providerType)
                val presets = dataStore.listPresets()
                val selectedNames = deletedPreset?.category?.let { category ->
                    val selected = dataStore.readSelectedPresetName(category)
                    if (selected == null) _uiState.value.selectedPresetNames - category
                    else _uiState.value.selectedPresetNames + (category to selected)
                } ?: _uiState.value.selectedPresetNames
                _uiState.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = selectedNames,
                        isLoading = false,
                        info = "预设已删除。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "删除预设失败：${e.message}",
                    )
                }
            }
        }
    }

    fun importPreset(context: Context, uri: Uri, providerCategory: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }
                val fileName = UriUtils.resolveDisplayName(context, uri)
                    ?: uri.lastPathSegment
                    ?: "preset.json"
                val result = dataStore.importPreset(bytes, providerCategory, fileName)
                val imported = result.preset
                val presets = dataStore.listPresets()
                _uiState.update {
                    it.copy(
                        presets = presets,
                        selectedPresetNames = it.selectedPresetNames + (result.inferredCategory to imported.id),
                        isLoading = false,
                        info = buildString {
                            append("预设「${imported.name}」已导入并启用")
                            if (result.warnings.isNotEmpty()) append("；${result.warnings.joinToString("；")}")
                            append('。')
                        },
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "导入预设失败：${e.message}",
                    )
                }
            }
        }
    }

    fun addPersona(name: String, description: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val persona = Persona(
                    id = "persona_${UUID.randomUUID()}",
                    name = name.trim().ifBlank { "未命名人设" },
                    description = description,
                )
                dataStore.savePersona(persona)
                val personas = dataStore.listPersonas()
                _uiState.update {
                    it.copy(
                        personas = personas,
                        isLoading = false,
                        info = "人设“${persona.name}”已创建。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "创建人设失败：${e.message}")
                }
            }
        }
    }

    fun updatePersona(id: String, name: String, description: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val existing = dataStore.listPersonas().firstOrNull { it.id == id }
                    ?: error("人设不存在：$id")
                dataStore.savePersona(
                    existing.copy(
                        name = name.trim().ifBlank { existing.name },
                        description = description,
                    ),
                )
                val personas = dataStore.listPersonas()
                _uiState.update {
                    it.copy(personas = personas, isLoading = false, info = "人设已更新。")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "更新人设失败：${e.message}")
                }
            }
        }
    }

    fun deletePersona(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.deletePersona(id)
                val personas = dataStore.listPersonas()
                _uiState.update {
                    it.copy(
                        personas = personas,
                        isLoading = false,
                        info = "人设已删除。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "删除人设失败：${e.message}")
                }
            }
        }
    }

    fun addSecret(key: String, value: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                secretStore.putSecret(key, value)
                val secretIds = secretStore.listSecretIds()
                _uiState.update {
                    it.copy(
                        secretIds = secretIds,
                        isLoading = false,
                        info = "密钥“$key”已保存。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "保存密钥失败：${e.message}",
                    )
                }
            }
        }
    }

    fun deleteSecret(key: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                secretStore.deleteSecret(key)
                val secretIds = secretStore.listSecretIds()
                _uiState.update {
                    it.copy(
                        secretIds = secretIds,
                        isLoading = false,
                        info = "密钥“$key”已删除。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "删除密钥失败：${e.message}",
                    )
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        appPreferences.themeModeName = mode.name
        themeModeFlow.value = mode
        _uiState.update {
            it.copy(
                themeMode = mode,
                info = "主题已切换为${mode.displayName()}。",
            )
        }
    }

    fun exportBackup(context: Context, targetUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            var tempFile: java.io.File? = null
            try {
                tempFile = java.io.File.createTempFile("tellev-backup-", ".zip", context.cacheDir)
                dataStore.exportBackup(tempFile.toPath())

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("Unable to open backup target")
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "备份已导出。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "导出备份失败：${e.message}",
                    )
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val tempFile = java.io.File.createTempFile("tellev-import-", ".zip", context.cacheDir)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val sourcePath = tempFile.toPath()
                dataStore.importBackup(sourcePath)
                tempFile.delete()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "备份已导入。请重启应用以查看变化。",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "导入备份失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearInfo() {
        _uiState.update { it.copy(info = null) }
    }

    private fun providerConfigFromState(state: SettingsUiState): ProviderConfig {
        val advanced = compatibilitySettingsFromState(state)
        val useAdvanced = ProviderConfigPersistence.hasAdvancedSettings(state.selectedProviderId)
        return ProviderConfig(
            providerType = ProviderConfigPersistence.adapterIdFor(state.selectedProviderId),
            baseUrl = state.baseUrl,
            apiKey = state.apiKey.ifBlank { null },
            model = state.model.ifBlank { null },
            headers = if (useAdvanced) advanced.headers else emptyMap(),
            options = if (useAdvanced) advanced.toOptions() else JsonObject(emptyMap()),
        )
    }

    private fun compatibilitySettingsFromState(state: SettingsUiState): OpenAiCompatibilitySettings {
        if (!ProviderConfigPersistence.hasAdvancedSettings(state.selectedProviderId)) return state.compatibility
        val headerObject = parseJsonObject(state.extraHeadersJson, "附加 Headers")
        val headers = headerObject.mapValues { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("附加 Header“$name”必须是字符串")
        }
        val extraBody = parseJsonObject(state.extraBodyJson, "附加请求体")
        return state.compatibility.copy(
            modelsPath = state.compatibility.modelsPath.trim().ifBlank { "/v1/models" },
            chatCompletionsPath = state.compatibility.chatCompletionsPath.trim().ifBlank { "/v1/chat/completions" },
            authHeader = state.compatibility.authHeader.trim().ifBlank { "Authorization" },
            maxTokensField = state.compatibility.maxTokensField.trim().also {
                require(it in setOf("max_tokens", "max_completion_tokens")) {
                    "输出长度字段只能是 max_tokens 或 max_completion_tokens"
                }
            },
            headers = headers,
            extraBody = extraBody,
        )
    }

    private fun parseJsonObject(source: String, label: String): JsonObject = runCatching {
        json.parseToJsonElement(source.ifBlank { "{}" }).jsonObject
    }.getOrElse { throw IllegalArgumentException("$label 必须是合法的 JSON 对象") }

    private fun presetId(name: String): String = name.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .ifBlank { "preset" }
}

class SettingsViewModelFactory(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val appPreferences: AppPreferences,
    private val themeModeFlow: MutableStateFlow<ThemeMode>,
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
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}

private fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.Light -> "浅色"
    ThemeMode.Dark -> "深色"
    ThemeMode.System -> "跟随系统"
}
