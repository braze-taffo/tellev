package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Imports character cards from SillyTavern-compatible formats:
 * JSON (V1, V2, V3, Gradio/Pygmalion), PNG, WebP, CHARX, and BYAF.
 */
class CharacterImporter(
    private val json: Json = FileStDataStore.defaultJson,
) {

    fun importFromBytes(bytes: ByteArray, fileName: String): CharacterCard {
        val format = detectFormat(bytes, fileName)
        return when (format) {
            "json" -> importFromJson(bytes.decodeUtf8Text())
            "png" -> importFromPng(bytes, fileName)
            "webp" -> importFromWebp(bytes, fileName)
            "charx" -> importFromCharx(bytes)
            "byaf" -> importFromByaf(bytes)
            "zip" -> tryImportAsZip(bytes) ?: error("No supported character archive found in: $fileName")
            else -> {
                tryImportAsJson(bytes)
                    ?: tryImportAsPng(bytes, fileName)
                    ?: tryImportAsWebp(bytes, fileName)
                    ?: tryImportAsZip(bytes)
                    ?: error("Unable to detect character format for: $fileName")
            }
        }
    }

    fun importFromJson(jsonString: String): CharacterCard {
        val raw = json.parseToJsonElement(jsonString.stripBom()).jsonObject
        return parseCharacterJsonObject(raw)
    }

    private fun importFromPng(bytes: ByteArray, fileName: String): CharacterCard {
        val cardJson = PngCardParser.extractCardJson(bytes)
            ?: error("No character card metadata found in PNG: $fileName")
        return parseCharacterJsonObject(cardJson)
    }

    private fun importFromWebp(bytes: ByteArray, fileName: String): CharacterCard {
        val cardJson = WebpCardParser.extractCardJson(bytes)
            ?: error("No character card metadata found in WebP: $fileName")
        return parseCharacterJsonObject(cardJson)
    }

    private fun importFromCharx(bytes: ByteArray): CharacterCard {
        val entries = readZipEntries(bytes)
        val cardJsonString = entries["card.json"]?.decodeUtf8Text()
            ?: error("No card.json found in CHARX archive")
        return importFromJson(cardJsonString)
    }

    private fun importFromByaf(bytes: ByteArray): CharacterCard {
        val entries = readZipEntries(bytes)
        val manifestJsonString = entries["manifest.json"]?.decodeUtf8Text()
            ?: error("No manifest.json found in BYAF archive")
        val manifest = json.parseToJsonElement(manifestJsonString).jsonObject
        val characters = manifest["characters"]?.jsonArray
            ?: error("No characters array in BYAF manifest")
        val firstCharacter = characters.firstOrNull()
            ?: error("Empty characters array in BYAF manifest")

        val cardObject = when (firstCharacter) {
            is JsonObject -> firstCharacter
            is JsonPrimitive -> {
                val characterPath = firstCharacter.contentOrBlank()
                    ?: error("Invalid BYAF character path")
                val characterJson = entries[normalizeZipEntryPath(characterPath)]?.decodeUtf8Text()
                    ?: error("Character JSON not found in BYAF archive: $characterPath")
                val character = json.parseToJsonElement(characterJson).jsonObject
                buildByafCard(manifest, character, readByafScenarios(manifest, entries))
            }
            else -> error("Invalid BYAF character entry")
        }

        val normalized = if (cardObject["spec"] == null) {
            buildLegacyByafCard(cardObject)
        } else {
            cardObject
        }
        return parseCharacterJsonObject(normalized)
    }

    internal fun parseCharacterJsonObject(raw: JsonObject): CharacterCard {
        val spec = raw.string("spec")

        return when {
            spec == "chara_card_v3" -> parseV2V3Card(raw, isV3 = true)
            spec == "chara_card_v2" -> parseV2V3Card(raw, isV3 = false)
            raw["char_name"] != null -> parseGradioCard(raw)
            raw["data"] != null -> parseV2V3Card(raw, isV3 = false)
            spec != null -> parseV2V3Card(raw, isV3 = false)
            else -> parseV1Card(raw)
        }
    }

    private fun parseV1Card(raw: JsonObject): CharacterCard {
        val name = raw.string("name") ?: "Unknown"
        return CharacterCard(
            id = name.sanitizeId(),
            name = name,
            description = raw.string("description").orEmpty(),
            personality = raw.string("personality").orEmpty(),
            scenario = raw.string("scenario").orEmpty(),
            firstMessage = raw.string("first_mes")
                ?: raw.string("firstMessage")
                ?: raw.string("first_message")
                ?: "",
            alternateGreetings = extractAlternateGreetings(raw),
            exampleMessages = raw.string("mes_example").orEmpty(),
            creatorNotes = raw.string("creator_notes")
                ?: raw.string("creatorcomment")
                ?: raw.string("creatorComment")
                ?: "",
            tags = extractTags(raw),
            raw = raw,
        )
    }

    private fun parseGradioCard(raw: JsonObject): CharacterCard {
        val name = raw.string("char_name") ?: raw.string("name") ?: "Unknown"
        return CharacterCard(
            id = name.sanitizeId(),
            name = name,
            description = raw.string("char_persona").orEmpty(),
            personality = raw.string("personality").orEmpty(),
            scenario = raw.string("world_scenario").orEmpty(),
            firstMessage = raw.string("char_greeting").orEmpty(),
            alternateGreetings = extractAlternateGreetings(raw),
            exampleMessages = raw.string("example_dialogue").orEmpty(),
            creatorNotes = raw.string("creator_notes")
                ?: raw.string("creatorcomment")
                ?: "",
            tags = extractTags(raw),
            raw = raw,
        )
    }

    private fun parseV2V3Card(raw: JsonObject, isV3: Boolean): CharacterCard {
        val data = raw["data"]?.asObjectOrNull() ?: raw
        val name = data.string("name") ?: raw.string("name") ?: "Unknown"
        val characterBook = parseCharacterBook(data)

        val tags = extractTags(data) + extractTags(raw)
        val creatorNotes = if (isV3) {
            data.string("creator_notes")
                ?: raw.string("creator_notes")
                ?: ""
        } else {
            data.string("creator_notes").orEmpty()
        }

        return CharacterCard(
            id = name.sanitizeId(),
            name = name,
            description = data.string("description").orEmpty(),
            personality = data.string("personality").orEmpty(),
            scenario = data.string("scenario").orEmpty(),
            firstMessage = data.string("first_mes")
                ?: data.string("firstMessage")
                ?: data.string("first_message")
                ?: "",
            alternateGreetings = (extractAlternateGreetings(data) + extractAlternateGreetings(raw)).distinct(),
            exampleMessages = data.string("mes_example").orEmpty(),
            creatorNotes = creatorNotes,
            tags = tags.distinct(),
            characterBook = characterBook,
            raw = raw,
        )
    }

    private fun parseCharacterBook(data: JsonObject): WorldBook? {
        val bookObj = data["character_book"]?.asObjectOrNull() ?: return null
        val entriesElement = bookObj["entries"] ?: return null

        val entries = when (entriesElement) {
            is JsonArray -> entriesElement.mapIndexedNotNull { index, value ->
                parseCharacterBookEntry(index.toString(), value.asObjectOrNull())
            }
            is JsonObject -> entriesElement.mapNotNull { (key, value) ->
                parseCharacterBookEntry(key, value.asObjectOrNull())
            }
            else -> emptyList()
        }

        return WorldBook(
            id = bookObj.string("id") ?: data.string("name")?.sanitizeId() ?: "character_book",
            name = bookObj.string("name") ?: "Character Book",
            entries = entries,
            raw = bookObj,
        )
    }

    private fun parseCharacterBookEntry(key: String, entryObj: JsonObject?): WorldBookEntry? {
        if (entryObj == null) return null
        val extensions = entryObj["extensions"]?.asObjectOrNull()
        val disabled = entryObj.boolean("disable") ?: false
        val enabled = entryObj.boolean("enabled") ?: !disabled

        return WorldBookEntry(
            id = entryObj.string("uid") ?: entryObj.string("id") ?: key,
            keys = extractStringList(entryObj, "key") + extractStringList(entryObj, "keys"),
            secondaryKeys = extractStringList(entryObj, "keysecondary") + extractStringList(entryObj, "secondary_keys"),
            content = entryObj.string("content").orEmpty(),
            enabled = enabled,
            selective = entryObj.boolean("selective") ?: false,
            constant = entryObj.boolean("constant") ?: false,
            priority = entryObj.int("priority") ?: 0,
            insertionOrder = entryObj.int("order")
                ?: entryObj.int("insertion_order")
                ?: 100,
            depth = entryObj.int("depth")
                ?: extensions?.int("depth")
                ?: 4,
            position = entryObj.int("position") ?: 0,
            probability = entryObj.int("probability") ?: 100,
            useProbability = entryObj.boolean("useProbability") ?: false,
            selectiveLogic = entryObj.int("selectiveLogic") ?: 0,
            role = entryObj.int("role") ?: 0,
            matchWholeWords = entryObj.boolean("matchWholeWords") ?: false,
            useRegex = entryObj.boolean("useRegex") ?: false,
            caseSensitive = entryObj.boolean("caseSensitive") ?: false,
            comment = entryObj.string("comment").orEmpty(),
            excludeRecursion = entryObj.boolean("excludeRecursion") ?: false,
            preventRecursion = entryObj.boolean("preventRecursion") ?: false,
            delayUntilRecursion = entryObj.boolean("delayUntilRecursion") ?: false,
            raw = entryObj,
        )
    }

    private fun readByafScenarios(manifest: JsonObject, entries: Map<String, ByteArray>): List<JsonObject> {
        val scenarios = manifest["scenarios"] as? JsonArray ?: return emptyList()
        return scenarios.mapNotNull { element ->
            val path = element.asStringOrNull() ?: return@mapNotNull null
            val text = entries[normalizeZipEntryPath(path)]?.decodeUtf8Text() ?: return@mapNotNull null
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
    }

    private fun buildByafCard(
        manifest: JsonObject,
        character: JsonObject,
        scenarios: List<JsonObject>,
    ): JsonObject {
        val firstScenario = scenarios.firstOrNull()
        val firstMessage = firstScenario?.firstStringFromObjectArray("firstMessages", "text")
            ?: character.string("first_mes")
            ?: character.string("firstMessage")
            ?: ""

        return buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            putJsonObject("data") {
                put("name", character.string("name") ?: character.string("displayName") ?: "BYAF Character")
                put("description", applyByafMacroReplacements(character.string("persona") ?: character.string("description") ?: ""))
                put("personality", character.string("personality").orEmpty())
                put("scenario", applyByafMacroReplacements(firstScenario?.string("narrative") ?: character.string("scenario") ?: ""))
                put("first_mes", applyByafMacroReplacements(firstMessage))
                put("mes_example", formatByafExampleMessages(firstScenario))
                put("creator_notes", manifest["author"]?.asObjectOrNull()?.string("backyardURL").orEmpty())
                put("system_prompt", applyByafMacroReplacements(firstScenario?.string("formattingInstructions").orEmpty()))
                put("post_history_instructions", "")
                putJsonArray("alternate_greetings") {
                    scenarios.flatMap { scenario -> scenario.objectArrayStrings("firstMessages", "text").drop(1) }
                        .forEach { add(JsonPrimitive(applyByafMacroReplacements(it))) }
                }
                putJsonArray("tags") {
                    if (character.boolean("isNSFW") == true) add(JsonPrimitive("nsfw"))
                    extractTags(character).forEach { add(JsonPrimitive(it)) }
                }
                put("creator", manifest["author"]?.asObjectOrNull()?.string("name").orEmpty())
                put("character_version", "")
                putJsonObject("extensions") {
                    character.string("displayName")?.let { put("display_name", it) }
                }
            }
        }
    }

    private fun buildLegacyByafCard(character: JsonObject): JsonObject =
        buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            putJsonObject("data") {
                put("name", character.string("name") ?: character.string("displayName") ?: "BYAF Character")
                put("description", applyByafMacroReplacements(character.string("description") ?: character.string("persona") ?: ""))
                put("personality", character.string("personality").orEmpty())
                put("scenario", applyByafMacroReplacements(character.string("scenario").orEmpty()))
                put("first_mes", applyByafMacroReplacements(character.string("first_mes") ?: character.string("firstMessage") ?: ""))
                put("mes_example", applyByafMacroReplacements(character.string("mes_example").orEmpty()))
                put("creator_notes", character.string("creator_notes").orEmpty())
                putJsonArray("tags") {
                    extractTags(character).forEach { add(JsonPrimitive(it)) }
                }
                putJsonObject("extensions") {}
            }
        }

    private fun formatByafExampleMessages(scenario: JsonObject?): String {
        if (scenario == null) return ""
        val messages = scenario["exampleMessages"] as? JsonArray ?: return ""
        return messages.mapNotNull { item ->
            val obj = item.asObjectOrNull() ?: return@mapNotNull null
            val text = obj.string("text") ?: return@mapNotNull null
            val name = when (obj.string("type")) {
                "human", "user" -> "{{user}}"
                "ai", "character" -> "{{char}}"
                else -> obj.string("name").orEmpty()
            }
            "${name}: ${applyByafMacroReplacements(text)}".trim()
        }.joinToString("\n")
    }

    private fun applyByafMacroReplacements(text: String): String {
        return text
            .replace(Regex("""#\{user}:?""", RegexOption.IGNORE_CASE)) { if (it.value.endsWith(":")) "{{user}}:" else "{{user}}" }
            .replace(Regex("""#\{character}:?""", RegexOption.IGNORE_CASE)) { if (it.value.endsWith(":")) "{{char}}:" else "{{char}}" }
            .replace(Regex("""\{user}(?!})""", RegexOption.IGNORE_CASE), "{{user}}")
            .replace(Regex("""\{character}(?!})""", RegexOption.IGNORE_CASE), "{{char}}")
    }

    private fun extractTags(obj: JsonObject): List<String> {
        val tagsElement = obj["tags"] ?: return emptyList()
        return when (tagsElement) {
            is JsonArray -> tagsElement.mapNotNull { it.asStringOrNull() }
            is JsonPrimitive -> tagsElement.contentOrBlank()
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            else -> emptyList()
        }
    }

    private fun extractStringList(obj: JsonObject, key: String): List<String> {
        val element = obj[key] ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { it.asStringOrNull() }
            is JsonPrimitive -> element.contentOrBlank()
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            else -> emptyList()
        }
    }

    private fun extractAlternateGreetings(obj: JsonObject): List<String> =
        (extractStringList(obj, "alternate_greetings") + extractStringList(obj, "alternateGreetings"))
            .distinct()

    private fun tryImportAsJson(bytes: ByteArray): CharacterCard? {
        val text = bytes.decodeUtf8Text().stripBom().trimStart()
        if (!text.startsWith("{")) return null
        return runCatching { importFromJson(text) }.getOrNull()
    }

    private fun tryImportAsPng(bytes: ByteArray, fileName: String): CharacterCard? {
        if (!isPng(bytes)) return null
        return runCatching { importFromPng(bytes, fileName) }.getOrNull()
    }

    private fun tryImportAsWebp(bytes: ByteArray, fileName: String): CharacterCard? {
        if (!isWebp(bytes)) return null
        return runCatching { importFromWebp(bytes, fileName) }.getOrNull()
    }

    private fun tryImportAsZip(bytes: ByteArray): CharacterCard? {
        if (!isZip(bytes)) return null
        return runCatching { importFromCharx(bytes) }.getOrNull()
            ?: runCatching { importFromByaf(bytes) }.getOrNull()
    }

    companion object {
        fun detectFormat(bytes: ByteArray, fileName: String): String? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext in setOf("json", "png", "webp", "charx", "byaf")) return ext
            if (looksLikeJson(bytes)) return "json"
            if (isPng(bytes)) return "png"
            if (isWebp(bytes)) return "webp"
            if (isZip(bytes)) return "zip"
            return null
        }

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()

        fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

        private fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 &&
                bytes[0] == 0x50.toByte() &&
                bytes[1] == 0x4B.toByte() &&
                bytes[2] == 0x03.toByte() &&
                bytes[3] == 0x04.toByte()

        private fun looksLikeJson(bytes: ByteArray): Boolean =
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith("{")
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val output = ByteArrayOutputStream()
                    zip.copyTo(output)
                    entries[normalizeZipEntryPath(entry.name)] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun normalizeZipEntryPath(path: String): String =
        path.replace('\\', '/').trimStart('/').removePrefix("./")

    private fun ByteArray.decodeUtf8Text(): String = String(this, Charsets.UTF_8)

    private fun String.stripBom(): String = removePrefix("\uFEFF")

    private fun String.sanitizeId(): String {
        return lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "imported_character" }
    }

    private fun JsonObject.string(key: String): String? = this[key].asStringOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        string(key)?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        string(key)?.toIntOrNull()

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrBlank()

    private fun JsonPrimitive.contentOrBlank(): String? =
        runCatching { content.trim().takeIf { it.isNotEmpty() } }.getOrNull()

    private fun JsonObject.objectArrayStrings(arrayKey: String, valueKey: String): List<String> {
        val array = this[arrayKey] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item -> item.asObjectOrNull()?.string(valueKey) }
    }

    private fun JsonObject.firstStringFromObjectArray(arrayKey: String, valueKey: String): String? =
        objectArrayStrings(arrayKey, valueKey).firstOrNull()
}
