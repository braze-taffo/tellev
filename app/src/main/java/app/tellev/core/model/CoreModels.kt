package app.tellev.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class MessageRole {
    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("character")
    Character,

    @SerialName("assistant")
    Assistant,

    @SerialName("tool")
    Tool,
}

@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val relativePath: String,
    val source: AttachmentSource = AttachmentSource.Chat,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
enum class AttachmentSource {
    Global,
    Character,
    Chat,
}

@Serializable
data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val name: String,
    val content: String,
    val createdAtMillis: Long,
    val isHidden: Boolean = false,
    val swipeIndex: Int = 0,
    val swipes: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val metadata: JsonObject = buildJsonObject { },
    /** Complete SillyTavern JSONL message object used for lossless round-trips. */
    val raw: JsonObject = buildJsonObject { },
    // ── ST-Prompt-Template extension arrays (preserved for round-trip) ──
    // Per-swipe variable snapshots, evaluation flags, and init flags.
    // Defaults are empty so legacy jsonl without these fields still parses.
    val variables: List<JsonElement> = emptyList(),
    /** Preserve null/unknown slots without shifting subsequent swipe identities. */
    val swipeInfo: List<JsonElement> = emptyList(),
    val isEjsProcessed: List<JsonElement> = emptyList(),
    val variablesInitialized: List<JsonElement> = emptyList(),
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val characterId: String?,
    val groupId: String?,
    val messages: List<ChatMessage>,
    val metadata: JsonObject = buildJsonObject { },
    /** Complete SillyTavern JSONL header object used for lossless round-trips. */
    val rawHeader: JsonObject = buildJsonObject { },
    /** Internal durable revision; exported Tavern fields remain unchanged. */
    @kotlinx.serialization.Transient val storageRevision: Long = 0,
)

@Serializable
data class CharacterCard(
    val id: String,
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleMessages: String = "",
    val creatorNotes: String = "",
    val avatarRelativePath: String? = null,
    val tags: List<String> = emptyList(),
    val characterBook: WorldBook? = null,
    val raw: JsonObject = buildJsonObject { },
)

@Serializable
data class CharacterSummary(
    val id: String,
    val name: String,
    val avatarRelativePath: String?,
    val tags: List<String> = emptyList(),
)

@Serializable
data class GroupChat(
    val id: String,
    val name: String,
    val memberCharacterIds: List<String>,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val avatarRelativePath: String? = null,
    val metadata: JsonObject = buildJsonObject { },
)
@Serializable
enum class PresetCategory {
    @SerialName("openai") OpenAi,
    @SerialName("textgen") TextGen,
    @SerialName("kobold") Kobold,
    @SerialName("novelai") NovelAi,
}

@Serializable
data class PresetKey(
    val category: PresetCategory,
    val fileStem: String,
)

@Serializable
data class PresetPrompt(
    val identifier: String,
    val name: String = identifier,
    val role: String = "system",
    val content: String = "",
    val enabled: Boolean = true,
    val relative: Boolean = false,
    val depth: Int = 0,
    val order: Int = 0,
    /** ST injection_order (default 100): merge priority among same-depth chat injections. */
    val injectionOrder: Int = 100,
    /** ST forbid_overrides: block character-card main/jailbreak overrides for this slot. */
    val forbidOverrides: Boolean = false,
    val raw: JsonObject = buildJsonObject { },
)


@Serializable
data class GenerationPreset(
    val id: String,
    val name: String,
    val providerType: String,
    val category: PresetCategory = PresetCategory.OpenAi,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val topA: Double? = null,
    val minP: Double? = null,
    val repetitionPenalty: Double? = null,
    val repetitionPenaltyRange: Int? = null,
    val maxTokens: Int? = null,
    val maxContextTokens: Int? = null,
    val maxCompletionTokens: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Long? = null,
    val prompts: List<PresetPrompt> = emptyList(),
    val promptsUnused: List<PresetPrompt> = emptyList(),
    val extensions: JsonObject = buildJsonObject { },
    val stop: List<String> = emptyList(),
    val raw: JsonObject = buildJsonObject { },
)

