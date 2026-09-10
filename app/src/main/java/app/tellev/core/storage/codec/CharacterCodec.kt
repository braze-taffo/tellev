package app.tellev.core.storage.codec

import app.tellev.core.model.CharacterCard
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.WebpCardParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

internal object CharacterCodec {

    fun decodeCharacterJson(path: Path, id: String, json: Json, characterImporter: CharacterImporter): CharacterCard {
        val raw = json.parseToJsonElement(path.readText()).jsonObject
        val card = characterImporter.parseCharacterJsonObject(raw)
        return card.copy(
            id = id,
            avatarRelativePath = "characters/${path.name}",
        )
    }

    fun decodeCharacterPng(path: Path, id: String, characterImporter: CharacterImporter): CharacterCard {
        val pngBytes = path.readBytes()
        val cardJson = PngCardParser.extractCardJson(pngBytes)

        return if (cardJson != null) {
            val card = characterImporter.parseCharacterJsonObject(cardJson)
            card.copy(
                id = id,
                avatarRelativePath = "characters/${path.name}",
            )
        } else {
            // Fallback: return a basic card with the file name
            CharacterCard(
                id = id,
                name = id,
                avatarRelativePath = "characters/${path.name}",
                raw = buildJsonObject {
                    put("tellev_import_note", JsonPrimitive("Could not parse PNG metadata."))
                },
            )
        }
    }

    fun decodeCharacterWebp(path: Path, id: String, characterImporter: CharacterImporter): CharacterCard {
        val webpBytes = path.readBytes()
        val cardJson = WebpCardParser.extractCardJson(webpBytes)

        return if (cardJson != null) {
            val card = characterImporter.parseCharacterJsonObject(cardJson)
            card.copy(
                id = id,
                avatarRelativePath = "characters/${path.name}",
            )
        } else {
            CharacterCard(
                id = id,
                name = id,
                avatarRelativePath = "characters/${path.name}",
                raw = buildJsonObject {
                    put("tellev_import_note", JsonPrimitive("Could not parse WebP metadata."))
                },
            )
        }
    }

    fun readCharacterNameAndTags(path: Path, json: Json): Pair<String, List<String>>? = runCatching {
        when (path.extension.lowercase()) {
            "json" -> {
                val raw = json.parseToJsonElement(path.readText()).jsonObject
                val data = raw["data"]?.jsonObject ?: raw
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(raw)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            "png" -> {
                val cardJson = PngCardParser.extractCardJson(path.readBytes()) ?: return null
                val data = cardJson["data"]?.jsonObject ?: cardJson
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(cardJson)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            "webp" -> {
                val cardJson = WebpCardParser.extractCardJson(path.readBytes()) ?: return null
                val data = cardJson["data"]?.jsonObject ?: cardJson
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(cardJson)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            else -> null
        }
    }.getOrNull()

    fun extractTagsList(obj: JsonObject): List<String> {
        val tagsElement = obj["tags"] ?: return emptyList()
        return runCatching {
            tagsElement.jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
}
