package app.tellev.core.extension

import app.tellev.core.storage.JournaledFileWriter
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExtensionSettingsStoreTest {
    private val root = Files.createTempDirectory("mvu-settings")
    @After fun clean() { root.toFile().deleteRecursively() }

    @Test fun `typed settings updates preserve unknown imported fields`() = runBlocking {
        val store = ExtensionSettingsStore(root)
        val extra = buildJsonObject { putJsonObject("future_extension") { put("keep", 7) } }
        store.saveSettings("EjsTemplate", extra)
        store.saveSettings("tavern_helper", extra)
        store.saveEjsTemplateSettings(EjsTemplateSettings.DEFAULT)
        store.saveTavernHelperSettings(TavernHelperSettings.DEFAULT)
        assertEquals(extra["future_extension"], store.getSettings("EjsTemplate")["future_extension"])
        assertEquals(extra["future_extension"], store.getSettings("tavern_helper")["future_extension"])
    }

    @Test fun `first access recovers an interrupted settings write before exposing data`() = runBlocking {
        val target = root.resolve("fixture/settings.json")
        val writer = JournaledFileWriter(root) { if (it == JournaledFileWriter.Stage.PREPARED) throw IOException("stopped") }
        assertThrows(IOException::class.java) { writer.write(target, "{\"value\":8}".toByteArray()) }
        assertEquals(8, ExtensionSettingsStore(root).getSettings("fixture")["value"]!!.jsonPrimitive.int)
    }

    @Test fun `deletion stays deleted after reopening storage`() = runBlocking {
        val store = ExtensionSettingsStore(root)
        store.saveSettings("fixture", buildJsonObject { put("value", 1) })
        store.deleteSettings("fixture")
        assertEquals(JsonObject(emptyMap()), ExtensionSettingsStore(root).getSettings("fixture"))
        assertTrue(store.listExtensionIds().isEmpty())
    }
}
