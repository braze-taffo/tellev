package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiAdapterTest {

    private val workflow = """
        {
          "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "%prompt%"}},
          "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "%negative_prompt%"}},
          "3": {"class_type": "KSampler", "inputs": {"steps": "%steps%"}}
        }
    """.trimIndent()

    @Test
    fun `generates image through prompt history and view`() = runBlocking {
        var submittedBody: String? = null
        var viewQuery: String? = null
        var historyCalls = 0
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            when {
                path.endsWith("/prompt") -> {
                    val buffer = Buffer()
                    chain.request().body!!.writeTo(buffer)
                    submittedBody = buffer.readUtf8()
                    response(chain, 200, """{"prompt_id": "job-1", "number": 1, "node_errors": {}}""")
                }
                path.startsWith("/history/") -> {
                    historyCalls++
                    if (historyCalls == 1) {
                        // Job still queued/running: history omits the entry.
                        response(chain, 200, "{}")
                    } else {
                        response(
                            chain,
                            200,
                            """{"job-1": {
                                "status": {"status_str": "success", "completed": true, "messages": []},
                                "outputs": {"9": {"images": [
                                    {"filename": "img_0001.png", "subfolder": "", "type": "output"},
                                    {"filename": "tmp.png", "subfolder": "x", "type": "temp"}
                                ]}}
                            }}""",
                        )
                    }
                }
                path.endsWith("/view") -> {
                    viewQuery = chain.request().url.query
                    response(chain, 200, "PNGDATA", "image/png")
                }
                else -> response(chain, 404, "{}")
            }
        }
        val adapter = ComfyUiAdapter(client = client(interceptor))
        val settings = ComfyUiSettings(workflowJson = workflow, steps = 28)
        val chunks = adapter.streamGenerate(config(), request(settings, negative = "lowres")).toList()

        assertEquals(1, chunks.size)
        val completed = chunks.single() as GenerateChunk.Completed
        assertEquals("image_generated", completed.finishReason)
        assertEquals(java.util.Base64.getEncoder().encodeToString("PNGDATA".toByteArray()), completed.text)

        // The submitted workflow is rendered: prompt/negative/steps substituted.
        val submitted = Json.parseToJsonElement(submittedBody!!).jsonObject
        assertTrue(submitted["client_id"]!!.jsonPrimitive.content.isNotBlank())
        val nodes = submitted["prompt"]!!.jsonObject
        assertEquals(
            "masterpiece, 1girl",
            nodes["4"]!!.jsonObject["inputs"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "lowres",
            nodes["5"]!!.jsonObject["inputs"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(
            28,
            nodes["3"]!!.jsonObject["inputs"]!!.jsonObject["steps"]!!.jsonPrimitive.content.toInt(),
        )
        // The output image (not the temp preview) is downloaded from /view.
        assertEquals("filename=img_0001.png&subfolder=&type=output", viewQuery)
        assertTrue(historyCalls >= 2)
    }

    @Test
    fun `fails with comfy_workflow_missing when no workflow configured`() = runBlocking {
        val adapter = ComfyUiAdapter(client = client(Interceptor { response(it, 500, "{}") }))
        val chunks = adapter.streamGenerate(config(), request(ComfyUiSettings())).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_workflow_missing", failed.error.code)
    }

    @Test
    fun `fails with comfy_workflow_invalid for non-json workflow`() = runBlocking {
        val adapter = ComfyUiAdapter(client = client(Interceptor { response(it, 500, "{}") }))
        val chunks = adapter.streamGenerate(
            config(),
            request(ComfyUiSettings(workflowJson = "{\"broken\": ")),
        ).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_workflow_invalid", failed.error.code)
    }

    @Test
    fun `prompt endpoint http error surfaces comfy_http code`() = runBlocking {
        val interceptor = Interceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/prompt")) {
                response(chain, 400, """{"error": "prompt outputs failed validation"}""")
            } else {
                response(chain, 404, "{}")
            }
        }
        val adapter = ComfyUiAdapter(client = client(interceptor))
        val chunks = adapter.streamGenerate(config(), request(ComfyUiSettings(workflowJson = workflow))).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_http_400", failed.error.code)
        assertTrue(failed.error.message.contains("prompt outputs failed validation"))
    }

    @Test
    fun `node validation errors are reported`() = runBlocking {
        val interceptor = Interceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/prompt")) {
                response(chain, 200, """{"prompt_id": "job-1", "node_errors": {"4": ["bad node"]}}""")
            } else {
                response(chain, 404, "{}")
            }
        }
        val adapter = ComfyUiAdapter(client = client(interceptor))
        val chunks = adapter.streamGenerate(config(), request(ComfyUiSettings(workflowJson = workflow))).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_node_errors", failed.error.code)
        assertTrue(failed.error.message.contains("bad node"))
    }

    @Test
    fun `execution error in history surfaces comfy_execution_error`() = runBlocking {
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            when {
                path.endsWith("/prompt") ->
                    response(chain, 200, """{"prompt_id": "job-1", "number": 1, "node_errors": {}}""")
                path.startsWith("/history/") -> response(
                    chain,
                    200,
                    """{"job-1": {
                        "status": {"status_str": "error", "completed": true,
                            "messages": [["execution_error",
                                {"node_type": "KSampler", "exception_message": "boom"}]]},
                        "outputs": {}
                    }}""",
                )
                else -> response(chain, 404, "{}")
            }
        }
        val adapter = ComfyUiAdapter(client = client(interceptor))
        val chunks = adapter.streamGenerate(config(), request(ComfyUiSettings(workflowJson = workflow))).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_execution_error", failed.error.code)
        assertTrue(failed.error.message.contains("KSampler"))
        assertTrue(failed.error.message.contains("boom"))
    }

    @Test
    fun `finished job without output images surfaces comfy_no_images`() = runBlocking {
        val interceptor = Interceptor { chain ->
            val path = chain.request().url.encodedPath
            when {
                path.endsWith("/prompt") ->
                    response(chain, 200, """{"prompt_id": "job-1", "number": 1, "node_errors": {}}""")
                path.startsWith("/history/") -> response(
                    chain,
                    200,
                    """{"job-1": {
                        "status": {"status_str": "success", "completed": true, "messages": []},
                        "outputs": {}
                    }}""",
                )
                else -> response(chain, 404, "{}")
            }
        }
        val adapter = ComfyUiAdapter(client = client(interceptor))
        val chunks = adapter.streamGenerate(config(), request(ComfyUiSettings(workflowJson = workflow))).toList()
        val failed = chunks.single() as GenerateChunk.Failed
        assertEquals("comfy_no_images", failed.error.code)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun config() = ProviderConfig(
        providerType = ProviderCatalog.COMFYUI,
        baseUrl = "http://127.0.0.1:8188",
        model = "sd15.safetensors",
    )

    private fun request(settings: ComfyUiSettings, negative: String = "defaults") = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(
                PromptMessage(role = MessageRole.System, content = "system preamble"),
                PromptMessage(role = MessageRole.User, content = "masterpiece, 1girl"),
            ),
            stop = emptyList(),
            maxTokens = null,
            providerType = ProviderCatalog.COMFYUI,
            diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
        ),
        preset = GenerationPreset(id = "p", name = "p", providerType = ProviderCatalog.COMFYUI),
        stream = false,
        metadata = buildJsonObject {
            put("negative_prompt", negative)
            put("comfy_settings", Json.encodeToJsonElement(ComfyUiSettings.serializer(), settings))
        },
    )

    private fun client(interceptor: Interceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: String,
        mediaType: String = "application/json",
    ): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(mediaType.toMediaType()))
            .build()
}
