package app.tellev.core.storage.coordinator

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.codec.CharacterCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

internal class EmbeddedAssetsCoordinator(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val characterImporter: CharacterImporter,
    private val saveWorldBook: suspend (WorldBook) -> Unit,
) {
    private val supportedCharacterExtensions = setOf("png", "webp", "json")

    suspend fun rebuildEmbeddedCharacterAssets() {
        if (!layout.characters.exists()) return
        layout.characters.listDirectoryEntries()
            .filter { it.extension.lowercase() in supportedCharacterExtensions }
            .forEach { path ->
                runCatching {
                    val id = path.nameWithoutExtension
                    val card = when (path.extension.lowercase()) {
                        "json" -> CharacterCodec.decodeCharacterJson(path, id, json, characterImporter)
                        "png" -> CharacterCodec.decodeCharacterPng(path, id, characterImporter)
                        "webp" -> CharacterCodec.decodeCharacterWebp(path, id, characterImporter)
                        else -> return@runCatching
                    }
                    saveEmbeddedCharacterAssets(card)
                }
            }
    }

    suspend fun saveEmbeddedCharacterAssets(card: CharacterCard) {
        card.characterBook
            ?.takeIf { it.entries.isNotEmpty() }
            ?.let { embeddedBook ->
                saveWorldBook(
                    embeddedBook.copy(
                        id = StDataStore.embeddedCharacterBookId(card.id),
                        name = embeddedBook.name.ifBlank { "${card.name} 角色书" },
                    ),
                )
            }

        val extensions = card.raw.cardDataObject()["extensions"] as? JsonObject ?: return
        val assetDir = layout.extensions.resolve("character-assets").resolve(card.id)
        assetDir.createDirectories()

        val regexScripts = extensions["regex_scripts"]
        val tavernHelper = extensions["tavern_helper"]
        val tavernHelperScripts = extensions["TavernHelper_scripts"] ?: tavernHelper.arrayField("scripts")

        writeCharacterAsset(assetDir, "extensions.json", extensions)
        writeCharacterAsset(assetDir, "regex_scripts.json", regexScripts)
        writeCharacterAsset(assetDir, "TavernHelper_scripts.json", tavernHelperScripts)
        writeCharacterAsset(assetDir, "tavern_helper.json", tavernHelper)

        val manifest = buildJsonObject {
            put("character_id", card.id)
            put("character_name", card.name)
            put("world_book_id", StDataStore.embeddedCharacterBookId(card.id))
            put("regex_scripts", regexScripts.jsonItemCount())
            put("TavernHelper_scripts", tavernHelperScripts.jsonItemCount())
            put("tavern_helper", tavernHelper.tavernHelperVariableCount())
        }
        writeCharacterAsset(assetDir, "manifest.json", manifest)
    }

    private fun writeCharacterAsset(assetDir: Path, fileName: String, value: JsonElement?) {
        val path = assetDir.resolve(fileName)
        if (value == null || value.isEmptyJsonContainer()) {
            path.deleteIfExists()
            return
        }
        durableFiles.write(path, json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8))
    }

    private fun JsonObject.cardDataObject(): JsonObject =
        (this["data"] as? JsonObject) ?: this

    private fun JsonElement?.jsonItemCount(): Int =
        when (this) {
            is JsonArray -> size
            is JsonObject -> size
            else -> 0
        }

    private fun JsonElement?.arrayField(name: String): JsonElement? =
        (this as? JsonObject)?.get(name)?.takeIf { it is JsonArray }

    private fun JsonElement?.tavernHelperVariableCount(): Int {
        val obj = this as? JsonObject ?: return jsonItemCount()
        return when (val variables = obj["variables"]) {
            is JsonArray -> variables.size
            is JsonObject -> variables.size
            else -> obj.size
        }
    }

    private fun JsonElement.isEmptyJsonContainer(): Boolean =
        when (this) {
            is JsonArray -> isEmpty()
            is JsonObject -> isEmpty()
            else -> false
        }
}
