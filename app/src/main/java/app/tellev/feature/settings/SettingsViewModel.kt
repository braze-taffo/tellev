package app.tellev.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.model.GenerationPreset
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ThemeMode {
    Light, Dark, System,
}

data class SettingsUiState(
    val providers: List<ProviderAdapter> = emptyList(),
    val selectedProviderId: String = "openai-compatible",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val providerStatus: ProviderStatus? = null,
    val isTesting: Boolean = false,
    val presets: List<GenerationPreset> = emptyList(),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val providers = providerRegistry.all()
                val presets = dataStore.listPresets()
                val secretIds = secretStore.listSecretIds()
                val selectedId = secretStore.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: _uiState.value.selectedProviderId

                val baseUrl = secretStore.readSecret("provider-$selectedId-baseurl")
                    ?: ProviderDefaults.baseUrl(selectedId)
                val apiKey = secretStore.readSecret("provider-$selectedId-apikey") ?: ""
                val model = secretStore.readSecret("provider-$selectedId-model")
                    ?: ProviderDefaults.model(selectedId)

                _uiState.update {
                    it.copy(
                        providers = providers,
                        selectedProviderId = selectedId,
                        presets = presets,
                        secretIds = secretIds,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
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

    fun selectProvider(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedProviderId = id, providerStatus = null) }
            try {
                val baseUrl = secretStore.readSecret("provider-$id-baseurl")
                    ?: ProviderDefaults.baseUrl(id)
                val apiKey = secretStore.readSecret("provider-$id-apikey") ?: ""
                val model = secretStore.readSecret("provider-$id-model")
                    ?: ProviderDefaults.model(id)

                _uiState.update {
                    it.copy(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        isLoading = false,
                        availableModels = emptyList(),
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

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun updateModel(model: String) {
        _uiState.update { it.copy(model = model) }
    }

    fun testConnection() {
        val state = _uiState.value
        val config = ProviderConfig(
            providerType = state.selectedProviderId,
            baseUrl = state.baseUrl,
            apiKey = state.apiKey.ifBlank { null },
            model = state.model.ifBlank { null },
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, providerStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(state.selectedProviderId)
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

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        info = "模型服务配置已保存。",
                    )
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

    fun deletePreset(id: String, providerType: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.deletePreset(id, providerType)
                val presets = dataStore.listPresets()
                _uiState.update {
                    it.copy(
                        presets = presets,
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
}

class SettingsViewModelFactory(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            return SettingsViewModel(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                secretStore = secretStore,
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
