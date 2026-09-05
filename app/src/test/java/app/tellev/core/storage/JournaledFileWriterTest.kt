package app.tellev.core.storage

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class JournaledFileWriterTest {
    private val root = Files.createTempDirectory("mvu-journal-test")
    private val target = root.resolve("chat.jsonl")
    @After fun clean() { root.toFile().deleteRecursively() }

    @Test fun `every accepted failure recovers exactly once and preserves original bytes`() {
        for (stage in JournaledFileWriter.Stage.entries) {
            val directory = Files.createDirectories(root.resolve(stage.name))
            val file = directory.resolve("chat.jsonl")
            file.writeText("original\n未知字段")
            val writer = JournaledFileWriter(directory) { if (it == stage) throw IOException("injected $stage") }
            assertThrows(IOException::class.java) { writer.write(file, "next\n变量".toByteArray(), "op") }
            if (stage <= JournaledFileWriter.Stage.PREPARED) assertEquals("original\n未知字段", file.readText())
            val recovered = JournaledFileWriter(directory)
            val receipts = recovered.recover()
            if (stage == JournaledFileWriter.Stage.PAYLOAD_SYNCED) {
                assertTrue(receipts.isEmpty()) // No accepted journal record, no operation to replay.
                assertEquals("original\n未知字段", file.readText())
            } else {
                assertEquals(1, receipts.size)
                assertEquals("next\n变量", file.readText())
                assertEquals(1L, recovered.revision(file))
                assertTrue(recovered.recover().isEmpty())
                assertEquals(1L, recovered.write(file, "next\n变量".toByteArray(), "op").revision)
                val backups = Files.list(directory.resolve(".tellev-writes")).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".original") }.toList()
                }
                assertEquals("original\n未知字段", backups.single().readText())
            }
        }
    }

    @Test fun `duplicate request cannot revert a later write or reuse an id with another payload`() {
        val writer = JournaledFileWriter(root)
        writer.write(target, "one".toByteArray(), "one", 0)
        writer.write(target, "two".toByteArray(), "two", 1)
        assertEquals(1L, writer.write(target, "one".toByteArray(), "one", 0).revision)
        assertEquals("two", target.readText())
        assertThrows(IllegalStateException::class.java) { writer.write(target, "changed".toByteArray(), "one") }
        assertThrows(IllegalStateException::class.java) { writer.write(target, "stale".toByteArray(), "three", 0) }
        assertEquals("two", target.readText())
    }

    @Test fun `unrecovered writes block new writes and external changes cause explicit conflicts`() {
        target.writeText("old")
        val writer = JournaledFileWriter(root) { if (it == JournaledFileWriter.Stage.PREPARED) throw IOException("disk full") }
        assertThrows(IOException::class.java) { writer.write(target, "pending".toByteArray(), "pending") }
        val next = JournaledFileWriter(root)
        assertThrows(IllegalStateException::class.java) { next.write(target, "new".toByteArray()) }
        target.writeText("external")
        assertThrows(IllegalStateException::class.java) { next.recover() }
        assertEquals("external", target.readText())
    }

    @Test fun `concurrent compare and commit has only one winner across writer instances`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (0..7).map { n -> pool.submit<Boolean> {
                gate.await()
                try { JournaledFileWriter(root).write(target, "$n".toByteArray(), "$n", 0); true }
                catch (_: IllegalStateException) { false }
            } }
            gate.countDown()
            assertEquals(1, results.count { it.get() })
            assertEquals(1L, JournaledFileWriter(root).revision(target))
        } finally { pool.shutdownNow() }
    }

    @Test fun `destinations cannot escape the data directory`() {
        val writer = JournaledFileWriter(root)
        assertThrows(IllegalArgumentException::class.java) { writer.write(root.resolve("../outside"), byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(root.resolve(".tellev-writes/file"), byteArrayOf()) }
    }

    @Test fun `deletion recovery does not resurrect data and duplicate deletes are idempotent`() {
        target.writeText("original")
        val writer = JournaledFileWriter(root) { if (it == JournaledFileWriter.Stage.REPLACED) throw IOException("stopped") }
        assertThrows(IOException::class.java) { writer.delete(target, "delete") }
        assertFalse(Files.exists(target))
        val recovered = JournaledFileWriter(root)
        assertEquals(1, recovered.recover().size)
        assertFalse(Files.exists(target))
        recovered.write(target, "recreated".toByteArray(), "create", 1)
        recovered.delete(target, "delete")
        assertEquals("recreated", target.readText())
    }
}
