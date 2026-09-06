package app.tellev.core.provider

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDreamAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun adapter(root: File?) = LocalDreamAdapter(modelsRoot = root)

    private fun imageRequest(settings: LocalDreamSettings?, extra: Map<String, String> = emptyMap()) =
        GenerateRequest(
            prompt = PromptBuildResult(
                messages = listOf(PromptMessage(role = MessageRole.User, content = "1girl, masterpiece")),
                stop = emptyList(),
                maxTokens = null,
                providerType = ProviderCatalog.LOCAL_DREAM,
                diagnostics = PromptDiagnostics(activatedWorldEntryIds = emptyList()),
            ),
            preset = GenerationPreset(id = "none", name = "none", providerType = ProviderCatalog.LOCAL_DREAM),
            stream = false,
            metadata = buildJsonObject {
                extra.forEach { (k, v) -> put(k, v) }
                settings?.let { put("local_dream_settings", kotlinx.serialization.json.Json.encodeToJsonElement(LocalDreamSettings.serializer(), it)) }
            },
        )

    // ── SSE 协议解析 ─────────────────────────────────────────────────────

    @Test
    fun `sse progress event parses step and total`() {
        val event = LocalDreamProtocol.parse("progress", """data: {"type":"progress","step":3,"total_steps":12}""")
        assertEquals(LocalDreamProtocol.Event.Progress(3, 12), event)
    }

    @Test
    fun `sse complete event parses image and duration`() {
        val event = LocalDreamProtocol.parse("complete", """data: {"type":"complete","image":"QUJD","generation_time_ms":41749}""")
        assertEquals(LocalDreamProtocol.Event.Complete("QUJD", 41749L), event)
    }

    @Test
    fun `sse error event parses message`() {
        val event = LocalDreamProtocol.parse("error", """data: {"type":"error","message":"boom"}""")
        assertEquals(LocalDreamProtocol.Event.Error("boom"), event)
    }

    @Test
    fun `sse unknown event or garbage returns null`() {
        assertEquals(null, LocalDreamProtocol.parse("mystery", """data: {}"""))
        assertEquals(null, LocalDreamProtocol.parse("progress", "data: not-json"))
        assertEquals(null, LocalDreamProtocol.parse(null, """data: {}"""))
        assertEquals(null, LocalDreamProtocol.parse("complete", "data: "))
    }

    // ── 请求组装 ─────────────────────────────────────────────────────────

    @Test
    fun `request body carries all generation fields`() {
        val settings = LocalDreamSettings(
            modelDirName = "aom2",
            steps = 10,
            cfgScale = 7.0,
            width = 512,
            height = 512,
            scheduler = "dpm",
            useOpencl = true,
        )
        val body = LocalDreamProtocol.buildRequestBody("1girl", "lowres", settings, seed = 42L)
        assertTrue(body.contains(""""prompt":"1girl""""))
        assertTrue(body.contains(""""negative_prompt":"lowres""""))
        assertTrue(body.contains(""""steps":10"""))
        assertTrue(body.contains(""""cfg":7.0"""))
        assertTrue(body.contains(""""width":512"""))
        assertTrue(body.contains(""""height":512"""))
        assertTrue(body.contains(""""scheduler":"dpm""""))
        assertTrue(body.contains(""""use_opencl":true"""))
        assertTrue(body.contains(""""seed":42"""))
        assertTrue(body.contains(""""output_format":"png""""))
    }

    // ── 模型目录列举 ─────────────────────────────────────────────────────

    @Test
    fun `listModels only returns dirs whose conversion finished`() = runBlocking {
        val root = tmp.newFolder("models-mnn")
        val done = File(root, "aom2").apply { mkdirs() }
        File(done, "finished").writeText("")
        File(root, "half-converted").mkdirs()
        File(root, "stray.safetensors").writeText("not a dir")

        val models = adapter(root).listModels(
            ProviderConfig(providerType = ProviderCatalog.LOCAL_DREAM, baseUrl = "local://x"),
        )
        assertEquals(listOf("aom2"), models.map { it.id })
        assertTrue(models.single().displayName.contains("aom2"))
    }

    @Test
    fun `listModels is empty without root`() = runBlocking {
        assertTrue(adapter(null).listModels(ProviderConfig(providerType = "x", baseUrl = "")).isEmpty())
    }

    // ── 生成前置校验（不拉起核心进程） ────────────────────────────────────

    @Test
    fun `generate without configured model fails with model missing`() = runBlocking {
        val chunks = adapter(tmp.newFolder()).streamGenerate(
            ProviderConfig(providerType = ProviderCatalog.LOCAL_DREAM, baseUrl = "local://x"),
            imageRequest(settings = null),
        ).toList()
        val failure = chunks.filterIsInstance<GenerateChunk.Failed>().single()
        assertEquals("local_dream_model_missing", failure.error.code)
    }

    @Test
    fun `generate with unconverted model dir fails with model missing`() = runBlocking {
        val root = tmp.newFolder("models-mnn2")
        File(root, "aom2").mkdirs() // no finished marker
        val chunks = adapter(root).streamGenerate(
            ProviderConfig(providerType = ProviderCatalog.LOCAL_DREAM, baseUrl = "local://x"),
            imageRequest(settings = LocalDreamSettings(modelDirName = "aom2")),
        ).toList()
        val failure = chunks.filterIsInstance<GenerateChunk.Failed>().single()
        assertEquals("local_dream_model_missing", failure.error.code)
    }

    @Test
    fun `generate uses default settings when metadata carries none`() = runBlocking {
        val chunks = adapter(null).streamGenerate(
            ProviderConfig(providerType = ProviderCatalog.LOCAL_DREAM, baseUrl = "local://x"),
            imageRequest(settings = null),
        ).toList()
        assertTrue(chunks.filterIsInstance<GenerateChunk.Failed>().isNotEmpty())
    }
}
