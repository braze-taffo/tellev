package app.tellev.core.storage

import app.tellev.core.model.*
import app.tellev.core.storage.repository.ChatSessionSummaryReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

class ChatSessionSummaryTest {
    @Test fun `summary preserves titles and ordering without retaining message histories`() = runBlocking {
        val root = Files.createTempDirectory("chat-summary-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val books = (0 until 12).map { n ->
                ChatSession("s$n", "会话 $n", "card", null,
                    listOf(ChatMessage("m$n", MessageRole.Character, "角色", "正文", n * 1000L)))
            }
            books.forEach { store.saveChatSession(it) }
            assertEquals(store.listChatSessions("card").map { it.toSummary() }, store.listChatSessionSummaries("card"))
            val updated = books[0].copy(title = "重命名", messages = listOf(
                ChatMessage("new", MessageRole.User, "用户", "新消息", 50000L)))
            store.saveChatSession(updated)
            assertEquals(updated.toSummary(), store.listChatSessionSummaries("card").first())
            store.saveChatSession(ChatSession("group", "群聊", null, "party", emptyList()))
            assertEquals("group", store.listChatSessionSummaries(groupId = "party").single().id)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `tail reads handle utf8 block boundaries blank lines and legacy headerless files`() {
        val root = Files.createTempDirectory("chat-summary-lines-")
        try {
            val reader = ChatSessionSummaryReader(Json)
            val file = root.resolve("legacy.jsonl")
            val line = """{"name":"角色","mes":"${"中文内容".repeat(6000)}","send_date":"2026-01-02T00:00:00Z"}"""
            file.writeText("\r\n" + line + "\r\n\r\n")
            assertEquals(1767312000000L, reader.read(file).lastMessageAtMillis)
            assertEquals("legacy", reader.read(file).title)
            file.writeText("""{"user_name":"User","chat_metadata":{"title":"空会话"}}""")
            assertEquals(0L, reader.read(file).lastMessageAtMillis)
            assertEquals("空会话", reader.read(file).title)
            file.writeText("")
            assertEquals(0L, reader.read(file).lastMessageAtMillis)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `listing neither parses middle history nor migrates old generated images`() = runBlocking {
        val root = Files.createTempDirectory("chat-summary-legacy-")
        try {
            val layout = StDirectoryLayout.fromRoot(root)
            val store = FileStDataStore(layout)
            store.bootstrap()
            val parent = layout.chats.resolve("card").createDirectories()
            val file = parent.resolve("old.jsonl")
            file.writeText(listOf(
                """{"user_name":"User","chat_metadata":{"title":"旧会话"}}""",
                // Deliberately ill-typed middle message: a full parser would fail.
                """{"name":{},"mes":"inactive history"}""",
                """{"name":"角色","mes":"最后一条","send_date":"2026-01-02T00:00:00Z"}""",
                """{"name":"角色","mes":"image","send_date":"2026-02-02T00:00:00Z","extra":{"image_prompt":"p","image_engine":"e"},"attachments":[{"id":"img","name":"image","mimeType":"image/png","relativePath":"user/images/a.png"}]}"""
            ).joinToString("\n"))
            val before = file.readBytes()
            val summary = store.listChatSessionSummaries("card").single()
            assertEquals("旧会话", summary.title)
            assertEquals(1767312000000L, summary.lastMessageAtMillis)
            assertArrayEquals(before, file.readBytes())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `large history comparison exceeds the full session cache capacity`() = runBlocking {
        val root = Files.createTempDirectory("chat-summary-scale-")
        try {
            val layout = StDirectoryLayout.fromRoot(root)
            val store = FileStDataStore(layout)
            store.bootstrap()
            val parent = layout.chats.resolve("card").createDirectories()
            val message = """{"name":"角色","mes":"${"正文".repeat(800)}","send_date":"2026-01-02T00:00:00Z"}"""
            repeat(16) { n ->
                parent.resolve("s$n.jsonl").toFile().bufferedWriter().use { out ->
                    out.appendLine("""{"user_name":"User","chat_metadata":{"title":"会话$n"}}""")
                    repeat(400) { out.appendLine(message) }
                }
            }
            lateinit var summaries: List<ChatSessionSummary>
            val summaryTime = measureNanoTime { summaries = store.listChatSessionSummaries("card") }
            lateinit var full: List<ChatSession>
            val fullTime = measureNanoTime { full = store.listChatSessions("card") }
            assertEquals(full.map { it.toSummary() }, summaries)
            println("CHAT_ENTRY_SCALE sessions=16 messages=6400 summary_ms=${summaryTime / 1_000_000} full_ms=${fullTime / 1_000_000}")
        } finally { root.toFile().deleteRecursively() }
    }
}
