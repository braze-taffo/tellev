package app.tellev.core.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import app.tellev.core.storage.JournaledFileWriter
import java.util.stream.Collectors

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
    private val json: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    private val mutex = Mutex()
    private val writer = JournaledFileWriter(extensionsDir)
    private var recovered = false

    private fun recoverOnce() {
        if (!recovered) { writer.recover(); recovered = true }
    }

    /**
     * Return the persisted settings for [extensionId], or an empty JSON object
     * if no settings file exists yet.
     */
    suspend fun getSettings(extensionId: String): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverOnce()
            val file = settingsFile(extensionId)
            if (!Files.exists(file)) return@withContext buildJsonObject { }
            runCatching {
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                json.parseToJsonElement(text) as JsonObject
            }.getOrElse { throw java.io.IOException("无法解析扩展设置：$file", it) }
        }
    }

    /**
     * Overwrite the settings for [extensionId].  Parent directories are
     * created automatically.
     */
    suspend fun saveSettings(extensionId: String, settings: JsonObject): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
            recoverOnce()
                val file = settingsFile(extensionId)
                Files.createDirectories(file.parent)
                val text = json.encodeToString(JsonObject.serializer(), settings)
                writer.write(file, text.toByteArray(Charsets.UTF_8))
            }
        }

    /**
     * Delete the settings file (and parent directory if empty) for
     * [extensionId].  No-op when nothing exists on disk.
     */
    suspend fun deleteSettings(extensionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverOnce()
            val file = settingsFile(extensionId)
            writer.delete(file)
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
            recoverOnce()
            if (!Files.isDirectory(extensionsDir)) return@withContext emptyList()
            Files.list(extensionsDir).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .filter { Files.exists(it.resolve("settings.json")) }
                    .map { it.fileName.toString() }
                    .sorted()
                    .collect(Collectors.toList())
            }
        }
    }


    // ── typed compat-module settings ──────────────────────────────────

    /**
     * Extension IDs used to persist the built-in compatibility module
     * settings.  These match the keys the real SillyTavern extensions
     * use under `extension_settings` so that data round-trips correctly
     * through the virtual `/api/settings` endpoints.
     */
    private val ejsTemplateExtensionId = "EjsTemplate"
    private val tavernHelperExtensionId = "tavern_helper"

    suspend fun readEjsTemplateSettings(): EjsTemplateSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverOnce()
            val file = settingsFile(ejsTemplateExtensionId)
            if (!Files.exists(file)) return@withLock EjsTemplateSettings.DEFAULT
            runCatching {
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                json.decodeFromString(EjsTemplateSettings.serializer(), text)
            }.getOrElse { throw java.io.IOException("无法解析模板设置：$file", it) }
        }
    }

    suspend fun saveEjsTemplateSettings(settings: EjsTemplateSettings): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
            recoverOnce()
                val file = settingsFile(ejsTemplateExtensionId)
                Files.createDirectories(file.parent)
                val text = json.encodeToString(JsonObject.serializer(), mergeKnownSettings(file,
                    json.encodeToJsonElement(EjsTemplateSettings.serializer(), settings) as JsonObject))
                writer.write(file, text.toByteArray(Charsets.UTF_8))
            }
        }

    suspend fun readTavernHelperSettings(): TavernHelperSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverOnce()
            val file = settingsFile(tavernHelperExtensionId)
            if (!Files.exists(file)) return@withLock TavernHelperSettings.DEFAULT
            runCatching {
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                json.decodeFromString(TavernHelperSettings.serializer(), text)
            }.getOrElse { throw java.io.IOException("无法解析酒馆助手设置：$file", it) }
        }
    }

    suspend fun saveTavernHelperSettings(settings: TavernHelperSettings): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
            recoverOnce()
                val file = settingsFile(tavernHelperExtensionId)
                Files.createDirectories(file.parent)
                val text = json.encodeToString(JsonObject.serializer(), mergeKnownSettings(file,
                    json.encodeToJsonElement(TavernHelperSettings.serializer(), settings) as JsonObject))
                writer.write(file, text.toByteArray(Charsets.UTF_8))
            }
        }

    private fun mergeKnownSettings(file: Path, fields: JsonObject): JsonObject {
        if (!Files.exists(file)) return fields
        val old = json.parseToJsonElement(String(Files.readAllBytes(file), Charsets.UTF_8)) as JsonObject
        fun merge(before: JsonElement?, after: JsonElement): JsonElement =
            if (before is JsonObject && after is JsonObject) {
                JsonObject(before + after.mapValues { (key, value) -> merge(before[key], value) })
            } else after
        return merge(old, fields) as JsonObject
    }

        // ── private helpers ────────────────────────────────────────────────

    private fun settingsFile(extensionId: String): Path {
        val name = sanitize(extensionId)
        require(name.isNotBlank() && name != "." && name != "..") { "无效的扩展 ID" }
        val root = extensionsDir.toAbsolutePath().normalize()
        val target = root.resolve(name).resolve("settings.json").normalize()
        check(target.startsWith(root)) { "扩展设置路径超出所属目录" }
        check(!Files.isSymbolicLink(target.parent) && !Files.isSymbolicLink(target)) { "扩展设置不能通过符号链接改写其他对象" }
        return target
    }

    /**
     * Strip path-traversal and non-alphanumeric characters from an extension
     * ID so it is safe to use as a directory name.
     */
    private fun sanitize(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
