package app.tellev.core.storage.coordinator

import app.tellev.core.security.SensitiveFieldScanner
import app.tellev.core.storage.CharacterBatchImporter
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedOutputStream
import java.io.InputStream
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
    /** Restore bounds; injectable so the guards can be exercised without gigabyte fixtures. */
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    private val maxTotalBytes: Long = MAX_UNCOMPRESSED_BYTES,
    private val maxEntries: Int = MAX_ENTRIES,
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
     *
     * The three bounds (per entry, whole archive, entry count) are enforced on
     * bytes actually read: a streamed zip reports -1 for `entry.size`, so only
     * the delivered byte count is trustworthy. Crossing any of them aborts the
     * restore with a readable failure — a truncated backup must never be
     * mistaken for a complete one.
     */
    suspend fun importBackup(sourceZip: Path): List<String> = withContext(Dispatchers.IO) {
        require(sourceZip.exists()) { "Backup file does not exist: $sourceZip" }
        val restored = mutableListOf<String>()
        var entries = 0
        var uncompressed = 0L

        ZipInputStream(sourceZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries += 1
                check(entries <= maxEntries) {
                    "Backup contains more than $maxEntries entries; refusing to restore"
                }
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
                    val entryStream = BoundedEntryStream(zis, entryName, maxEntryBytes)
                    durableFiles.write(targetPath, entryStream)
                    uncompressed += entryStream.bytesRead
                    check(uncompressed <= maxTotalBytes) {
                        "Backup expands past ${maxTotalBytes / (1024 * 1024)}MB; refusing to restore at $entryName"
                    }
                    restored += normalizedPath
                }

                entry = zis.nextEntry
            }
        }
        restored
    }

    /**
     * Counts the bytes actually delivered from one archive entry and aborts once
     * [maxBytes] is crossed. `ZipEntry.size` is deliberately ignored: a streamed
     * entry reports -1, and the read count is the only figure that bounds what
     * reaches the journal and the data directory.
     */
    private class BoundedEntryStream(
        private val source: InputStream,
        private val entryName: String,
        private val maxBytes: Long,
    ) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val byte = source.read()
            if (byte >= 0) advance(1)
            return byte
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(destination, offset, length)
            if (read > 0) advance(read)
            return read
        }

        private fun advance(count: Int) {
            bytesRead += count
            check(bytesRead <= maxBytes) {
                "Backup entry $entryName expands past the ${maxBytes / (1024 * 1024)}MB restore limit"
            }
        }
    }

    companion object {
        /** Same headroom the other import entries grant a single payload. */
        private const val MAX_ENTRY_BYTES = CharacterBatchImporter.IMPORT_MAX_BYTES

        /**
         * Ten times the archive cap: enough for the text/JSONL expansion of a
         * legitimate backup, low enough that a zip bomb cannot fill the disk.
         */
        private const val MAX_UNCOMPRESSED_BYTES = 1L * 1024 * 1024 * 1024

        /** Generous against a chat/thumbnail-heavy data directory; a bound on the central directory. */
        private const val MAX_ENTRIES = 20_000
    }
}
