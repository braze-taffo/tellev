package app.tellev.core.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class VariableStoreTest {

    companion object {
        /**
         * Build a VariableStore backed by an in-memory [local] map and a
         * real (temp-dir) ExtensionSettingsStore so global persistence can be
         * exercised.  Shared with SlashCommandEngineTest.
         */
        fun storeWith(local: MutableMap<String, String>): VariableStore {
            val dir = Files.createTempDirectory("tellev-vars-test")
            val settingsStore = ExtensionSettingsStore(dir)
            val store = VariableStore(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                settingsStore = settingsStore,
                settingsKey = "_test_global",
            )
            store.setLocalBackend(object : LocalVariableBackend {
                override fun snapshot(): Map<String, String> = local.toMap()
                override fun update(transform: (MutableMap<String, String>) -> Unit): Map<String, String> {
                    transform(local)
                    return local.toMap()
                }
            })
            return store
        }
    }

    private fun newStore(): VariableStore {
        val local = ConcurrentHashMap<String, String>()
        return storeWith(local)
    }

    @Test
    fun `local and global are isolated`() {
        val s = newStore()
        s.setLocal("k", "L")
        s.setGlobal("k", "G")
        assertEquals("L", s.getLocal("k"))
        assertEquals("G", s.getGlobal("k"))
    }

    @Test
    fun `incLocal starts from zero`() {
        val s = newStore()
        assertEquals("1", s.incLocal("counter"))
        assertEquals("2", s.incLocal("counter"))
    }

    @Test
    fun `addLocal concatenates non-numeric`() {
        val s = newStore()
        s.setLocal("greeting", "hello")
        assertEquals("helloworld", s.addLocal("greeting", "world"))
    }

    @Test
    fun `deleteLocal only removes local`() {
        val s = newStore()
        s.setLocal("k", "L")
        s.setGlobal("k", "G")
        s.deleteLocal("k")
        assertNull(s.getLocal("k"))
        assertEquals("G", s.getGlobal("k"))
    }

    @Test
    fun `mergedObject has local overriding global`() {
        val s = newStore()
        s.setGlobal("shared", "G")
        s.setGlobal("onlyG", "G")
        s.setLocal("shared", "L")
        s.setLocal("onlyL", "L")
        val merged = s.mergedObject()
        assertEquals("L", merged["shared"]!!.jsonPrimitive.content)
        assertEquals("G", merged["onlyG"]!!.jsonPrimitive.content)
        assertEquals("L", merged["onlyL"]!!.jsonPrimitive.content)
    }

    @Test
    fun `replaceGlobal overwrites and globalObject reflects it`() {
        val s = newStore()
        s.setGlobal("a", "1")
        s.replaceGlobal(buildJsonObject { put("b", kotlinx.serialization.json.JsonPrimitive("2")) })
        assertNull(s.getGlobal("a"))
        assertEquals("2", s.getGlobal("b"))
    }

    @Test
    fun `global persists across a new store reading the same settings`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-vars-persist")
        val settingsStore = ExtensionSettingsStore(dir)
        val key = "_persist_test"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val s1 = VariableStore(scope, settingsStore, key)
        s1.setGlobal("saved", "yes")
        // persistGlobal launches on the scope; give it time to flush.
        kotlinx.coroutines.delay(100)

        val s2 = VariableStore(scope, settingsStore, key)
        s2.loadGlobal(settingsStore.getSettings(key))
        assertEquals("yes", s2.getGlobal("saved"))
    }

    @Test
    fun `hasLocal and hasGlobal distinguish scopes`() {
        val s = newStore()
        s.setLocal("lk", "1")
        s.setGlobal("gk", "1")
        assertTrue(s.hasLocal("lk"))
        assertFalse(s.hasLocal("gk"))
        assertTrue(s.hasGlobal("gk"))
        assertFalse(s.hasGlobal("lk"))
    }

    @Test
    fun `replaceLocal rewrites the local store`() {
        val local = ConcurrentHashMap<String, String>()
        val s = storeWith(local)
        s.setLocal("old", "x")
        s.replaceLocal(buildJsonObject { put("new", kotlinx.serialization.json.JsonPrimitive("y")) })
        assertNull(s.getLocal("old"))
        assertEquals("y", s.getLocal("new"))
    }
}
