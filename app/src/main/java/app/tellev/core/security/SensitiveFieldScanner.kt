package app.tellev.core.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object SensitiveFieldScanner {
    /**
     * Known sensitive field name patterns (case-insensitive).
     */
    private val sensitiveKeys = setOf(
        "api_key", "apikey", "api-key", "api_secret", "apisecret",
        "access_token", "access-token", "bearer_token", "bearer-token",
        "secret_key", "secret-key", "secret_token",
        "password", "passwd", "auth_token", "auth-token",
        "private_key", "private-key", "credentials",
        "refresh_token", "client_secret",
    )

    /**
     * Patterns for values that look like API keys.
     * e.g., sk-..., key-..., strings that are very long and look like tokens
     */
    private val sensitiveValuePatterns = listOf(
        Regex("^sk-[a-zA-Z0-9]{20,}$"),
        Regex("^key-[a-zA-Z0-9]{20,}$"),
        Regex("^xai-[a-zA-Z0-9]{20,}$"),
        Regex("^[a-zA-Z0-9]{40,}$"),  // Very long alphanumeric strings
    )

    /**
     * Scans a JsonObject for sensitive fields and returns their paths.
     * Returns a list of dotted paths like "extensions.api_key" or "data.secret".
     */
    fun findSensitiveFields(obj: JsonObject): List<String> {
        val result = mutableListOf<String>()
        findSensitiveFieldsRecursive(obj, "", result)
        return result
    }

    /**
     * Returns a sanitized copy of the JsonObject with sensitive fields replaced
     * by a redacted placeholder "[REDACTED]".
     */
    fun sanitize(obj: JsonObject): JsonObject {
        return sanitizeRecursive(obj)
    }

    /**
     * Checks if a value looks like it could be an API key or secret.
     */
    fun looksLikeSecret(key: String, value: String): Boolean {
        val normalizedKey = key.lowercase()
        if (sensitiveKeys.contains(normalizedKey)) {
            return true
        }
        return sensitiveValuePatterns.any { it.matches(value) }
    }

    private fun findSensitiveFieldsRecursive(obj: JsonObject, prefix: String, result: MutableList<String>) {
        for ((key, value) in obj) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"

            if (value is JsonPrimitive && value.isString) {
                val stringValue = value.content
                if (looksLikeSecret(key, stringValue)) {
                    result.add(path)
                }
            } else if (value is JsonObject) {
                findSensitiveFieldsRecursive(value, path, result)
            }
        }
    }

    private fun sanitizeRecursive(obj: JsonObject): JsonObject {
        return buildJsonObject {
            for ((key, value) in obj) {
                if (value is JsonPrimitive && value.isString) {
                    val stringValue = value.content
                    if (looksLikeSecret(key, stringValue)) {
                        put(key, JsonPrimitive("[REDACTED]"))
                    } else {
                        put(key, value)
                    }
                } else if (value is JsonObject) {
                    put(key, sanitizeRecursive(value))
                } else {
                    put(key, value)
                }
            }
        }
    }
}
