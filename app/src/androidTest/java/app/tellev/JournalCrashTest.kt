package app.tellev

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import app.tellev.core.storage.JournaledFileWriter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileOutputStream
import java.nio.file.Files

/** Opt-in two-process replay driven by tools/mvu/android-storage-replay.ps1. */
class JournalCrashTest {
    @Test fun processDeathRecovery() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("journalPhase")
        assumeTrue(phase == "prepare" || phase == "recover")
        val stage = JournaledFileWriter.Stage.valueOf(requireNotNull(arguments.getString("journalStage")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".mvuvalidation"))
        val root = context.cacheDir.toPath().resolve("mvu-process-crash").resolve(stage.name)
        val target = root.resolve("chat.jsonl")
        val old = "{\"变量\":{\"hp\":100},\"unknown\":[1,2]}\n".toByteArray()
        val next = "{\"变量\":{\"hp\":85},\"unknown\":[1,2]}\n".toByteArray()
        if (phase == "prepare") {
            root.toFile().deleteRecursively()
            Files.createDirectories(root)
            JournaledFileWriter(root).write(target, old, "initial")
            JournaledFileWriter(root) { reached ->
                if (reached == stage) {
                    FileOutputStream(root.resolve("reached.txt").toFile()).use { stream ->
                        stream.write(stage.name.toByteArray()); stream.fd.sync()
                    }
                    Process.killProcess(Process.myPid())
                    error("Process termination did not stop execution")
                }
            }.write(target, next, "update", 1)
            fail("Requested crash stage was not reached")
        } else {
            assertEquals(stage.name, String(Files.readAllBytes(root.resolve("reached.txt"))))
            val writer = JournaledFileWriter(root)
            val recovered = writer.recover()
            if (stage == JournaledFileWriter.Stage.PAYLOAD_SYNCED) {
                assertTrue(recovered.isEmpty())
                assertArrayEquals(old, Files.readAllBytes(target))
                assertEquals(1L, writer.revision(target))
            } else {
                assertEquals(1, recovered.size)
                assertArrayEquals(next, Files.readAllBytes(target))
                assertEquals(2L, writer.revision(target))
                assertEquals(2L, writer.write(target, next, "update", 1).revision)
            }
            assertTrue(writer.recover().isEmpty())
        }
    }
}
