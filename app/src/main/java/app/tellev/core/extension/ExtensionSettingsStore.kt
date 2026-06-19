package app.tellev.core.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Persists per-extension settings as JSON files in a SillyTavern-compatible
 * directory layout:
 *
 * ```
 * {extensionsDir}/{extensionId}/settings.json
 * ```
 *
 * All public methods are `suspend` and dispatch to [Dispatchers.IO] so the
 * caller is never blocked on file I/O.
 */
class ExtensionSettingsStore(
    private val extensionsDir: Path,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()

    /**
     * Return the persisted settings for [extensionId], or an empty JSON object
     * if no settings file exists yet.
     */
    suspend fun getSettings(extensionId: String): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = settingsFile(extensionId)
            if (!Files.exists(file)) return@withContext buildJsonObject { }
            runCatching {
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                json.parseToJsonElement(text) as JsonObject
            }.getOrElse { buildJsonObject { } }
        }
    }

    /**
     * Overwrite the settings for [extensionId].  Parent directories are
     * created automatically.
     */
    suspend fun saveSettings(extensionId: String, settings: JsonObject): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val file = settingsFile(extensionId)
                Files.createDirectories(file.parent)
                val text = json.encodeToString(JsonObject.serializer(), settings)
                Files.write(
                    file,
                    text.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
        }

    /**
     * Delete the settings file (and parent directory if empty) for
     * [extensionId].  No-op when nothing exists on disk.
     */
    suspend fun deleteSettings(extensionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = settingsFile(extensionId)
            Files.deleteIfExists(file)
            val dir = file.parent
            if (Files.exists(dir) && Files.list(dir).use { !it.findFirst().isPresent }) {
                Files.deleteIfExists(dir)
            }
        }
    }

    /**
     * List every extension ID that currently has a persisted settings file.
     */
    suspend fun listExtensionIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!Files.isDirectory(extensionsDir)) return@withContext emptyList()
            Files.list(extensionsDir).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { Files.exists(it.resolve("settings.json")) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .toList()
            }
        }
    }

    // ── private helpers ────────────────────────────────────────────────

    private fun settingsFile(extensionId: String): Path =
        extensionsDir.resolve(sanitize(extensionId)).resolve("settings.json")

    /**
     * Strip path-traversal and non-alphanumeric characters from an extension
     * ID so it is safe to use as a directory name.
     */
    private fun sanitize(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
