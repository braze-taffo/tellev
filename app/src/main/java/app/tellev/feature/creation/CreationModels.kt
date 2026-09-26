package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
enum class CreationKind { Character, WorldBook }

@Serializable
data class CreationTurn(val role: String, val text: String)

@Serializable
data class CharacterDraft(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleMessages: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    /** Portable HTML/CSS fragment placed in the opening message after review. */
    val frontendHtml: String = "",
)

@Serializable
data class LoreDraft(
    val title: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
    val secondaryKeys: List<String> = emptyList(),
    val selective: Boolean = false,
    val constant: Boolean = false,
    val insertionOrder: Int = 100,
    val depth: Int = 4,
    val position: Int = 0,
    val probability: Int = 100,
    val matchWholeWords: Boolean = false,
    val sourceQuote: String = "",
    val sourceOffset: Int = -1,
    val sourceName: String = "",
    val sourceSha256: String = "",
    val note: String = "",
    /** Stable agent-facing identity ("L1", "L2", …); assigned lazily, never reused. */
    val id: String = "",
    /**
     * Imported entry captured as the merge base. System-managed: never sent to
     * the model, never writable through tools; advanced ST fields (recursion,
     * groups, entry raw) survive edits by copying from it.
     */
    val originalEntry: WorldBookEntry? = null,
)

@Serializable
data class CreationSession(
    val id: String = "creation_${UUID.randomUUID()}",
    val kind: CreationKind,
    val turns: List<CreationTurn> = emptyList(),
    val card: CharacterDraft = CharacterDraft(),
    /** SHA-256 of the separately stored PNG cover; empty for no cover. */
    val coverSha256: String = "",
    val worldName: String = "",
    val lore: List<LoreDraft> = emptyList(),
    val sourceName: String = "",
    val sourceSha256: String = "",
    /** Character offset after the last successfully extracted source chunk. */
    val sourceCursor: Int = 0,
    val sourceLength: Int = 0,
    val savedArtifactId: String = "",
    /** Complete tool calls were saved before this model turn ended; continue instead of replaying it. */
    val partialTurnSaved: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Monotonic lore-id counter: "L$n" numbers are never reused, even after
     * removals. 0 = unassigned; the toolbox derives it from current lore max.
     */
    val nextLoreNumber: Int = 0,
    /** Merge base for editing an existing stored card; keeps id/avatar/raw/extensions. */
    val originalCard: CharacterCard? = null,
    /** Merge base for editing an existing stored (or embedded) world book. */
    val originalBook: WorldBook? = null,
    /** Editable SillyTavern extension tree; unknown keys remain untouched. */
    val advancedExtensions: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromCharacter(card: CharacterCard): CreationSession {
            val data = card.raw["data"] as? JsonObject
            fun dataString(key: String): String =
                runCatching { data?.get(key)?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val book = card.characterBook
            return CreationSession(
                kind = CreationKind.Character,
                card = CharacterDraft(
                    name = card.name,
                    description = card.description,
                    personality = card.personality,
                    scenario = card.scenario,
                    firstMessage = card.firstMessage,
                    alternateGreetings = card.alternateGreetings,
                    exampleMessages = card.exampleMessages,
                    systemPrompt = dataString("system_prompt"),
                    postHistoryInstructions = dataString("post_history_instructions"),
                    creatorNotes = card.creatorNotes,
                    tags = card.tags,
                ),
                worldName = book?.name.orEmpty(),
                lore = book?.entries.orEmpty().map(WorldBookEntry::toLoreDraft),
                savedArtifactId = card.id,
                originalCard = card,
                originalBook = book,
                advancedExtensions = (data?.get("extensions") as? JsonObject) ?: JsonObject(emptyMap()),
            ).withAssignedLoreIds()
        }

        fun fromWorldBook(book: WorldBook): CreationSession = CreationSession(
            kind = CreationKind.WorldBook,
            worldName = book.name,
            lore = book.entries.map(WorldBookEntry::toLoreDraft),
            savedArtifactId = book.id,
            originalBook = book,
        ).withAssignedLoreIds()

        /** Start an independent world book using a card as source material. */
        fun worldBookFromCharacter(card: CharacterCard): CreationSession {
            val source = fromCharacter(card)
            return source.copy(
                id = "creation_${UUID.randomUUID()}",
                kind = CreationKind.WorldBook,
                worldName = card.characterBook?.name?.takeIf(String::isNotBlank)
                    ?: "${card.name}世界书",
                savedArtifactId = "",
                sourceName = card.name,
                // The source card remains available to read_card, while the
                // new book receives its own id on save.
            )
        }
    }
}

