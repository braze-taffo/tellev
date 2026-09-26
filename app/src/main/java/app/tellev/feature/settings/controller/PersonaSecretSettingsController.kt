package app.tellev.feature.settings.controller

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
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
                        info = UiStrings.get(S.persctl_created, persona.name),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = UiStrings.get(S.persctl_create_failed, e.message))
                }
            }
        }
    }

    fun updatePersona(id: String, name: String, description: String) {
        scope.launch {
            stateFlow.update { it.copy(isLoading = true, error = null) }
            try {
                val existing = dataStore.listPersonas().firstOrNull { it.id == id }
                    ?: error(UiStrings.get(S.persctl_not_found, id))
                dataStore.savePersona(
                    existing.copy(
                        name = name.trim().ifBlank { existing.name },
                        description = description,
                    ),
                )
                val personas = dataStore.listPersonas()
                stateFlow.update {
                    it.copy(personas = personas, isLoading = false, info = UiStrings.get(S.persctl_updated))
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = UiStrings.get(S.persctl_update_failed, e.message))
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
                        info = UiStrings.get(S.persctl_deleted),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(isLoading = false, error = UiStrings.get(S.persctl_delete_failed, e.message))
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
                        info = UiStrings.get(S.persctl_secret_saved, key),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = UiStrings.get(S.persctl_secret_save_failed, e.message),
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
                        info = UiStrings.get(S.persctl_secret_deleted, key),
                    )
                }
            } catch (e: Exception) {
                stateFlow.update {
                    it.copy(
                        isLoading = false,
                        error = UiStrings.get(S.persctl_secret_delete_failed, e.message),
                    )
                }
            }
        }
    }
}
