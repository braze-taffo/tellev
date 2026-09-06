package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pins the NovelAI image request to SillyTavern's implementation: the body is
 * compared field-for-field against src/endpoints/novelai.js POST
 * /generate-image (with getNovelParams clamping and combinePrefixes prompt
 * assembly applied exactly as public/scripts/extensions/stable-diffusion
 * index.js does them).
 */
class NovelAiImageAdapterTest {

    private val defaultNegative = "lowres, bad anatomy, bad hands, text, error, cropped, worst quality, " +
        "low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry"

    @Test
    fun `generate-image payload mirrors SillyTavern field for field`() = runBlocking {
        val png = "PNG1".toByteArray()
        val captured = mutableListOf<Pair<String, String>>() // path to body
        var authHeader: String? = null
        val interceptor = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            captured += chain.request().url.encodedPath to buffer.readUtf8()
            authHeader = chain.request().header("Authorization")
            response(chain, 200, zip("image_0.png" to png), "application/zip")
        }
        val settings = NovelAiImageSettings(
            model = "nai-diffusion-3",
            sampler = "k_euler",
            scheduler = "native",
            steps = 20,
            scale = 7.0,
            width = 832,
            height = 1216,
            seed = 1234L,
        )
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(config(), request(settings, prompt = "1girl, running", negative = "extra hand"))
            .toList()

        assertEquals("/ai/generate-image", captured.single().first)
        assertEquals("Bearer tok", authHeader)
        val completed = chunks.single() as GenerateChunk.Completed
        assertEquals("image_generated", completed.finishReason)
        assertEquals(Base64.getEncoder().encodeToString(png), completed.text)

