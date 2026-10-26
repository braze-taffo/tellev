package app.tellev.core.storage.repository

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookSummary
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.safeStorageChild
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.lang.ref.WeakReference
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

internal class WorldBookRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val worldBookChanges: MutableSharedFlow<String>,
) {
    private data class SummaryStamp(val modified: FileTime, val size: Long, val fileKey: String?)
    private val summaryCache = mutableMapOf<Path, Pair<SummaryStamp, WorldBookSummary>>()
    // Reuse immutable models already held by consumers; do not pin book bodies in storage.
    private val bookCache = mutableMapOf<Path, Pair<SummaryStamp, WeakReference<WorldBook>>>()

    private fun stamp(path: Path): SummaryStamp {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        return SummaryStamp(attrs.lastModifiedTime(), attrs.size(), attrs.fileKey()?.toString())
    }

    private fun readBookFile(path: Path): WorldBook = synchronized(summaryCache) {
        val stamp = stamp(path)
        bookCache[path]?.takeIf { it.first == stamp }?.second?.get()?.let { return@synchronized it }
        val raw = json.parseToJsonElement(path.readText()).jsonObject
        WorldBook(path.nameWithoutExtension, raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
            WorldBookCodec.parseWorldBookEntries(raw), raw).also {
            bookCache[path] = stamp to WeakReference(it)
        }
    }
    suspend fun listWorldBooks(): List<WorldBook> = withContext(Dispatchers.IO) {
        synchronized(summaryCache) {
            val paths = if (layout.worlds.isDirectory()) layout.worlds.listDirectoryEntries("*.json") else emptyList()
            bookCache.keys.retainAll(paths.toSet())
            val existing = paths.toSet()
            summaryCache.keys.retainAll(existing)
            bookCache.keys.retainAll(existing)
            paths.mapNotNull { path ->
                runCatching { readBookFile(path) }.getOrNull() }
        }
    }

    /**
     * Cache metadata only, never entry bodies. Re-scan membership and file attributes on
     * every call so imports, deletions and raw ST edits are visible. Changed files are
     * parsed one at a time instead of retaining the whole library's JSON trees.
     */
    suspend fun listWorldBookSummaries(): List<WorldBookSummary> = withContext(Dispatchers.IO) {
        synchronized(summaryCache) {
            val paths = if (layout.worlds.isDirectory()) layout.worlds.listDirectoryEntries("*.json") else emptyList()
            val existing = paths.toSet()
            summaryCache.keys.retainAll(existing)
            bookCache.keys.retainAll(existing)
            paths.mapNotNull { path ->
                runCatching {
                    val stamp = stamp(path)
                    val cached = summaryCache[path]
                    if (cached?.first == stamp) cached.second else {
                        val raw = bookCache[path]?.takeIf { it.first == stamp }?.second?.get()?.raw
                            ?: json.parseToJsonElement(path.readText()).jsonObject
                        val summary = WorldBookSummary(
                            id = path.nameWithoutExtension,
                            name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                            entryCount = (raw["entries"] as? JsonObject)?.size ?: 0,
                        )
                        summaryCache[path] = stamp to summary
                        summary
                    }
                }.getOrNull()
            }
        }
    }

    suspend fun readWorldBook(id: String): WorldBook = withContext(Dispatchers.IO) {
        val path = safeStorageChild(layout.worlds, id, ".json")
        readBookFile(path)
    }

    suspend fun saveWorldBook(book: WorldBook): Unit = withContext(Dispatchers.IO) {
        val path = safeStorageChild(layout.worlds, book.id, ".json")
        layout.worlds.createDirectories()
        val output = WorldBookCodec.serializeWorldBook(book)
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(JsonObject.serializer(), output))
        synchronized(summaryCache) { summaryCache.remove(path); bookCache.remove(path) }
        worldBookChanges.tryEmit(book.id)
    }

    suspend fun importWorldBook(
        jsonBytes: ByteArray,
        sourceFileName: String,
    ): WorldBook = withContext(Dispatchers.IO) {
        val raw = runCatching {
            json.parseToJsonElement(jsonBytes.decodeToString()) as? JsonObject
        }.getOrNull() ?: error(UiStrings.get(S.wbrepo_error_invalid_json, sourceFileName))

        if (raw["entries"] !is JsonObject) {
            error(UiStrings.get(S.wbrepo_error_missing_entries, sourceFileName))
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
        val path = safeStorageChild(layout.worlds, id, ".json")
        path.deleteIfExists()
        synchronized(summaryCache) { summaryCache.remove(path); bookCache.remove(path) }
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
