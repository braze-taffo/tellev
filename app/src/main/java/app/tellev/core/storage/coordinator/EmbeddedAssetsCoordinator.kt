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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

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
                    // Unchanged cards skip the full decode + rewrite entirely: the boot-time
                    // rebuild used to re-encode every card and journal up to 5 writes per character.
                    val fingerprint = cardFingerprint(path)
                    val manifest = layout.extensions.resolve("character-assets")
                        .resolve(path.nameWithoutExtension).resolve("manifest.json")
                    val recorded = runCatching {
                        (json.parseToJsonElement(manifest.readText()) as? JsonObject)
                            ?.get("tellev_card_fingerprint")?.let { (it as? JsonPrimitive)?.content }
                    }.getOrNull()
                    if (manifest.exists() && recorded == fingerprint) return@runCatching
                    val id = path.nameWithoutExtension
                    val card = when (path.extension.lowercase()) {
                        "json" -> CharacterCodec.decodeCharacterJson(path, id, json, characterImporter)
                        "png" -> CharacterCodec.decodeCharacterPng(path, id, characterImporter)
                        "webp" -> CharacterCodec.decodeCharacterWebp(path, id, characterImporter)
                        else -> return@runCatching
                    }
                    saveEmbeddedCharacterAssets(card, fingerprint)
                }
            }
    }

    suspend fun saveEmbeddedCharacterAssets(card: CharacterCard, cardFingerprint: String? = null) {
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
            cardFingerprint?.let { put("tellev_card_fingerprint", JsonPrimitive(it)) }
        }
        writeCharacterAsset(assetDir, "manifest.json", manifest)
    }

    /** Cheap stat fingerprint (size + mtime): no card bytes are read. */
    private fun cardFingerprint(path: Path): String =
        "${java.nio.file.Files.size(path)}-${java.nio.file.Files.getLastModifiedTime(path).toMillis()}"

    private fun writeCharacterAsset(assetDir: Path, fileName: String, value: JsonElement?) {
        val path = assetDir.resolve(fileName)
        if (value == null || value.isEmptyJsonContainer()) {
            path.deleteIfExists()
            return
        }
        val encoded = json.encodeToString(JsonElement.serializer(), value).toByteArray(Charsets.UTF_8)
        // Identical content must not produce journal churn on every rebuild.
        if (path.exists() && path.readText() == String(encoded, Charsets.UTF_8)) return
        durableFiles.write(path, encoded)
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
