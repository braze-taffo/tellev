package app.tellev.core.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Test fun `first mutation loads persisted sibling values before accepting a write`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-global-startup-")
        val job = SupervisorJob()
        try {
            val settings = ExtensionSettingsStore(dir)
            settings.saveSettings("fixture", buildJsonObject { put("preserved", JsonPrimitive(9)) })
            val store = VariableStore(CoroutineScope(job + Dispatchers.Default), settings, "fixture")
            store.setGlobal("new", "value")
            store.flushWrites()
            assertEquals(JsonPrimitive(9), settings.getSettings("fixture")["preserved"])
            assertEquals("value", settings.getSettings("fixture")["new"]?.jsonPrimitive?.content)
        } finally { job.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun `concurrent global increments are durable at the write barrier`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-global-concurrency-")
        val job = SupervisorJob()
        val workers = CoroutineScope(job + Dispatchers.Default)
        try {
            val settings = ExtensionSettingsStore(dir)
            val store = VariableStore(workers, settings, "fixture")
            kotlinx.coroutines.coroutineScope {
                val tasks = (1..32).map { async(Dispatchers.Default) { store.incGlobal("count") } }
                tasks.forEach { it.await() }
            }
            store.flushWrites()
            assertEquals("32", store.getGlobal("count"))
            assertEquals("32", settings.getSettings("fixture")["count"]?.jsonPrimitive?.content)
        } finally { job.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun `failed global save fails the barrier and blocks subsequent state changes`() = runBlocking {
        val dir = Files.createTempDirectory("tellev-global-failure-")
        val job = SupervisorJob()
        try {
            Files.write(dir.resolve("fixture"), "original".toByteArray())
            val store = VariableStore(CoroutineScope(job + Dispatchers.Default), ExtensionSettingsStore(dir), "fixture")
            store.setGlobal("count", "1")
            assertTrue(runCatching { store.flushWrites() }.isFailure)
            assertTrue(runCatching { store.setGlobal("count", "2") }.isFailure)
            assertEquals("1", store.getGlobal("count"))
            assertEquals("original", String(Files.readAllBytes(dir.resolve("fixture"))))
        } finally { job.cancel(); dir.toFile().deleteRecursively() }
    }

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
            store.setLocalBackend(stringBackedBackend(local))
            return store
        }

        /**
         * Adapter over a plain `String` map so the scalar-oriented tests keep
         * working now that [LocalVariableBackend] carries JsonElement values.
         */
        fun stringBackedBackend(local: MutableMap<String, String>): LocalVariableBackend =
            object : LocalVariableBackend {
                override fun snapshot(): Map<String, JsonElement> =
                    local.mapValues { (_, v) -> JsonPrimitive(v) }

                override fun update(
                    transform: (MutableMap<String, JsonElement>) -> Unit,
                ): Map<String, JsonElement> {
                    val working = LinkedHashMap<String, JsonElement>(
                        local.mapValues { (_, v) -> JsonPrimitive(v) as JsonElement },
                    )
                    transform(working)
                    local.clear()
                    working.forEach { (k, v) ->
                        local[k] = (v as? JsonPrimitive)?.content ?: v.toString()
                    }
                    return working
                }
            }
    }

    /**
     * Adapter over a plain String map so the existing scalar-oriented tests
     * keep working now that [LocalVariableBackend] speaks JsonElement.
     */
    private fun newStore(): VariableStore {
        val local = ConcurrentHashMap<String, String>()
        return storeWith(local)
    }

    @Test
    fun `local scope keeps nested structures intact`() {
        // Regression: local values used to be stringified, so a variable card's
        // nested state came back as an opaque JSON string and any scalar write
        // flattened the whole table.
        val local = LinkedHashMap<String, JsonElement>()
        val backing = object : LocalVariableBackend {
            override fun snapshot(): Map<String, JsonElement> = local.toMap()
            override fun update(
                transform: (MutableMap<String, JsonElement>) -> Unit,
            ): Map<String, JsonElement> {
                transform(local)
                return local.toMap()
            }
        }
        val dir = Files.createTempDirectory("tellev-vars-nested")
        val store = VariableStore(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            settingsStore = ExtensionSettingsStore(dir),
            settingsKey = "_test_global",
        )
        store.setLocalBackend(backing)

        store.replaceLocal(
            buildJsonObject {
                put("stat_data", buildJsonObject { put("hp", JsonPrimitive(100)) })
            },
        )
        // A scalar write elsewhere must not collapse the nested value.
        store.setLocal("mood", "calm")

        val nested = store.localObject()["stat_data"]
        assertTrue("stat_data must stay an object, was $nested", nested is JsonObject)
        assertEquals("100", (nested as JsonObject)["hp"]?.jsonPrimitive?.content)
        assertEquals("calm", store.getLocal("mood"))
    }

    @Test
    fun `addLocal adds decimals numerically`() {
        val s = newStore()
        s.setLocal("affection", "1.5")
        assertEquals("2", s.addLocal("affection", "0.5"))
        assertEquals("2.5", s.addLocal("affection", "0.5"))
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
    fun `scoped backend isolates prompt mutations from active chat`() {
        val active = mutableMapOf("name" to "active")
        val scoped = mutableMapOf("name" to "origin")
        val store = storeWith(active)
        val scopedBackend = stringBackedBackend(scoped)

        store.withLocalBackend(scopedBackend) {
            assertEquals("origin", store.getLocal("name"))
            store.setLocal("name", "changed")
        }

        assertEquals("changed", scoped["name"])
        assertEquals("active", store.getLocal("name"))
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
        s1.flushWrites()

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
