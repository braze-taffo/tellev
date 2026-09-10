package app.tellev.feature.settings.controller

import app.tellev.core.model.Persona
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import app.tellev.feature.settings.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal class PersonaSecretSettingsController(
    private val dataStore: StDataStore,
    private val secretStore: SecretStore,
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<SettingsUiState>,
) {
    fun addPersona(name: String, description: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val persona = Persona(
                    id = "persona_${UUID.randomUUID()}",
                    name = name.trim().ifBlank { "未命名人设" },
                    description = description,
                )
                dataStore.savePersona(persona)
                val personas = dataStore.listPersonas()
                stateFlow.update {
                    it.copy(
                        personas = personas,
                        isLoading = false,
                        info = "人设“${persona.name}”已创建。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "创建人设失败：${e.message}")
                }
            }
        }
    }

    fun updatePersona(id: String, name: String, description: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
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
                stateFlow.update {
                    it.copy(personas = personas, isLoading = false, info = "人设已更新。")
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "更新人设失败：${e.message}")
                }
            }
        }
    }

    fun deletePersona(id: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                dataStore.deletePersona(id)
                val personas = dataStore.listPersonas()
                stateFlow.update {
                    it.copy(
                        personas = personas,
                        isLoading = false,
                        info = "人设已删除。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = "删除人设失败：${e.message}")
                }
            }
        }
    }

    fun addSecret(key: String, value: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                secretStore.putSecret(key, value)
                val secretIds = secretStore.listSecretIds()
                stateFlow.update {
                    it.copy(
                        secretIds = secretIds,
                        isLoading = false,
                        info = "密钥“$key”已保存。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "保存密钥失败：${e.message}",
                    )
                }
            }
        }
    }

    fun deleteSecret(key: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                secretStore.deleteSecret(key)
                val secretIds = secretStore.listSecretIds()
                stateFlow.update {
                    it.copy(
                        secretIds = secretIds,
                        isLoading = false,
                        info = "密钥“$key”已删除。",
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = "删除密钥失败：${e.message}",
                    )
                }
            }
        }
    }
}
