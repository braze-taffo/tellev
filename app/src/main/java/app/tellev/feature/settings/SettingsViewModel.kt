package app.tellev.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.ldream.LocalDreamCore
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.ComfyUiSettings
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.LocalDreamSettings
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // ── 本地生图（Local Dream MNN OpenCL），与 ComfyUI 并存 ──
    val imageEngine: String = ProviderCatalog.COMFYUI,
    val localDreamSettings: LocalDreamSettings = LocalDreamSettings(),
    val localDreamStatus: ProviderStatus? = null,
    val isTestingLocalDream: Boolean = false,
    val localDreamModels: List<String> = emptyList(),
    val isImportingLocalModel: Boolean = false,
    val localConvertLine: String? = null,
    val isDeletingLocalModel: Boolean = false,
    // ── NovelAI 生图（远程）──
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
    private val localDreamModelsRoot: java.io.File,
    private val contentResolver: android.content.ContentResolver,
    private val assets: android.content.res.AssetManager?,
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

                val localSettings = ProviderConfigPersistence.loadLocalDreamSettings(secretStore)
                val imageEngine = ProviderConfigPersistence.loadImageEngine(secretStore)
                val localDreamModels = listLocalDreamModelDirs()
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
                        localDreamSettings = localSettings,
                        localDreamModels = localDreamModels,
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

    // ── Local Dream Actions ──

    fun updateLocalDreamSettings(transform: (LocalDreamSettings) -> LocalDreamSettings) {
        _uiState.update { state -> state.copy(localDreamSettings = transform(state.localDreamSettings)) }
    }

    private fun listLocalDreamModelDirs(): List<String> =
        localDreamModelsRoot.listFiles { file -> file.isDirectory }
            ?.filter { java.io.File(it, "finished").isFile }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImportingLocalModel = true, error = null, info = null, localConvertLine = "读取模型文件…")
            }
            var freshDir: java.io.File? = null
            try {
                val modelDir = withContext(Dispatchers.IO) {
                    localDreamModelsRoot.mkdirs()
                    val displayName = runCatching {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                        }
                    }.getOrNull()
                    val rawName = displayName?.takeIf { it.isNotBlank() }
                        ?: "model-${System.currentTimeMillis()}.safetensors"
                    val baseName = rawName.substringBeforeLast('.').ifBlank { "model" }
                        .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    val dir = java.io.File(localDreamModelsRoot, baseName)
                    freshDir = if (dir.isDirectory) null else dir
                    dir.mkdirs()
                    val target = java.io.File(dir, "model.safetensors")
                    contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                    dir
                }
                _uiState.update { it.copy(localConvertLine = "铺开转换骨架…") }
                val clipSkip = _uiState.value.localDreamSettings.clipSkip
                val staged = withContext(Dispatchers.IO) {
                    LocalDreamCore.stageConversionAssets(modelDir, clipSkip) { path ->
                        assets?.open(path)
                    }
                }
                if (!staged) throw IllegalStateException("转换骨架资产缺失（assets/ldcvt）")
                val converted = LocalDreamCore.convert(modelDir, clipSkip >= 2) { line ->
                    _uiState.update { it.copy(localConvertLine = line) }
                }
                if (!converted) throw IllegalStateException("模型转换失败：请确认这是 SD1.5 的 safetensors 单文件模型")
                _uiState.update {
                    it.copy(
                        isImportingLocalModel = false,
                        localConvertLine = null,
                        localDreamModels = (it.localDreamModels + modelDir.name).distinct().sorted(),
                        localDreamSettings = it.localDreamSettings.copy(modelDirName = modelDir.name),
                        info = "模型已导入并转换完成：${modelDir.name}（记得点击保存）",
                    )
                }
            } catch (e: Exception) {
                freshDir?.deleteRecursively()
                _uiState.update {
                    it.copy(isImportingLocalModel = false, localConvertLine = null, error = "导入模型失败：${e.message}")
                }
            }
        }
    }

    fun testLocalDream() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingLocalDream = true, localDreamStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(ProviderCatalog.LOCAL_DREAM)
                val config = ProviderConfig(
                    providerType = ProviderCatalog.LOCAL_DREAM,
                    baseUrl = ProviderDefaults.baseUrl(ProviderCatalog.LOCAL_DREAM),
                )
                val status = withContext(Dispatchers.IO) { adapter.checkStatus(config) }
                val models = withContext(Dispatchers.IO) { listLocalDreamModelDirs() }
                _uiState.update {
                    it.copy(isTestingLocalDream = false, localDreamStatus = status, localDreamModels = models)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTestingLocalDream = false, error = "本地引擎测试失败：${e.message}")
                }
            }
        }
    }

    fun saveLocalDreamConfig() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                ProviderConfigPersistence.saveLocalDreamSettings(secretStore, state.localDreamSettings)
                _uiState.update {
                    it.copy(isLoading = false, info = "本地生图配置已保存。")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "保存本地生图配置失败：${e.message}") }
            }
        }
    }

    fun stopLocalDreamEngine() {
        LocalDreamCore.stop()
        testLocalDream()
    }

    fun deleteLocalModel(dirName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingLocalModel = true, error = null, info = null) }
            try {
                val freedGb = withContext(Dispatchers.IO) {
                    val dir = java.io.File(localDreamModelsRoot, dirName)
                    if (!dir.isDirectory) return@withContext 0.0
                    val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    if (LocalDreamCore.isServing(dirName)) LocalDreamCore.stop()
                    val deleted = dir.deleteRecursively()
                    if (!deleted && dir.isDirectory) {
                        throw IllegalStateException("部分文件删除失败，请重试")
                    }
                    bytes / (1024.0 * 1024 * 1024)
                }
                val models = listLocalDreamModelDirs()
                val clearedSelection = _uiState.value.localDreamSettings.modelDirName == dirName
                if (clearedSelection) {
                    ProviderConfigPersistence.saveLocalDreamSettings(
                        secretStore,
                        _uiState.value.localDreamSettings.copy(modelDirName = ""),
                    )
                }
                _uiState.update {
                    it.copy(
                        isDeletingLocalModel = false,
                        localDreamModels = models,
                        localDreamSettings = if (clearedSelection) {
                            it.localDreamSettings.copy(modelDirName = "")
                        } else {
                            it.localDreamSettings
                        },
                        info = "已删除模型 $dirName（释放约 ${"%.1f".format(freedGb)} GB）" +
                            if (clearedSelection) "；当前选择已清空" else "",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isDeletingLocalModel = false, error = "删除模型失败：${e.message}")
                }
            }
        }
    }

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
    private val localDreamModelsRoot: java.io.File,
    private val contentResolver: android.content.ContentResolver,
    private val assets: android.content.res.AssetManager?,
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
                localDreamModelsRoot = localDreamModelsRoot,
                contentResolver = contentResolver,
                assets = assets,
            ) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
