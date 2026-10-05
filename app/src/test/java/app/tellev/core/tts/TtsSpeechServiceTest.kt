package app.tellev.core.tts

import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.model.TellevError
import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

internal class FakeAudioOutput : TtsAudioOutput {
    var prepareError: Throwable? = null
    var prepared: () -> Unit = {}
    var completed: () -> Unit = {}
    var failed: (Throwable) -> Unit = {}
    var starts = 0
    var stops = 0
    override fun prepare(file: File, onPrepared: () -> Unit, onCompleted: () -> Unit, onError: (Throwable) -> Unit) {
        prepareError?.let { throw it }
        prepared = onPrepared
        completed = onCompleted
        failed = onError
        onPrepared()
    }
    override fun start() { starts++ }
    override fun pause() {}
    override fun stop() { stops++ }
}

internal class FakeTtsSecrets : SecretStore {
    var reads = 0
    val values = mutableMapOf<String, String>()
    override suspend fun readSecret(id: String): String? { reads++; return values[id] }
    override suspend fun putSecret(id: String, value: String) { values[id] = value }
    override suspend fun deleteSecret(id: String) { values.remove(id) }
    override suspend fun listSecretIds(): List<String> = values.keys.toList()
}

internal class FakeSpeechAdapter : ProviderAdapter {
    override val id = ProviderCatalog.OPENAI_SPEECH
    override val displayName = "test"
    override val capabilities = setOf(ProviderCapability.TextToSpeech)
    var calls = 0
    var beforeResult: suspend () -> Unit = {}
    var chunk: GenerateChunk = GenerateChunk.Completed(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "test")
    override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        calls++
        beforeResult()
        emit(chunk)
    }
}

class TtsSpeechServiceTest {
    private val values = TtsSettingsValues(enabled = true)
    private fun cache() = TtsCache(Files.createTempDirectory("tts-service").toFile())
    private fun service(cache: TtsCache, player: TtsPlayer, adapter: FakeSpeechAdapter, secrets: FakeTtsSecrets = FakeTtsSecrets()) =
        TtsSpeechService({ values }, cache, player, ProviderRegistry(listOf(adapter)), secrets)

    @Test
    fun cacheHitDoesNotReadProviderConfigOrInvokeAdapter() = runTest {
        val cache = cache()
        val file = cache.put("hello", values, byteArrayOf(1, 2, 3))
        val audio = FakeAudioOutput()
        val player = TtsPlayer(audio)
        val adapter = FakeSpeechAdapter()
        val secrets = FakeTtsSecrets()
        val service = service(cache, player, adapter, secrets)

        assertEquals(file, service.speak("hello", "message", "chat").getOrThrow())
        assertEquals(0, adapter.calls)
        assertEquals(0, secrets.reads)
        assertEquals("message", (player.state.value as TtsPlaybackState.Playing).item.id)
    }

    @Test
    fun uncachedSynthesisCachesAudioAndSecondSpeakShortCircuits() = runTest {
        val adapter = FakeSpeechAdapter()
        val player = TtsPlayer(FakeAudioOutput())
        val service = service(cache(), player, adapter)
        val first = service.speak("hello", "one", "chat").getOrThrow()
        val second = service.speak("hello", "two", "chat").getOrThrow()
        assertEquals(first, second)
        assertEquals(1, adapter.calls)
        assertEquals("two", (player.state.value as TtsPlaybackState.Playing).item.id)
    }

    @Test
    fun preparationAndAsynchronousFailureEmitAndNextSpeakPlays() = runTest {
        val cache = cache().also { it.put("hello", values, byteArrayOf(1)) }
        val audio = FakeAudioOutput().also { it.prepareError = IllegalStateException("diagnostic-only") }
        val player = TtsPlayer(audio)
        val service = service(cache, player, FakeSpeechAdapter())
        val events = mutableListOf<TtsPlayerError>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { player.errors.toList(events) }
        service.speak("hello", "one").getOrThrow()
        testScheduler.runCurrent()
        assertTrue(player.state.value is TtsPlaybackState.Error)
        assertEquals(1, events.size)
        assertEquals("playback_error", events.single().failure.code)
        audio.prepareError = null
        service.speak("hello", "two").getOrThrow()
        assertEquals("two", (player.state.value as TtsPlaybackState.Playing).item.id)
        audio.failed(IllegalStateException("async-failure"))
        testScheduler.runCurrent()
        assertEquals(2, events.size)
        service.speak("hello", "three").getOrThrow()
        assertTrue(player.state.value is TtsPlaybackState.Playing)
        collector.cancel()
    }

    @Test
    fun stableIdsSeparateCollidingTextsMessagesAndSessions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(TtsSpeechService.stablePlaybackId("Aa"), TtsSpeechService.stablePlaybackId("BB"))
        val one = TtsSpeechService.messagePlaybackId("chat", "one", 0, "Aa")
        assertNotEquals(one, TtsSpeechService.messagePlaybackId("chat", "two", 0, "Aa"))
        assertNotEquals(one, TtsSpeechService.messagePlaybackId("other", "one", 0, "Aa"))
        assertNotEquals(one, TtsSpeechService.messagePlaybackId("chat", "one", 1, "BB"))
    }

    @Test
    fun unrelatedOwnerAndSameSessionCannotStopPlayback() = runTest {
        val cache = cache().also { it.put("hello", values, byteArrayOf(1)) }
        val player = TtsPlayer(FakeAudioOutput())
        val service = service(cache, player, FakeSpeechAdapter())
        service.speak("hello", "active", "chat").getOrThrow()
        service.stopIfOwner("other-bubble")
        service.onSessionChanged("chat")
        assertTrue(player.state.value is TtsPlaybackState.Playing)
        service.onSessionChanged("different-chat")
        assertEquals(TtsPlaybackState.Idle, player.state.value)
    }

    @Test
    fun switchingSessionCancelsPendingSynthesisBeforeItCanPlay() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeSpeechAdapter().also { it.beforeResult = { started.complete(Unit); release.await() } }
        val player = TtsPlayer(FakeAudioOutput())
        val service = service(cache(), player, adapter)
        val job = async { service.speak("pending", "message", "chat") }
        started.await()
        service.onSessionChanged("new-chat")
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(TtsPlaybackState.Idle, player.state.value)
        assertNull(service.requestingId.value)
    }

    @Test
    fun providerErrorIsMappedToLocalizedMessageWithoutRawDetail() = runTest {
        val adapter = FakeSpeechAdapter().also {
            it.chunk = GenerateChunk.Failed(TellevError("openai_speech_http_401", "raw auth failure", false))
        }
        val service = service(cache(), TtsPlayer(FakeAudioOutput()), adapter)
        val error = service.speak("hello").exceptionOrNull() as TtsException
        assertEquals("provider_error", error.failure.code)
        assertFalse(error.ttsUserMessage().contains("raw auth failure"))
        assertFalse(error.ttsUserMessage().startsWith("[missing:"))
    }

    @Test
    fun allFailureVariantsHaveLocalizedResources() {
        listOf(
            TtsFailure.EmptyText, TtsFailure.Disabled, TtsFailure.ProviderNotConfigured,
            TtsFailure.ProviderUnavailable, TtsFailure.ProviderError("http_500"), TtsFailure.Network,
            TtsFailure.CacheWrite, TtsFailure.NoAudio, TtsFailure.PlaybackError("raw media error"),
        ).forEach { failure ->
            assertFalse(failure.userMessage().startsWith("[missing:"))
            assertFalse(failure.userMessage().contains("raw media error"))
        }
    }
}
