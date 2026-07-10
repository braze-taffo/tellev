package app.tellev.core.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CompatModuleSettingsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    // ── EjsTemplateSettings serialization ──────────────────────────────

    @Test
    fun `EjsTemplateSettings defaults match ST-Prompt-Template defaults`() {
        val s = EjsTemplateSettings.DEFAULT
        assertTrue(s.enabled)
        assertTrue(s.generateEnabled)
        assertTrue(s.generateLoaderEnabled)
        assertTrue(s.renderEnabled)
        assertTrue(s.renderLoaderEnabled)
        assertFalse(s.injectLoaderEnabled)
        assertFalse(s.codeBlocksEnabled)
        assertTrue(s.rawMessageEvaluationEnabled)
        assertTrue(s.filterMessageEnabled)
        assertEquals(0, s.cacheEnabled)
        assertEquals(64, s.cacheSize)
        assertEquals("h32ToString", s.cacheHasher)
        assertEquals(-1, s.depthLimit)
        assertFalse(s.sandbox)
        assertFalse(s.debugEnabled)
        assertFalse(s.autosaveEnabled)
        assertTrue(s.preloadWorldinfoEnabled)
        assertFalse(s.withContextDisabled)
        assertTrue(s.invertEnabled)
        assertFalse(s.compileWorkers)
        assertFalse(s.codeEditor)
    }

    @Test
    fun `EjsTemplateSettings round-trips through JSON with snake_case keys`() {
        val original = EjsTemplateSettings(
            enabled = false,
            generateEnabled = false,
            generateLoaderEnabled = false,
            renderEnabled = false,
            depthLimit = 5,
            sandbox = true,
            cacheSize = 128,
        )
        val encoded = json.encodeToString(EjsTemplateSettings.serializer(), original)
        // Verify snake_case keys are present
        assertTrue(encoded.contains("\"enabled\""))
        assertTrue(encoded.contains("\"generate_enabled\""))
        assertTrue(encoded.contains("\"generate_loader_enabled\""))
        assertTrue(encoded.contains("\"render_enabled\""))
        assertTrue(encoded.contains("\"depth_limit\""))
        assertTrue(encoded.contains("\"sandbox\""))
        assertTrue(encoded.contains("\"cache_size\""))

        val decoded = json.decodeFromString(EjsTemplateSettings.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `EjsTemplateSettings tolerates unknown keys`() {
        val jsonStr = """{"enabled":true,"generate_enabled":true,"unknown_future_field":42}"""
        val decoded = json.decodeFromString(EjsTemplateSettings.serializer(), jsonStr)
        assertTrue(decoded.enabled)
        assertTrue(decoded.generateEnabled)
    }

    @Test
    fun `EjsTemplateSettings tolerates missing keys by using defaults`() {
        val jsonStr = """{"enabled":false}"""
        val decoded = json.decodeFromString(EjsTemplateSettings.serializer(), jsonStr)
        assertFalse(decoded.enabled)
        // Missing fields should fall back to defaults
        assertTrue(decoded.generateEnabled)
        assertTrue(decoded.renderEnabled)
        assertEquals(-1, decoded.depthLimit)
    }

    // ── TavernHelperSettings serialization ────────────────────────────

    @Test
    fun `TavernHelperSettings defaults match js-slash-runner defaults`() {
        val s = TavernHelperSettings.DEFAULT
        assertTrue(s.audio.enabled)
        assertTrue(s.audio.bgm.enabled)
        assertEquals("repeat_all", s.audio.bgm.mode)
        assertEquals(50, s.audio.bgm.volume)
        assertTrue(s.audio.ambient.enabled)
        assertEquals("play_one_and_stop", s.audio.ambient.mode)
        assertFalse(s.listener.enabled)
        assertTrue(s.listener.enableEcho)
        assertEquals("http://localhost:6621", s.listener.url)
        assertTrue(s.macro.enabled)
        assertTrue(s.optimize.disableIncompatibleOption)
        assertTrue(s.optimize.betterMessageToLoad)
        assertTrue(s.optimize.maximizePresetContextLength)
        assertTrue(s.render.enabled)
        assertEquals("frontend_only", s.render.collapseCodeBlock)
        assertTrue(s.render.optimizeHljs)
    }

    @Test
    fun `TavernHelperSettings round-trips through JSON`() {
        val original = TavernHelperSettings(
            audio = TavernHelperAudioSettings(
                enabled = false,
                bgm = TavernHelperBgmSettings(volume = 75, muted = true),
            ),
            optimize = TavernHelperOptimizeSettings(
                disableIncompatibleOption = false,
                maximizePresetContextLength = false,
            ),
            render = TavernHelperRenderSettings(
                allowStreaming = true,
                depth = 3,
            ),
        )
        val encoded = json.encodeToString(TavernHelperSettings.serializer(), original)
        val decoded = json.decodeFromString(TavernHelperSettings.serializer(), encoded)
        assertEquals(original, decoded)
        assertFalse(decoded.audio.enabled)
        assertEquals(75, decoded.audio.bgm.volume)
        assertTrue(decoded.audio.bgm.muted)
        assertFalse(decoded.optimize.disableIncompatibleOption)
        assertTrue(decoded.render.allowStreaming)
        assertEquals(3, decoded.render.depth)
    }

    // ── ExtensionSettingsStore typed read/write ───────────────────────

    @Test
    fun `readEjsTemplateSettings returns defaults when no file exists`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-ejs-test")
        val store = ExtensionSettingsStore(dir)
        val settings = store.readEjsTemplateSettings()
        assertEquals(EjsTemplateSettings.DEFAULT, settings)
    }

    @Test
    fun `saveEjsTemplateSettings persists and readEjsTemplateSettings returns saved values`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-ejs-test")
        val store = ExtensionSettingsStore(dir)
        val custom = EjsTemplateSettings(
            enabled = false,
            depthLimit = 10,
            sandbox = true,
            debugEnabled = true,
        )
        store.saveEjsTemplateSettings(custom)
        val read = store.readEjsTemplateSettings()
        assertEquals(custom, read)
        assertFalse(read.enabled)
        assertEquals(10, read.depthLimit)
        assertTrue(read.sandbox)
        assertTrue(read.debugEnabled)
    }

    @Test
    fun `readTavernHelperSettings returns defaults when no file exists`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-th-test")
        val store = ExtensionSettingsStore(dir)
        val settings = store.readTavernHelperSettings()
        assertEquals(TavernHelperSettings.DEFAULT, settings)
    }

    @Test
    fun `saveTavernHelperSettings persists and readTavernHelperSettings returns saved values`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-th-test")
        val store = ExtensionSettingsStore(dir)
        val custom = TavernHelperSettings(
            macro = TavernHelperMacroSettings(enabled = false),
            render = TavernHelperRenderSettings(allowStreaming = true, depth = 5),
        )
        store.saveTavernHelperSettings(custom)
        val read = store.readTavernHelperSettings()
        assertEquals(custom, read)
        assertFalse(read.macro.enabled)
        assertTrue(read.render.allowStreaming)
        assertEquals(5, read.render.depth)
    }

    @Test
    fun `EjsTemplate and tavern_helper settings are stored in separate files`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-separate-test")
        val store = ExtensionSettingsStore(dir)
        store.saveEjsTemplateSettings(EjsTemplateSettings(enabled = false))
        store.saveTavernHelperSettings(TavernHelperSettings(macro = TavernHelperMacroSettings(enabled = false)))

        // Both files should exist
        val ejsFile = dir.resolve("EjsTemplate").resolve("settings.json")
        val thFile = dir.resolve("tavern_helper").resolve("settings.json")
        assertTrue(Files.exists(ejsFile))
        assertTrue(Files.exists(thFile))

        // They should be independent
        val ejs = store.readEjsTemplateSettings()
        val th = store.readTavernHelperSettings()
        assertFalse(ejs.enabled)
        assertFalse(th.macro.enabled)
        // EJS defaults are otherwise intact
        assertTrue(ejs.generateEnabled)
        // TH defaults are otherwise intact
        assertTrue(th.audio.enabled)
    }

    @Test
    fun `corrupted settings file falls back to defaults`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-corrupt-test")
        val store = ExtensionSettingsStore(dir)
        // Write invalid JSON to the EjsTemplate settings file
        val ejsFile = dir.resolve("EjsTemplate").resolve("settings.json")
        Files.createDirectories(ejsFile.parent)
        Files.write(ejsFile, "{ this is not valid json".toByteArray())

        val settings = store.readEjsTemplateSettings()
        assertEquals(EjsTemplateSettings.DEFAULT, settings)
    }
}
