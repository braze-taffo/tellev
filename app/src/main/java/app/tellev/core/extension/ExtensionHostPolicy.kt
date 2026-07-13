package app.tellev.core.extension

import java.net.URI

/** Permission required by the native virtual API route, if any. */
internal fun requiredExtensionPermissionForPath(rawPath: String): ExtensionPermission? {
    val path = runCatching { URI(rawPath).path }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: rawPath.substringBefore('?').substringBefore('#')
    val normalized = if (path.startsWith('/')) path.lowercase() else "/${path.lowercase()}"
    return when {
        normalized == "/api/secrets" || normalized.startsWith("/api/secrets/") ->
            ExtensionPermission.Secrets

        normalized == "/api/providers" || normalized.startsWith("/api/providers/") ||
            normalized == "/api/backends" || normalized.startsWith("/api/backends/") ->
            ExtensionPermission.ProviderRequest

        STORAGE_API_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        } -> ExtensionPermission.Storage

        else -> null
    }
}

/** Allow a module WebView to remain on its own isolated HTTPS origin only. */
internal fun isAllowedExtensionNavigation(extensionId: String, rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl).normalize() }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.host.equals("extensions.tellev.local", ignoreCase = true)) return false
    if (uri.userInfo != null || uri.port != -1) return false
    val root = "/$extensionId/"
    return uri.path == root || uri.path == root.removeSuffix("/")
}

private val STORAGE_API_PREFIXES = listOf(
    "/api/characters",
    "/api/chats",
    "/api/worlds",
    "/api/worldinfo",
    "/api/settings",
    "/api/groups",
    "/api/personas",
    "/api/presets",
    "/api/avatars",
)
