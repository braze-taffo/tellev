package app.tellev.core.storage

import app.tellev.core.model.*
import app.tellev.core.prompt.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GeneratedImageIsolationTest {
    @Test fun `legacy image rows migrate to gallery without changing narrative snapshots or prompt depth`() = runBlocking {
        val root = Files.createTempDirectory("image-isolation")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val variables = buildJsonObject { putJsonObject("stat_data") { put("magic", 88) } }
            val first = ChatMessage("first", MessageRole.Character, "Alice", "A girl walking home.", 1L,
                variables = listOf(variables), isEjsProcessed = listOf(JsonPrimitive(true)))
            val picture = ChatMessage("image", MessageRole.Character, "Alice", "【图片】", 2L,
                attachments = listOf(Attachment("file", "picture.png", "image/png", "user/images/picture.png")),
                metadata = buildJsonObject {
                    put("image_prompt", "An unrelated generated landscape.")
                    put("image_engine", "comfyui"); put("image_mode", "scene")
                }, variables = listOf(buildJsonObject { put("legacy_image_variable", 123) }))
            // User-supplied vision attachments remain legitimate chat input.
            val user = ChatMessage("user", MessageRole.User, "User", "Continue walking.", 3L,
                attachments = listOf(Attachment("upload", "upload.png", "image/png", "user/upload.png")),
                variables = listOf(variables), variablesInitialized = listOf(JsonPrimitive(true)))
            val session = ChatSession("scene", "Scene", "alice", null, listOf(first, picture, user),
                metadata = buildJsonObject { put("variables", variables) })
            store.saveChatSession(session)
            val originalPath = store.layout.chats.resolve("alice/scene.jsonl")
            val original = originalPath.toFile().readText().lines()
            val migrated = store.readChatSession("scene")
            assertEquals(listOf("first", "user"), migrated.messages.map { it.id })
            assertEquals(session.metadata, migrated.metadata.filterKeys { it != "title" && it != "session_id" }.let(::JsonObject))
            assertEquals(first.variables, migrated.messages[0].variables)
            assertEquals(user.variablesInitialized, migrated.messages[1].variablesInitialized)
            assertEquals(user.attachments, migrated.messages[1].attachments)
            assertEquals(listOf(original[0], original[1], original[3]), originalPath.toFile().readText().lines())
            val archived = GeneratedImageStore(store.layout).read("scene").single()
            assertEquals(picture.content, archived.legacyMessage!!.content)
            assertEquals(picture.variables, archived.legacyMessage.variables)
            assertEquals("An unrelated generated landscape.", archived.prompt)
            assertEquals(picture.attachments, archived.attachments)
            val afterBytes = Files.readAllBytes(originalPath)
            val reopened = FileStDataStore(store.layout).readChatSession("scene")
            assertEquals(migrated, reopened)
            assertArrayEquals(afterBytes, Files.readAllBytes(originalPath))
            assertEquals(1, GeneratedImageStore(store.layout).read("scene").size)

            fun prompt(messages: List<ChatMessage>) = DefaultPromptEngine().build(PromptBuildRequest(
                character = CharacterCard("alice", "Alice", description = "Latest: {{lastMessage}}"),
                persona = null, messages = messages, worldBooks = emptyList(),
                preset = GenerationPreset("rp", "RP", "openai-compatible"),
                userInput = "What happens next?", providerType = "openai-compatible",
                metadata = buildJsonObject {
                    putJsonObject("injectedPrompts") {
                        putJsonObject("mvu") {
                            put("value", "Magic: 88"); put("position", 1); put("depth", 1); put("role", "system")
                        }
                    }
                },
            ))
            assertEquals(prompt(listOf(first, user)), prompt(reopened.messages))
            assertFalse(prompt(reopened.messages).messages.any { it.content.contains("landscape") || it.content.contains("【图片】") })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `gallery writes do not change chat bytes revision or another sessions gallery`() = runBlocking {
        val root = Files.createTempDirectory("image-gallery")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            store.saveChatSession(ChatSession("chat", "Chat", "alice", null, emptyList()))
            val before = store.readChatSession("chat")
            val path = store.layout.chats.resolve("alice/chat.jsonl")
            val bytes = Files.readAllBytes(path)
            val gallery = GeneratedImageStore(store.layout)
            val record = GeneratedImage("one", 1, emptyList(), "A girl walking home.")
            gallery.append("chat", listOf(record))
            gallery.append("chat", listOf(record))
            assertEquals(listOf(record), GeneratedImageStore(store.layout).read("chat"))
            assertTrue(gallery.read("another").isEmpty())
            assertEquals(before, store.readChatSession("chat"))
            assertArrayEquals(bytes, Files.readAllBytes(path))
        } finally { root.toFile().deleteRecursively() }
    }
}
