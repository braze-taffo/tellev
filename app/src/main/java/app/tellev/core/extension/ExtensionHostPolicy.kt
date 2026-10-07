package app.tellev.core.extension

import java.net.URI
import java.security.MessageDigest

/**
 * Collapses the path forms that can smuggle a protected prefix past a literal
 * `startsWith` check: duplicate slashes (`/api//secrets`) and dot segments
 * (`/api/./secrets`, `/api/x/../secrets`). Applied by BOTH the permission
 * gate and the router so the two can never disagree — the gate used to
 * compare the raw path while the router split on `/` and dropped empty
 * segments, letting an undeclared module reach `/api//secrets/<id>` with no
 * permission at all. Percent escapes are deliberately NOT decoded: the router
 * matches raw segments, and decoding here (plus '+'→space form decoding)
 * would change which id strings handlers receive.
 */
internal fun normalizeApiPath(rawPath: String): String {
    val pathOnly = rawPath.substringBefore('?').substringBefore('#')
    val segments = pathOnly.split('/')
        .mapNotNull { segment -> segment.takeIf { it.isNotEmpty() && it != "." } }
        .toMutableList()
    var index = 0
    while (index < segments.size) {
        if (segments[index] == "..") {
            segments.removeAt(index)
            if (index > 0) {
                index--
                segments.removeAt(index)
            }
        } else {
            index++
        }
    }
    // Keep the original case: the router matches raw segment ids (chat ids,
    // character ids, secret ids). Case folding happens only where the OLD
    // gate did it — inside requiredExtensionPermissionForPath.
    return "/" + segments.joinToString("/")
}

/** Permission required by the native virtual API route, if any. */
internal fun requiredExtensionPermissionForPath(rawPath: String): ExtensionPermission? {
    val path = runCatching { URI(rawPath).path }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: rawPath.substringBefore('?').substringBefore('#')
    val normalized = normalizeApiPath(path).lowercase()
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

/** Web Storage is scoped to an origin, so a path cannot isolate two extensions. */
internal fun extensionBaseUrl(extensionId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(extensionId.toByteArray(Charsets.UTF_8))
        .take(20)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "https://e-$digest.extensions.tellev.local/"
}

/** Allow a module WebView to remain on its own isolated HTTPS origin only. */
internal fun isAllowedExtensionNavigation(extensionId: String, rawUrl: String): Boolean {
    val uri = runCatching { URI(rawUrl).normalize() }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.host.equals(URI(extensionBaseUrl(extensionId)).host, ignoreCase = true)) return false
    if (uri.userInfo != null || uri.port != -1) return false
    return uri.path == "/" || uri.path.isNullOrEmpty()
}

/** Child frames need srcdoc/blob URLs, but must not navigate to remote pages with the native bridge. */
internal fun isAllowedExtensionFrameNavigation(extensionId: String, rawUrl: String): Boolean {
    if (rawUrl == "about:blank" || rawUrl == "about:srcdoc") return true
    if (rawUrl.startsWith("blob:${extensionBaseUrl(extensionId)}")) return true
    return isAllowedExtensionNavigation(extensionId, rawUrl)
}

/**
 * Subresource policy for module WebViews (fetch/XHR/WebSocket handshake attempts).
 * Navigation is origin-locked by [isAllowedExtensionNavigation], but
 * subresource loads bypass [android.webkit.WebViewClient.shouldOverrideUrlLoading]
 * entirely — without this check a loaded script could POST chat data to any
 * host regardless of the permission model.
 *
 * Allowed without the Network permission: the module's own isolated
 * `e-<hash>.extensions.tellev.local` origin (relative URLs resolve against it
 * and arrive with that host), schemes WebView resolves internally or that
 * never reach the network (`data:`, `blob:`, `about:`), and the pinned compat
 * CDN aliases, which [CompatAssets.intercept] serves before this check runs.
 * Everything else — any cross-origin fetch — requires [ExtensionPermission.Network];
 * plain `http://` is denied even then (remote backends go through the native
 * provider route, and cleartext stays behind the app-level CleartextGuard).
 */
internal fun isAllowedExtensionSubresource(
    extensionId: String,
    rawUrl: String,
    networkGranted: Boolean,
): Boolean {
    val uri = runCatching { URI(rawUrl).normalize() }.getOrNull() ?: return false
    return when (val scheme = uri.scheme?.lowercase()) {
        // request.url is always absolute, but a scheme-less defensive default
        // resolves against the module's own origin anyway.
        null -> true
        "data", "blob", "about" -> true
        "http" -> false
        "https" -> {
            val host = uri.host?.lowercase() ?: return false
            host == URI(extensionBaseUrl(extensionId)).host || networkGranted
        }
        // content:/javascript:/file: never carry remote exfil; file/content are
        // additionally disabled in WebView settings.
        else -> true
    }
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
