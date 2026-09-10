package app.tellev.core.storage.repository

import app.tellev.core.storage.JournaledFileWriter
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
            .map { root.resolve("$id.$it") }
            .firstOrNull { it.exists() }

    fun findByFileName(roots: List<Path>, fileName: String): Path? =
        roots.asSequence()
            .filter { it.exists() }
            .flatMap { root ->
                root.listDirectoryEntries().asSequence().flatMap { entry ->
                    if (entry.isDirectory()) entry.listDirectoryEntries(fileName).asSequence()
                    else sequenceOf(entry).filter { it.name == fileName }
                }
            }
            .firstOrNull()
}
