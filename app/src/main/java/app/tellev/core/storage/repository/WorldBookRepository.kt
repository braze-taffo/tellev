package app.tellev.core.storage.repository

import app.tellev.core.model.WorldBook
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.codec.WorldBookCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

internal class WorldBookRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val worldBookChanges: MutableSharedFlow<String>,
) {
    suspend fun listWorldBooks(): List<WorldBook> = withContext(Dispatchers.IO) {
        StorageFileOps.readJsonFiles(layout.worlds, json).map { (path, raw) ->
            val entries = WorldBookCodec.parseWorldBookEntries(raw)
            WorldBook(
                id = path.nameWithoutExtension,
                name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                entries = entries,
                raw = raw,
            )
        }
    }

    suspend fun readWorldBook(id: String): WorldBook = withContext(Dispatchers.IO) {
        listWorldBooks().firstOrNull { it.id == id } ?: error("World book not found: $id")
    }

    suspend fun saveWorldBook(book: WorldBook): Unit = withContext(Dispatchers.IO) {
        layout.worlds.createDirectories()
        val output = WorldBookCodec.serializeWorldBook(book)
        val path = layout.worlds.resolve("${book.id}.json")
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(JsonObject.serializer(), output))
        worldBookChanges.tryEmit(book.id)
    }

    suspend fun importWorldBook(
        jsonBytes: ByteArray,
        sourceFileName: String,
    ): WorldBook = withContext(Dispatchers.IO) {
        val raw = runCatching {
            json.parseToJsonElement(jsonBytes.decodeToString()) as? JsonObject
        }.getOrNull() ?: error("世界书 JSON 格式无效：$sourceFileName 不是有效的 JSON 对象")

        if (raw["entries"] !is JsonObject) {
            error("世界书 JSON 格式无效：$sourceFileName 缺少 entries 对象")
        }

        val fallbackName = sourceFileName.substringBeforeLast('.').ifBlank { "导入的世界书" }
        val name = (raw["name"] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val book = WorldBook(
            id = "wb_${UUID.randomUUID()}",
            name = name,
            entries = WorldBookCodec.parseWorldBookEntries(raw),
            raw = raw,
        )
        saveWorldBook(book)
        book
    }

    suspend fun deleteWorldBook(id: String): Unit = withContext(Dispatchers.IO) {
        layout.worlds.resolve("$id.json").deleteIfExists()
        val disabled = readDisabledWorldIds() - id
        if (disabled.isEmpty()) {
            layout.worldInfoActivation.deleteIfExists()
        } else {
            saveDisabledWorldIds(disabled)
        }
        worldBookChanges.tryEmit(id)
    }

    suspend fun readDisabledWorldIds(): Set<String> = withContext(Dispatchers.IO) {
        val path = layout.worldInfoActivation
        if (!path.exists()) return@withContext emptySet()
        val raw = runCatching { json.parseToJsonElement(path.readText()) }.getOrNull() as? JsonObject
            ?: return@withContext emptySet()
        val disabled = raw["disabled"] as? JsonArray ?: return@withContext emptySet()
        disabled.mapNotNull { it.stringContentOrNull() }.toSet()
    }

    suspend fun saveDisabledWorldIds(ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        val output = buildJsonObject {
            putJsonArray("disabled") {
                ids.sorted().forEach { add(JsonPrimitive(it)) }
            }
        }
        StorageFileOps.durableWriteText(durableFiles, layout.worldInfoActivation, json.encodeToString(JsonObject.serializer(), output))
        worldBookChanges.tryEmit("*")
    }

    private fun JsonElement.stringContentOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
