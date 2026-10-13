package app.tellev.core.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface SecretStore {
    val changes: Flow<String>
        get() = emptyFlow()

    suspend fun putSecret(id: String, value: String)

    /**
     * The value for [id], or null when the entry is absent **or unreadable**
     * (corrupt payload, lost or reset master key). Implementations must never
     * throw for either case: callers read before writing, so one corrupt entry
     * must not make its own overwrite impossible.
     */
    suspend fun readSecret(id: String): String?
    suspend fun deleteSecret(id: String)
    suspend fun listSecretIds(): List<String>
}
