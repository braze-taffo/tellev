package app.tellev.core.storage.coordinator

import app.tellev.core.security.SensitiveFieldScanner
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
) {
    suspend fun exportBackup(targetZip: Path, includeSecrets: Boolean = false): Unit = withContext(Dispatchers.IO) {
        targetZip.parent?.createDirectories()

        ZipOutputStream(BufferedOutputStream(targetZip.outputStream())).use { zos ->
            val baseDir = layout.root

            if (baseDir.exists()) {
                baseDir.walk().forEach { filePath ->
                    if (filePath.isDirectory()) return@forEach
                    if (filePath.normalize() == targetZip.normalize()) return@forEach

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

    suspend fun importBackup(sourceZip: Path): Unit = withContext(Dispatchers.IO) {
        require(sourceZip.exists()) { "Backup file does not exist: $sourceZip" }

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

                if (entry.isDirectory) {
                    targetPath.createDirectories()
                } else {
                    targetPath.parent?.createDirectories()
                    targetPath.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }

                entry = zis.nextEntry
            }
        }
    }
}
