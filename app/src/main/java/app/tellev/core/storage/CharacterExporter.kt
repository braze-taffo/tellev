package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.security.SensitiveFieldScanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Exports character cards to JSON or PNG format.
 * Creates V2 cards and preserves the spec of imported cards.
 * Removes the chat field on export; preserves the user's `fav` flag.
 */
class CharacterExporter(
    private val json: Json = FileStDataStore.defaultJson,
) {

    /**
     * Export a character card as a SillyTavern-compatible JSON string.
     * Removes the chat field; preserves the original `fav` flag.
     */
    fun exportToJson(card: CharacterCard): String {
        val exportData = buildExportObject(card)

        // Sanitize sensitive fields before export
        val sanitizedData = SensitiveFieldScanner.sanitize(exportData)

        return json.encodeToString(JsonObject.serializer(), sanitizedData)
    }

    private fun buildExportObject(card: CharacterCard): JsonObject {
        val rawData = card.raw["data"] as? JsonObject

        return buildJsonObject {
            for ((key, value) in card.raw) {
                if (key != "data" && key != "chat") {
                    put(key, value)
                }
            }
            put("spec", card.raw["spec"] ?: JsonPrimitive("chara_card_v2"))
            put("spec_version", card.raw["spec_version"] ?: JsonPrimitive("2.0"))
            // SillyTavern writes these legacy mirrors alongside data for older readers.
            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.exampleMessages)
            put("creatorcomment", card.creatorNotes)
            put("tags", buildJsonArray { card.tags.forEach { add(JsonPrimitive(it)) } })
            if (card.raw.containsKey("creator_notes")) put("creator_notes", card.creatorNotes)
            if (card.raw.containsKey("alternate_greetings")) {
                putJsonArray("alternate_greetings") {
                    card.alternateGreetings.forEach { add(JsonPrimitive(it)) }
                }
            }
            put("data", buildDataObject(card, rawData))
        }
    }

    private fun buildDataObject(card: CharacterCard, rawData: JsonObject?): JsonObject =
        buildJsonObject {
            if (rawData != null) {
                for ((key, value) in rawData) {
                    if (key != "chat") put(key, value)
                }
            }

            put("name", card.name)
            put("description", card.description)
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.firstMessage)
            put("mes_example", card.exampleMessages)
            put("creator_notes", card.creatorNotes)
            // A raw card supplied by an extension may omit the typed fields.
            // Keep its existing values until the editor explicitly changes them.
            fun field(key: String, value: String): JsonElement =
                if (value.isEmpty()) rawData?.get(key) ?: JsonPrimitive("") else JsonPrimitive(value)
            put("system_prompt", field("system_prompt", card.systemPrompt))
            put("post_history_instructions", field("post_history_instructions", card.postHistoryInstructions))
            put("creator", field("creator", card.creator))
            put("character_version", field("character_version", card.characterVersion))
            put("tags", buildJsonArray {
                card.tags.forEach { add(JsonPrimitive(it)) }
            })
            putJsonArray("alternate_greetings") {
                card.alternateGreetings.forEach { add(JsonPrimitive(it)) }
            }
            if (rawData?.containsKey("extensions") != true) putJsonObject("extensions") {}

            card.characterBook?.let { book ->
                put("character_book", exportCharacterBook(book.raw.takeIf { it.isNotEmpty() }, book))
            }

            // Preserve the user's favorite flag from the original card data
            // instead of unconditionally clearing it on every save/export.
            put("fav", rawData?.get("fav") ?: JsonPrimitive(false))
        }

    private fun exportCharacterBook(rawBook: JsonObject?, book: app.tellev.core.model.WorldBook): JsonElement {
        if (rawBook != null) return rawBook

        // Imported entries keep their original raw; rebuilding from scratch would
        // drop ST fields tellev does not model (sticky/cooldown/delay, entry
        // extensions, …). raw is the base, typed fields overlay it so edits win.
        val usedUids = mutableSetOf<Int>()
        book.entries.forEach { entry ->
            entry.id.toIntOrNull()?.let { usedUids += it }
            (entry.raw["uid"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { usedUids += it }
        }
        var nextUid = (usedUids.maxOrNull() ?: -1) + 1

        return buildJsonObject {
            put("name", book.name)
            putJsonObject("entries") {
                book.entries.forEachIndexed { index, entry ->
                    val merged = mutableMapOf<String, JsonElement>()
                    merged.putAll(entry.raw)
                    merged["uid"] = JsonPrimitive(entry.id.toIntOrNull() ?: nextUid++)
                    merged["key"] = buildJsonArray { entry.keys.forEach { add(JsonPrimitive(it)) } }
                    merged["keysecondary"] = buildJsonArray { entry.secondaryKeys.forEach { add(JsonPrimitive(it)) } }
                    merged["comment"] = JsonPrimitive(entry.comment)
                    merged["content"] = JsonPrimitive(entry.content)
                    merged["constant"] = JsonPrimitive(entry.constant)
                    merged["selective"] = JsonPrimitive(entry.selective)
                    merged["order"] = JsonPrimitive(entry.insertionOrder)
                    merged["position"] = JsonPrimitive(entry.position)
                    merged["disable"] = JsonPrimitive(!entry.enabled)
                    merged["depth"] = JsonPrimitive(entry.depth)
                    merged["probability"] = JsonPrimitive(entry.probability)
                    merged["useProbability"] = JsonPrimitive(entry.useProbability)
                    merged["selectiveLogic"] = JsonPrimitive(entry.selectiveLogic)
                    merged["role"] = JsonPrimitive(entry.role)
                    merged["matchWholeWords"] = JsonPrimitive(entry.matchWholeWords)
                    merged["useRegex"] = JsonPrimitive(entry.useRegex)
                    merged["caseSensitive"] = JsonPrimitive(entry.caseSensitive)
                    merged["excludeRecursion"] = JsonPrimitive(entry.excludeRecursion)
                    merged["preventRecursion"] = JsonPrimitive(entry.preventRecursion)
                    merged["delayUntilRecursion"] = JsonPrimitive(entry.delayUntilRecursion)
                    merged["ignoreBudget"] = JsonPrimitive(entry.ignoreBudget)
                    merged["priority"] = JsonPrimitive(entry.priority)
                    merged["group"] = JsonPrimitive(entry.group)
                    merged["groupOverride"] = JsonPrimitive(entry.groupOverride)
                    merged["groupWeight"] = JsonPrimitive(entry.groupWeight)
                    merged["useGroupScoring"] = JsonPrimitive(entry.useGroupScoring)
                    if (!merged.containsKey("sticky")) merged["sticky"] = JsonPrimitive(0)
                    if (!merged.containsKey("cooldown")) merged["cooldown"] = JsonPrimitive(0)
                    if (!merged.containsKey("delay")) merged["delay"] = JsonPrimitive(0)
                    if (!merged.containsKey("displayIndex")) merged["displayIndex"] = JsonPrimitive(index)
                    if (!merged.containsKey("addMemo")) merged["addMemo"] = JsonPrimitive(true)
                    if (!merged.containsKey("extensions")) merged["extensions"] = buildJsonObject { }
                    put(index.toString(), JsonObject(merged))
                }
            }
        }
    }

    /**
     * Export a character card embedded into a PNG image.
     * Uses the provided PNG bytes as a template and injects the V2 JSON into tEXt chunks.
     */
    fun exportToPng(card: CharacterCard, pngTemplateBytes: ByteArray): ByteArray {
        val jsonString = exportToJson(card)
        return PngCardParser.embedCardJson(pngTemplateBytes, jsonString)
    }
}
