package app.tellev.core.storage.repository

import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.safeStorageChild
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

internal object StorageFileOps {

    fun durableWriteText(durableFiles: JournaledFileWriter, path: Path, text: String) {
        durableFiles.write(path, text.toByteArray(Charsets.UTF_8))
    }

    /** Same journaled discipline for raw/copy-style writes (preset import, in_use.json). */
    fun durableWriteBytes(durableFiles: JournaledFileWriter, path: Path, bytes: ByteArray) {
        durableFiles.write(path, bytes)
    }

    fun readJsonFiles(root: Path, json: Json): List<Pair<Path, JsonObject>> {
        if (!root.exists() || !root.isDirectory()) return emptyList()
        return root.listDirectoryEntries("*.json").mapNotNull { path ->
            runCatching { path to json.parseToJsonElement(path.readText()).jsonObject }.getOrNull()
        }
    }

    fun readJsonObjects(root: Path, json: Json): List<JsonObject> =
        readJsonFiles(root, json).map { it.second }

    fun resolveExisting(root: Path, id: String, extensions: Set<String>): Path? =
        extensions.asSequence()
            .map { safeStorageChild(root, id, ".$it") }
            .firstOrNull { it.exists() }

    fun findByFileName(roots: List<Path>, fileName: String): Path? {
        // Validate before listing, even when the requested roots do not exist.
        roots.forEach { safeStorageChild(it, fileName) }
        return roots.asSequence()
            .filter { it.exists() }
            .flatMap { root ->
                root.listDirectoryEntries().asSequence().flatMap { entry ->
                    if (java.nio.file.Files.isSymbolicLink(entry)) emptySequence()
                    else if (entry.isDirectory()) entry.listDirectoryEntries().asSequence().filter { it.name == fileName }
                    else sequenceOf(entry).filter { it.name == fileName }
                }
            }
            .filter { !java.nio.file.Files.isSymbolicLink(it) }
            .firstOrNull()
    }
}
