package app.tellev.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
    // ── ST-Prompt-Template extension arrays (preserved for round-trip) ──
    // Per-swipe variable snapshots, evaluation flags, and init flags.
    // Defaults are empty so legacy jsonl without these fields still parses.
    val variables: List<JsonObject> = emptyList(),
    val isEjsProcessed: List<Boolean> = emptyList(),
    val variablesInitialized: List<Boolean> = emptyList(),
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val characterId: String?,
    val groupId: String?,
    val messages: List<ChatMessage>,
    val metadata: JsonObject = buildJsonObject { },
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
data class GenerationPreset(
    val id: String,
    val name: String,
    val providerType: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val maxTokens: Int? = null,
    val stop: List<String> = emptyList(),
    val raw: JsonObject = buildJsonObject { },
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
    val selective: Boolean = false,
    val constant: Boolean = false,
    val priority: Int = 0,
    val insertionOrder: Int = 100,
    val depth: Int = 4,
    // ── SillyTavern world-info advanced fields ───────────────────────────
    /** world_info_position: 0=before,1=after,2=ANTop,3=ANBottom,4=atDepth,5=EMTop,6=EMBottom,7=outlet. */
    val position: Int = 0,
    /** 0-100. Only rolled when [useProbability] is true. */
    val probability: Int = 100,
    val useProbability: Boolean = false,
    /** world_info_logic: 0=AND_ANY,1=NOT_ALL,2=NOT_ANY,3=AND_ALL. Only used when [selective] is true. */
    val selectiveLogic: Int = 0,
    /** extension_prompt_roles: 0=SYSTEM,1=USER,2=ASSISTANT. Only meaningful at position=atDepth. */
    val role: Int = 0,
    val matchWholeWords: Boolean = false,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val comment: String = "",
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Boolean = false,
    val raw: JsonObject = buildJsonObject { },
)

@Serializable
data class TellevError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val causeType: String? = null,
)
