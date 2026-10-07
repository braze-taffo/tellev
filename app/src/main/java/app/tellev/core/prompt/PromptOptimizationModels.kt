package app.tellev.core.prompt

import kotlinx.serialization.Serializable

/**
 * Legacy mode labels. Retained so existing callers compile; the strategy
 * system ([PromptOptimizationStrategies]) is the live surface — a run maps a
 * strategy to one of these only when the caller still speaks in modes.
 */
@Serializable
enum class PromptOptimizationMode {
    /** Improve wording and flow without changing meaning or length much. */
    Polish,
    /** Enrich with concrete detail while keeping the original intent. */
    Expand,
    /** Tighten and shorten while preserving every constraint. */
    Condense,
    /** Rewrite as an in-character instruction set for roleplay prompts. */
    Roleplay,
    /** Restructure into clearly labeled sections for complex requests. */
    Structured,
}

/** User-controllable constraints for one optimization run. */
data class PromptOptimizationOptions(
    val mode: PromptOptimizationMode = PromptOptimizationMode.Polish,
    /**
     * Strategy id from [PromptOptimizationStrategies.ALL]; blank falls back to
     * the legacy mode mapping so old callers keep working.
     */
    val strategyId: String = "",
    /** Iteration requirement for the iterate strategy (merged, never executed). */
    val iterateInput: String = "",
    /** The last optimized text the iterate round starts from. */
    val basePrompt: String? = null,
    /** Facts, names and numbers in the input must survive unchanged. */
    val keepFacts: Boolean = true,
    /** Whether the model may add concrete details beyond the input. */
    val allowDetail: Boolean = false,
    /** Tavern macros ({{char}}, {{user}}, …) must be preserved verbatim. */
    val keepMacros: Boolean = true,
    /** BCP-47-ish hint for the output language; blank keeps the input language. */
    val languageHint: String = "",
    /** Free-form extra instruction from the user; capped before sending. */
    val instruction: String = "",
) {
    companion object {
        const val MAX_INSTRUCTION_CHARS = 2_000
        const val MAX_INPUT_CHARS = 32_000
        const val MAX_ITERATE_CHARS = 2_000

        /** Legacy mode → strategy id (for callers that still set only a mode). */
        fun strategyFor(mode: PromptOptimizationMode): String = when (mode) {
            PromptOptimizationMode.Polish -> "general"
            PromptOptimizationMode.Expand -> "professional"
            PromptOptimizationMode.Condense -> "condense"
            PromptOptimizationMode.Roleplay -> "general"
            PromptOptimizationMode.Structured -> "analytical"
        }
    }
}

/** One finished optimization attempt; [optimized] null means the input stays. */
data class PromptOptimizationResult(
    val optimized: String?,
    val notes: String = "",
    val repairRounds: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val ok: Boolean get() = optimized != null

    companion object {
        /** The original input is always the fallback — optimization never destroys a draft. */
        fun failed(warnings: List<String>, repairRounds: Int = 0): PromptOptimizationResult =
            PromptOptimizationResult(optimized = null, warnings = warnings, repairRounds = repairRounds)
    }
}
