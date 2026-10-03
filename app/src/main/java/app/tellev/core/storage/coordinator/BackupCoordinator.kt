package app.tellev.core.storage.coordinator

import app.tellev.core.security.SensitiveFieldScanner
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.walk

internal class BackupCoordinator(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
) {
    suspend fun exportBackup(targetZip: Path, includeSecrets: Boolean = false): Unit = withContext(Dispatchers.IO) {
        targetZip.parent?.createDirectories()

        ZipOutputStream(BufferedOutputStream(targetZip.outputStream())).use { zos ->
            val baseDir = layout.root

            if (baseDir.exists()) {
                baseDir.walk().forEach { filePath ->
                    if (filePath.isDirectory()) return@forEach
                    if (filePath.normalize() == targetZip.normalize()) return@forEach
                    // The write journal is derived state; shipping it would near-double the archive
                    // and resurrect orphans on restore.
                    if (baseDir.relativize(filePath).any { it.toString() == JournaledFileWriter.JOURNAL_DIR_NAME }) return@forEach

                    val relativePath = baseDir.relativize(filePath).toString().replace('\\', '/')
                    zos.putNextEntry(ZipEntry(relativePath))

                    if (!includeSecrets && filePath.extension.lowercase() == "json") {
                        val sanitizedContent = runCatching {
                            val jsonObj = json.parseToJsonElement(filePath.readText()).jsonObject
                            val sanitized = SensitiveFieldScanner.sanitize(jsonObj)
                            json.encodeToString(JsonObject.serializer(), sanitized)
                        }.getOrNull()

                        if (sanitizedContent != null) {
                            zos.write(sanitizedContent.toByteArray())
                        } else {
                            filePath.inputStream().use { input ->
                                input.copyTo(zos)
                            }
                        }
                    } else {
                        filePath.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                    }

                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * Restores every archive entry and returns the normalized relative paths
     * that were written, so the caller can broadcast change events (M4).
     */
    suspend fun importBackup(sourceZip: Path): List<String> = withContext(Dispatchers.IO) {
        require(sourceZip.exists()) { "Backup file does not exist: $sourceZip" }
        val restored = mutableListOf<String>()

        ZipInputStream(sourceZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name

                val normalizedPath = entryName.replace('\\', '/').trimStart('/')
                val root = layout.root.normalize()
                val targetPath = root.resolve(normalizedPath).normalize()

                if (!targetPath.startsWith(root)) {
                    throw IllegalArgumentException("Path traversal detected in backup entry: $entryName")
                }

                if (normalizedPath.split('/').any { it == JournaledFileWriter.JOURNAL_DIR_NAME }) {
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    targetPath.createDirectories()
                } else {
                    targetPath.parent?.createDirectories()
                    // M4: a plain outputStream() write was not crash-safe — a kill
                    // mid-restore left a truncated live file, and the untouched
                    // journal revision desynced caches and CAS afterwards. Going
                    // through the journal gives each entry tmp+fsync+ATOMIC_MOVE
                    // plus a revision bump, so post-restore readers and writers
                    // see a coherent, conflict-detecting state.
                    durableFiles.write(targetPath, zis.readBytes())
                    restored += normalizedPath
                }

                entry = zis.nextEntry
            }
        }
        restored
    }
}
