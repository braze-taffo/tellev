package app.tellev.feature.extensions

import app.tellev.core.extension.ExtensionEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionDebugModelsTest {

    @Test
    fun `load events survive a discovery refresh merge`() {
        val runtime = reduceExtensionRuntimeEvent(
            current = ExtensionRuntimeOverview(),
            event = ExtensionEvent(name = "extension_loaded", extensionId = "demo"),
            nowMillis = 100L,
        )

        assertTrue(resolveExtensionLoaded("demo", runtime, hostHasCapability = false))
        assertTrue(runtime.statusByExtensionId.getValue("demo").loaded)
        assertEquals("扩展已加载", runtime.logs.single().message)

        val unloaded = reduceExtensionRuntimeEvent(
            current = runtime,
            event = ExtensionEvent(name = "extension_unloaded", extensionId = "demo"),
            nowMillis = 200L,
        )
        assertFalse(resolveExtensionLoaded("demo", unloaded, hostHasCapability = false))
        assertFalse(unloaded.statusByExtensionId.getValue("demo").loaded)
    }

    @Test
    fun `error logs become a visible runtime error`() {
        val runtime = reduceExtensionRuntimeEvent(
            current = ExtensionRuntimeOverview(),
            event = ExtensionEvent(
                name = "extension_log",
                extensionId = "broken",
                payload = buildJsonObject {
                    put("level", "error")
                    put("message", "ReferenceError: missingApi")
                },
            ),
            nowMillis = 300L,
        )

        assertEquals(
            "ReferenceError: missingApi",
            runtime.statusByExtensionId.getValue("broken").lastError,
        )
        assertEquals(ExtensionRuntimeLogLevel.Error, runtime.logs.single().level)
    }

    @Test
    fun `load failures are visible and never marked loaded`() {
        val runtime = reduceExtensionRuntimeEvent(
            current = ExtensionRuntimeOverview(loadedExtensionIds = setOf("broken")),
            event = ExtensionEvent(
                name = "extension_load_failed",
                extensionId = "broken",
                payload = buildJsonObject { put("message", "SyntaxError: unexpected token") },
            ),
            nowMillis = 350L,
        )

        assertFalse(runtime.statusByExtensionId.getValue("broken").loaded)
        assertFalse("broken" in runtime.loadedExtensionIds)
        assertEquals(
            "SyntaxError: unexpected token",
            runtime.statusByExtensionId.getValue("broken").lastError,
        )
        assertEquals("extension_load_failed", runtime.statusByExtensionId.getValue("broken").lastEvent)
        assertEquals(ExtensionRuntimeLogLevel.Error, runtime.logs.single().level)
    }

    @Test
    fun `prompt diagnostics retain the final message payload`() {
        val runtime = reduceExtensionRuntimeEvent(
            current = ExtensionRuntimeOverview(),
            event = ExtensionEvent(
                name = PROMPT_DIAGNOSTICS_EVENT,
                payload = buildJsonObject {
                    put("estimatedTokenCount", 42)
                    putJsonArray("activatedWorldEntryIds") { add(JsonPrimitive("lore-1")) }
                    putJsonArray("warnings") { add(JsonPrimitive("unsupported expression")) }
                    putJsonArray("messages") {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", "final system prompt")
                            },
                        )
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("name", "Tester")
                                put("content", "hello")
                            },
                        )
                    }
                },
            ),
            nowMillis = 400L,
        )

        assertNotNull(runtime.promptDebugSnapshot)
        val snapshot = runtime.promptDebugSnapshot!!
        assertEquals(42, snapshot.estimatedTokenCount)
        assertEquals(listOf("lore-1"), snapshot.activatedWorldEntryIds)
        assertEquals(listOf("unsupported expression"), snapshot.warnings)
        assertEquals("final system prompt", snapshot.messages.first().content)
        assertEquals("Tester", snapshot.messages.last().name)
        assertEquals(400L, snapshot.capturedAtMillis)
    }
}
