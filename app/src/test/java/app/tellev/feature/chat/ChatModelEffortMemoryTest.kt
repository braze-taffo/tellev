package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChatModelEffortMemoryTest {

    @Test
    fun `writes reads and clears per-model effort memory`() {
        val root = Files.createTempDirectory("effort-mem")
        try {
            assertTrue(ChatModelEffortMemory.write(root, mapOf("deepseek-chat" to "High", "gpt-4o" to "Off")))
            val read = ChatModelEffortMemory.read(root)
            assertEquals("High", read.getValue("deepseek-chat").name)
            assertEquals("Off", read.getValue("gpt-4o").name)

            // 移除一个、更新一个：Auto 提交即清除该模型的记忆。
            assertTrue(ChatModelEffortMemory.write(root, mapOf("deepseek-chat" to "Low")))
            val updated = ChatModelEffortMemory.read(root)
            assertEquals("Low", updated.getValue("deepseek-chat").name)
            assertTrue("gpt-4o" !in updated)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing or corrupt file reads as empty`() {
        val root = Files.createTempDirectory("effort-mem-empty")
        try {
            assertTrue(ChatModelEffortMemory.read(root).isEmpty())
            root.resolve("model-efforts.json").toFile().writeText("{ not json")
            assertTrue(ChatModelEffortMemory.read(root).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
