package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * The standard character blueprint: the canonical SillyTavern V2 card body
 * plus lore, written by the creation agent through the `write_blueprint` tool
 * and compiled back into the draft. Keys use the ST-native snake_case so the
 * exported JSON reads naturally next to `data.*`; accepted inputs also take
 * the draft's camelCase aliases (see [KEY_ALIASES]).
 *
 * Applied through [CharacterBlueprintCompiler.applyToSession]:
 * - card fields are overwritten wholesale (the blueprint is the full card);
 * - lore entries upsert by trimmed title (update in place, keep the merge
 *   base and agent id) and never delete entries absent from the blueprint;
 * - [coverPrompt] feeds the AI cover generator and round-trips through
 *   `data.extensions.tellev_standard_profile`.
 */
@Serializable
data class CharacterBlueprint(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMessage: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    @SerialName("mes_example") val exampleMessages: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("cover_prompt") val coverPrompt: String = "",
    val lore: List<BlueprintLore> = emptyList(),
) {
    companion object {
        val WRITABLE_KEYS = listOf(
            "name", "description", "personality", "scenario", "first_mes", "alternate_greetings",
            "mes_example", "system_prompt", "post_history_instructions", "creator_notes",
            "tags", "cover_prompt", "lore",
        )

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJsonOrNull(text: String): CharacterBlueprint? = runCatching {
            json.decodeFromString<CharacterBlueprint>(text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        }.getOrNull()
    }
}

/** One world-book entry inside a blueprint; fields mirror [LoreDraft]. */
@Serializable
data class BlueprintLore(
    val title: String = "",
    val keys: List<String> = emptyList(),
    @SerialName("secondary_keys") val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val constant: Boolean = false,
    val selective: Boolean = false,
    @SerialName("insertion_order") val insertionOrder: Int = 100,
    val depth: Int = 4,
    val position: Int = 0,
    val probability: Int = 100,
    @SerialName("match_whole_words") val matchWholeWords: Boolean = false,
)

/**
 * Compiles blueprints to and from the creation session. The session draft is
 * the single source of truth: the blueprint stored under
 * [EXTENSION_KEY] is regenerated at export time, so it can never go stale
 * against later edits, and third-party extension keys pass through untouched.
 */
internal object CharacterBlueprintCompiler {
    /** `data.extensions` key carrying the compiled blueprint on exported cards. */
    const val EXTENSION_KEY = "tellev_standard_profile"

    private val decodeJson = Json { ignoreUnknownKeys = false }
    private val encodeJson = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    /** camelCase / ST-alias inputs accepted on top of the snake_case protocol. */
    private val KEY_ALIASES = mapOf(
        "first_message" to "first_mes",
        "exampleMessages" to "mes_example",
        "systemPrompt" to "system_prompt",
        "postHistoryInstructions" to "post_history_instructions",
        "creatorNotes" to "creator_notes",
        "coverPrompt" to "cover_prompt",
        "greetings" to "alternate_greetings",
        "secondaryKeys" to "secondary_keys",
        "keysecondary" to "secondary_keys",
        "insertionOrder" to "insertion_order",
        "matchWholeWords" to "match_whole_words",
    )

    /** Unknown top-level keys are rejected with the accepted list, mirroring set_card_fields. */
    internal fun fromArguments(arguments: JsonObject): CharacterBlueprint {
        val normalized = JsonObject(buildMap {
            arguments.forEach { (key, value) ->
                if (key !in KEY_ALIASES) put(key, value)
            }
            KEY_ALIASES.forEach { (alias, target) ->
                val value = arguments[alias]
                if (value != null && arguments[target] == null) put(target, value)
            }
        })
        val unknown = normalized.keys - CharacterBlueprint.WRITABLE_KEYS.toSet()
        if (unknown.isNotEmpty()) {
            error(UiStrings.get(S.creng_blueprint_unknown_fields,
                unknown.sorted().joinToString(", "), CharacterBlueprint.WRITABLE_KEYS.joinToString(", ")))
        }
        return runCatching { decodeJson.decodeFromJsonElement(CharacterBlueprint.serializer(), normalized) }
            .getOrElse { e ->
                error(UiStrings.get(S.creng_blueprint_invalid, e.message ?: e.javaClass.simpleName))
            }
    }

    /**
     * Applies [blueprint] to the session: card fields wholesale, lore upsert
     * by title, agent lore ids preserved. Returns the counts payload for the
     * tool result.
     */
    internal fun applyToSession(session: CreationSession, blueprint: CharacterBlueprint): CreationSession {
        val card = session.card.copy(
            name = blueprint.name.trim(),
            description = blueprint.description,
            personality = blueprint.personality,
            scenario = blueprint.scenario,
            firstMessage = blueprint.firstMessage,
            alternateGreetings = blueprint.alternateGreetings,
            exampleMessages = blueprint.exampleMessages,
            systemPrompt = blueprint.systemPrompt,
            postHistoryInstructions = blueprint.postHistoryInstructions,
            creatorNotes = blueprint.creatorNotes,
            tags = blueprint.tags,
            coverPrompt = blueprint.coverPrompt,
        )
        var created = 0
        var updated = 0
        var nextNumber = session.nextLoreNumber.takeIf { it > 0 }
            ?: session.lore.mapNotNull { it.id.removePrefix("L").toIntOrNull() }.maxOrNull() ?: 0
        val lore = session.lore.toMutableList()
        for (entry in blueprint.lore) {
            val index = lore.indexOfFirst {
                it.title.trim().equals(entry.title.trim(), ignoreCase = true) && entry.title.isNotBlank()
            }
            if (index >= 0) {
                val existing = lore[index]
                lore[index] = existing.copy(
                    keys = entry.keys,
                    secondaryKeys = entry.secondaryKeys,
                    content = entry.content,
                    constant = entry.constant,
                    selective = entry.selective && entry.secondaryKeys.isNotEmpty(),
                    insertionOrder = entry.insertionOrder,
                    depth = entry.depth,
                    position = entry.position,
                    probability = entry.probability.coerceIn(0, 100),
                    matchWholeWords = entry.matchWholeWords,
                )
                updated++
            } else {
                nextNumber += 1
                lore += LoreDraft(
                    id = "L$nextNumber",
                    title = entry.title,
                    keys = entry.keys,
                    secondaryKeys = entry.secondaryKeys,
                    content = entry.content,
                    constant = entry.constant,
                    selective = entry.selective && entry.secondaryKeys.isNotEmpty(),
                    insertionOrder = entry.insertionOrder,
                    depth = entry.depth,
                    position = entry.position,
                    probability = entry.probability.coerceIn(0, 100),
                    matchWholeWords = entry.matchWholeWords,
                )
                created++
            }
        }
        return session.copy(
            card = card,
            lore = lore,
            nextLoreNumber = nextNumber,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** The blueprint compiled from the current draft + lore (export form). */
    internal fun compile(session: CreationSession): JsonObject = buildJsonObject {
        put("name", session.card.name.trim())
        put("description", session.card.description)
        put("personality", session.card.personality)
        put("scenario", session.card.scenario)
        put("first_mes", session.card.firstMessage)
        put("alternate_greetings", JsonArray(session.card.alternateGreetings.map(::JsonPrimitive)))
        put("mes_example", session.card.exampleMessages)
        put("system_prompt", session.card.systemPrompt)
        put("post_history_instructions", session.card.postHistoryInstructions)
        put("creator_notes", session.card.creatorNotes)
        put("tags", JsonArray(session.card.tags.map(::JsonPrimitive)))
        put("cover_prompt", session.card.coverPrompt)
        put("lore", JsonArray(session.lore.map { entry ->
            buildJsonObject {
                put("title", entry.title)
                put("keys", JsonArray(entry.keys.map(::JsonPrimitive)))
                put("secondary_keys", JsonArray(entry.secondaryKeys.map(::JsonPrimitive)))
                put("content", entry.content)
                put("constant", entry.constant)
                put("selective", entry.selective && entry.secondaryKeys.isNotEmpty())
                put("insertion_order", entry.insertionOrder)
                put("depth", entry.depth)
                put("position", entry.position)
                put("probability", entry.probability)
                put("match_whole_words", entry.matchWholeWords)
            }
        }))
    }

    /** Pretty JSON for the blueprint copy button; used by the UI only. */
    internal fun compileToJson(session: CreationSession): String =
        encodeJson.encodeToString(JsonObject.serializer(), compile(session))
}

/**
 * Asks the agent to consolidate the finished card into one standard blueprint
 * (shown as a canned creation-conversation request). Language-neutral enough
 * for the agent, user-visible in the input box before sending.
 */
internal fun blueprintRequestPrompt(): String = UiStrings.get(S.creng_blueprint_request)

/** Counts payload for the write_blueprint tool result. */
internal fun blueprintAppliedPayload(created: Int, updated: Int, kept: Int): JsonObject = buildJsonObject {
    put("applied", JsonArray(listOf(JsonPrimitive("blueprint"))))
    put("lore_created", JsonPrimitive(created))
    put("lore_updated", JsonPrimitive(updated))
    put("lore_kept", JsonPrimitive(kept))
}
