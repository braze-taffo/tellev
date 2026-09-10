package app.tellev.core.storage.repository

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.storage.CharacterExporter
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.WebpCardParser
import app.tellev.core.storage.codec.CharacterCodec
import app.tellev.core.storage.coordinator.EmbeddedAssetsCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

internal class CharacterRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val characterImporter: CharacterImporter,
    private val embeddedCoordinator: EmbeddedAssetsCoordinator,
    private val characterChanges: MutableSharedFlow<String>,
    private val deleteWorldBook: suspend (String) -> Unit,
) {
    private val supportedCharacterExtensions = setOf("png", "webp", "json")

    suspend fun listCharacters(): List<CharacterSummary> = withContext(Dispatchers.IO) {
        if (!layout.characters.exists()) return@withContext emptyList()
        layout.characters.listDirectoryEntries()
            .filter { it.extension.lowercase() in supportedCharacterExtensions }
            .sortedBy { it.name.lowercase() }
            .map { path ->
                val id = path.nameWithoutExtension
                val nameAndTags = CharacterCodec.readCharacterNameAndTags(path, json)
                CharacterSummary(
                    id = id,
                    name = nameAndTags?.first ?: id,
                    avatarRelativePath = "characters/${path.name}",
                    tags = nameAndTags?.second ?: emptyList(),
                )
            }
    }

    suspend fun readCharacter(id: String): CharacterCard = withContext(Dispatchers.IO) {
        val path = StorageFileOps.resolveExisting(layout.characters, id, supportedCharacterExtensions)
            ?: error("Character not found: $id")
        when (path.extension.lowercase()) {
            "json" -> CharacterCodec.decodeCharacterJson(path, id, json, characterImporter)
            "png" -> CharacterCodec.decodeCharacterPng(path, id, characterImporter)
            "webp" -> CharacterCodec.decodeCharacterWebp(path, id, characterImporter)
            else -> error("Unsupported character format: ${path.extension}")
        }
    }

    suspend fun saveCharacter(card: CharacterCard): Unit = withContext(Dispatchers.IO) {
        layout.characters.createDirectories()

        val existingPng = layout.characters.resolve("${card.id}.png")
        if (existingPng.exists()) {
            val exporter = CharacterExporter(json)
            val jsonStr = exporter.exportToJson(card)
            val pngBytes = PngCardParser.embedCardJson(existingPng.readBytes(), jsonStr)
            existingPng.outputStream().use { it.write(pngBytes) }
            embeddedCoordinator.saveEmbeddedCharacterAssets(card)
            characterChanges.tryEmit(card.id)
            return@withContext
        }

        val existingWebp = layout.characters.resolve("${card.id}.webp")
        if (existingWebp.exists()) {
            val exporter = CharacterExporter(json)
            val jsonStr = exporter.exportToJson(card)
            val webpBytes = WebpCardParser.embedCardJson(existingWebp.readBytes(), jsonStr)
            existingWebp.outputStream().use { it.write(webpBytes) }
            embeddedCoordinator.saveEmbeddedCharacterAssets(card)
            characterChanges.tryEmit(card.id)
            return@withContext
        }

        val exporter = CharacterExporter(json)
        val path = layout.characters.resolve("${card.id}.json")
        StorageFileOps.durableWriteText(durableFiles, path, exporter.exportToJson(card))
        embeddedCoordinator.saveEmbeddedCharacterAssets(card)
        characterChanges.tryEmit(card.id)
    }

    suspend fun importCharacter(
        card: CharacterCard,
        sourceBytes: ByteArray,
        sourceFileName: String,
    ): Unit = withContext(Dispatchers.IO) {
        layout.characters.createDirectories()

        val exporter = CharacterExporter(json)
        val jsonString = exporter.exportToJson(card)
        val format = CharacterImporter.detectFormat(sourceBytes, sourceFileName)

        when (format) {
            "png" -> {
                removeCharacterVariants(card.id, keepExtension = "png")
                val pngBytes = PngCardParser.embedCardJson(sourceBytes, jsonString)
                layout.characters.resolve("${card.id}.png").outputStream().use { it.write(pngBytes) }
                embeddedCoordinator.saveEmbeddedCharacterAssets(card)
            }
            "webp" -> {
                removeCharacterVariants(card.id, keepExtension = "webp")
                val webpBytes = WebpCardParser.embedCardJson(sourceBytes, jsonString)
                layout.characters.resolve("${card.id}.webp").outputStream().use { it.write(webpBytes) }
                embeddedCoordinator.saveEmbeddedCharacterAssets(card)
            }
            else -> saveCharacter(card)
        }
        characterChanges.tryEmit(card.id)
    }

    suspend fun deleteCharacter(id: String): Unit = withContext(Dispatchers.IO) {
        removeCharacterVariants(id, keepExtension = "")
        val assetDir = layout.extensions.resolve("character-assets").resolve(id)
        if (assetDir.exists()) assetDir.toFile().deleteRecursively()
        runCatching { deleteWorldBook(StDataStore.embeddedCharacterBookId(id)) }
        characterChanges.tryEmit(id)
    }

    suspend fun replaceCharacterAvatar(id: String, pngBytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val card = readCharacter(id)
        val jsonString = CharacterExporter(json).exportToJson(card)
        val embedded = PngCardParser.embedCardJson(pngBytes, jsonString)
        layout.characters.createDirectories()
        layout.characters.resolve("$id.png").outputStream().use { it.write(embedded) }
        removeCharacterVariants(id, keepExtension = "png")
        embeddedCoordinator.saveEmbeddedCharacterAssets(card)
        characterChanges.tryEmit(id)
    }

    fun removeCharacterVariants(id: String, keepExtension: String) {
        supportedCharacterExtensions
            .filter { it != keepExtension }
            .forEach { extension -> layout.characters.resolve("$id.$extension").deleteIfExists() }
    }
}
