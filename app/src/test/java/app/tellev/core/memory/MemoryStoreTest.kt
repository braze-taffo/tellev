package app.tellev.core.memory

import app.tellev.core.storage.StDirectoryLayout
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreTest {
    private fun newLayout(): StDirectoryLayout = StDirectoryLayout.fromRoot(Files.createTempDirectory("memory-store-test"))

    @Test
    fun `write and read round-trip only for existing chats`() = runBlocking {
        val layout = newLayout()
        try {
            val store = MemoryStore(layout)
            // No chat file: the write must be a no-op and read stays null.
            store.write("ghost", MemoryDocument.empty(MemoryMode.ARCHIVE))
            assertNull(store.read("ghost"))

            layout.chats.createDirectories()
            layout.chats.resolve("chat1.jsonl").writeText("{}\n")
            store.write("chat1", MemoryDocument.empty(MemoryMode.ARCHIVE))
            assertEquals(MemoryMode.ARCHIVE.name, store.read("chat1")?.mode)
        } finally {
            layout.root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt document is quarantined instead of wedging the feature`() = runBlocking {
        val layout = newLayout()
        try {
            layout.chats.createDirectories()
            layout.chats.resolve("chat1.jsonl").writeText("{}\n")
            val store = MemoryStore(layout)
            store.write("chat1", MemoryDocument.empty(MemoryMode.ARCHIVE))
            val memoryDir = layout.root.resolve("memory")
            val document = memoryDir.listDirectoryEntries("*.json").single()
            document.writeText("{ broken")

            assertNull(store.read("chat1"))

            val quarantined = memoryDir.listDirectoryEntries("*.corrupt-*")
            assertEquals(1, quarantined.size)

            // The next write rebuilds the document; reads work again.
            store.write("chat1", MemoryDocument.empty(MemoryMode.EPISODIC))
            assertEquals(MemoryMode.EPISODIC.name, store.read("chat1")?.mode)
            assertNotNull(store.read("chat1"))
            assertTrue(store.read("chat1") is MemoryDocument)
        } finally {
            layout.root.toFile().deleteRecursively()
        }
    }
}
