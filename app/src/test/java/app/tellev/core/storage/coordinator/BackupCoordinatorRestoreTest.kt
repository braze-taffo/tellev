package app.tellev.core.storage.coordinator

import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13 (TELLER-2-2): restore is streamed and bounded — each entry, the whole
 * archive and the entry count are capped on the bytes actually read, and
 * crossing a bound aborts the restore instead of committing a partial backup.
 */
class BackupCoordinatorRestoreTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `restores every entry through the journal and keeps the journal clean`() = runBlocking {
        val source = Files.createTempDirectory("backup-restore-src")
        val target = Files.createTempDirectory("backup-restore-dst")
        try {
            val archive = zipOf(
                "chats/" to null,
                "chats/a.jsonl" to "line one\nline two\n".toByteArray(),
                "worlds/book.jsonl" to ByteArray(200_000) { (it % 251).toByte() },
                "backgrounds/bg.png" to byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09, 0x09),
            )
            val zip = source.resolve("backup.zip")
            Files.write(zip, archive)

            val layout = StDirectoryLayout.fromRoot(target)
            val writer = JournaledFileWriter(layout.root)
            val restored = BackupCoordinator(layout, json, writer).importBackup(zip)

            assertEquals(
                listOf("chats/a.jsonl", "worlds/book.jsonl", "backgrounds/bg.png"),
                restored,
            )
            assertTrue(Files.isDirectory(target.resolve("chats")))
            assertArrayEquals("line one\nline two\n".toByteArray(), target.resolve("chats/a.jsonl").readBytes())
            assertArrayEquals(ByteArray(200_000) { (it % 251).toByte() }, target.resolve("worlds/book.jsonl").readBytes())
            assertArrayEquals(
                byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x09, 0x09),
                target.resolve("backgrounds/bg.png").readBytes(),
            )
            assertEquals(1L, writer.revision(target.resolve("worlds/book.jsonl")))

            // The streamed payload is moved into place and reclaimed, so nothing
            // but receipts survives the restore.
            val journalNames = target.resolve(JournaledFileWriter.JOURNAL_DIR_NAME).toFile().list()!!.toList()
            assertTrue("left-over payload: $journalNames", journalNames.none { it.endsWith(".payload") })
            assertTrue("left-over temp: $journalNames", journalNames.none { it.endsWith(".new") })
            assertTrue("left-over pending: $journalNames", journalNames.none { it.endsWith(".pending") })
        } finally {
            source.toFile().deleteRecursively()
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `skips journal directories even when they are named inside the archive`() = runBlocking {
        val target = Files.createTempDirectory("backup-restore-journal-skip")
        // finally 里也要能删到，所以 zip 建在 try 之外。
        val zip = Files.createTempFile("backup-restore-journal", ".zip")
        try {
            val archive = zipOf(
                ".tellev-writes/orphan.payload" to "orphan".toByteArray(),
                "chats/a.jsonl" to "kept".toByteArray(),
            )
            Files.write(zip, archive)

            val layout = StDirectoryLayout.fromRoot(target)
            val restored = BackupCoordinator(layout, json, JournaledFileWriter(layout.root)).importBackup(zip)

            assertEquals(listOf("chats/a.jsonl"), restored)
            assertArrayEquals("kept".toByteArray(), target.resolve("chats/a.jsonl").readBytes())
        } finally {
            Files.deleteIfExists(zip)
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aborts an entry that expands past the per entry bound`() = runBlocking {
        val target = Files.createTempDirectory("backup-restore-entry-bound")
        val zip = Files.createTempFile("backup-restore-entry", ".zip")
        try {
            // 64KB of zeros: the entry compresses to almost nothing, so only the
            // bytes actually read bound what reaches the data directory.
            val archive = zipOf(
                "small.txt" to "small".toByteArray(),
                "big.jsonl" to ByteArray(64 * 1024),
            )
            Files.write(zip, archive)

            val layout = StDirectoryLayout.fromRoot(target)
            val writer = JournaledFileWriter(layout.root)
            val coordinator = BackupCoordinator(layout, json, writer, maxEntryBytes = 4_096)

            // importBackup 是 suspend，而 JUnit4 的 ThrowingRunnable 不是 suspend
            // 功能接口：断言里自己起协程再调用。
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.importBackup(zip) }
            }

            assertTrue("unreadable failure: ${error.message}", error.message!!.contains("big.jsonl"))
            assertTrue("unreadable failure: ${error.message}", error.message!!.contains("restore limit"))
            // Entries accepted before the abort stay committed; the aborted one does not.
            assertArrayEquals("small".toByteArray(), target.resolve("small.txt").readBytes())
            assertFalse("aborted entry must not be committed", Files.exists(target.resolve("big.jsonl")))

            writer.sweep()
            val journalNames = target.resolve(JournaledFileWriter.JOURNAL_DIR_NAME).toFile().list()!!.toList()
            assertTrue("orphaned staging temp: $journalNames", journalNames.none { it.endsWith(".new") })
        } finally {
            Files.deleteIfExists(zip)
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aborts once the whole archive expands past the total bound`() = runBlocking {
        val target = Files.createTempDirectory("backup-restore-total-bound")
        val zip = Files.createTempFile("backup-restore-total", ".zip")
        try {
            val archive = zipOf(
                "one.txt" to ByteArray(40),
                "two.txt" to ByteArray(40),
                "three.txt" to ByteArray(40),
                "four.txt" to ByteArray(40),
            )
            Files.write(zip, archive)

            val layout = StDirectoryLayout.fromRoot(target)
            val writer = JournaledFileWriter(layout.root)
            val coordinator = BackupCoordinator(layout, json, writer, maxEntryBytes = 64, maxTotalBytes = 80)

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.importBackup(zip) }
            }

            assertTrue("unreadable failure: ${error.message}", error.message!!.contains("three.txt"))
            // The bound is only observable after the entry that crosses it was
            // streamed, so the restore stops there instead of finishing the archive.
            assertTrue(Files.exists(target.resolve("one.txt")))
            assertTrue(Files.exists(target.resolve("two.txt")))
            assertTrue(Files.exists(target.resolve("three.txt")))
            assertFalse("restore must not continue past the total bound", Files.exists(target.resolve("four.txt")))
        } finally {
            Files.deleteIfExists(zip)
            target.toFile().deleteRecursively()
        }
    }

    @Test
    fun `aborts past the entry count bound`() = runBlocking {
        val target = Files.createTempDirectory("backup-restore-count-bound")
        val zip = Files.createTempFile("backup-restore-count", ".zip")
        try {
            val archive = zipOf(
                "one.txt" to "one".toByteArray(),
                "two.txt" to "two".toByteArray(),
                "three.txt" to "three".toByteArray(),
            )
            Files.write(zip, archive)

            val layout = StDirectoryLayout.fromRoot(target)
            val coordinator = BackupCoordinator(layout, json, JournaledFileWriter(layout.root), maxEntries = 2)

            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { coordinator.importBackup(zip) }
            }

            assertTrue("unreadable failure: ${error.message}", error.message!!.contains("entries"))
            assertFalse("archive past the entry bound must not be fully restored", Files.exists(target.resolve("three.txt")))
        } finally {
            Files.deleteIfExists(zip)
            target.toFile().deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                if (bytes != null) zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