        val body = Json.parseToJsonElement(captured.single().second).jsonObject
        assertEquals("generate", body.str("action"))
        // SillyTavern combines prompt_prefix in front of the prompt.
        assertEquals("best quality, absurdres, aesthetic, 1girl, running", body.str("input"))
        assertEquals("nai-diffusion-3", body.str("model"))
        val p = body.obj("parameters")
        assertEquals(3, p.int("params_version"))
        assertEquals("true", p.raw("prefer_brownian"))
        // Per-request negative first, then the extension's default negative.
        assertEquals("extra hand, $defaultNegative", p.str("negative_prompt"))
        assertEquals(1216, p.int("height"))
        assertEquals(832, p.int("width"))
        assertEquals(7.0, p.dbl("scale"), 1e-9)
        assertEquals(1234L, p.lng("seed"))
        assertEquals("k_euler", p.str("sampler"))
        assertEquals("native", p.str("noise_schedule"))
        assertEquals(20, p.int("steps"))
        assertEquals(1, p.int("n_samples"))
        assertEquals(0, p.int("ucPreset"))
        assertEquals("false", p.raw("qualityToggle"))
        assertEquals("false", p.raw("add_original_image"))
        assertEquals(1, p.int("controlnet_strength"))
        assertEquals("false", p.raw("deliberate_euler_ancestral_bug"))
        assertEquals("false", p.raw("dynamic_thresholding"))
        assertEquals("false", p.raw("legacy"))
        assertEquals("false", p.raw("legacy_v3_extend"))
        assertEquals("false", p.raw("sm"))
        assertEquals("false", p.raw("sm_dyn"))
        assertEquals(1, p.int("uncond_scale"))
        assertTrue(p["skip_cfg_above_sigma"] is JsonNull)
        assertEquals("false", p.raw("use_coords"))
        assertEquals(0, p.arr("characterPrompts").size)
        assertEquals(0, p.arr("reference_image_multiple").size)
        assertEquals(0, p.arr("reference_information_extracted_multiple").size)
        assertEquals(0, p.arr("reference_strength_multiple").size)
        val neg4 = p.obj("v4_negative_prompt").obj("caption")
        assertEquals("extra hand, $defaultNegative", neg4.str("base_caption"))
        assertEquals(0, neg4.arr("char_captions").size)
        val pos4 = p.obj("v4_prompt").obj("caption")
        assertEquals("best quality, absurdres, aesthetic, 1girl, running", pos4.str("base_caption"))
        assertEquals(0, pos4.arr("char_captions").size)
        assertEquals("false", p.obj("v4_prompt").raw("use_coords"))
        assertEquals("true", p.obj("v4_prompt").raw("use_order"))
    }

    @Test
    fun `negative seed rolls within SillyTavern's random range`() = runBlocking {
        val png = "P".toByteArray()
        var seed: Long? = null
        val capture = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            seed = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                .obj("parameters").lng("seed")
            response(chain, 200, zip("image_0.png" to png), "application/zip")
        }
        val chunks = NovelAiImageAdapter(client = client(capture))
            .streamGenerate(config(), request(NovelAiImageSettings(seed = -1L)))
            .toList()
        assertTrue(chunks.single() is GenerateChunk.Completed)
        assertNotNull(seed)
        assertTrue("seed $seed out of range", seed!! in 0..9_999_999_998L)
    }

    @Test
    fun `steps clamp to 50 like getNovelParams`() = runBlocking {
        val body = capturedBody(NovelAiImageSettings(steps = 60))
        assertEquals(50, body.obj("parameters").int("steps"))
    }

    @Test
    fun `scheduler outside novel list falls back to karras`() = runBlocking {
        val body = capturedBody(NovelAiImageSettings(scheduler = "normal"))
        assertEquals("karras", body.obj("parameters").str("noise_schedule"))
    }

    @Test
    fun `sm and sm dyn forced off for ddim and v4 full models`() = runBlocking {
        assertEquals("false", capturedBody(NovelAiImageSettings(sampler = "ddim", sm = true, smDyn = true))
            .obj("parameters").raw("sm"))
        assertEquals("false", capturedBody(NovelAiImageSettings(model = "nai-diffusion-4-full", sm = true))
            .obj("parameters").raw("sm_dyn"))
        // An unaffected model keeps the requested sm flags.
        assertEquals("true", capturedBody(NovelAiImageSettings(model = "nai-diffusion-3", sm = true))
            .obj("parameters").raw("sm"))
    }

    @Test
    fun `anlas guard shrinks pixels to 1MP multiple of 64 and steps to 28`() = runBlocking {
        val body = capturedBody(
            NovelAiImageSettings(width = 2048, height = 2048, steps = 40, anlasGuard = true),
        )
        val p = body.obj("parameters")
        assertEquals(1024, p.int("width"))
        assertEquals(1024, p.int("height"))
        assertEquals(28, p.int("steps"))
    }

    @Test
    fun `variety boost sigma uses 58 for v4_5 and 19 otherwise`() = runBlocking {
        fun sigma(settings: NovelAiImageSettings): Double =
            capturedBody(settings).obj("parameters").dbl("skip_cfg_above_sigma")

        // 832*1216 is the reference resolution: sigma equals the magic number.
        assertEquals(58.0, sigma(NovelAiImageSettings(model = "nai-diffusion-4-5-full", varietyBoost = true, width = 832, height = 1216)), 1e-9)
        assertEquals(19.0, sigma(NovelAiImageSettings(model = "nai-diffusion-3", varietyBoost = true, width = 832, height = 1216)), 1e-9)
        // 2x the reference pixels -> sqrt(4) = 2x the magic number.
        assertEquals(116.0, sigma(NovelAiImageSettings(model = "nai-diffusion-4-5-curated", varietyBoost = true, width = 1664, height = 2432)), 1e-9)
    }

    @Test
    fun `prompt prefix supports the SillyTavern prompt macro`() = runBlocking {
        val body = capturedBody(
            NovelAiImageSettings(promptPrefix = "masterpiece, {prompt}, detailed"),
            prompt = "1girl, running",
        )
        assertEquals("masterpiece, 1girl, running, detailed", body.str("input"))
    }

    @Test
    fun `user and char macros are substituted after prefix combination`() = runBlocking {
        val body = capturedBodyWithRequest(
            request(
                NovelAiImageSettings(promptPrefix = ""),
                prompt = "{{char}} and {{user}} dancing",
                userName = "Bob",
                charName = "Alice",
            ),
        )
        assertEquals("Alice and Bob dancing", body.str("input"))
    }

    @Test
    fun `blank per-request negative leaves the default negative alone`() = runBlocking {
        val body = capturedBody(NovelAiImageSettings(), negative = "")
        assertEquals(defaultNegative, body.obj("parameters").str("negative_prompt"))
    }

    @Test
    fun `zip extraction skips macosx entries and picks the first png`() = runBlocking {
        val png = "REAL".toByteArray()
        val interceptor = Interceptor { chain ->
            response(
                chain,
                200,
                zip(
                    "__MACOSX/._image_0.png" to "junk".toByteArray(),
                    "image_0.png" to png,
                ),
                "application/zip",
            )
        }
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(config(), request(NovelAiImageSettings()))
            .toList()
        val completed = chunks.single() as GenerateChunk.Completed
        assertEquals(Base64.getEncoder().encodeToString(png), completed.text)
    }

    @Test
    fun `archive without png fails with novelai_no_png`() = runBlocking {
        val interceptor = Interceptor { chain ->
            response(chain, 200, zip("note.txt" to "hi".toByteArray()), "application/zip")
        }
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(config(), request(NovelAiImageSettings()))
            .toList()
        assertEquals("novelai_no_png", (chunks.single() as GenerateChunk.Failed).error.code)
    }

    @Test
    fun `http error surfaces novelai_http code with body message`() = runBlocking {
        val interceptor = Interceptor { chain ->
            response(chain, 401, """{"statusCode":401,"message":"Access token is invalid"}""")
        }
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(config(), request(NovelAiImageSettings()))
            .toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("novelai_http_401", failed.error.code)
        assertEquals("Access token is invalid", failed.error.message)
    }

    @Test
    fun `missing token fails with novelai_token_missing`() = runBlocking {
        val chunks = NovelAiImageAdapter(client = client(Interceptor { response(it, 200, "{}") }))
            .streamGenerate(config(apiKey = null), request(NovelAiImageSettings()))
            .toList()
        assertEquals("novelai_token_missing", (chunks.single() as GenerateChunk.Failed).error.code)
    }

    @Test
    fun `upscale ratio above one runs the api upscale second pass`() = runBlocking {
        val original = "SMALL".toByteArray()
        val upscaled = "BIG".toByteArray()
        val requests = mutableListOf<Pair<String, String>>()
        val interceptor = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += chain.request().url.encodedPath to buffer.readUtf8()
            when (chain.request().url.encodedPath) {
                "/ai/generate-image" -> response(chain, 200, zip("image_0.png" to original), "application/zip")
                else -> response(chain, 200, zip("image_0.png" to upscaled), "application/zip")
            }
        }
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(
                config(),
                request(NovelAiImageSettings(width = 832, height = 1216, upscaleRatio = 2.0)),
            )
            .toList()

        assertEquals(listOf("/ai/generate-image", "/ai/upscale"), requests.map { it.first })
        val upscaleBody = Json.parseToJsonElement(requests[1].second).jsonObject
        assertEquals(Base64.getEncoder().encodeToString(original), upscaleBody.str("image"))
        assertEquals(832, upscaleBody.int("width"))
        assertEquals(1216, upscaleBody.int("height"))
        assertEquals(2.0, upscaleBody.dbl("scale"), 1e-9)
        // The upscaled image is the one emitted.
        assertEquals(Base64.getEncoder().encodeToString(upscaled), (chunks.single() as GenerateChunk.Completed).text)
    }

    @Test
    fun `upscale failure falls back to the original image`() = runBlocking {
        val original = "SMALL".toByteArray()
        val interceptor = Interceptor { chain ->
            when (chain.request().url.encodedPath) {
                "/ai/generate-image" -> response(chain, 200, zip("image_0.png" to original), "application/zip")
                else -> response(chain, 500, "boom")
            }
        }
        val chunks = NovelAiImageAdapter(client = client(interceptor))
            .streamGenerate(config(), request(NovelAiImageSettings(upscaleRatio = 2.0)))
            .toList()
        assertEquals(Base64.getEncoder().encodeToString(original), (chunks.single() as GenerateChunk.Completed).text)
    }

    @Test
    fun `checkStatus reports subscription tier`() = runBlocking {
        val interceptor = Interceptor { chain ->
            assertEquals("/user/subscription", chain.request().url.encodedPath)
            assertEquals("https://api.novelai.net/user/subscription", chain.request().url.toString())
            response(chain, 200, """{"tier":3,"active":true,"expiresAt":1893456000}""")
        }
        val status = NovelAiImageAdapter(client = client(interceptor)).checkStatus(config())
        assertTrue(status.available)
        assertTrue(status.message.contains("Opus"))
    }

    @Test
    fun `checkStatus marks invalid token unavailable`() = runBlocking {
        val interceptor = Interceptor { chain -> response(chain, 401, """{"message":"nope"}""") }
        val status = NovelAiImageAdapter(client = client(interceptor)).checkStatus(config())
        assertTrue(!status.available)
    }

    // ── combinePrefixes 行为（酒馆 index.js 同名函数） ────────────────────

    @Test
    fun `combinePrefixes joins with comma and trims edges`() {
        assertEquals("a, b", NovelAiImageProtocol.combinePrefixes("a,", "b"))
        assertEquals("a, b", NovelAiImageProtocol.combinePrefixes(" a , ", " , b , "))
        // Blank second fragment returns the first untouched.
        assertEquals(" a , ", NovelAiImageProtocol.combinePrefixes(" a , ", " "))
    }

    @Test
    fun `combinePrefixes splices the macro in place`() {
        assertEquals("x y z", NovelAiImageProtocol.combinePrefixes("x {prompt} z", "y", "{prompt}"))
        // No macro present -> plain comma join with a trailing comma trimmed.
        assertEquals("x z, y", NovelAiImageProtocol.combinePrefixes("x z", "y", "{prompt}"))
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Runs one generation with the settings and returns the captured /ai/generate-image body. */
    private fun capturedBody(settings: NovelAiImageSettings, prompt: String = "1girl, running", negative: String = ""): kotlinx.serialization.json.JsonObject =
        capturedBodyWithRequest(request(settings, prompt = prompt, negative = negative))

    private fun capturedBodyWithRequest(request: GenerateRequest): kotlinx.serialization.json.JsonObject {
        var body: String? = null
        val interceptor = Interceptor { chain ->
            if (chain.request().url.encodedPath == "/ai/generate-image") {
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                body = buffer.readUtf8()
            }
            response(chain, 200, zip("image_0.png" to "P".toByteArray()), "application/zip")
        }
        runBlocking {
            NovelAiImageAdapter(client = client(interceptor)).streamGenerate(config(), request).toList()
        }
        return Json.parseToJsonElement(body!!).jsonObject
    }

    private fun config(apiKey: String? = "tok") = ProviderConfig(
        providerType = ProviderCatalog.NOVELAI_IMAGE,
        baseUrl = "https://image.novelai.net",
        apiKey = apiKey,
    )

    private fun request(
        settings: NovelAiImageSettings,
        prompt: String = "1girl, running",
        negative: String = "",
        userName: String? = null,
        charName: String? = null,
    ) = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(role = MessageRole.System, content = "system preamble"),
                PromptMessage(role = MessageRole.User, content = prompt),
            ),
            stop = emptyList(),
            maxTokens = null,
            providerType = ProviderCatalog.NOVELAI_IMAGE,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        ),
        preset = GenerationPreset(id = "p", name = "p", providerType = ProviderCatalog.NOVELAI_IMAGE),
        stream = false,
        metadata = buildJsonObject {
            put("negative_prompt", negative)
            put("novelai_settings", Json.encodeToJsonElement(NovelAiImageSettings.serializer(), settings))
            if (userName != null) put("macro_user", userName)
            if (charName != null) put("macro_char", charName)
        },
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                entries.forEach { (name, data) ->
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            bos.toByteArray()
        }

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
    ): Response = response(chain, code, body.toByteArray(), "application/json")

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: ByteArray,
        mediaType: String,
    ): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(mediaType.toMediaType()))
            .build()

    private fun kotlinx.serialization.json.JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun kotlinx.serialization.json.JsonObject.int(key: String) = this[key]!!.jsonPrimitive.content.toInt()
    private fun kotlinx.serialization.json.JsonObject.lng(key: String) = this[key]!!.jsonPrimitive.content.toLong()
    private fun kotlinx.serialization.json.JsonObject.dbl(key: String) = this[key]!!.jsonPrimitive.content.toDouble()
    private fun kotlinx.serialization.json.JsonObject.raw(key: String) = this[key]!!.jsonPrimitive.content
    private fun kotlinx.serialization.json.JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun kotlinx.serialization.json.JsonObject.arr(key: String) = this[key]!!.jsonArray
}
