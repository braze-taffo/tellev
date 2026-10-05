package app.tellev.core.prompt

import kotlinx.serialization.Serializable

/** What the optimizer should do with the draft text. */
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
