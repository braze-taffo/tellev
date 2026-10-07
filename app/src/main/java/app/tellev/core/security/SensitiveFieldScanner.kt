package app.tellev.core.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
     * Fields that carry content hashes or fingerprints, not secrets. The
     * shape rule above (40+ alphanumerics) matches every SHA-256 hex digest,
     * and backup export sanitizes every .json it ships: without this exclusion
     * a backup round-trip rewrote memory `processed` hashes and script-consent
     * fingerprints to "[REDACTED]", so the first prompt after a restore wiped
     * long-term memory and re-asked every script consent.
     */
    private val hashLikeKeys = setOf(
        "processed",
        "fingerprint",
        "sha256",
        "sha_256",
        "sha-256",
        "sha1",
        "sha-1",
        "checksum",
        "digest",
        "hash",
        "tellev_card_fingerprint",
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
            // An explicit secret key wins even when it also looks like a hash field.
            return true
        }
        if (normalizedKey in hashLikeKeys) {
            return false
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
            } else if (value is JsonArray) {
                value.forEachIndexed { index, element ->
                    findSensitiveElements(element, "$path[$index]", result)
                }
            }
        }
    }

    private fun findSensitiveElements(element: JsonElement, path: String, result: MutableList<String>) {
        when (element) {
            is JsonObject -> findSensitiveFieldsRecursive(element, path, result)
            is JsonArray -> element.forEachIndexed { index, child ->
                findSensitiveElements(child, "$path[$index]", result)
            }
            else -> Unit
        }
    }

    /**
     * A value under a hash-like parent (e.g. `processed: {messageId: sha256}`)
     * is exempt even though its own key is an arbitrary id, unless that key
     * itself names a secret.
     */
    private fun shouldRedact(parentKey: String, childKey: String, value: String): Boolean {
        if (!looksLikeSecret(childKey, value)) return false
        val parentIsHash = parentKey.lowercase() in hashLikeKeys
        return !(parentIsHash && childKey.lowercase() !in sensitiveKeys)
    }

    private fun sanitizeRecursive(obj: JsonObject): JsonObject =
        sanitizeElement(obj, "") as JsonObject

    private fun sanitizeElement(element: JsonElement, key: String): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((childKey, child) in element) {
                put(childKey, if (child is JsonPrimitive && child.isString && shouldRedact(key, childKey, child.content)) {
                    JsonPrimitive("[REDACTED]")
                } else {
                    sanitizeElement(child, childKey)
                })
            }
        }
        is JsonArray -> buildJsonArray {
            element.forEach { add(sanitizeElement(it, key)) }
        }
        else -> element
    }
}
