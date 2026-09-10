package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal const val DEFAULT_MAX_CONTEXT_TOKENS = 1_000_000
internal const val DEFAULT_MAX_COMPLETION_TOKENS = 128 * 1_024

/** [PromptMessage.channel] of the main/system prompt — anchor for BEFORE_PROMPT/IN_PROMPT injections. */
internal const val CHANNEL_MAIN = "main"

/** [PromptMessage.channel] of chat-history (and pending user input) messages — anchor span for depth injections. */
internal const val CHANNEL_CHAT = "chat"

/** [PromptMessage.channel] of structural markers ('[Start a new Chat]', '[Example Chat]') —
 * excluded from the prompt-template numeric index space so [GENERATE:N]/@INJECT index=N
 * keep addressing "system prompt, then chat messages". */
internal const val CHANNEL_MARKER = "marker"

/** Internal injection position: standalone message after the chat span (fallback-path PHI). */
internal const val POSITION_AFTER_CHAT = 99

/** ST {{original}} macro inside character-card main/jailbreak overrides (PromptManager preparePrompt). */
internal val ORIGINAL_MACRO = Regex("\\{\\{original\\}\\}", RegexOption.IGNORE_CASE)

/** ST splits example messages into chats on `<START>` lines (case-insensitive). */
internal val EXAMPLE_CHAT_SPLIT = Regex("<START>", RegexOption.IGNORE_CASE)

@Serializable
data class PromptBuildRequest(
    val character: CharacterCard,
    val persona: Persona?,
    val messages: List<ChatMessage>,
    val worldBooks: List<WorldBook>,
    val preset: GenerationPreset,
    val userInput: String,
    val providerType: String,
    val metadata: JsonObject = buildJsonObject { },
    /** Background instruction, separate from chat history and pending user input (ST quiet generation). */
    val quietPrompt: String? = null,
)

@Serializable
data class PromptBuildResult(
    val messages: List<PromptMessage>,
    val stop: List<String>,
    val maxTokens: Int?,
    val providerType: String,
    val diagnostics: PromptDiagnostics,
    val promptTemplateVariableUpdates: PromptTemplateVariableUpdates = PromptTemplateVariableUpdates(),
)

@Serializable
data class PromptTemplateVariableSnapshot(
    val local: JsonObject = JsonObject(emptyMap()),
    val global: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class PromptMessage(
    val role: MessageRole,
    val name: String? = null,
    val content: String,
    /**
     * Which prompt slot this message belongs to. Depth injections anchor to the
     * span of "chat" messages and BEFORE_PROMPT/IN_PROMPT extension prompts
     * anchor to the "main" message, mirroring ST where injections splice into
     * the chat-history array (openai.js populationInjectionPrompts) and
     * relative extension prompts insert around the `main` prompt (injectToMain).
     */
    val channel: String? = null,
)

@Serializable
data class PromptDiagnostics(
    val activatedWorldEntryIds: List<String>,
    val estimatedTokenCount: Int? = null,
    val warnings: List<String> = emptyList(),
)

internal data class ExtensionInjection(
    val value: String,
    /** ST extension_prompt_types: -1 NONE, 0 IN_PROMPT, 1 IN_CHAT, 2 BEFORE_PROMPT; 99 internal AFTER_CHAT. */
    val position: Int,
    val depth: Int,
    val role: MessageRole,
    /** Arrival order; tiebreak within preset absolutes of one order group. */
    val order: Int,
    /** Extension-prompt key for ST's key-sorted joining (script.js:3250);
     * null marks a preset absolute prompt, which joins before keyed ones. */
    val key: String? = null,
    /** ST injection_order (default 100); groups merge ascending in the final prompt. */
    val orderGroup: Int = 100,
)

/** Result of preset prompt ordering: the relative (in-order) messages plus
 * absolute (injection_position=1) prompts that must be depth-injected into
 * the chat history alongside extension/WI injections, matching ST's
 * absolutePrompts → populationInjectionPrompts flow (openai.js:1239-1325). */
internal data class PresetOrderResult(
    val messages: List<PromptMessage>,
    val absoluteInjections: List<ExtensionInjection> = emptyList(),
)
