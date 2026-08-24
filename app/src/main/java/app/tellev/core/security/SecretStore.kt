package app.tellev.core.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface SecretStore {
    val changes: Flow<String>
        get() = emptyFlow()

    suspend fun putSecret(id: String, value: String)
    suspend fun readSecret(id: String): String?
    suspend fun deleteSecret(id: String)
    suspend fun listSecretIds(): List<String>
}
