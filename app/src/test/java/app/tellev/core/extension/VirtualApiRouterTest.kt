package app.tellev.core.extension

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
    fun `POST _api_extensions_version reports prompt template compatibility`() = runBlocking {
        val response = router.route(
            VirtualApiRequest(
                "POST",
                "/api/extensions/version",
                body = """{"name":"ST-Prompt-Template"}""",
            ),
        )

        assertEquals(200, response.status)
        val body = json.parseToJsonElement(response.body).jsonObject
        assertEquals("true", body["installed"]?.jsonPrimitive?.content)
        assertEquals("true", body["compatible"]?.jsonPrimitive?.content)
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
