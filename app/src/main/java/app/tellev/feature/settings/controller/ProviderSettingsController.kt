package app.tellev.feature.settings.controller

import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.OpenAiCompatibilitySettings
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

internal data class ConfigFields(
    val customConfigName: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val compatibility: OpenAiCompatibilitySettings,
)

internal class ProviderSettingsController(
    private val secretStore: SecretStore,
    private val providerRegistry: ProviderRegistry,
    private val json: Json,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    suspend fun loadConfigFields(
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

    fun activateProvider(id: String) = loadProvider(id, activate = true)

    private fun loadProvider(id: String, activate: Boolean) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, selectedProviderId = id, providerStatus = null) }
            try {
                val customConfigs = ProviderConfigPersistence.listCustomConfigs(secretStore)
                val fields = loadConfigFields(id, customConfigs)
                if (activate) {
                    secretStore.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, id)
                }
                stateFlow.update {
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
                stateFlow.update {
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
        stateFlow.update { it.copy(baseUrl = url) }
    }

    fun updateApiKey(key: String) {
        stateFlow.update { it.copy(apiKey = key) }
    }

    fun updateModel(model: String) {
        stateFlow.update { it.copy(model = model) }
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
    fun updateExtraHeadersJson(value: String) = stateFlow.update { it.copy(extraHeadersJson = value) }
    fun updateExtraBodyJson(value: String) = stateFlow.update { it.copy(extraBodyJson = value) }

    fun updateCompatibility(transform: OpenAiCompatibilitySettings.() -> OpenAiCompatibilitySettings) {
        stateFlow.update { it.copy(compatibility = it.compatibility.transform()) }
    }

    fun updateCustomConfigName(name: String) {
        stateFlow.update { it.copy(customConfigName = name) }
    }

    fun createCustomConfig() {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
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
                stateFlow.update {
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
                stateFlow.update { it.copy(isLoading = false, error = "创建自定义配置失败：${e.message}") }
            }
        }
    }

    fun deleteCustomConfig(rawId: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val wasSelected = stateFlow.value.selectedProviderId ==
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
                    stateFlow.update {
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
                    stateFlow.update {
                        it.copy(
                            customConfigs = updated,
                            isLoading = false,
                            info = "自定义配置已删除。",
                        )
                    }
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(isLoading = false, error = "删除自定义配置失败：${e.message}") }
            }
        }
    }

    fun testConnection() {
        val state = stateFlow.value
        val config = runCatching { providerConfigFromState(state) }.getOrElse { error ->
            stateFlow.update { it.copy(error = error.message ?: "高级配置格式错误") }
            return
        }

        scope.launch {
            stateFlow.update { it.copy(isTesting = true, providerStatus = null, error = null) }
            try {
                val adapter = providerRegistry.require(
                    ProviderConfigPersistence.adapterIdFor(state.selectedProviderId)
                )
                val status = withContext(Dispatchers.IO) {
                    adapter.checkStatus(config)
                }
                stateFlow.update {
                    it.copy(
                        isTesting = false,
                        providerStatus = status,
                    )
                }

                if (status.available) {
                    try {
                        val models = withContext(Dispatchers.IO) {
                            adapter.listModels(config)
                        }
                        stateFlow.update {
                            it.copy(availableModels = models.map { m -> m.id })
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isTesting = false,
                        error = "连接测试失败：${e.message}",
                    )
                }
            }
        }
    }

    fun saveProviderConfig() {
        val state = stateFlow.value
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
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
                    stateFlow.update {
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
                    stateFlow.update {
                        it.copy(
                            isLoading = false,
                            info = "模型服务配置已保存。",
                        )
                    }
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "保存服务商配置失败：${e.message}",
                    )
                }
            }
        }
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
}
