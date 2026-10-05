package app.tellev.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageProviderProfileTest {

    private fun request(metadata: kotlinx.serialization.json.JsonObject) = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(PromptMessage(role = MessageRole.User, content = "a castle at dusk")),
            stop = emptyList(),
            maxTokens = null,
            providerType = ProviderCatalog.OPENAI_IMAGE,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        ),
        preset = GenerationPreset(id = "image", name = "Image generation", providerType = ProviderCatalog.OPENAI_IMAGE),
        stream = false,
        metadata = metadata,
    )

    private val emptyMetadata = kotlinx.serialization.json.JsonObject(emptyMap())

    @Test
    fun `payload carries model prompt and n with adapter defaults`() {
        val config = ProviderConfig(providerType = ProviderCatalog.OPENAI_IMAGE, baseUrl = "https://api.openai.com")
        val payload = imageGenerationPayload(config, request(emptyMetadata)).jsonObject
        assertEquals("dall-e-3", payload["model"]!!.jsonPrimitive.content)
        assertEquals("a castle at dusk", payload["prompt"]!!.jsonPrimitive.content)
        assertEquals(1, payload["n"]!!.jsonPrimitive.content.toInt())
        // DALL·E-only fields stay omitted when the profile leaves them blank,
        // so strict compatible relays never see unrecognized arguments.
        assertNull(payload["size"])
        assertNull(payload["quality"])
        assertNull(payload["style"])
        assertNull(payload["response_format"])
        assertNull(payload["negative_prompt"])
    }

    @Test
    fun `profile metadata maps size quality style and format only when present`() {
        val profile = ImageProviderProfile(
            id = "p1", name = "Relay", baseUrl = "https://relay.example",
            model = "flux.1-dev", size = "512x512", quality = "hd", style = "vivid",
            responseFormat = "b64_json", sendNegativePrompt = true,
        )
        val payload = imageGenerationPayload(
            profile.toProviderConfig(),
            request(profile.requestMetadata("blurry, lowres")),
        ).jsonObject
        assertEquals("flux.1-dev", payload["model"]!!.jsonPrimitive.content)
        assertEquals("512x512", payload["size"]!!.jsonPrimitive.content)
        assertEquals("hd", payload["quality"]!!.jsonPrimitive.content)
        assertEquals("vivid", payload["style"]!!.jsonPrimitive.content)
        assertEquals("b64_json", payload["response_format"]!!.jsonPrimitive.content)
        assertEquals("blurry, lowres", payload["negative_prompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank optional fields are omitted and negative prompt withheld unless enabled`() {
        val profile = ImageProviderProfile(id = "p1", name = "Strict", baseUrl = "https://x", size = "")
        val metadata = profile.requestMetadata("blurry")
        val payload = imageGenerationPayload(profile.toProviderConfig(), request(metadata)).jsonObject
        assertNull(payload["negative_prompt"])
        assertNull(payload["size"])
        assertTrue(!metadata.containsKey("negative_prompt"))
    }

    @Test
    fun `extra body merges last and can override base fields`() {
        val profile = ImageProviderProfile(
            id = "p1", name = "Relay", baseUrl = "https://x",
            extraBody = Json.parseToJsonElement("""{"model":"custom-model","steps":30}""").jsonObject,
        )
        val payload = imageGenerationPayload(profile.toProviderConfig(), request(profile.requestMetadata(""))).jsonObject
        assertEquals("custom-model", payload["model"]!!.jsonPrimitive.content)
        assertEquals(30, payload["steps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `toProviderConfig normalizes base url and carries models path option`() {
        val profile = ImageProviderProfile(
            id = "p1", name = "Relay", baseUrl = "https://relay.example/",
            apiKey = " sk-1 ", model = " dall-e-3 ", modelsPath = "/models",
        )
        val config = profile.toProviderConfig()
        assertEquals("https://relay.example", config.baseUrl)
        assertEquals("sk-1", config.apiKey)
        assertEquals("dall-e-3", config.model)
        assertEquals(ProviderCatalog.OPENAI_IMAGE, config.providerType)
        assertEquals("/models", config.options["models_path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseExtraBody rejects non-object json and accepts blank`() {
        assertNull(ImageProviderProfile.parseExtraBody("[1,2]"))
        assertNull(ImageProviderProfile.parseExtraBody("not json"))
        assertEquals(0, ImageProviderProfile.parseExtraBody("")!!.size)
        assertEquals(1, ImageProviderProfile.parseExtraBody("""{"a":1}""")!!.size)
    }

    @Test
    fun `configured means non-blank base url`() {
        assertFalse(ImageProviderProfile(id = "p1", name = "x").isConfigured)
        assertTrue(ImageProviderProfile(id = "p1", name = "x", baseUrl = "https://x").isConfigured)
    }
}
