package app.tellev.core.storage.repository

import app.tellev.core.storage.JournaledFileWriter
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** M10: the in-place recover-retry ChatRepository uses must cover every repository. */
class StorageFileOpsTest {

    @Test
    fun `durable writes self-heal a half-committed journal record`() {
        val root = Files.createTempDirectory("storage-ops-test")
        try {
            val target = root.resolve("worlds").resolve("book.json")
            val writer = JournaledFileWriter(root) {
                if (it == JournaledFileWriter.Stage.PREPARED) throw IOException("injected failure")
            }
            assertThrows(IOException::class.java) {
                StorageFileOps.durableWriteText(writer, target, "first")
            }
            // The interrupted commit left a pending record; a bare JournaledFileWriter.write
            // would now refuse with "Unrecovered write". The shared op replays it instead.
            // A fresh writer (no fault injection) stands in for the next process.
            val healed = JournaledFileWriter(root)
            StorageFileOps.durableWriteText(healed, target, "second")
            assertEquals("second", target.readText())
            assertEquals(2L, healed.revision(target))
            // Idempotent: further writes keep working.
            StorageFileOps.durableWriteText(healed, target, "third")
            assertEquals("third", target.readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `durable writes still surface genuine recovery conflicts`() {
        val root = Files.createTempDirectory("storage-ops-conflict-test")
        try {
            val target = root.resolve("worlds").resolve("book.json")
            val writer = JournaledFileWriter(root) {
                if (it == JournaledFileWriter.Stage.PREPARED) throw IOException("injected failure")
            }
            assertThrows(IOException::class.java) {
                StorageFileOps.durableWriteText(writer, target, "pending")
            }
            // External change under the pending write: recover() must refuse,
            // and the op must not guess the conflict away.
            target.parent.toFile().mkdirs()
            Files.write(target, "tampered".toByteArray())
            assertThrows(IllegalStateException::class.java) {
                StorageFileOps.durableWriteText(JournaledFileWriter(root), target, "next")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
