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

        private val MODE_LABELS = mapOf(
            PromptOptimizationMode.Polish to
                "polish (improve wording and flow; keep meaning and rough length)",
            PromptOptimizationMode.Expand to
                "expand (enrich with concrete detail; do not invent new plot-critical facts)",
            PromptOptimizationMode.Condense to
                "condense (tighten and shorten; every constraint must survive)",
            PromptOptimizationMode.Roleplay to
                "roleplay (rewrite as an in-character instruction set for a roleplay prompt)",
            PromptOptimizationMode.Structured to
                "structured (reorganize into clearly labeled sections)",
        )

        private fun systemInstruction(options: PromptOptimizationOptions): String = buildString {
            appendLine("You are a prompt editor inside a roleplay chat app. The user gives you a draft prompt text to optimize.")
            appendLine("Tasks:")
            appendLine("- Rewrite the DRAFT according to the requested mode and constraints.")
            appendLine("- Keep the draft's language unless an explicit output language is given.")
            appendLine("- Never answer the prompt, never roleplay in the reply, never add commentary outside the JSON.")
            if (options.keepFacts) appendLine("- Preserve every fact, name, number and explicit constraint exactly.")
            if (options.allowDetail) appendLine("- You may add concrete details that serve the draft's intent.")
            else appendLine("- Do not add information that is not implied by the draft.")
            if (options.keepMacros) appendLine("- Preserve template macros like {{char}}, {{user}}, {{random}}… verbatim, including their braces.")
            appendLine("Reply with ONLY one JSON object and nothing else:")
            appendLine("""{"optimized": "<the rewritten draft>", "notes": "<one short sentence, may be empty>"}""")
        }

        private fun userInstruction(input: String, options: PromptOptimizationOptions): String = buildString {
            appendLine("Mode: ${MODE_LABELS.getValue(options.mode)}")
            if (options.languageHint.isNotBlank()) appendLine("Output language: ${options.languageHint.take(60)}")
            if (options.instruction.isNotBlank()) {
                appendLine("Additional instruction from the user (follow unless it conflicts with the rules above):")
                appendLine(options.instruction.take(PromptOptimizationOptions.MAX_INSTRUCTION_CHARS))
            }
            appendLine("<draft>")
            append(input.take(PromptOptimizationOptions.MAX_INPUT_CHARS))
            append("\n</draft>")
        }

        private val repairSystem = buildString {
            appendLine("Your previous reply was not the required JSON object. Return ONLY one JSON object:")
            appendLine("""{"optimized": "<the rewritten draft>", "notes": "<one short sentence, may be empty>"}""")
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

        val first = request(
            messages = listOf(
                PromptMessage(MessageRole.System, content = systemInstruction(options)),
                PromptMessage(MessageRole.User, content = userInstruction(input, options)),
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
