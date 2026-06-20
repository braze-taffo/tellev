package app.tellev.core.extension

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionManifestTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `parses real SillyTavern manifest with display_name and js`() {
        val manifestJson = """
        {
            "display_name": "酒馆助手",
            "loading_order": 100,
            "requires": [],
            "optional": [],
            "js": "dist/index.js",
            "css": "dist/index.css",
            "author": "KAKAA",
            "version": "4.8.11",
            "homePage": "https://github.com/N0VI028/JS-Slash-Runner",
            "auto_update": true,
            "minimum_client_version": "1.12.13",
            "i18n": { "en": "i18n/en.json" }
        }
        """.trimIndent()

        val manifest = json.decodeFromString(ExtensionManifest.serializer(), manifestJson)

        assertEquals("酒馆助手", manifest.displayName)
        assertEquals(100, manifest.loadingOrder)
        assertEquals("dist/index.js", manifest.js)
        assertEquals("dist/index.css", manifest.css)
        assertEquals("KAKAA", manifest.author)
        assertEquals("4.8.11", manifest.version)
        assertEquals("https://github.com/N0VI028/JS-Slash-Runner", manifest.homePage)
        assertTrue(manifest.autoUpdate)
        assertEquals("1.12.13", manifest.minimumClientVersion)
        assertEquals(mapOf("en" to "i18n/en.json"), manifest.i18n)
    }

    @Test
    fun `effectiveName falls back from name to displayName to id`() {
        val withName = ExtensionManifest(id = "ext1", name = "Explicit Name")
        assertEquals("Explicit Name", withName.effectiveName)

        val withDisplayName = ExtensionManifest(id = "ext1", displayName = "Display Name")
        assertEquals("Display Name", withDisplayName.effectiveName)

        val withIdOnly = ExtensionManifest(id = "ext1")
        assertEquals("ext1", withIdOnly.effectiveName)

        val withBoth = ExtensionManifest(id = "ext1", name = "Name", displayName = "Display")
        assertEquals("Name", withBoth.effectiveName)
    }

    @Test
    fun `parses tellev-specific manifest with id name and permissions`() {
        val manifestJson = """
        {
            "id": "my-extension",
            "name": "My Extension",
            "version": "1.0.0",
            "description": "A test extension",
            "permissions": ["Storage", "Network"]
        }
        """.trimIndent()

        val manifest = json.decodeFromString(ExtensionManifest.serializer(), manifestJson)

        assertEquals("my-extension", manifest.id)
        assertEquals("My Extension", manifest.name)
        assertEquals("1.0.0", manifest.version)
        assertEquals("A test extension", manifest.description)
        assertTrue(ExtensionPermission.Storage in manifest.permissions)
        assertTrue(ExtensionPermission.Network in manifest.permissions)
    }

    @Test
    fun `unknown manifest fields are ignored`() {
        val manifestJson = """
        {
            "display_name": "Test",
            "unknown_field": "should not crash",
            "another_unknown": 42
        }
        """.trimIndent()

        val manifest = json.decodeFromString(ExtensionManifest.serializer(), manifestJson)
        assertEquals("Test", manifest.displayName)
    }

    @Test
    fun `manifest with no fields uses defaults`() {
        val manifestJson = "{}"
        val manifest = json.decodeFromString(ExtensionManifest.serializer(), manifestJson)

        assertEquals("", manifest.id)
        assertEquals("", manifest.displayName)
        assertEquals("", manifest.js)
        assertEquals(true, manifest.autoUpdate)
        assertFalse(manifest.permissions.isNotEmpty())
    }

    @Test
    fun `copy with id sets id for directory-derived manifests`() {
        val original = ExtensionManifest(displayName = "Test Extension", js = "main.js")
        val withId = original.copy(id = "test-extension")

        assertEquals("test-extension", withId.id)
        assertEquals("Test Extension", withId.effectiveName)
        assertEquals("main.js", withId.js)
    }
}
