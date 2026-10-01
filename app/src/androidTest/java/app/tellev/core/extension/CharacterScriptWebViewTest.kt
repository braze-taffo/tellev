package app.tellev.core.extension

import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import app.tellev.MainActivity
import app.tellev.TellevGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Exercises Web Storage and the actual child-frame loader in Android WebView. */
class CharacterScriptWebViewTest {
    private lateinit var activity: android.app.Activity

    @org.junit.Before fun launchForegroundActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }

    @org.junit.After fun closeForegroundActivity() {
        activity.finish()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test
    fun nativeMutableGenerationEventsReturnEditsAcrossChildFramesAndRuntimes() = runBlocking {
        val graph = TellevGraph.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val ids = listOf("mutable-a-${UUID.randomUUID()}", "mutable-b-${UUID.randomUUID()}")
        try {
            for ((index, id) in ids.withIndex()) {
                val script = if (index == 0) "message.content = message.content.replace('raw', 'filtered');"
                    else "message.content = message.content.replace('\\n<StatusPlaceHolderImpl/>', '');"
                val scripts = buildJsonArray { add(buildJsonObject {
                    put("id", JsonPrimitive("filter")); put("name", JsonPrimitive("Filter"))
                    put("content", JsonPrimitive("""
                        eventOn(tavern_events.CHAT_COMPLETION_SETTINGS_READY, async data => {
                            await Promise.resolve();
                            data.messages.filter(message => typeof message.content === 'string').forEach(message => { $script });
                        });
                    """.trimIndent()))
                }) }
                host.load(ExtensionManifest(id = id), "export {}; await window.__tellevLoadScripts($scripts);")
            }
            val original = buildJsonObject { put("args", buildJsonArray { add(buildJsonObject {
                put("messages", buildJsonArray { add(buildJsonObject {
                    put("role", JsonPrimitive("assistant")); put("content", JsonPrimitive("raw\n<StatusPlaceHolderImpl/>"))
                }) })
            }) }) }
            val result = host.emitMutable(ExtensionEvent(name = StEventCatalog.CHAT_COMPLETION_SETTINGS_READY, payload = original))
            fun content(payload: kotlinx.serialization.json.JsonObject) = payload.getValue("args").jsonArray.single()
                .jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
            assertEquals("filtered", content(result))
            assertEquals("raw\n<StatusPlaceHolderImpl/>", content(original))
        } finally { ids.forEach { host.unload(it) } }
    }

    @Test
    fun cancelledBootstrapDestroysRuntimeAndAllowsReload() = runBlocking {
        val graph = TellevGraph.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val id = "cancelled-bootstrap-${UUID.randomUUID()}"
        try {
            val loading = async {
                host.load(ExtensionManifest(id = id), "export {}; await new Promise(() => {});")
            }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { host.webViewForUi(id) == null }) delay(10)
            }
            loading.cancelAndJoin()
            org.junit.Assert.assertNull(host.capabilityToken(id))
            withContext(Dispatchers.Main) { org.junit.Assert.assertNull(host.webViewForUi(id)) }
            host.load(ExtensionManifest(id = id), "export {}; window.reloaded = true;")
            assertEquals("true", host.evaluateRuntime(id, "window.reloaded"))
        } finally {
            host.unload(id)
        }
    }

    @Test
    fun scriptsUseOwnFramesAndOriginScopedStorage() = runBlocking {
        val graph = TellevGraph.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val host = graph.extensionHost as WebViewJsExtensionHost
        val id = "character-webview-test-${UUID.randomUUID()}"
        val otherId = "character-webview-test-${UUID.randomUUID()}"
        val scripts = buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive("storage"))
                put("name", JsonPrimitive("Storage"))
                put("content", JsonPrimitive("""
                    localStorage.setItem('card.sound', 'on');
                    parent.document.body.insertAdjacentHTML('beforeend', '<main id="card-ui">visible</main>');
                    window.probeId = getScriptId();
                """.trimIndent()))
            })
            add(buildJsonObject {
                put("id", JsonPrimitive("listener"))
                put("name", JsonPrimitive("Listener"))
                put("content", JsonPrimitive("window.probeId = getScriptId();"))
            })
        }
        try {
            host.load(ExtensionManifest(id = id), "export {}; await window.__tellevLoadScripts($scripts);")
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#card-ui')?.textContent === 'visible'"))
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#TH-script--Storage--storage')?.contentWindow.probeId === 'storage'"))
            assertEquals("true", host.evaluateRuntime(id,
                "document.querySelector('#TH-script--Listener--listener')?.contentWindow.probeId === 'listener'"))
            assertEquals("true", host.evaluateRuntime(id,
                "localStorage.getItem('card.sound') === 'on'"))

            host.unload(id)
            host.load(ExtensionManifest(id = id), "export {};")
            assertEquals("true", host.evaluateRuntime(id,
                "localStorage.getItem('card.sound') === 'on'"))

            host.load(ExtensionManifest(id = otherId), "export {};")
            assertEquals("true", host.evaluateRuntime(otherId,
                "localStorage.getItem('card.sound') === null"))
        } finally {
            host.unload(id)
            host.unload(otherId)
        }
    }
    @Test fun nativePresetReadsAreSynchronousAndAsyncWritesPreserveRawFields() = runBlocking {
        val graph = TellevGraph.create(InstrumentationRegistry.getInstrumentation().targetContext)
        graph.dataStore.bootstrap()
        val store = graph.dataStore
        val host = graph.extensionHost as WebViewJsExtensionHost
        val name = "compat-preset-${UUID.randomUUID()}"
        val id = "preset-bridge-${UUID.randomUUID()}"
        val raw = kotlinx.serialization.json.Json.parseToJsonElement("""{"temperature":0.8,"openai_max_tokens":200,"reasoning_effort":"high","unknown":{"keep":true},"prompts":[{"identifier":"main","name":"Main","role":"system","content":"original","system_prompt":true}],"prompt_order":[{"character_id":100001,"order":[{"identifier":"main","enabled":true}]}]}""").jsonObject
        val nativeOptions = java.util.concurrent.atomic.AtomicReference<kotlinx.serialization.json.JsonObject>()
        host.setContextProvider(object : ExtensionContextProvider {
            override fun snapshot() = buildJsonObject { }
            override suspend fun generateText(options: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
                nativeOptions.set(options)
                return buildJsonObject { put("text", JsonPrimitive("generated")) }
            }
        })
        store.savePreset(app.tellev.core.model.GenerationPreset(name, name, "openai-compatible", temperature = 0.8,
            maxTokens = 200, maxCompletionTokens = 200, raw = raw,
            prompts = listOf(app.tellev.core.model.PresetPrompt("main", "Main", content = "original", raw = raw["prompts"]!!.jsonArray[0].jsonObject))))
        try {
            graph.permissionManager.grantAll(id, setOf(ExtensionPermission.Storage, ExtensionPermission.ProviderRequest))
            host.load(ExtensionManifest(id = id, permissions = setOf(ExtensionPermission.Storage, ExtensionPermission.ProviderRequest)), "export {};")
            assertEquals("true", host.evaluateRuntime(id, "Array.isArray(getPresetNames()) && getPresetNames().includes('$name')"))
            assertEquals("true", host.evaluateRuntime(id, "getPreset('$name').settings.temperature === 0.8 && getPreset('$name').prompts[0].id === 'main'"))
            host.evaluateRuntime(id, "updatePresetWith('$name',async p=>{await Promise.resolve();p.settings.temperature=0.25;p.settings.max_completion_tokens=345;p.prompts.push({id:'depth',name:'Depth',enabled:true,position:{type:'in_chat',depth:2,order:70},role:'assistant',content:'injected'});return p})")
            val saved = store.readPreset(app.tellev.core.model.PresetCategory.OpenAi, name)!!
            assertEquals(0.25, saved.temperature!!, 0.0)
            assertEquals(345, saved.maxCompletionTokens)
            val depth = saved.prompts.first { it.identifier == "depth" }
            org.junit.Assert.assertTrue(depth.relative); assertEquals(2, depth.depth); assertEquals(70, depth.injectionOrder)
            host.evaluateRuntime(id, "updatePresetWith('$name',async p=>{p.prompts.find(x=>x.id==='depth').position={type:'relative'};return p})")
            org.junit.Assert.assertFalse(store.readPreset(app.tellev.core.model.PresetCategory.OpenAi, name)!!.prompts.first { it.identifier == "depth" }.relative)
            assertEquals(raw["unknown"], saved.raw["unknown"])
            assertEquals("high", saved.raw["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals("false", host.evaluateRuntime(id, "loadPreset('missing-${UUID.randomUUID()}')"))
            val generated = host.evaluateRuntime(id, "generateRaw({ordered_prompts:[{role:'user',content:'raw input'}]})")
            assertEquals("generated", kotlinx.serialization.json.Json.parseToJsonElement(generated).jsonPrimitive.content)
            assertEquals(JsonPrimitive(false), nativeOptions.get()["__tellev_use_preset"])
            assertEquals("raw input", nativeOptions.get()["ordered_prompts"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)

        } finally {
            host.unload(id)
            store.deletePreset(name, "openai")
            graph.permissionManager.clearExtension(id)
            host.setContextProvider(null)
        }
    }

}
