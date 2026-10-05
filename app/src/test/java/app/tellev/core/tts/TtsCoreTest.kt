package app.tellev.core.tts

import app.tellev.core.provider.ProviderConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TtsCoreTest {
    @Test
    fun cacheHitUsesSameKeyAndDifferentSettingsMiss() {
        val cache = TtsCache(Files.createTempDirectory("tts-cache").toFile(), maxBytes = 1024)
        val settings = TtsSettingsValues(enabled = true)
        val file = cache.put("hello", settings, byteArrayOf(1, 2, 3))

        assertEquals(file, cache.get("hello", settings))
        assertNull(cache.get("different", settings))
        assertNull(cache.get("hello", settings.copy(voice = "nova")))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedAfterRecentAccess() {
        var time = 1_700_000_000_000L
        val cache = TtsCache(Files.createTempDirectory("tts-cache").toFile(), maxBytes = 6, clock = { time })
        val settings = TtsSettingsValues(enabled = true)
        cache.put("first", settings, byteArrayOf(1, 2, 3))
        time += 1_000
        cache.put("second", settings, byteArrayOf(4, 5, 6))
        time += 1_000
        assertNotNull(cache.get("first", settings))
        time += 1_000
        cache.put("third", settings, byteArrayOf(7, 8, 9))

        assertNotNull(cache.get("first", settings))
        assertNull(cache.get("second", settings))
        assertNotNull(cache.get("third", settings))
    }

    @Test
    fun settingsValidationRestoresUnsupportedValuesAndClampsSpeed() {
        val values = TtsSettingsValues(
            enabled = true,
            voice = "invalid",
            speed = 99f,
            model = "invalid",
            format = "wav",
        ).validated()

        assertEquals(TtsSettingsValues.DEFAULT_VOICE, values.voice)
        assertEquals(TtsSettingsValues.MAX_SPEED, values.speed)
        assertEquals(TtsSettingsValues.DEFAULT_MODEL, values.model)
        assertEquals(TtsSettingsValues.DEFAULT_FORMAT, values.format)
        assertEquals(TtsSettingsValues.MIN_SPEED, values.copy(speed = 0f).validated().speed)
    }

    @Test
    fun requestPayloadContainsOpenAiSpeechFields() {
        val payload = TtsRequestPayload("hello", "tts-1-hd", "nova", 1.25f, "opus")
        val request = payload.toRequest(ProviderConfig("openai-compatible", "https://example.test"))

        assertEquals("hello", request.prompt.messages.single().content)
        assertEquals("nova", request.metadata["voice"]?.toString()?.trim('"'))
        assertEquals("1.25", request.metadata["speed"]?.toString()?.trim('"'))
        assertEquals("opus", request.metadata["response_format"]?.toString()?.trim('"'))
    }

    @Test
    fun speechConfigRemovesV1AndBuildsCustomRawAuth() {
        val config = ProviderConfig(
            providerType = "openai-compatible",
            baseUrl = "https://host.example/v1/",
            apiKey = "secret",
            options = buildJsonObject {
                put("authHeader", JsonPrimitive("X-API-Key"))
                put("authScheme", JsonPrimitive(""))
            },
        )
        val normalized = TtsRequestPayload.normalizeConfig(config)

        assertEquals("https://host.example", normalized.baseUrl)
        assertNull(normalized.apiKey)
        assertEquals("secret", normalized.headers["X-API-Key"])
    }

    @Test
    fun playbackIdsDoNotCollideForHashCollisionTexts() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertFalse(TtsSpeechService.stablePlaybackId("Aa") == TtsSpeechService.stablePlaybackId("BB"))
    }

    @Test
    fun stateMachinePreservesQueueOrderAndTransitions() {
        val machine = TtsPlaybackStateMachine()
        val first = TtsQueueItem("first", java.io.File("first.mp3"))
        val second = TtsQueueItem("second", java.io.File("second.mp3"))
        val third = TtsQueueItem("third", java.io.File("third.mp3"))

        assertEquals(first, machine.enqueue(first))
        machine.beginLoading(first)
        assertEquals(first, machine.started())
        assertTrue(machine.pause())
        assertTrue(machine.resume())
        assertNull(machine.enqueue(second))
        assertEquals(second, machine.complete())
        assertEquals(second, (machine.state as TtsPlaybackState.Loading).item)
        machine.beginLoading(second)
        machine.started()
        assertNull(machine.enqueue(third))
        assertEquals(third, machine.complete())
    }

    @Test
    fun failureResetsToIdleAndNextEnqueueCanStart() {
        val machine = TtsPlaybackStateMachine()
        val item = TtsQueueItem("one", java.io.File("one.mp3"))
        val retry = TtsQueueItem("retry", java.io.File("retry.mp3"))
        machine.enqueue(item)
        machine.beginLoading(item)
        machine.failed("broken")
        assertTrue(machine.state is TtsPlaybackState.Error)
        assertEquals(retry, machine.enqueue(retry))
        assertEquals(TtsPlaybackState.Loading(retry), machine.state)
    }
}