fun WorldBookEntry.toLoreDraft(): LoreDraft = LoreDraft(
    title = comment.ifBlank {
        keys.firstOrNull() ?: content.lineSequence().firstOrNull().orEmpty().take(24)
    },
    keys = keys,
    secondaryKeys = secondaryKeys,
    content = content,
    selective = selective && secondaryKeys.isNotEmpty(),
    constant = constant,
    insertionOrder = insertionOrder,
    depth = depth,
    position = position,
    probability = probability,
    matchWholeWords = matchWholeWords,
    originalEntry = this,
)

/** Blank ids come from sessions saved before ids existed; fill them with the smallest unused numbers. */
fun CreationSession.withAssignedLoreIds(): CreationSession {
    if (lore.all { it.id.isNotBlank() }) return this
    val used = lore.mapNotNull { it.id.removePrefix("L").toIntOrNull() }.toMutableSet()
    var next = 1
    val filled = lore.map { entry ->
        if (entry.id.isNotBlank()) entry else {
            while (next in used) next++
            used += next
            entry.copy(id = "L$next")
        }
    }
    return copy(
        lore = filled,
        nextLoreNumber = maxOf(nextLoreNumber, used.maxOrNull() ?: 0),
    )
}

/** All generated fields are kept in the standard V2 data object. */
fun CreationSession.toCharacterCard(): CharacterCard {
    require(kind == CreationKind.Character && card.name.isNotBlank())
    fun withFrontend(opening: String): String = listOf(opening.trim(), card.frontendHtml.trim())
        .filter { it.isNotBlank() }.joinToString("\n\n")
    val opening = withFrontend(card.firstMessage)
    // Both the typed card and raw.data must carry the same list: the exporter
    // lets raw.data shadow alternate_greetings, so a divergence would strip
    // the frontend fragment from the saved card.
    val greetings = card.alternateGreetings.map(::withFrontend)
    val base = originalCard
    val rawBeforeExtensions = if (base != null) mergedCardRaw(base.raw, card, opening, greetings) else buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", buildJsonObject {
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            put("creator", "Tellev AI 协作创作")
            put("character_version", "1.0")
        })
    }
    val rawData = rawBeforeExtensions["data"] as? JsonObject ?: JsonObject(emptyMap())
    val extensions = advancedExtensions.takeIf { it.isNotEmpty() }
        ?: rawData["extensions"] as? JsonObject ?: JsonObject(emptyMap())
    val raw = JsonObject(rawBeforeExtensions + ("data" to JsonObject(rawData +
        ("extensions" to extensions))))
    return CharacterCard(
        id = savedArtifactId.ifBlank { "char_${UUID.randomUUID()}" },
        name = card.name.trim(),
        description = card.description,
        personality = card.personality,
        scenario = card.scenario,
        firstMessage = opening,
        alternateGreetings = greetings,
        exampleMessages = card.exampleMessages,
        creatorNotes = card.creatorNotes,
        tags = card.tags,
        avatarRelativePath = base?.avatarRelativePath,
        characterBook = if (lore.isEmpty()) null else toWorldBook(),
        raw = raw,
    )
}

/**
 * The exporter keeps raw values for alternate_greetings / system_prompt /
 * post_history_instructions / extensions instead of typed card fields, so an
 * edited card must carry its draft values inside raw.data or edits silently
 * revert on save. Top-level raw keys (spec, extensions, creator notes, …) and
 * unknown data keys pass through untouched.
 */
