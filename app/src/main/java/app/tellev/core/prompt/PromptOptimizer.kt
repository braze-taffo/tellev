package app.tellev.core.prompt

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Side-effect-free draft optimization shared by the chat input bar and the
 * creation conversation. Unlike chat generation this never touches sessions,
 * extension events, or the prompt engine — it builds one standalone request
 * against the CURRENT chat provider/preset and returns a result the caller
 * may apply to a draft. The caller always keeps the original text; a failed
 * run can only ever return [PromptOptimizationResult.failed].
 */
class PromptOptimizer {

    companion object {
        /** Optimization output is a rewritten draft, not an essay. */
        internal const val MAX_OUTPUT_TOKENS = 4_096

        /** One bounded format-repair round; never an unbounded loop. */
        internal const val MAX_REPAIR_ROUNDS = 1


        private val repairSystem = buildString {
            appendLine("你的上一条回复不是要求的 JSON 对象。只回复一个 JSON 对象：")
            appendLine("""{"optimized": "<优化后的完整提示词>", "notes": "<一句改动说明，可为空>"}""")
        }
    }

    /**
     * Runs one optimization. [adapter] is resolved by the caller from the
     * current chat runtime so transport behavior (HTTP client, retries,
     * cleartext policy) stays identical to chat.
     */
    suspend fun optimize(
        input: String,
        options: PromptOptimizationOptions,
        config: ProviderConfig,
        adapter: ProviderAdapter,
        preset: GenerationPreset? = null,
        onPreview: ((String) -> Unit)? = null,
    ): PromptOptimizationResult {
        if (input.isBlank()) {
            return PromptOptimizationResult.failed(listOf("输入为空，未优化"))
        }
        val outputBudget = preset?.maxCompletionTokens
            ?.coerceIn(512, MAX_OUTPUT_TOKENS)
            ?: preset?.maxTokens?.coerceIn(512, MAX_OUTPUT_TOKENS)
            ?: MAX_OUTPUT_TOKENS
        val warnings = mutableListOf<String>()
        var repairRounds = 0

        // 策略模板（linshenkx/prompt-optimizer 复刻）：完整结构系统提示词 +
        // JSON 证据包裹；空 strategyId 的旧调用方回落 mode 映射。
        val strategyId = options.strategyId.ifBlank {
            PromptOptimizationOptions.strategyFor(options.mode)
        }
        val first = request(
            messages = PromptOptimizationStrategies.messages(
                strategyId = strategyId,
                draft = input,
                languageHint = options.languageHint,
                instruction = options.instruction,
                basePrompt = options.basePrompt,
                iterateInput = options.iterateInput.take(PromptOptimizationOptions.MAX_ITERATE_CHARS),
            ),
            temperature = 0.4,
            maxTokens = outputBudget,
            config = config,
            adapter = adapter,
            preset = preset,
            onPreview = onPreview,
        )
        first.failure?.let { return PromptOptimizationResult.failed(listOf(it), repairRounds) }
        val parsedFirst = PromptOptimizationParser.parse(first.text)
        if (parsedFirst != null) {
            return PromptOptimizationResult(parsedFirst.optimized, parsedFirst.notes)
        }
        warnings += "首次回复不是要求的 JSON 结构"

        while (repairRounds < MAX_REPAIR_ROUNDS) {
            repairRounds++
            val repaired = request(
                messages = listOf(
                    PromptMessage(MessageRole.System, content = repairSystem),
                    PromptMessage(MessageRole.User, content = first.text.take(12_000)),
                ),
                temperature = 0.0,
                maxTokens = outputBudget,
                config = config,
                adapter = adapter,
                preset = preset,
                onPreview = null,
            )
            repaired.failure?.let { return PromptOptimizationResult.failed(warnings + it, repairRounds) }
            val parsed = PromptOptimizationParser.parse(repaired.text)
            if (parsed != null) {
                return PromptOptimizationResult(parsed.optimized, parsed.notes, repairRounds, warnings)
            }
        }
        return PromptOptimizationResult.failed(warnings + "格式修复后仍无法解析", repairRounds)
    }

    private class Generation(val text: String, val failure: String?)

    private suspend fun request(
        messages: List<PromptMessage>,
        temperature: Double,
        maxTokens: Int,
        config: ProviderConfig,
        adapter: ProviderAdapter,
        preset: GenerationPreset?,
        onPreview: ((String) -> Unit)?,
    ): Generation {
        val effectivePreset = (preset ?: GenerationPreset(
            id = "prompt-optimizer",
            name = "Prompt optimizer",
            providerType = config.providerType,
        )).copy(temperature = temperature)
        val request = GenerateRequest(
            prompt = PromptBuildResult(
                messages = messages,
                stop = emptyList(),
                maxTokens = maxTokens,
                providerType = config.providerType,
                diagnostics = PromptDiagnostics(emptyList()),
            ),
            preset = effectivePreset,
            stream = true,
            metadata = buildJsonObject {
                put("tellev_prompt_optimizer", true)
            },
        )
        var accumulated = ""
        var completed: String? = null
        var failure: TellevError? = null
        val flow: Flow<GenerateChunk> = adapter.streamGenerate(config, request)
        flow.collect { chunk ->
            when (chunk) {
                is GenerateChunk.Delta -> {
                    if (chunk.text.isNotEmpty()) {
                        accumulated += chunk.text
                        onPreview?.invoke(accumulated)
                    }
                }
                is GenerateChunk.Completed -> {
                    completed = chunk.text
                    failure = null
                }
                is GenerateChunk.Failed -> failure = chunk.error
            }
        }
        val error = failure
        if (error != null) {
            return Generation("", "请求失败（${error.code}）：${error.message.take(200)}")
        }
        val raw = completed?.takeIf(String::isNotBlank) ?: accumulated
        if (raw.isBlank()) return Generation("", "服务商未返回内容")
        // Reasoning-only replies (no body) are a known reasoning-model failure
        // mode; surface it instead of feeding <think> soup into the parser.
        val parts = MessageReasoning.fromResponse(raw, "")
        if (parts.body.isBlank()) return Generation("", "模型只返回了思考内容，没有正文")
        return Generation(parts.body, null)
    }
}
