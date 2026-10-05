package app.tellev.core.prompt

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptOptimizerTest {

    // ── parser ────────────────────────────────────────────────────────────

    @Test
    fun parserAcceptsPlainFencedAndWrappedJson() {
        assertEquals("更好", PromptOptimizationParser.parse("{\"optimized\":\"更好\",\"notes\":\"\"}")!!.optimized)
        assertEquals(
            "更好",
            PromptOptimizationParser.parse("```json\n{\"optimized\":\"更好\",\"notes\":\"n\"}\n```")!!.optimized,
        )
        val wrapped = PromptOptimizationParser.parse("好的，结果如下：{\"optimized\":\"更好\"} 请查收")!!
        assertEquals("更好", wrapped.optimized)
        assertEquals("n", PromptOptimizationParser.parse("{\"optimized\":\"更好\",\"notes\":\"n\"}")!!.notes)
    }

    @Test
    fun parserRejectsMissingBlankOrNonPrimitiveOptimized() {
        assertNull(PromptOptimizationParser.parse("{\"notes\":\"x\"}"))
        assertNull(PromptOptimizationParser.parse("{\"optimized\":\"\"}"))
        assertNull(PromptOptimizationParser.parse("{\"optimized\":{\"text\":\"x\"}}"))
        assertNull(PromptOptimizationParser.parse("没有 JSON 的普通回复"))
        assertNull(PromptOptimizationParser.parse(""))
    }

    // ── fake adapter ──────────────────────────────────────────────────────

    private class FakeAdapter(
        private val replies: List<GenerateChunk>,
    ) : ProviderAdapter {
        val requests = mutableListOf<GenerateRequest>()
        private var call = 0

        override val id = "openai-compatible"
        override val displayName = "Fake"
        override val capabilities = setOf(ProviderCapability.Chat)

        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
        override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()

        // One generation per request: the n-th call replays the n-th reply
        // (the last one repeats), modelling a stateful provider session.
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
            requests += request
            val reply = replies[call.coerceAtMost(replies.lastIndex)]
            call++
            return flowOf(reply)
        }
    }

    private fun config() = ProviderConfig(providerType = "openai-compatible", baseUrl = "https://x", model = "m")
    private fun registry(adapter: ProviderAdapter) = ProviderRegistry(listOf(adapter))
    private fun completed(text: String) = GenerateChunk.Completed(text, "stop")

    // ── optimizer behavior ────────────────────────────────────────────────

    @Test
    fun successReturnsOptimizedTextAndNotes() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("{\"optimized\":\"优化后的提示词\",\"notes\":\"更清晰\"}")))
        val result = PromptOptimizer().optimize(
            input = "原始提示词", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertTrue(result.ok)
        assertEquals("优化后的提示词", result.optimized)
        assertEquals("更清晰", result.notes)
        assertEquals(0, result.repairRounds)
    }

    @Test
    fun blankInputFailsWithoutHittingProvider() = runBlocking {
        val adapter = FakeAdapter(emptyList())
        val result = PromptOptimizer().optimize(
            input = "   ", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertFalse(result.ok)
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun reasoningOnlyReplyFailsWithExplicitWarning() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("<think>思考过程而已</think>")))
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertFalse(result.ok)
        assertTrue(result.warnings.any { it.contains("思考") })
    }

    @Test
    fun failedChunkSurfacesCodeAndMessage() = runBlocking {
        val adapter = FakeAdapter(listOf(GenerateChunk.Failed(TellevError("provider_http_500", "boom"))))
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertFalse(result.ok)
        assertTrue(result.warnings.any { it.contains("provider_http_500") && it.contains("boom") })
    }

    @Test
    fun emptyCompletionIsAFailureNotAnEmptyDraft() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("")))
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertFalse(result.ok)
    }

    @Test
    fun garbageFirstReplyIsRepairedExactlyOnce() = runBlocking {
        val adapter = FakeAdapter(listOf(
            completed("我觉得可以直接用原文，不需要 JSON。"),
            completed("""{"optimized":"修复后的版本"}"""),
        ))
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertTrue(result.ok)
        assertEquals("修复后的版本", result.optimized)
        assertEquals(1, result.repairRounds)
        assertEquals(2, adapter.requests.size)
    }

    @Test
    fun unrepairableReplyStopsAtTheBound() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("no"), completed("still no")))
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = adapter,
        )
        assertFalse(result.ok)
        assertEquals(1, result.repairRounds)
        assertEquals(2, adapter.requests.size)
    }

    // ── request contract ─────────────────────────────────────────────────

    @Test
    fun requestCarriesOptimizerContractAndClampedBudget() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("{\"optimized\":\"x\"}")))
        val preset = GenerationPreset(
            id = "p", name = "p", providerType = "openai-compatible",
            temperature = 0.9, maxCompletionTokens = 200,
        )
        PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(mode = PromptOptimizationMode.Condense),
            config = config(), adapter = adapter, preset = preset,
        )
        val request = adapter.requests.single()
        assertTrue(request.metadata["tellev_prompt_optimizer"]!!.jsonPrimitive.boolean)
        // Budget clamped: preset asked 200, the floor is 512.
        assertEquals(512, request.prompt.maxTokens!!)
        // Optimizer owns sampling for the request; preset temperature is overridden.
        assertEquals(0.4, request.preset.temperature!!, 1e-9)
        // No chat history: exactly one system + one user message.
        assertEquals(2, request.prompt.messages.size)
        assertEquals(MessageRole.System, request.prompt.messages[0].role)
        assertEquals(MessageRole.User, request.prompt.messages[1].role)
        assertTrue(request.prompt.messages[0].content.contains("\"optimized\""))
        assertTrue(request.prompt.messages[1].content.contains("condense"))
        assertTrue(request.prompt.messages[1].content.contains("<draft>"))
        assertTrue(request.prompt.messages[1].content.contains("草稿"))
    }

    @Test
    fun macroAndInstructionOptionsReachThePrompt() = runBlocking {
        val adapter = FakeAdapter(listOf(completed("{\"optimized\":\"x\"}")))
        PromptOptimizer().optimize(
            input = "带 {{char}} 的草稿",
            options = PromptOptimizationOptions(
                mode = PromptOptimizationMode.Expand,
                allowDetail = true,
                languageHint = "简体中文",
                instruction = "强调雨夜氛围",
            ),
            config = config(), adapter = adapter,
        )
        val userText = adapter.requests.single().prompt.messages[1].content
        assertTrue(userText.contains("expand"))
        assertTrue(userText.contains("简体中文"))
        assertTrue(userText.contains("强调雨夜氛围"))
        assertTrue(userText.contains("{{char}}"))
        assertTrue(adapter.requests.single().prompt.messages[0].content.contains("verbatim"))
    }

    @Test
    fun streamingPreviewAccumulatesDeltas() = runBlocking {
        val streaming = object : ProviderAdapter {
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
                emit(GenerateChunk.Delta("{\"optim"))
                emit(GenerateChunk.Delta("ized\":\"预览\"}"))
                emit(completed("""{"optimized":"最终"}"""))
            }
        }
        var lastPreview = ""
        val result = PromptOptimizer().optimize(
            input = "草稿", options = PromptOptimizationOptions(),
            config = config(), adapter = streaming,
            onPreview = { lastPreview = it },
        )
        assertTrue(result.ok)
        assertEquals("最终", result.optimized)
        assertEquals("{\"optimized\":\"预览\"}", lastPreview)
    }
}