/** Result of importing a raw ST preset. Routing fields are preserved but never applied. */
data class PresetImportResult(
    val preset: GenerationPreset,
    val inferredCategory: PresetCategory,
    val appliedFields: Set<String>,
    val preservedFields: Set<String>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class WorldBook(
    val id: String,
    val name: String,
    val entries: List<WorldBookEntry>,
    val raw: JsonObject = buildJsonObject { },
)

@Serializable
data class WorldBookEntry(
    val id: String,
    val keys: List<String>,
    val secondaryKeys: List<String> = emptyList(),
    val content: String,
    val enabled: Boolean = true,
    /** ST default is true (world-info.js newWorldInfoEntryDefinition). */
    val selective: Boolean = true,
    val constant: Boolean = false,
    val priority: Int = 0,
    val insertionOrder: Int = 100,
    val depth: Int = 4,
    // ── SillyTavern world-info advanced fields ───────────────────────────
    /** world_info_position: 0=before,1=after,2=ANTop,3=ANBottom,4=atDepth,5=EMTop,6=EMBottom,7=outlet. */
    val position: Int = 0,
    /** 0-100. Only rolled when [useProbability] is true. */
    val probability: Int = 100,
    /** ST default is true (world-info.js newWorldInfoEntryDefinition). */
    val useProbability: Boolean = true,
    /** world_info_logic: 0=AND_ANY,1=NOT_ALL,2=NOT_ANY,3=AND_ALL. Only used when [selective] is true. */
    val selectiveLogic: Int = 0,
    /** extension_prompt_roles: 0=SYSTEM,1=USER,2=ASSISTANT. Only meaningful at position=atDepth. */
    val role: Int = 0,
    val matchWholeWords: Boolean = false,
    /** tellev extension (not in ST): treat plain keys as regex. ST-style `/pattern/flags` keys are always detected per key. */
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val comment: String = "",
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    /** ST delayUntilRecursion is a number (recursion level); boolean true maps to 1. 0 = no delay. */
    val delayUntilRecursion: Int = 0,
    /** Include this entry even after the world-info token budget is exhausted. */
    val ignoreBudget: Boolean = false,
    // ── Inclusion groups (ST world-info.js filterByInclusionGroups) ──────
    /** Comma-separated group labels; at most one entry per group activates per scan. */
    val group: String = "",
    /** "Prioritize inclusion": win the group by insertion order instead of random roll. */
    val groupOverride: Boolean = false,
    /** Weight for the group's random roll (ST DEFAULT_WEIGHT = 100). */
    val groupWeight: Int = 100,
    /** Keep only the best key-match scorers in the group before rolling. */
    val useGroupScoring: Boolean = false,
    val raw: JsonObject = buildJsonObject { },
)

/** World-info scan settings, mirroring ST's world_info_* settings (world-info.js:69-82). */
@Serializable
data class WorldInfoSettings(
    /** ST world_info_recursive (default false): rescan using newly activated entries' content. */
    val recursive: Boolean = false,
    /** ST world_info_max_recursion_steps (default 0 = unlimited, only meaningful when recursive is on). */
    val maxRecursionSteps: Int = 0,
    /** Chat history depth scanned for keys. ST world_info_depth defaults to 2; tellev keeps its wider 12. */
    val scanDepth: Int = 12,
)

/**
 * Prompt assembly preferences, mirroring ST power_user settings that control
 * whether character-card-provided prompts override the global defaults.
 * Both default to true so variable cards work out of the box.
 */
@Serializable
data class PromptSettings(
    /** ST power_user.prefer_character_prompt: use the card's data.system_prompt when present. */
    val preferCharacterPrompt: Boolean = true,
    /** ST power_user.prefer_character_jailbreak: use the card's data.post_history_instructions when present. */
    val preferCharacterJailbreak: Boolean = true,
    /** Enable instruct mode formatting (for completion-style APIs like textgen/kobold). */
    val instructEnabled: Boolean = false,
    /** Instruct preset name (file stem in the instruct/ directory, or empty for built-in ChatML). */
    val instructPresetName: String = "",
)

@Serializable
data class TellevError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val causeType: String? = null,
)
