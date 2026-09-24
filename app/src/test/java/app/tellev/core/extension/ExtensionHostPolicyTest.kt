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
