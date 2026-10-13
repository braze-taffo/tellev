package app.tellev.feature.settings.controller

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * T13 (TELLER-2-2): the SAF import entry stages a picked backup in bounded memory
 * or refuses it — a cap on the bytes copied, and a signature check that fails a
 * non-ZIP document before its body is transferred.
 */
class BackupImportStagingTest {

    @Test
    fun `stages a well formed archive byte for byte`() {
        val root = Files.createTempDirectory("staging-ok")
        try {
            val archive = zipOf(
                "chats/a.jsonl" to "hello".repeat(2_000).toByteArray(),
                "backgrounds/bg.png" to ByteArray(300_000) { (it % 251).toByte() },
            )
            val target = root.resolve("staged.zip")

            val staged = BackupImportStaging.stage(ByteArrayInputStream(archive), target.toFile())

            assertEquals(archive.size.toLong(), staged)
            assertArrayEquals(archive, target.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `refuses a document past the byte cap`() {
        val root = Files.createTempDirectory("staging-too-large")
        try {
            // 8 KiB 的低压缩率载荷：stage 的上限数的是「实际拷贝的字节」，而
            // 'x'.repeat(8_192) deflate 后整个归档只有 ~150 字节，根本碰不到上限。
            val archive = zipOf("chats/a.jsonl" to incompressible(8_192))

            val error = assertThrows(IOException::class.java) {
                BackupImportStaging.stage(
                    ByteArrayInputStream(archive),
                    root.resolve("staged.zip").toFile(),
                    maxBytes = 1_024,
                )
            }

            assertEquals(UiStrings.get(S.main_error_too_large), error.message)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a non zip document before any of its body is copied`() {
        val root = Files.createTempDirectory("staging-not-zip")
        try {
            val target = root.resolve("staged.zip")

            val error = assertThrows(IOException::class.java) {
                BackupImportStaging.stage(
                    ByteArrayInputStream("plain text, definitely not a zip".toByteArray()),
                    target.toFile(),
                )
            }

            assertEquals(UiStrings.get(S.main_error_read_file), error.message)
            assertFalse("a non-ZIP pick must not leave a staged file behind", target.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a truncated head instead of treating it as an archive`() {
        val root = Files.createTempDirectory("staging-short")
        try {
            val error = assertThrows(IOException::class.java) {
                BackupImportStaging.stage(ByteArrayInputStream(byteArrayOf(0x50, 0x4B)), root.resolve("s.zip").toFile())
            }
            assertEquals(UiStrings.get(S.main_error_read_file), error.message)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `accepts an empty archive whose signature is the end of central directory`() {
        val root = Files.createTempDirectory("staging-empty")
        try {
            val empty = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { } }.toByteArray()

            assertEquals(empty.size.toLong(), BackupImportStaging.stage(ByteArrayInputStream(empty), root.resolve("s.zip").toFile()))
            assertArrayEquals(empty, root.resolve("s.zip").readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `assembles the signature even when the source dribbles one byte at a time`() {
        val root = Files.createTempDirectory("staging-dribble")
        try {
            val archive = zipOf("a.jsonl" to "b".toByteArray())

            val staged = BackupImportStaging.stage(SingleByteStream(archive), root.resolve("s.zip").toFile())

            assertEquals(archive.size.toLong(), staged)
            assertArrayEquals(archive, root.resolve("s.zip").readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Deterministic pseudo-random bytes: deflate cannot shrink them the way it
     *  shrinks a run of one repeated byte, so the staged size stays ~= the input. */
    private fun incompressible(size: Int): ByteArray {
        var state = 0x2545F491
        return ByteArray(size) {
            state = state * 1664525 + 1013904223
            (state ushr 24).toByte()
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Hands out one byte per read, so a signature read needs several calls. */
    private class SingleByteStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xFF else -1
    }
}