private fun mergedCardRaw(
    originalRaw: JsonObject,
    draft: CharacterDraft,
    opening: String,
    alternateGreetings: List<String>,
): JsonObject {
    val data = originalRaw["data"] as? JsonObject ?: JsonObject(emptyMap())
    val updatedData = JsonObject(buildMap {
        putAll(data)
        put("name", JsonPrimitive(draft.name.trim()))
        put("description", JsonPrimitive(draft.description))
        put("personality", JsonPrimitive(draft.personality))
        put("scenario", JsonPrimitive(draft.scenario))
        put("first_mes", JsonPrimitive(opening))
        put("mes_example", JsonPrimitive(draft.exampleMessages))
        put("creator_notes", JsonPrimitive(draft.creatorNotes))
        put("tags", JsonArray(draft.tags.map(::JsonPrimitive)))
        put("alternate_greetings", JsonArray(alternateGreetings.map(::JsonPrimitive)))
        put("system_prompt", JsonPrimitive(draft.systemPrompt))
        put("post_history_instructions", JsonPrimitive(draft.postHistoryInstructions))
    })
    return JsonObject(buildMap {
        putAll(originalRaw)
        put("data", updatedData)
        remove("chat")
    })
}

fun CreationSession.toWorldBook(): WorldBook = WorldBook(
    id = savedArtifactId.ifBlank { "wb_${UUID.randomUUID()}" },
    name = worldName.ifBlank { card.name.ifBlank { "新世界书" } },
    entries = lore.mapIndexed { index, item ->
        val original = item.originalEntry
        if (original != null) original.copy(
            keys = item.keys.map(String::trim).filter(String::isNotBlank).distinct(),
            secondaryKeys = item.secondaryKeys.map(String::trim).filter(String::isNotBlank).distinct(),
            content = item.content.trim(),
            constant = item.constant,
            selective = item.selective && item.secondaryKeys.isNotEmpty(),
            insertionOrder = item.insertionOrder,
            depth = item.depth,
            position = item.position,
            probability = item.probability.coerceIn(0, 100),
            matchWholeWords = item.matchWholeWords,
            comment = item.title,
        ) else WorldBookEntry(
            // "n" prefix: numeric ids collide with imported entries' original ids.
            id = "n$index",
            keys = item.keys.map(String::trim).filter(String::isNotBlank).distinct(),
            secondaryKeys = item.secondaryKeys.map(String::trim).filter(String::isNotBlank).distinct(),
            content = item.content.trim(),
            constant = item.constant,
            selective = item.selective && item.secondaryKeys.isNotEmpty(),
            comment = item.title,
            insertionOrder = item.insertionOrder,
            depth = item.depth,
            position = item.position,
            probability = item.probability.coerceIn(0, 100),
            matchWholeWords = item.matchWholeWords,
            raw = buildJsonObject {
                put("comment", item.title)
                put("extensions", JsonObject(emptyMap()))
            },
        )
    },
    // Only standalone world books may keep book-level raw: the character
    // exporter short-circuits on non-empty book.raw and would freeze edits.
    raw = if (kind == CreationKind.WorldBook) originalBook?.raw ?: JsonObject(emptyMap()) else JsonObject(emptyMap()),
)

/** No JavaScript, external assets, or Tavern-only helper APIs in a portable opening panel. */
fun portableFrontendIssues(html: String): List<String> = buildList {
    if (html.isBlank()) return@buildList
    val forbidden = listOf(
        Regex("<\\s*script\\b", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_script),
        Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_inline_event),
        Regex("<\\s*(iframe|object|embed|link|form|img|video|audio|canvas|svg|math|meta|base|input|button|textarea|select|template)\\b", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_forbidden_tag),
        Regex("\\b(src|href|srcset|action)\\s*=", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_external_ref),
        Regex("@import\\b|url\\s*\\(|expression\\s*\\(|behavior\\s*:", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_dynamic_style),
        Regex("\\{\\{", RegexOption.IGNORE_CASE) to UiStrings.get(S.crmodels_frontend_template_var),
    )
    forbidden.forEach { (pattern, reason) -> if (pattern.containsMatchIn(html)) add(reason) }
}
