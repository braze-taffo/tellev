package app.tellev.core.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole

/**
 * M3: the extension whole-file save used to overwrite without any revision
 * check. With expectedRevision wired through, a writer that committed between
 * the reader's snapshot and the save must fail loudly instead of clobbering.
 */
class ChatSaveCasTest {

    private fun session(id: String, marker: String) = ChatSession(
        id = id,
        title = "Talk",
        characterId = "char",
        groupId = null,
        messages = listOf(
            ChatMessage(id = "m1", role = MessageRole.User, name = "Bob", content = "hello", createdAtMillis = 1L),
        ),
        metadata = buildJsonObject { put("marker", JsonPrimitive(marker)) },
    )

    @Test
    fun `saveChatSession with a stale expectedRevision fails instead of clobbering`() = runBlocking {
        val root = Files.createTempDirectory("chat-cas-")
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        try {
            store.bootstrap()
            store.saveChatSession(session("chat", "before"))
            val snapshot = store.readChatSession("chat")

            // 一个并发写先落地（推进 journal revision）。
            store.saveChatSession(session("chat", "concurrent"))

            // 基于旧快照的整文件保存必须被拒绝。
            val outcome = runCatching {
                store.saveChatSession(session("chat", "stale"), expectedRevision = snapshot.storageRevision)
            }
            val message = outcome.exceptionOrNull()?.message.orEmpty()
            assertTrue("expected Stale write, got: $message", message.contains("Stale write"))
            assertEquals("concurrent", store.readChatSession("chat").metadata["marker"]?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `saveChatSession with the current expectedRevision commits`() = runBlocking {
        val root = Files.createTempDirectory("chat-cas-ok-")
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        try {
            store.bootstrap()
            store.saveChatSession(session("chat", "before"))
            val snapshot = store.readChatSession("chat")

            store.saveChatSession(session("chat", "fresh"), expectedRevision = snapshot.storageRevision)

            assertEquals("fresh", store.readChatSession("chat").metadata["marker"]?.jsonPrimitive?.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
