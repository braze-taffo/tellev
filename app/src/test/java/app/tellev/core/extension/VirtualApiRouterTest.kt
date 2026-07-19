package app.tellev.core.extension

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.model.PresetCategory
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class VirtualApiRouterTest {

    private lateinit var tempDir: Path
    private lateinit var layout: StDirectoryLayout
    private lateinit var store: FileStDataStore
    private lateinit var secretStore: InMemorySecretStore
    private lateinit var router: VirtualApiRouter
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tellev-api-test-")
        layout = StDirectoryLayout.fromRoot(tempDir)
        store = FileStDataStore(layout)
        secretStore = InMemorySecretStore()
        router = VirtualApiRouter(store, ProviderRegistry(emptyList()), secretStore)
        runBlocking { store.bootstrap() }
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `GET _version returns tellev version`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/version"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["version"]?.jsonPrimitive?.content?.contains("tellev") == true)
    }

    @Test
    fun `GET _api_characters returns empty list when no characters`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/characters"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["characters"]?.jsonArray?.isEmpty() == true)
    }

    // ── parseSimpleQuery URL-decoding (guard for the parseSimpleQuery fix) ──
    // Query params must be URL-decoded so %-encoded characters (and '+' as a
    // space) match real names. Before the fix the raw `My%20Char` / `My+Char`
    // was passed straight through and never matched a stored session whose
    // characterId contained a space.

    private suspend fun saveSessionFor(characterId: String) {
        store.saveChatSession(
            ChatSession(
                id = "chat-$characterId",
                title = "Talk",
                characterId = characterId,
                groupId = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "Bob",
                        content = "hello",
                        createdAtMillis = 1L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `list chats decodes percent-encoded space in characterId`() = runBlocking {
        saveSessionFor("My Char")
        val response = router.route(VirtualApiRequest("GET", "/api/chats?characterId=My%20Char"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals(1, body["chats"]?.jsonArray?.size)
    }

    @Test
    fun `list chats decodes plus as space in characterId`() = runBlocking {
        saveSessionFor("My Char")
        val response = router.route(VirtualApiRequest("GET", "/api/chats?characterId=My+Char"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals(1, body["chats"]?.jsonArray?.size)
    }

    @Test
    fun `list chats decodes non-ASCII characters in characterId`() = runBlocking {
        saveSessionFor("角色甲")
        val encoded = java.net.URLEncoder.encode("角色甲", "UTF-8")
        val response = router.route(VirtualApiRequest("GET", "/api/chats?characterId=$encoded"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals(1, body["chats"]?.jsonArray?.size)
    }

    @Test
    fun `list chats returns empty when no session matches decoded characterId`() = runBlocking {
        saveSessionFor("My Char")
        val response = router.route(VirtualApiRequest("GET", "/api/chats?characterId=Other"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals(0, body["chats"]?.jsonArray?.size)
    }

    @Test
    fun `POST _api_settings_get returns world_names and character_names`() = runBlocking {
        val response = router.route(VirtualApiRequest("POST", "/api/settings/get"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["world_names"]?.jsonArray != null)
        assertTrue(body["character_names"]?.jsonArray != null)
    }

    @Test
    fun `GET _api_settings returns presets array`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/settings"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["presets"]?.jsonArray != null)
    }

    @Test
    fun `GET _api_secrets returns secret ids`() = runBlocking {
        secretStore.putSecret("api_key_openai", "sk-test")
        val response = router.route(VirtualApiRequest("GET", "/api/secrets"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["secretIds"]?.jsonArray?.isNotEmpty() == true)
    }

    @Test
    fun `POST _api_secrets writes and GET reads back`() = runBlocking {
        val writeResponse = router.route(
            VirtualApiRequest(
                "POST",
                "/api/secrets",
                body = """{"id":"test_key","value":"secret_value"}""",
            ),
        )
        assertEquals(200, writeResponse.status)

        val readResponse = router.route(VirtualApiRequest("GET", "/api/secrets/test_key"))
        assertEquals(200, readResponse.status)
        val body = json.parseToJsonElement(readResponse.body).jsonObject
        assertEquals("secret_value", body["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE _api_secrets removes secret`() = runBlocking {
        secretStore.putSecret("to_delete", "value")
        val deleteResponse = router.route(VirtualApiRequest("DELETE", "/api/secrets/to_delete"))
        assertEquals(200, deleteResponse.status)
        assertEquals(null, secretStore.readSecret("to_delete"))
    }

    @Test
    fun `POST _api_secrets_write uses ST-style key field`() = runBlocking {
        val response = router.route(
            VirtualApiRequest(
                "POST",
                "/api/secrets/write",
                body = """{"key":"my_secret","value":"abc123"}""",
            ),
        )
        assertEquals(200, response.status)
        assertEquals("abc123", secretStore.readSecret("my_secret"))
    }

    @Test
    fun `POST _api_secrets_read uses ST-style key field`() = runBlocking {
        secretStore.putSecret("read_test", "value123")
        val response = router.route(
            VirtualApiRequest(
                "POST",
                "/api/secrets/read",
                body = """{"key":"read_test"}""",
            ),
        )
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals("value123", body["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET _api_providers returns provider list`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/providers"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["providers"]?.jsonArray != null)
    }

    @Test
    fun `GET _api_groups returns empty list`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/groups"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["groups"]?.jsonArray != null)
    }

    @Test
    fun `GET _api_personas returns empty list`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/personas"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["personas"]?.jsonArray != null)
    }

    @Test
    fun `GET _api_worlds returns empty list`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/worlds"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertTrue(body["worlds"]?.jsonArray != null)
    }

    @Test
    fun `unknown route returns 404`() = runBlocking {
        val response = router.route(VirtualApiRequest("GET", "/api/nonexistent"))
        assertEquals(404, response.status)
    }

    @Test
    fun `POST _api_backends_chat-completions_generate returns 501`() = runBlocking {
        val response = router.route(
            VirtualApiRequest("POST", "/api/backends/chat-completions/generate", body = "{}"),
        )
        assertEquals(501, response.status)
    }

    @Test
    fun `POST _api_chats_message-field returns 501`() = runBlocking {
        val response = router.route(
            VirtualApiRequest(
                "POST",
                "/api/chats/test-chat/message-field",
                body = """{"index":"0","field":"message","value":"test"}""",
            ),
        )
        assertEquals(501, response.status)
    }

    @Test
    fun `POST _api_extensions_install returns 501`() = runBlocking {
        val response = router.route(
            VirtualApiRequest("POST", "/api/extensions/install", body = "{}"),
        )
        assertEquals(501, response.status)
    }

    @Test
    fun `POST _api_extensions_version reports built-in compatibility shims as partial`() = runBlocking {
        for (extensionName in listOf("ST-Prompt-Template", "酒馆助手")) {
            val response = router.route(
                VirtualApiRequest(
                    "POST",
                    "/api/extensions/version",
                    body = """{"name":"$extensionName"}""",
                ),
            )

            assertEquals(200, response.status)
            val body = json.parseToJsonElement(response.body).jsonObject
            assertEquals("true", body["installed"]?.jsonPrimitive?.content)
            assertEquals("false", body["compatible"]?.jsonPrimitive?.content)
            assertEquals("partial", body["compatibilityLevel"]?.jsonPrimitive?.content)
            assertTrue(body["limitations"]?.jsonArray?.isNotEmpty() == true)
        }
    }

    // ── Preset writes must not destroy prompt lists ─────────────────────
    // Regression tests for the audit finding: presetFromRaw used to ignore
    // prompts/prompts_unused/prompt_order in the request body, and
    // FileStDataStore.savePreset then rewrote merged["prompts"] = [] whenever
    // the raw contained a "prompts" key — silently destroying prompt lists on
    // TavernHelper.setPreset/createPreset/replacePreset/updatePresetWith.

    private val stPresetBody = """
        {
          "category": "openai",
          "name": "promptkeep",
          "preset": {
            "temperature": 0.9,
            "seed": 12345,
            "prompts": [
              {"identifier": "main", "name": "Main Prompt", "role": "system", "content": "You are X."},
              {"identifier": "chatHistory", "marker": true},
              {"identifier": "jailbreak", "name": "JB", "role": "system", "content": "Do Y."}
            ],
            "prompt_order": [
              {"character_id": 100001, "order": [
                {"identifier": "main", "enabled": true},
                {"identifier": "chatHistory", "enabled": true},
                {"identifier": "jailbreak", "enabled": false}
              ]}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `POST presets_create preserves prompt list`() = runBlocking {
        val response = router.route(VirtualApiRequest("POST", "/api/presets/create", body = stPresetBody))
        assertEquals(201, response.status)

        val saved = requireNotNull(store.readPreset(PresetCategory.OpenAi, "promptkeep"))
        assertEquals(listOf("main", "chatHistory", "jailbreak"), saved.prompts.map { it.identifier })
        assertEquals(false, saved.prompts.first { it.identifier == "jailbreak" }.enabled)
        // On-disk raw must keep a non-empty prompts array (was rewritten to [] before the fix).
        assertEquals(3, saved.raw["prompts"]?.jsonArray?.size)
        assertEquals(12345L, saved.seed)
    }

    @Test
    fun `POST presets_replace applies new prompt list`() = runBlocking {
        router.route(VirtualApiRequest("POST", "/api/presets/create", body = stPresetBody))
        val replaceBody = """
            {"category":"openai","name":"promptkeep","load":false,"preset":{
              "prompts":[{"identifier":"nsp","name":"New","role":"user","content":"fresh"}]
            }}
        """.trimIndent()
        val response = router.route(VirtualApiRequest("POST", "/api/presets/replace", body = replaceBody))
        assertEquals(200, response.status)

        val saved = requireNotNull(store.readPreset(PresetCategory.OpenAi, "promptkeep"))
        assertEquals(listOf("nsp"), saved.prompts.map { it.identifier })
        assertEquals("fresh", saved.prompts[0].content)
        // Scalars not present in the replacement body are inherited from the existing preset.
        assertEquals(12345L, saved.seed)
    }

    @Test
    fun `POST presets_replace without prompts keeps existing prompt list`() = runBlocking {
        router.route(VirtualApiRequest("POST", "/api/presets/create", body = stPresetBody))
        val replaceBody = """{"category":"openai","name":"promptkeep","load":false,"preset":{"temperature":0.5}}"""
        val response = router.route(VirtualApiRequest("POST", "/api/presets/replace", body = replaceBody))
        assertEquals(200, response.status)

        val saved = requireNotNull(store.readPreset(PresetCategory.OpenAi, "promptkeep"))
        assertEquals(listOf("main", "chatHistory", "jailbreak"), saved.prompts.map { it.identifier })
        assertEquals(0.5, saved.temperature ?: -1.0, 0.0001)
    }

    // ── ST-style worldinfo / characters routes ─────────────────────────

    @Test
    fun `POST worldinfo_get returns entries as uid-keyed object`() = runBlocking {
        val stJson = """{"name":"Lore","entries":{"0":{"uid":0,"key":["dragon"],"content":"lore"}}}"""
        java.nio.file.Files.createDirectories(layout.worlds)
        layout.worlds.resolve("lore.json").toFile().writeText(stJson)
        val response = router.route(VirtualApiRequest("POST", "/api/worldinfo/get", body = """{"name":"lore"}"""))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        val entries = body["entries"]?.jsonObject
        assertTrue(entries != null && entries["0"] != null)
        assertEquals("lore", entries!!["0"]!!.jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST worldinfo_list returns bare array`() = runBlocking {
        java.nio.file.Files.createDirectories(layout.worlds)
        layout.worlds.resolve("lore.json").toFile().writeText("""{"name":"Lore","entries":{}}""")
        val response = router.route(VirtualApiRequest("POST", "/api/worldinfo/list", body = "{}"))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonArray
        assertEquals(1, body.size)
        assertEquals("lore", body[0].jsonObject["file_id"]?.jsonPrimitive?.content)
        assertEquals("Lore", body[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST worldinfo_edit writes the file verbatim`() = runBlocking {
        val body = """{"name":"edited","data":{"name":"Edited","entries":{"0":{"uid":0,"key":["x"],"content":"new"}}}}"""
        val response = router.route(VirtualApiRequest("POST", "/api/worldinfo/edit", body = body))
        assertEquals(200, response.status)
        val book = store.readWorldBook("edited")
        assertEquals(1, book.entries.size)
        assertEquals("new", book.entries[0].content)
    }

    @Test
    fun `POST characters_all returns bare array`() = runBlocking {
        val response = router.route(VirtualApiRequest("POST", "/api/characters/all", body = "{}"))
        assertEquals(200, response.status)
        // Must parse as a bare JSON array (ST shape), not a wrapper object.
        assertTrue(json.parseToJsonElement(response.body).jsonArray.isEmpty())
    }

    @Test
    fun `POST chats_get returns ST-shaped bare array with metadata header`() = runBlocking {
        store.saveChatSession(
            ChatSession(
                id = "chat-1",
                title = "T",
                characterId = "char1",
                groupId = null,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = MessageRole.User,
                        name = "Bob",
                        content = "hello",
                        createdAtMillis = 1L,
                    ),
                    ChatMessage(
                        id = "m2",
                        role = MessageRole.Assistant,
                        name = "Alice",
                        content = "hi there",
                        createdAtMillis = 2L,
                    ),
                ),
            ),
        )
        val response = router.route(VirtualApiRequest("POST", "/api/chats/get", body = """{"file_name":"chat-1"}"""))
        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonArray
        // First element is the metadata header, then messages with ST field names.
        assertTrue(body[0].jsonObject["chat_metadata"] != null)
        assertEquals("hello", body[1].jsonObject["mes"]?.jsonPrimitive?.content)
        assertEquals(true, body[1].jsonObject["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
        assertEquals(false, body[2].jsonObject["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull())
    }

    private class InMemorySecretStore : SecretStore {
        private val secrets = ConcurrentHashMap<String, String>()

        override suspend fun putSecret(id: String, value: String) {
            secrets[id] = value
        }

        override suspend fun readSecret(id: String): String? = secrets[id]

        override suspend fun deleteSecret(id: String) {
            secrets.remove(id)
        }

        override suspend fun listSecretIds(): List<String> = secrets.keys.sorted()
    }
}
