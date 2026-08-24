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
        assertTrue(isAllowedExtensionNavigation("demo", "https://extensions.tellev.local/demo/"))
        assertFalse(isAllowedExtensionNavigation("demo", "https://extensions.tellev.local/demo/settings"))
        assertFalse(isAllowedExtensionNavigation("demo", "https://extensions.tellev.local/other/"))
        assertFalse(isAllowedExtensionNavigation("demo", "https://example.com/demo/"))
        assertFalse(isAllowedExtensionNavigation("demo", "http://extensions.tellev.local/demo/"))
        assertFalse(isAllowedExtensionNavigation("demo", "javascript:alert(1)"))
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
