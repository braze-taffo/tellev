package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileStDataStoreTest {

    private lateinit var tempDir: Path
    private lateinit var layout: StDirectoryLayout
    private lateinit var store: FileStDataStore

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tellev-test-")
        layout = StDirectoryLayout.fromRoot(tempDir)
        store = FileStDataStore(layout)
        runBlocking { store.bootstrap() }
    }

    @After
    fun tearDown() {
        // Clean up temp directory
        tempDir.toFile().deleteRecursively()
    }

    // ---- World Book Tests ----

    @Test
    fun `listWorldBooks parses ST format with numeric string keys`() = runBlocking {
        val worldBookJson = this::class.java.classLoader
            .getResourceAsStream("fixtures/world_book.json")
            ?.bufferedReader()?.readText()
            ?: error("world_book.json fixture not found")

        layout.worlds.createDirectories()
        layout.worlds.resolve("test_world.json").writeText(worldBookJson)

        val books = store.listWorldBooks()
        assertEquals(1, books.size)

        val book = books[0]
        assertEquals("test_world", book.id)
        assertEquals(4, book.entries.size)

        // Verify first entry (selective)
        val forestEntry = book.entries.first { it.content.contains("dark forest") }
        assertEquals(listOf("forest", "woods"), forestEntry.keys)
        assertEquals(listOf("dark", "night"), forestEntry.secondaryKeys)
        assertEquals(true, forestEntry.selective)
        assertEquals(false, forestEntry.constant)
        assertEquals(true, forestEntry.enabled)
        assertEquals(4, forestEntry.depth)

        // Verify constant entry
        val constantEntry = book.entries.first { it.constant }
        assertEquals("This entry is always included in the context regardless of keyword matching.", constantEntry.content)

        // Verify disabled entry
        val disabledEntry = book.entries.first { !it.enabled }
        assertEquals(listOf("disabled_entry"), disabledEntry.keys)
        assertEquals(false, disabledEntry.enabled)
    }

    @Test
    fun `readWorldBook returns specific book by ID`() = runBlocking {
        layout.worlds.createDirectories()
        layout.worlds.resolve("lore.json").writeText("""
        {
            "name": "Lore Book",
            "entries": {
                "0": {
                    "uid": 0,
                    "key": ["dragon"],
                    "keysecondary": [],
                    "content": "Dragons are ancient creatures.",
                    "constant": false,
                    "selective": false,
                    "order": 100,
                    "disable": false,
                    "depth": 4
                }
            }
        }
        """.trimIndent())

        val book = store.readWorldBook("lore")
        assertEquals("Lore Book", book.name)
        assertEquals(1, book.entries.size)
        assertEquals("Dragons are ancient creatures.", book.entries[0].content)
    }

    @Test
    fun `saveWorldBook writes ST format and roundtrips`() = runBlocking {
        val book = WorldBook(
            id = "test_save",
            name = "Test Save Book",
            entries = listOf(
                WorldBookEntry(
                    id = "0",
                    keys = listOf("key1", "key2"),
                    secondaryKeys = listOf("skey1"),
                    content = "Test content for entry",
                    enabled = true,
                    selective = true,
                    constant = false,
                    priority = 5,
                    insertionOrder = 50,
                    depth = 3,
                ),
            ),
        )

        store.saveWorldBook(book)
        assertTrue(layout.worlds.resolve("test_save.json").exists())

        val loaded = store.readWorldBook("test_save")
        assertEquals("Test Save Book", loaded.name)
        assertEquals(1, loaded.entries.size)

        val entry = loaded.entries[0]
        assertEquals(listOf("key1", "key2"), entry.keys)
        assertEquals(listOf("skey1"), entry.secondaryKeys)
        assertEquals("Test content for entry", entry.content)
        assertEquals(true, entry.selective)
        assertEquals(50, entry.insertionOrder)
        assertEquals(3, entry.depth)
    }

    @Test
    fun `saveWorldBook preserves unknown raw entry fields`() = runBlocking {
        val entryRaw = buildJsonObject {
            put("uid", 42)
            put("probability", 37)
            put("comment", "keep me")
            put("extensions", buildJsonObject { put("source", "fixture") })
        }
        val book = WorldBook(
            id = "raw_preserve",
            name = "Raw Preserve",
            entries = listOf(
                WorldBookEntry(
                    id = "42",
                    keys = listOf("key"),
                    content = "Updated content",
                    raw = entryRaw,
                ),
            ),
            raw = buildJsonObject { put("scan_depth", 9) },
        )

        store.saveWorldBook(book)

        val saved = FileStDataStore.defaultJson
            .parseToJsonElement(layout.worlds.resolve("raw_preserve.json").readText())
            .jsonObject
        val entry = saved["entries"]!!.jsonObject["0"]!!.jsonObject
        assertEquals("9", saved["scan_depth"]!!.jsonPrimitive.content)
        assertEquals("42", entry["uid"]!!.jsonPrimitive.content)
        assertEquals("37", entry["probability"]!!.jsonPrimitive.content)
        assertEquals("keep me", entry["comment"]!!.jsonPrimitive.content)
        assertEquals("fixture", entry["extensions"]!!.jsonObject["source"]!!.jsonPrimitive.content)
    }

    // ---- Chat JSONL Tests ----

    @Test
    fun `readJsonlChat parses ST format with header line`() = runBlocking {
        val chatJsonl = this::class.java.classLoader
            .getResourceAsStream("fixtures/chat_sample.jsonl")
            ?.bufferedReader()?.readText()
            ?: error("chat_sample.jsonl fixture not found")

        val chatDir = layout.chats.resolve("alice")
        chatDir.createDirectories()
        chatDir.resolve("test_chat_001.jsonl").writeText(chatJsonl)

        val sessions = store.listChatSessions(characterId = "alice")
        assertEquals(1, sessions.size)

        val session = sessions[0]
        assertEquals("test_chat_001", session.id)
        assertEquals("alice", session.characterId)
        assertEquals(null, session.groupId)
        assertEquals(3, session.messages.size)

        // First message: character
        val firstMsg = session.messages[0]
        assertEquals(MessageRole.Character, firstMsg.role)
        assertEquals("Alice", firstMsg.name)
        assertTrue(firstMsg.content.startsWith("Hello!"))
        assertEquals(2, firstMsg.swipes.size)
        assertEquals(0, firstMsg.swipeIndex)

        // Second message: user
        val secondMsg = session.messages[1]
        assertEquals(MessageRole.User, secondMsg.role)
        assertEquals("You", secondMsg.name)
        assertTrue(secondMsg.content.contains("ancient castle"))

        // Third message: character
        val thirdMsg = session.messages[2]
        assertEquals(MessageRole.Character, thirdMsg.role)
        assertEquals("Alice", thirdMsg.name)

        // Verify send_date parsing
        assertTrue(firstMsg.createdAtMillis > 0L)
    }

    @Test
    fun `readJsonlChat keeps group and character session roots separate`() = runBlocking {
        val chatJsonl = this::class.java.classLoader
            .getResourceAsStream("fixtures/chat_sample.jsonl")
            ?.bufferedReader()?.readText()
            ?: error("chat_sample.jsonl fixture not found")

        val groupDir = layout.groupChats.resolve("party")
        groupDir.createDirectories()
        groupDir.resolve("group_chat_001.jsonl").writeText(chatJsonl)

        val sessions = store.listChatSessions(groupId = "party")
        assertEquals(1, sessions.size)

        val session = sessions[0]
        assertEquals("group_chat_001", session.id)
        assertEquals(null, session.characterId)
        assertEquals("party", session.groupId)
    }

    @Test
    fun `saveChatSession writes header line and messages`() = runBlocking {
        val session = ChatSession(
            id = "save_test",
            title = "Save Test Chat",
            characterId = "bob",
            groupId = null,
            messages = listOf(
                ChatMessage(
                    id = "save_test-0",
                    role = MessageRole.Character,
                    name = "Bob",
                    content = "Hello there!",
                    createdAtMillis = 1718443800000L,
                    swipes = listOf("Hello there!", "Hey!"),
                    swipeIndex = 0,
                ),
                ChatMessage(
                    id = "save_test-1",
                    role = MessageRole.User,
                    name = "You",
                    content = "Hi Bob!",
                    createdAtMillis = 1718443860000L,
                ),
            ),
        )

        store.saveChatSession(session)

        val chatFile = layout.chats.resolve("bob").resolve("save_test.jsonl")
        assertTrue(chatFile.exists())

        val lines = chatFile.readText().lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size) // header + 2 messages

        // Verify header line
        val headerJson = FileStDataStore.defaultJson.parseToJsonElement(lines[0]) as kotlinx.serialization.json.JsonObject
        assertNotNull(headerJson["user_name"])
        assertNotNull(headerJson["character_name"])

        // Re-read and verify
        val loaded = store.readChatSession("save_test")
        assertEquals(2, loaded.messages.size)
        assertEquals("Bob", loaded.messages[0].name)
        assertEquals(MessageRole.Character, loaded.messages[0].role)
        assertEquals("Hello there!", loaded.messages[0].content)
    }

    // ---- Group Parsing Tests ----

    @Test
    fun `listGroups parses members array`() = runBlocking {
        layout.groups.createDirectories()
        layout.groups.resolve("adventure_party.json").writeText("""
        {
            "id": "adventure_party",
            "name": "Adventure Party",
            "members": ["alice", "bob", "charlie"],
            "metadata": {}
        }
        """.trimIndent())

        val groups = store.listGroups()
        assertEquals(1, groups.size)

        val group = groups[0]
        assertEquals("adventure_party", group.id)
        assertEquals("Adventure Party", group.name)
        assertEquals(listOf("alice", "bob", "charlie"), group.memberCharacterIds)
    }

    @Test
    fun `listGroups handles missing members gracefully`() = runBlocking {
        layout.groups.createDirectories()
        layout.groups.resolve("empty_group.json").writeText("""
        {
            "id": "empty_group",
            "name": "Empty Group"
        }
        """.trimIndent())

        val groups = store.listGroups()
        assertEquals(1, groups.size)
        assertEquals("Empty Group", groups[0].name)
        assertEquals(emptyList<String>(), groups[0].memberCharacterIds)
    }

    @Test
    fun `saveGroup and listGroups roundtrip`() = runBlocking {
        val group = GroupChat(
            id = "test_group",
            name = "Test Group",
            memberCharacterIds = listOf("char_a", "char_b"),
        )

        store.saveGroup(group)

        val groups = store.listGroups()
        assertEquals(1, groups.size)
        assertEquals("Test Group", groups[0].name)
    }

    // ---- Backup Roundtrip Tests ----

    @Test
    fun `exportBackup and importBackup roundtrip preserves files`() = runBlocking {
        // Create some test data
        layout.characters.createDirectories()
        layout.characters.resolve("hero.json").writeText("""{"name":"Hero","description":"A brave hero"}""")

        layout.worlds.createDirectories()
        layout.worlds.resolve("lore.json").writeText("""{"name":"Lore","entries":{}}""")

        val chatDir = layout.chats.resolve("hero")
        chatDir.createDirectories()
        chatDir.resolve("chat1.jsonl").writeText("""{"user_name":"u","character_name":"c","chat_metadata":{}}
{"name":"Hero","is_user":false,"mes":"Hi","send_date":"","extra":{}}""")

        // Export
        val backupFile = tempDir.resolve("test_backup.zip")
        store.exportBackup(backupFile)
        assertTrue(backupFile.exists())

        // Import into a new directory
        val newTempDir = Files.createTempDirectory("tellev-restore-")
        val newLayout = StDirectoryLayout.fromRoot(newTempDir)
        val newStore = FileStDataStore(newLayout)
        newStore.bootstrap()

        newStore.importBackup(backupFile)

        // Verify files were restored
        assertTrue(newLayout.characters.resolve("hero.json").exists())
        assertTrue(newLayout.worlds.resolve("lore.json").exists())

        val restoredChat = newLayout.chats.resolve("hero").resolve("chat1.jsonl")
        assertTrue(restoredChat.exists())
        assertTrue(restoredChat.readText().contains("Hero"))

        // Cleanup
        newTempDir.toFile().deleteRecursively()
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importBackup rejects path traversal`() = runBlocking {
        // Create a malicious ZIP with path traversal
        val maliciousZipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("../../etc/passwd"))
                zos.write("malicious content".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val maliciousZip = tempDir.resolve("malicious.zip")
        maliciousZip.toFile().writeBytes(maliciousZipBytes)

        store.importBackup(maliciousZip)
    }

    // ---- Preset Tests ----

    @Test
    fun `bootstrap creates default chat preset when no presets exist`() = runBlocking {
        val presets = store.listPresets()
        val defaultPreset = presets.firstOrNull { it.id == "default" }

        assertNotNull(defaultPreset)
        val preset = requireNotNull(defaultPreset)
        assertEquals("默认聊天", preset.name)
        assertEquals(0.7, preset.temperature ?: -1.0, 0.0001)
        assertEquals(1.0, preset.topP ?: -1.0, 0.0001)
        assertEquals(null as Int?, preset.maxTokens)
    }

    @Test
    fun `savePreset routes to correct directory based on providerType`() = runBlocking {
        val openAiPreset = app.tellev.core.model.GenerationPreset(
            id = "gpt4_creative",
            name = "GPT-4 Creative",
            providerType = "openai",
            temperature = 0.9,
            maxTokens = 2048,
        )

        val textGenPreset = app.tellev.core.model.GenerationPreset(
            id = "llama_default",
            name = "LLaMA Default",
            providerType = "textgen",
            temperature = 0.7,
        )

        store.savePreset(openAiPreset)
        store.savePreset(textGenPreset)

        assertTrue(layout.openAiSettings.resolve("gpt4_creative.json").exists())
        assertTrue(layout.textGenSettings.resolve("llama_default.json").exists())
    }

    @Test
    fun `deletePreset removes the preset file instead of writing an empty preset`() = runBlocking {
        val preset = GenerationPreset(
            id = "delete_me",
            name = "Delete Me",
            providerType = "openai",
        )

        store.savePreset(preset)
        assertTrue(layout.openAiSettings.resolve("delete_me.json").exists())

        val deleted = store.deletePreset("delete_me", "openai")

        assertTrue(deleted)
        assertTrue(!layout.openAiSettings.resolve("delete_me.json").exists())
    }

    // ---- Character Tests ----

    @Test
    fun `listCharacters extracts name and tags from JSON`() = runBlocking {
        layout.characters.createDirectories()
        layout.characters.resolve("test_char.json").writeText("""
        {
            "spec": "chara_card_v2",
            "data": {
                "name": "Test Character",
                "description": "A test",
                "tags": ["fantasy", "warrior"]
            }
        }
        """.trimIndent())

        val chars = store.listCharacters()
        assertEquals(1, chars.size)
        assertEquals("Test Character", chars[0].name)
        assertEquals(listOf("fantasy", "warrior"), chars[0].tags)
    }

    @Test
    fun `saveCharacter writes SillyTavern V2 JSON format`() = runBlocking {
        val card = CharacterCard(
            id = "saved_char",
            name = "Saved Char",
            description = "A compatible card",
            tags = listOf("compat"),
        )

        store.saveCharacter(card)

        val saved = FileStDataStore.defaultJson
            .parseToJsonElement(layout.characters.resolve("saved_char.json").readText())
            .jsonObject
        assertEquals("chara_card_v2", saved["spec"]!!.jsonPrimitive.content)
        assertEquals("Saved Char", saved["data"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `readCharacter parses V2 JSON character`() = runBlocking {
        val v2Json = this::class.java.classLoader
            .getResourceAsStream("fixtures/character_v2.json")
            ?.bufferedReader()?.readText()
            ?: error("character_v2.json fixture not found")

        layout.characters.createDirectories()
        layout.characters.resolve("lyra.json").writeText(v2Json)

        val card = store.readCharacter("lyra")
        assertEquals("Lyra Nightwhisper", card.name)
        assertTrue(card.description.contains("elven scholar"))
        assertEquals(listOf("fantasy", "elf", "scholar", "library"), card.tags)
        assertNotNull(card.characterBook)
        assertEquals(1, card.characterBook!!.entries.size)
    }

    @Test
    fun `readCharacter parses PNG character with embedded metadata`() = runBlocking {
        val cardJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"PNG Hero","description":"Hero from PNG","tags":["action"]}}"""
        val png = PngCardParser.createMinimalPng()
        val embedded = PngCardParser.embedCardJson(png, cardJson)

        layout.characters.createDirectories()
        layout.characters.resolve("png_hero.png").toFile().writeBytes(embedded)

        val card = store.readCharacter("png_hero")
        assertEquals("PNG Hero", card.name)
        assertEquals("Hero from PNG", card.description)
    }

    @Test
    fun `importCharacter preserves PNG character card image`() = runBlocking {
        val cardJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Imported PNG","description":"Kept as PNG"}}"""
        val png = PngCardParser.createMinimalPng()
        val embedded = PngCardParser.embedCardJson(png, cardJson)
        val card = CharacterImporter().importFromBytes(embedded, "imported.png")

        store.importCharacter(card, embedded, "imported.png")

        assertTrue(layout.characters.resolve("imported_png.png").exists())
        assertTrue(!layout.characters.resolve("imported_png.json").exists())

        val reloaded = store.readCharacter("imported_png")
        assertEquals("Imported PNG", reloaded.name)
        assertEquals("Kept as PNG", reloaded.description)
    }

    @Test
    fun `importCharacter extracts embedded world book regex and tavern helper assets`() = runBlocking {
        val cardJson = """
        {
            "spec": "chara_card_v3",
            "spec_version": "3.0",
            "data": {
                "name": "Asset Card",
                "description": "Has embedded assets",
                "first_mes": "[start]",
                "extensions": {
                    "regex_scripts": [
                        {
                            "id": "r1",
                            "scriptName": "Render",
                            "findRegex": "/\\[start\\]/g",
                            "replaceString": "<body>ok</body>",
                            "placement": [2],
                            "disabled": false
                        }
                    ],
                    "TavernHelper_scripts": [{ "type": "script", "value": "console.log(1)" }],
                    "tavern_helper": [{ "type": "state", "name": "vars" }]
                },
                "character_book": {
                    "name": "Asset Book",
                    "entries": [
                        {
                            "keys": ["asset"],
                            "content": "Asset lore",
                            "enabled": true
                        }
                    ]
                }
            }
        }
        """.trimIndent()
        val png = PngCardParser.createMinimalPng()
        val embedded = PngCardParser.embedCardJson(png, cardJson)
        val card = CharacterImporter().importFromBytes(embedded, "asset.png")

        store.importCharacter(card, embedded, "asset.png")

        assertTrue(layout.worlds.resolve("asset_card_character_book.json").exists())
        assertTrue(layout.extensions.resolve("character-assets/asset_card/extensions.json").exists())
        assertTrue(layout.extensions.resolve("character-assets/asset_card/regex_scripts.json").exists())
        assertTrue(layout.extensions.resolve("character-assets/asset_card/TavernHelper_scripts.json").exists())
        assertTrue(layout.extensions.resolve("character-assets/asset_card/tavern_helper.json").exists())

        val reloaded = store.readCharacter("asset_card")
        val extensions = reloaded.raw["data"]!!.jsonObject["extensions"]!!.jsonObject
        assertEquals("Render", extensions["regex_scripts"]!!.jsonArray.first().jsonObject["scriptName"]!!.jsonPrimitive.content)
    }
}
