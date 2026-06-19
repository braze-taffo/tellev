package app.tellev.core.security

object PrivacyGuard {
    /**
     * Patterns that match common API key formats.
     */
    private val apiKeyPatterns = listOf(
        Regex("sk-[a-zA-Z0-9]{20,}"),
        Regex("key-[a-zA-Z0-9]{20,}"),
        Regex("xai-[a-zA-Z0-9]{20,}"),
        Regex("Bearer\\s+[a-zA-Z0-9\\-._~+/]+=*"),
        Regex("ghp_[a-zA-Z0-9]{36,}"),
        Regex("AKIA[0-9A-Z]{16}"),
    )

    /**
     * Check if a log message contains any sensitive patterns.
     * Returns a sanitized version of the message.
     */
    fun sanitizeLogMessage(message: String): String {
        var sanitized = message
        for (pattern in apiKeyPatterns) {
            sanitized = pattern.replace(sanitized, "[REDACTED]")
        }
        return sanitized
    }

    /**
     * Check if text contains what appears to be an API key.
     */
    fun containsApiKey(text: String): Boolean {
        return apiKeyPatterns.any { it.containsMatchIn(text) }
    }

    /**
     * Get a user-facing warning message for backup export.
     */
    fun getBackupWarningMessage(): String {
        return "Exporting a backup will include your chat history, characters, world books, and settings. API keys stored in the secret store will NOT be included. Please keep your backup file secure."
    }

    /**
     * Get a user-facing warning message for backup import.
     */
    fun getBackupImportWarningMessage(): String {
        return "Importing a backup will replace all current data including chats, characters, and settings. This action cannot be undone. Please export a backup first if you want to preserve your current data."
    }
}
