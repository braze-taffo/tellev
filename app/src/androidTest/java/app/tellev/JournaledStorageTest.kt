package app.tellev

import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.storage.JournaledFileWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/** Exercises the Android filesystem provider (including directory fsync), in isolated test data. */
class JournaledStorageTest {
    @Test fun acceptedJournalRecoversWithoutDuplicatingTheOperation() {
        val root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "journal-validation-")
        try {
            for (stage in JournaledFileWriter.Stage.entries) {
                val directory = Files.createDirectory(root.resolve(stage.name))
                val target = directory.resolve("chat.jsonl")
                Files.write(target, "original".toByteArray())
                val interrupted = JournaledFileWriter(directory) { if (it == stage) throw IOException("injected at $stage") }
                val failure = runCatching { interrupted.write(target, "next".toByteArray(), "accepted") }.exceptionOrNull()
                assertEquals("injected at $stage", failure?.message)
                val recovered = JournaledFileWriter(directory)
                val receipts = recovered.recover()
                if (stage == JournaledFileWriter.Stage.PAYLOAD_SYNCED) {
                    assertTrue(receipts.isEmpty())
                    assertEquals("original", String(Files.readAllBytes(target)))
                } else {
                    assertEquals(1, receipts.size)
                    assertEquals("next", String(Files.readAllBytes(target)))
                    assertEquals(1L, recovered.write(target, "next".toByteArray(), "accepted").revision)
                    assertTrue(recovered.recover().isEmpty())
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
