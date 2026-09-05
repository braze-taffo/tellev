package app.tellev.core.extension

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TavernChatMutationTest {
    @Test fun `normal data writes ignore swipe selection as in the fixed upstream`() {
        val message = ChatMessage("a", MessageRole.Character, "A", "one", 0,
            swipes = listOf("one", "two"), variables = listOf(buildJsonObject { put("n", 1) }))
        val updated = applyTavernChatMessages(listOf(message),
            Json.parseToJsonElement("""[{"message_id":-1,"swipe_id":1,"data":{"n":2}}]""").jsonArray).single()
        assertEquals("one", updated.content)
        assertEquals(2, updated.variables[0].jsonObject["n"]!!.jsonPrimitive.int)
        assertEquals(1, message.variables[0].jsonObject["n"]!!.jsonPrimitive.int)
    }

    @Test fun `nonexistent floors are skipped as in the fixed upstream`() {
        assertEquals(emptyList<ChatMessage>(), applyTavernChatMessages(emptyList(),
            Json.parseToJsonElement("""[{"message_id":0,"data":{}}]""").jsonArray))
    }

    @Test fun `independent upstream message mutation goldens survive native persistence`() = kotlinx.coroutines.runBlocking {
        val report = Json.parseToJsonElement(javaClass.classLoader!!.getResource("fixtures/upstream-message-mutations.json")!!.readText()).jsonObject
        for (case in report.getValue("cases").jsonArray) {
            val data = case.jsonObject
            val name = data.getValue("name").jsonPrimitive.content
            val root = java.nio.file.Files.createTempDirectory("tellev-message-golden-")
            try {
                val layout = app.tellev.core.storage.StDirectoryLayout.fromRoot(root)
                val store = app.tellev.core.storage.FileStDataStore(layout)
                store.bootstrap()
                val dir = layout.chats.resolve("fixture").toFile().apply { mkdirs() }
                val file = File(dir, "golden.jsonl")
                file.writeText("{\"user_name\":\"User\",\"character_name\":\"Fixture\",\"chat_metadata\":{}}\n" + data.getValue("input").jsonArray.joinToString("\n") + "\n")
                val before = store.readChatSession("golden")
                val changed = applyTavernChatMessages(before.messages, data.getValue("updates").jsonArray)
                store.commitChatMutation(before, before.copy(messages = changed))
                val actual = file.readLines().drop(1).map { Json.parseToJsonElement(it).jsonObject }.map { raw ->
                    // App-private stable ID is the sole declared nonsemantic field.
                    JsonObject(raw - "_tellev_message_id")
                }
                assertEquals(name, data.getValue("output").jsonArray.toList(), actual)
                val reread = store.readChatSession("golden")
                assertEquals(name, changed.map { it.variables }, reread.messages.map { it.variables })
                assertEquals(name, changed.map { it.swipeInfo }, reread.messages.map { it.swipeInfo })
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun `export actual host HTML for JavaScript integration replay`() {
        val field = WebViewJsExtensionHost::class.java.getDeclaredField("HTML_TEMPLATE")
        field.isAccessible = true
        File("build/compat-host.html").apply { parentFile.mkdirs(); writeText(field.get(null) as String) }
    }
}
