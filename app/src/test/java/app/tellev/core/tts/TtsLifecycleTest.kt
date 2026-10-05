package app.tellev.core.tts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.provider.ProviderRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TtsLifecycleTest {
    private class PageOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
        init { lifecycle.currentState = Lifecycle.State.RESUMED }
    }

    @Test
    fun repeatedBubbleBindingDoesNotStopPlaybackButPageStopDoes() = runTest {
        val audio = FakeAudioOutput()
        val player = TtsPlayer(audio)
        val values = TtsSettingsValues(enabled = true)
        val cache = TtsCache(Files.createTempDirectory("tts-lifecycle").toFile())
        cache.put("hello", values, byteArrayOf(1))
        val service = TtsSpeechService({ values }, cache, player, ProviderRegistry(listOf(FakeSpeechAdapter())), FakeTtsSecrets())
        val events = MutableSharedFlow<ExtensionEvent>()
        val binding = TtsLifecycleBinding(this, service, events)
        val page = PageOwner()
        binding.bind(page, "chat")
        service.speak("hello", "owner", "chat").getOrThrow()
        val stops = audio.stops

        // New/recomposed lazy items attach to the same page without owning stop/dispose.
        repeat(10) { binding.bind(page, "chat") }
        assertEquals(stops, audio.stops)
        assertTrue(player.state.value is TtsPlaybackState.Playing)
        page.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(TtsPlaybackState.Idle, player.state.value)
        binding.close()
    }

    @Test
    fun sessionEventStopsPlaybackEvenWithoutAnyVisibleBubbles() = runTest {
        val player = TtsPlayer(FakeAudioOutput())
        val values = TtsSettingsValues(enabled = true)
        val cache = TtsCache(Files.createTempDirectory("tts-lifecycle").toFile())
        cache.put("hello", values, byteArrayOf(1))
        val service = TtsSpeechService({ values }, cache, player, ProviderRegistry(listOf(FakeSpeechAdapter())), FakeTtsSecrets())
        val events = MutableSharedFlow<ExtensionEvent>()
        val binding = TtsLifecycleBinding(this, service, events)
        binding.bind(PageOwner(), "chat")
        service.speak("hello", "owner", "chat").getOrThrow()
        events.emit(ExtensionEvent(StEventCatalog.CHAT_CHANGED, payload = buildJsonObject {
            putJsonArray("args") { add(JsonPrimitive("new-chat")) }
        }))
        testScheduler.runCurrent()
        assertEquals(TtsPlaybackState.Idle, player.state.value)
        binding.close()
    }

    @Test
    fun lateCallbackFromStoppedItemCannotChangeNewPlayback() {
        val audio = FakeAudioOutput()
        val player = TtsPlayer(audio)
        player.enqueue(TtsQueueItem("first", java.io.File("first.mp3")))
        val oldError = audio.failed
        val oldCompletion = audio.completed
        player.stop()
        player.enqueue(TtsQueueItem("second", java.io.File("second.mp3")))
        oldError(IllegalStateException("late error"))
        oldCompletion()
        assertEquals("second", (player.state.value as TtsPlaybackState.Playing).item.id)
    }
}
