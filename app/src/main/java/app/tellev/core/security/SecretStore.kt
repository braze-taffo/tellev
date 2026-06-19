package app.tellev.core.security

interface SecretStore {
    suspend fun putSecret(id: String, value: String)
    suspend fun readSecret(id: String): String?
    suspend fun deleteSecret(id: String)
    suspend fun listSecretIds(): List<String>
}

