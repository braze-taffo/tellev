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
 * Uses V2 spec format for JSON output.
 * Strips private fields (fav, chat) on export.
 */
class CharacterExporter(
    private val json: Json = FileStDataStore.defaultJson,
) {

    /**
     * Export a character card as a V2 spec JSON string.
     * Strips private fields: fav is set to false, chat is removed.
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
            if (rawData?.containsKey("character_version") != true) {
                put("character_version", "1.0")
            }
            put("tags", buildJsonArray {
                card.tags.forEach { add(JsonPrimitive(it)) }
            })
            if (rawData?.containsKey("system_prompt") != true) put("system_prompt", "")
            if (rawData?.containsKey("post_history_instructions") != true) put("post_history_instructions", "")
            if (rawData?.containsKey("alternate_greetings") != true) {
                putJsonArray("alternate_greetings") {
                    card.alternateGreetings.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (rawData?.containsKey("extensions") != true) putJsonObject("extensions") {}

            card.characterBook?.let { book ->
                put("character_book", exportCharacterBook(book.raw.takeIf { it.isNotEmpty() }, book))
            }

            put("fav", false)
        }

    private fun exportCharacterBook(rawBook: JsonObject?, book: app.tellev.core.model.WorldBook): JsonElement {
        if (rawBook != null) return rawBook

        return buildJsonObject {
            put("name", book.name)
            putJsonObject("entries") {
                book.entries.forEachIndexed { index, entry ->
                    putJsonObject(index.toString()) {
                        put("uid", entry.id.toIntOrNull() ?: index)
                        putJsonArray("key") {
                            entry.keys.forEach { add(JsonPrimitive(it)) }
                        }
                        putJsonArray("keysecondary") {
                            entry.secondaryKeys.forEach { add(JsonPrimitive(it)) }
                        }
                        put("comment", "")
                        put("content", entry.content)
                        put("constant", entry.constant)
                        put("selective", entry.selective)
                        put("order", entry.insertionOrder)
                        put("position", 0)
                        put("disable", !entry.enabled)
                        put("depth", entry.depth)
                        put("probability", 100)
                        put("useProbability", true)
                        put("group", "")
                        put("groupOverride", false)
                        put("groupWeight", 100)
                        put("sticky", 0)
                        put("cooldown", 0)
                        put("delay", 0)
                        put("displayIndex", index)
                        put("addMemo", true)
                        putJsonObject("extensions") {}
                    }
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
