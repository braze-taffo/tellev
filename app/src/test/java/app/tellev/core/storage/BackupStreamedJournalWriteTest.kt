package app.tellev.core.storage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T13 (TELLER-2-2): the streamed [JournaledFileWriter.write] twin of the byte
 * variant. Restoring an archive entry hands the writer an InputStream, so the
 * payload must reach the journal and the target without ever being held whole —
 * and with the same atomic, recoverable commit as the byte variant.
 */
class BackupStreamedJournalWriteTest {
    private val root = Files.createTempDirectory("mvu-journal-stream-test")
    @After fun clean() { root.toFile().deleteRecursively() }

    @Test
    fun `a streamed write lands the payload and leaves nothing behind in the journal`() {
        val target = root.resolve("chats").resolve("a.jsonl")
        val payload = "hello stream".repeat(40_000)

        val receipt = JournaledFileWriter(root).write(target, ByteArrayInputStream(payload.toByteArray()), "stream-op")

        assertEquals(payload, target.readText())
        assertEquals(1L, receipt.revision)
        assertEquals(sha256(payload.toByteArray()), receipt.sha256)
        val journalNames = journalNames()
        assertTrue("left-over payload: $journalNames", journalNames.none { it.endsWith(".payload") })
        assertTrue("left-over temp: $journalNames", journalNames.none { it.endsWith(".new") })
        assertTrue("left-over pending: $journalNames", journalNames.none { it.endsWith(".pending") })
    }

    @Test
    fun `a streamed write chunks a payload larger than the copy buffer`() {
        val target = root.resolve("chats").resolve("big.bin")
        val payload = ByteArray(3 * 1024 * 1024) { (it * 31 % 256).toByte() }

        JournaledFileWriter(root).write(target, ByteArrayInputStream(payload), "stream-big")

        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `an interrupted streamed write recovers exactly like the byte variant`() {
        val target = root.resolve("chats").resolve("b.jsonl")
        Files.createDirectories(target.parent)
        target.writeText("original")
        val failing = JournaledFileWriter(root) {
            if (it == JournaledFileWriter.Stage.PREPARED) throw IOException("injected failure")
        }

        assertThrows(IOException::class.java) {
            failing.write(target, ByteArrayInputStream("replacement".toByteArray()), "stream-pending")
        }
        assertEquals("original", target.readText())

        val recovered = JournaledFileWriter(root)
        assertEquals(1, recovered.recover().size)
        assertEquals("replacement", target.readText())
        assertEquals(1L, recovered.revision(target))
    }

    @Test
    fun `a streamed replay is idempotent and refuses a different payload`() {
        val target = root.resolve("chats").resolve("c.jsonl")
        val writer = JournaledFileWriter(root)
        writer.write(target, ByteArrayInputStream("one".toByteArray()), "replayed")
        writer.write(target, ByteArrayInputStream("two".toByteArray()), "later")

        assertEquals(1L, writer.write(target, ByteArrayInputStream("one".toByteArray()), "replayed").revision)
        assertEquals("two", target.readText())

        val error = assertThrows(IllegalStateException::class.java) {
            writer.write(target, ByteArrayInputStream("changed".toByteArray()), "replayed")
        }
        assertTrue("unreadable failure: ${error.message}", error.message!!.contains("Operation ID reused"))
        assertEquals("two", target.readText())
    }

    @Test
    fun `a streamed write cannot escape the data root`() {
        val outside = root.resolve("..").resolve("escaped.jsonl").normalize()
        val error = assertThrows(IllegalArgumentException::class.java) {
            JournaledFileWriter(root).write(outside, ByteArrayInputStream("nope".toByteArray()), "escape")
        }
        assertTrue("unreadable failure: ${error.message}", error.message!!.contains("Storage destination escapes data root"))
    }

    private fun journalNames(): List<String> =
        root.resolve(JournaledFileWriter.JOURNAL_DIR_NAME).toFile().list()!!.toList()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
