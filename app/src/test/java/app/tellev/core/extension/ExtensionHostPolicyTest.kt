package app.tellev.core.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHostPolicyTest {
    @Test
    fun `provider generation route requires provider permission`() {
        assertEquals(
            ExtensionPermission.ProviderRequest,
            requiredExtensionPermissionForPath("/api/backends/chat-completions/generate"),
        )
        assertEquals(
            ExtensionPermission.ProviderRequest,
            requiredExtensionPermissionForPath("https://extensions.tellev.local/api/providers/a/models"),
        )
    }

    @Test
    fun `storage and secret routes use the right permission`() {
        assertEquals(ExtensionPermission.Storage, requiredExtensionPermissionForPath("/api/worldinfo/get"))
        assertEquals(ExtensionPermission.Storage, requiredExtensionPermissionForPath("/api/avatars/upload"))
        assertEquals(ExtensionPermission.Secrets, requiredExtensionPermissionForPath("/api/secrets/read"))
        assertNull(requiredExtensionPermissionForPath("/api/extensions/version"))
    }

    @Test
    fun `path manipulation cannot smuggle protected prefixes past the gate`() {
        // The router splits on '/' and drops empty segments, so every one of
        // these forms used to reach the secrets/storage handlers while the
        // gate (raw prefix match) saw an unprotected path.
        listOf(
            "/api//secrets",
            "/api//secrets/k1",
            "/api///secrets/k1",
            "api//secrets",
            "/api/./secrets",
            "/api/./secrets/k1",
            "/api/x/../secrets/k1",
        ).forEach { path ->
            assertEquals("path=[$path]", ExtensionPermission.Secrets, requiredExtensionPermissionForPath(path))
        }
        listOf("/api//providers", "/api//backends/chat-completions/generate").forEach { path ->
            assertEquals("path=[$path]", ExtensionPermission.ProviderRequest, requiredExtensionPermissionForPath(path))
        }
        listOf("/api//chats", "/api//characters", "/api//worldinfo/get", "/api//presets").forEach { path ->
            assertEquals("path=[$path]", ExtensionPermission.Storage, requiredExtensionPermissionForPath(path))
        }
    }

    @Test
    fun `normalizeApiPath collapses segments the same way the router does`() {
        assertEquals("/api/secrets/k1", normalizeApiPath("/api//secrets/k1"))
        assertEquals("/api/secrets/k1", normalizeApiPath("/api/./secrets/k1"))
        assertEquals("/api/secrets/k1", normalizeApiPath("/api/a/../secrets/k1"))
        assertEquals("/api/secrets/k1", normalizeApiPath("api//secrets/k1?x=1#frag"))
        // Case and percent escapes are preserved: the router matches raw ids.
        assertEquals("/api/extension%2Fname", normalizeApiPath("/api/extension%2Fname"))
        assertEquals("/api/Chats/chat-Char", normalizeApiPath("/api/Chats/chat-Char"))
        // Leading ../ must not escape above the root.
        assertEquals("/secrets", normalizeApiPath("/../secrets"))
    }

    @Test
    fun `navigation stays on the owning extension origin`() {
        val origin = extensionBaseUrl("demo")
        assertTrue(origin.matches(Regex("https://e-[0-9a-f]{40}\\.extensions\\.tellev\\.local/")))
        assertEquals(origin, extensionBaseUrl("demo"))
        assertFalse(origin == extensionBaseUrl("other"))
        assertTrue(isAllowedExtensionNavigation("demo", origin))
        assertFalse(isAllowedExtensionNavigation("demo", "${origin}settings"))
        assertFalse(isAllowedExtensionNavigation("demo", extensionBaseUrl("other")))
        assertFalse(isAllowedExtensionNavigation("demo", "https://extensions.tellev.local/demo/"))
        assertFalse(isAllowedExtensionNavigation("demo", "https://example.com/demo/"))
        assertFalse(isAllowedExtensionNavigation("demo", origin.replace("https:", "http:")))
        assertFalse(isAllowedExtensionNavigation("demo", "javascript:alert(1)"))
        assertTrue(isAllowedExtensionFrameNavigation("demo", "about:srcdoc"))
        assertTrue(isAllowedExtensionFrameNavigation("demo", "blob:${origin}script-id"))
        assertFalse(isAllowedExtensionFrameNavigation("demo", "https://example.com/frame"))
    }

    @Test
    fun `subresource fetches stay on-origin unless the network permission is granted`() {
        val origin = extensionBaseUrl("demo")
        val otherOrigin = extensionBaseUrl("other")
        // Same-origin always passes; internal schemes resolve inside the WebView
        // and never reach the network.
        assertTrue(isAllowedExtensionSubresource("demo", origin, networkGranted = false))
        assertTrue(isAllowedExtensionSubresource("demo", "about:blank", networkGranted = false))
        assertTrue(isAllowedExtensionSubresource("demo", "blob:${origin}x", networkGranted = false))
        assertTrue(isAllowedExtensionSubresource("demo", "data:text/plain,hi", networkGranted = false))
        // Pinned compat CDN URLs are served by CompatAssets.intercept before the
        // policy runs, so the policy itself does not need to know that host:
        // a non-aliased URL on the compat origin is denied like any other.
        assertFalse(isAllowedExtensionSubresource("demo", "https://extensions.tellev.local/compat/globals.js", networkGranted = false))
        // Cross-origin https needs the Network permission — including another
        // extension's isolated origin, which must not be reachable either.
        assertFalse(isAllowedExtensionSubresource("demo", "https://example.com/exfil", networkGranted = false))
        assertFalse(isAllowedExtensionSubresource("demo", otherOrigin, networkGranted = false))
        assertTrue(isAllowedExtensionSubresource("demo", "https://example.com/api", networkGranted = true))
        assertFalse(isAllowedExtensionSubresource("demo", "http://example.com/api", networkGranted = true))
        assertFalse(isAllowedExtensionSubresource("demo", "http://192.168.1.10:8188/upload", networkGranted = true))
        // A URL that cannot be parsed cannot be judged, so it is denied.
        assertFalse(isAllowedExtensionSubresource("demo", "https://ex ample.com", networkGranted = false))
    }

    @Test
    fun `bootstrap installs real load failure guards`() {
        val guards = WebViewJsExtensionHost.EXTENSION_LOAD_GUARDS
        assertTrue(guards.contains("addEventListener('error'"))
        assertTrue(guards.contains("if(!e||!e.message)return"))
        assertTrue(guards.contains("unhandledrejection"))
        assertTrue(guards.contains("extensionFailed"))
    }

    @Test
    fun `remote character modules have enough time to report ready`() {
        assertTrue(WebViewJsExtensionHost.DEFAULT_SCRIPT_READY_TIMEOUT_MS >= 30_000L)
    }
}
