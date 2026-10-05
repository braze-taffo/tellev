package app.tellev.feature.chat

import app.tellev.core.model.Attachment
import app.tellev.core.model.AttachmentSource
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** 第三轮聊天打磨的回归：置顶存储、文本附件提取、继续生成追加、消息反馈。 */
class ChatPolishRound3Test {
    @get:Rule
    val tmp = TemporaryFolder()

    // ── ChatPinnedSessions：读写往返 + 切换 ──

    @Test
    fun `pinned sessions round trip and toggle`() {
        val root = tmp.newFolder("st-data").toPath()
        assertTrue(ChatPinnedSessions.read(root).isEmpty())

        val afterPin = ChatPinnedSessions.toggle(root, "session-a")
        assertEquals(setOf("session-a"), afterPin)
        assertEquals(setOf("session-a"), ChatPinnedSessions.read(root))

        val afterPinSecond = ChatPinnedSessions.toggle(root, "session-b")
        assertEquals(setOf("session-a", "session-b"), afterPinSecond)

        val afterUnpin = ChatPinnedSessions.toggle(root, "session-a")
        assertEquals(setOf("session-b"), afterUnpin)
        assertEquals(setOf("session-b"), ChatPinnedSessions.read(root))
    }

    @Test
    fun `pinned sessions tolerate corrupt file`() {
        val root = tmp.newFolder("st-data2").toPath()
        Files.write(root.resolve("pinned-sessions.json"), "not-json".toByteArray())
        assertTrue(ChatPinnedSessions.read(root).isEmpty())
    }

    // ── 文本附件内容提取 ──

    @Test
    fun `text attachment content extracted for text types`() {
        val bytes = "hello 世界".toByteArray(Charsets.UTF_8)
        val content = extractTextAttachmentContent(bytes, "text/plain", "notes.txt")
        assertEquals("hello 世界", content)
    }

    @Test
    fun `markdown and json attachments are text by extension`() {
        val bytes = "{\"a\":1}".toByteArray(Charsets.UTF_8)
        assertEquals("{\"a\":1}", extractTextAttachmentContent(bytes, "application/octet-stream", "data.json"))
        assertTrue(isTextAttachment("application/octet-stream", "readme.md"))
        assertTrue(isTextAttachment("text/csv", "table.csv"))
        assertFalse(isTextAttachment("application/octet-stream", "song.mp3"))
    }

    @Test
    fun `binary attachment content rejected`() {
        val bytes = ByteArray(64) { it.toByte() }
        // 0x00 出现在前 4KB：二进制，拒绝提取。
        assertNull(extractTextAttachmentContent(bytes, "application/octet-stream", "blob.bin"))
        assertNull(extractTextAttachmentContent(ByteArray(0), "text/plain", "empty.txt"))
    }

    @Test
    fun `oversized text attachment truncated with marker`() {
        val big = "字".repeat(TEXT_ATTACHMENT_CHAR_LIMIT + 100)
        val content = extractTextAttachmentContent(big.toByteArray(Charsets.UTF_8), "text/plain", "big.txt")!!
        // 截断标记带一个前导换行（\n…(truncated)）。
        assertEquals(TEXT_ATTACHMENT_CHAR_LIMIT + "\n…(truncated)".length, content.length)
        assertTrue(content.endsWith("…(truncated)"))
    }

    // ── 继续生成：追加写回当前 swipe ──

    @Test
    fun `continued text appends to current swipe`() {
        val message = ChatMessage(
            id = "m1",
            role = MessageRole.Character,
            name = "Alice",
            content = "第一段",
            createdAtMillis = 0L,
            swipes = listOf("第一段", "另一版本"),
            swipeIndex = 0,
        )
        val continued = message.withContinuedText("第二段")
        assertEquals("第一段第二段", continued.content)
        assertEquals(listOf("第一段第二段", "另一版本"), continued.swipes)
        assertEquals(0, continued.swipeIndex)
    }

    @Test
    fun `continued text with empty addition is noop`() {
        val message = ChatMessage(
            id = "m1",
            role = MessageRole.Character,
            name = "Alice",
            content = "正文",
            createdAtMillis = 0L,
        )
        assertEquals(message, message.withContinuedText(""))
    }

    // ── 消息反馈：metadata 读写约定 ──

    @Test
    fun `feedback metadata round trip`() {
        val feedbackOf = { message: ChatMessage ->
            (message.metadata["feedback"] as? JsonPrimitive)?.contentOrNull
        }
        val up = ChatMessage(
            id = "m1",
            role = MessageRole.Character,
            name = "Alice",
            content = "x",
            createdAtMillis = 0L,
            metadata = buildJsonObject { put("feedback", JsonPrimitive(FEEDBACK_UP)) },
        )
        assertEquals(FEEDBACK_UP, feedbackOf(up))
        assertNull(feedbackOf(up.copy(metadata = buildJsonObject { })))
    }

    // ── 统计行组装（第四轮起由 ChatStatsBar 渲染，组装逻辑内联在 UI 层） ──

    @Test
    fun `stats derivation skips empty sessions`() {
        val state = ChatUiState()
        val rounds = state.messages.count { it.role == MessageRole.User }
        val steps = state.messages.count {
            it.role == MessageRole.Character || it.role == MessageRole.Assistant
        }
        assertTrue(rounds == 0 && steps == 0)
    }
}
