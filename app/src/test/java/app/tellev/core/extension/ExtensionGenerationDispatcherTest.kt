package app.tellev.core.extension

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionGenerationDispatcherTest {
    @Test
    fun `generation dispatcher returns provider result`() = runBlocking {
        val provider = object : ExtensionContextProvider {
            override fun snapshot() = buildJsonObject { }
            override suspend fun generateText(options: kotlinx.serialization.json.JsonObject) =
                buildJsonObject { put("text", options["prompt"]?.jsonPrimitive?.content ?: "") }
        }

        val response = dispatchExtensionGeneration(
            provider,
            buildJsonObject { put("prompt", "hello") },
        )

        assertEquals(200, response.status)
        assertEquals("hello", response.bodyJson()["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generation dispatcher reports missing and incomplete contexts`() = runBlocking {
        val missing = dispatchExtensionGeneration(null, buildJsonObject { })
        assertEquals(503, missing.status)
        assertEquals("chat_context_unavailable", missing.bodyJson()["code"]?.jsonPrimitive?.content)

        val incomplete = dispatchExtensionGeneration(
            object : ExtensionContextProvider {
                override fun snapshot() = buildJsonObject { }
                override suspend fun generateText(options: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
                    throw IllegalStateException("No character is selected")
                }
            },
            buildJsonObject { },
        )
        assertEquals(409, incomplete.status)
        assertEquals("chat_context_incomplete", incomplete.bodyJson()["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `generation dispatcher reports provider failure`() = runBlocking {
        val response = dispatchExtensionGeneration(
            object : ExtensionContextProvider {
                override fun snapshot() = buildJsonObject { }
                override suspend fun generateText(options: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject? {
                    throw RuntimeException("upstream unavailable")
                }
            },
            buildJsonObject { },
        )

        assertEquals(502, response.status)
        assertEquals("provider_generation_failed", response.bodyJson()["code"]?.jsonPrimitive?.content)
    }

    private fun VirtualApiResponse.bodyJson() =
        FileJson.json.parseToJsonElement(body).jsonObject

    private object FileJson {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
