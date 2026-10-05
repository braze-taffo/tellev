package app.tellev.core.tts

import app.tellev.core.provider.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** The real speech adapter builds requests; the interceptor never proceeds to a network call. */
class TtsSpeechRequestTest {
    private suspend fun requestFor(custom: CustomProviderConfig): Request {
        val secrets = FakeTtsSecrets()
        ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(custom))
        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderConfigPersistence.selectedIdFor(custom.id))
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("offline test")
                .body("{\"error\":{\"message\":\"offline rejection\"}}".toResponseBody())
                .build()
        }.build()
        val service = TtsSpeechService(
            { TtsSettingsValues(enabled = true, model = "tts-1-hd", voice = "nova", speed = 1.25f, format = "opus") },
            TtsCache(Files.createTempDirectory("tts-request").toFile()),
            TtsPlayer(FakeAudioOutput()),
            ProviderRegistry(listOf(OpenAiSpeechAdapter(client))),
            secrets,
        )
        assertTrue(service.speak("Read this text").isFailure)
        return requireNotNull(captured)
    }

    @Test
    fun versionedBaseUrlAndDefaultBearerProduceOneV1AndCorrectPayload() = runTest {
        val request = requestFor(CustomProviderConfig("test", "test", "https://host.example/v1/", "secret", "chat-model"))
        assertEquals("https://host.example/v1/audio/speech", request.url.toString())
        assertEquals("Bearer secret", request.header("Authorization"))
        assertEquals("POST", request.method)
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals("tts-1-hd", payload["model"]!!.jsonPrimitive.content)
        assertEquals("Read this text", payload["input"]!!.jsonPrimitive.content)
        assertEquals("nova", payload["voice"]!!.jsonPrimitive.content)
        assertEquals(1.25, payload["speed"]!!.jsonPrimitive.double, 0.0)
        assertEquals("opus", payload["response_format"]!!.jsonPrimitive.content)
        assertEquals("application/json", request.header("Content-Type"))
    }

    @Test
    fun customHeaderAndSchemeAreAppliedWithoutExtraAuthorization() = runTest {
        val request = requestFor(CustomProviderConfig(
            "test", "test", "https://host.example", "secret", advanced = OpenAiCompatibilitySettings(
                authHeader = "X-Access-Key", authScheme = "Token", headers = mapOf("X-Tenant" to "tenant"),
            ),
        ))
        assertEquals("https://host.example/v1/audio/speech", request.url.toString())
        assertEquals("Token secret", request.header("X-Access-Key"))
        assertEquals("tenant", request.header("X-Tenant"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun emptyAuthSchemeSendsRawKeyToConfiguredHeader() = runTest {
        val request = requestFor(CustomProviderConfig(
            "test", "test", "https://host.example/proxy/v1", "secret", advanced = OpenAiCompatibilitySettings(
                authHeader = "api-key", authScheme = "",
            ),
        ))
        assertEquals("https://host.example/proxy/v1/audio/speech", request.url.toString())
        assertEquals("secret", request.header("api-key"))
        assertNull(request.header("Authorization"))
    }

    @Test
    fun explicitCustomHeaderOverridesGeneratedAuthentication() = runTest {
        val request = requestFor(CustomProviderConfig(
            "test", "test", "https://host.example", "secret", advanced = OpenAiCompatibilitySettings(
                headers = mapOf("Authorization" to "Custom overridden"),
            ),
        ))
        assertEquals("Custom overridden", request.header("Authorization"))
    }
}
