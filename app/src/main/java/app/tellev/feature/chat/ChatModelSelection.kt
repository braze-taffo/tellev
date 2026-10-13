package app.tellev.feature.chat

import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.security.SecretStore

internal suspend fun persistSelectedProviderModel(
    secrets: SecretStore,
    providerId: String,
    model: String,
): ProviderConfig {
    val trimmed = model.trim()
    require(trimmed.isNotEmpty()) { "Model must not be blank" }
    val activeId = secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
        ?: ProviderCatalog.OPENAI_COMPATIBLE
    check(providerId == activeId) { "The active provider changed; reopen the model menu" }
    if (ProviderConfigPersistence.isCustomConfigId(providerId)) {
        val rawId = ProviderConfigPersistence.customIdFrom(providerId)
        val configs = ProviderConfigPersistence.listCustomConfigs(secrets)
        check(configs.any { it.id == rawId }) { "Provider configuration not found: $providerId" }
        ProviderConfigPersistence.saveCustomConfigs(
            secrets,
            configs.map { if (it.id == rawId) it.copy(model = trimmed) else it },
        )
    } else {
        secrets.putSecret("provider-$providerId-model", trimmed)
    }
    val saved = ProviderConfigPersistence.loadProviderConfig(secrets, providerId)
    check(saved.model == trimmed) { "The selected model could not be read back" }
    val currentId = secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
        ?: ProviderCatalog.OPENAI_COMPATIBLE
    check(currentId == providerId) { "The active provider changed; reopen the model menu" }
    return saved
}

/**
 * 模型目录按服务商过滤：只保留当前连接那一组。
 *
 * 目录本来就把所有已配置端点各列一组，菜单直接全量渲染时，用户点到别的服务商
 * 分组下的模型会被写进当前连接，下次发送才报错。这里在渲染前收窄，让菜单里
 * 出现的每个模型都属于当前连接。
 */
internal fun ModelCatalogState.forProvider(providerId: String): ModelCatalogState {
    val selectedGroups = groups.filter { it.id == providerId }
    return copy(
        groups = selectedGroups,
        allFailed = selectedGroups.isNotEmpty() && selectedGroups.all { it.isFailed },
    )
}
