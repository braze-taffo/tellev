package app.tellev.feature.settings.controller

import app.tellev.core.provider.ComfyUiSettings
import app.tellev.core.provider.ComfyWorkflowTemplate
import app.tellev.core.provider.NovelAiImageSettings
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.feature.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ImageGenSettingsController(
    private val secretStore: SecretStore,
    private val providerRegistry: ProviderRegistry,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun updateComfyBaseUrl(value: String) {
        stateFlow.update { it.copy(comfyBaseUrl = value) }
    }

    fun updateComfyModel(value: String) {
        stateFlow.update { it.copy(comfyModel = value) }
    }

    fun updateComfySettings(transform: (ComfyUiSettings) -> ComfyUiSettings) {
        stateFlow.update { state -> state.copy(comfySettings = transform(state.comfySettings)) }
    }

    private fun comfyConfigFromState(state: SettingsUiState): ProviderConfig = ProviderConfig(
        providerType = ProviderCatalog.COMFYUI,
        baseUrl = state.comfyBaseUrl.trim().ifBlank { ProviderDefaults.baseUrl(ProviderCatalog.COMFYUI) },
        model = state.comfyModel.trim().takeIf { it.isNotBlank() },
    )

    fun testComfyConnection() {
        val config = comfyConfigFromState(stateFlow.value)
        scope.launch {
            stateFlow.update { it.copy(isTestingComfy = true, comfyStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(ProviderCatalog.COMFYUI)
                val status = withContext(Dispatchers.IO) { adapter.checkStatus(config) }
                val models = if (status.available) {
                    runCatching { withContext(Dispatchers.IO) { adapter.listModels(config) } }
                        .getOrDefault(emptyList())
                        .map { it.id }
                } else {
                    emptyList()
                }
                stateFlow.update {
                    it.copy(isTestingComfy = false, comfyStatus = status, comfyModels = models)
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isTestingComfy = false, error = "ComfyUI 连接测试失败：${e.message}")
                }
            }
        }
    }

    fun saveComfyConfig() {
        val state = stateFlow.value
        val workflow = state.comfySettings.workflowJson.trim()
        if (workflow.isNotBlank() && ComfyWorkflowTemplate.parse(workflow) == null) {
            stateFlow.update {
                it.copy(error = "工作流 JSON 无法解析，未保存。请粘贴 ComfyUI「保存（API 格式）」导出的 JSON。")
            }
            return
        }
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                if (state.comfyBaseUrl.isNotBlank()) {
                    secretStore.putSecret("provider-${ProviderCatalog.COMFYUI}-baseurl", state.comfyBaseUrl.trim())
                } else {
                    secretStore.deleteSecret("provider-${ProviderCatalog.COMFYUI}-baseurl")
                }
                if (state.comfyModel.isNotBlank()) {
                    secretStore.putSecret("provider-${ProviderCatalog.COMFYUI}-model", state.comfyModel.trim())
                } else {
                    secretStore.deleteSecret("provider-${ProviderCatalog.COMFYUI}-model")
                }
                val saved = state.comfySettings.copy(workflowJson = workflow)
                ProviderConfigPersistence.saveComfySettings(secretStore, saved)
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        comfySettings = saved,
                        info = "生图模型配置已保存。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "保存生图模型配置失败：${e.message}")
                }
            }
        }
    }

    fun selectImageEngine(engine: String) {
        stateFlow.update { it.copy(imageEngine = engine) }
        scope.launch {
            runCatching { ProviderConfigPersistence.saveImageEngine(secretStore, engine) }
        }
    }

    fun updateNovelAiToken(value: String) {
        stateFlow.update { it.copy(novelAiToken = value) }
    }

    fun updateNovelAiSettings(transform: (NovelAiImageSettings) -> NovelAiImageSettings) {
        stateFlow.update { state -> state.copy(novelAiSettings = transform(state.novelAiSettings)) }
    }

    fun testNovelAiImage() {
        val state = stateFlow.value
        val config = ProviderConfig(
            providerType = ProviderCatalog.NOVELAI_IMAGE,
            baseUrl = ProviderDefaults.baseUrl(ProviderCatalog.NOVELAI_IMAGE),
            apiKey = state.novelAiToken.trim().takeIf { it.isNotBlank() },
        )
        scope.launch {
            stateFlow.update { it.copy(isTestingNovelAi = true, novelAiStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(ProviderCatalog.NOVELAI_IMAGE)
                val status = withContext(Dispatchers.IO) { adapter.checkStatus(config) }
                stateFlow.update { it.copy(isTestingNovelAi = false, novelAiStatus = status) }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isTestingNovelAi = false, error = "NovelAI 测试失败：${e.message}")
                }
            }
        }
    }

    fun saveNovelAiImageConfig() {
        val state = stateFlow.value
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val token = state.novelAiToken.trim()
                if (token.isNotBlank()) {
                    secretStore.putSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey", token)
                } else {
                    secretStore.deleteSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey")
                }
                ProviderConfigPersistence.saveNovelAiImageSettings(secretStore, state.novelAiSettings)
                stateFlow.update {
                    it.copy(isLoading = false, info = "NovelAI 生图配置已保存。")
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "保存 NovelAI 生图配置失败：${e.message}")
                }
            }
        }
    }
}
