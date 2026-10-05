package app.tellev.core.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface TtsPlaybackState {
    data object Idle : TtsPlaybackState
    data class Loading(val item: TtsQueueItem) : TtsPlaybackState
    data class Playing(val item: TtsQueueItem) : TtsPlaybackState
    data class Paused(val item: TtsQueueItem) : TtsPlaybackState
    data class Error(val item: TtsQueueItem?, val message: String) : TtsPlaybackState
}

data class TtsQueueItem(val id: String, val file: File)
data class TtsPlayerError(val item: TtsQueueItem?, val failure: TtsFailure)

interface TtsPlaybackController {
    val state: StateFlow<TtsPlaybackState>
    val errors: SharedFlow<TtsPlayerError>
    fun enqueue(item: TtsQueueItem)
    fun stop()
    fun release()
}

/** Pure transition engine, including an explicit Error -> Loading retry. */
class TtsPlaybackStateMachine {
    var state: TtsPlaybackState = TtsPlaybackState.Idle
        private set
    private val queue = ArrayDeque<TtsQueueItem>()
    private val history = ArrayDeque<TtsQueueItem>()

    fun enqueue(item: TtsQueueItem): TtsQueueItem? {
        resetAfterError()
        queue.addLast(item)
        return if (state is TtsPlaybackState.Idle) startNext() else null
    }

    fun beginLoading(item: TtsQueueItem) { state = TtsPlaybackState.Loading(item) }

    fun started(): TtsQueueItem? = (state as? TtsPlaybackState.Loading)?.also {
        state = TtsPlaybackState.Playing(it.item)
    }?.item

    fun pause(): Boolean = (state as? TtsPlaybackState.Playing)?.let {
        state = TtsPlaybackState.Paused(it.item)
        true
    } ?: false

    fun resume(): Boolean = (state as? TtsPlaybackState.Paused)?.let {
        state = TtsPlaybackState.Playing(it.item)
        true
    } ?: false

    fun complete(): TtsQueueItem? {
        currentItem()?.let { history.addLast(it) }
        state = TtsPlaybackState.Idle
        return startNext()
    }

    fun failed(code: String): TtsQueueItem? {
        val item = currentItem()
        state = TtsPlaybackState.Error(item, code)
        queue.clear()
        history.clear()
        return item
    }

    fun resetAfterError() {
        if (state is TtsPlaybackState.Error) state = TtsPlaybackState.Idle
    }

    fun stop() {
        queue.clear()
        history.clear()
        state = TtsPlaybackState.Idle
    }

    fun previous(): TtsQueueItem? {
        val current = currentItem()
        val previous = history.removeLastOrNull() ?: return current?.also { beginLoading(it) }
        current?.let { queue.addFirst(it) }
        beginLoading(previous)
        return previous
    }

    fun next(): TtsQueueItem? {
        currentItem()?.let { history.addLast(it) }
        state = TtsPlaybackState.Idle
        return startNext()
    }

    fun queuedItems(): List<TtsQueueItem> = queue.toList()

    fun currentItem(): TtsQueueItem? = when (val value = state) {
        is TtsPlaybackState.Loading -> value.item
        is TtsPlaybackState.Playing -> value.item
        is TtsPlaybackState.Paused -> value.item
        is TtsPlaybackState.Error -> value.item
        TtsPlaybackState.Idle -> null
    }

    private fun startNext(): TtsQueueItem? = queue.removeFirstOrNull()?.also(::beginLoading)
}

/** Injectable boundary: JVM tests can exercise preparation failures without Android/audio. */
interface TtsAudioOutput {
    fun prepare(file: File, onPrepared: () -> Unit, onCompleted: () -> Unit, onError: (Throwable) -> Unit)
    fun start()
    fun pause()
    fun stop()
    fun setFocusCallbacks(onLost: (Boolean) -> Unit, onGained: () -> Unit) {}
}

class TtsPlayer(
    private val output: TtsAudioOutput,
    private val machine: TtsPlaybackStateMachine = TtsPlaybackStateMachine(),
) : TtsPlaybackController {
    constructor(context: Context) : this(AndroidTtsAudioOutput(context.applicationContext))

    private val mutableState = MutableStateFlow<TtsPlaybackState>(TtsPlaybackState.Idle)
    override val state = mutableState.asStateFlow()
    private val mutableCompleted = MutableSharedFlow<TtsQueueItem>(extraBufferCapacity = 8)
    val completed = mutableCompleted.asSharedFlow()
    private val mutableErrors = MutableSharedFlow<TtsPlayerError>(extraBufferCapacity = 8)
    override val errors = mutableErrors.asSharedFlow()
    private var generation = 0L
    private var resumeAfterFocus = false

    init {
        output.setFocusCallbacks(
            onLost = { permanent ->
                if (permanent) stop() else {
                    resumeAfterFocus = machine.state is TtsPlaybackState.Playing
                    pause()
                }
            },
            onGained = { if (resumeAfterFocus) { resumeAfterFocus = false; resume() } },
        )
    }

    override fun enqueue(item: TtsQueueItem) {
        val next = machine.enqueue(item)
        syncState()
        if (next != null) prepareCurrent()
    }

    fun pause() {
        if (machine.pause()) runCatching { output.pause(); syncState() }.onFailure(::reportFailure)
    }

    fun resume() {
        if (machine.resume()) runCatching { output.start(); syncState() }.onFailure(::reportFailure)
    }

    override fun stop() {
        generation++
        resumeAfterFocus = false
        runCatching { output.stop() }
        machine.stop()
        syncState()
    }

    fun skipNext() { replaceCurrent { machine.next() } }
    fun skipPrevious() { replaceCurrent { machine.previous() } }
    override fun release() = stop()

    private fun replaceCurrent(transition: () -> TtsQueueItem?) {
        generation++
        runCatching { output.stop() }
        val next = transition()
        syncState()
        if (next != null) prepareCurrent()
    }

    private fun prepareCurrent() {
        val item = (machine.state as? TtsPlaybackState.Loading)?.item ?: return
        val token = ++generation
        runCatching {
            output.prepare(
                item.file,
                onPrepared = {
                    if (token == generation) {
                        runCatching { output.start(); machine.started(); syncState() }.onFailure(::reportFailure)
                    }
                },
                onCompleted = {
                    if (token == generation) {
                        generation++
                        runCatching { output.stop() }
                        val next = machine.complete()
                        mutableCompleted.tryEmit(item)
                        syncState()
                        if (next != null) prepareCurrent()
                    }
                },
                onError = { if (token == generation) reportFailure(it) },
            )
        }.onFailure(::reportFailure)
    }

    private fun reportFailure(error: Throwable) {
        generation++
        val failure = TtsFailure.PlaybackError(error.message)
        val item = machine.failed(failure.code)
        runCatching { output.stop() }
        syncState()
        mutableErrors.tryEmit(TtsPlayerError(item, failure))
    }

    private fun syncState() { mutableState.value = machine.state }
}

private class AndroidTtsAudioOutput(context: Context) : TtsAudioOutput {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var onLost: (Boolean) -> Unit = {}
    private var onGained: () -> Unit = {}
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> onLost(true)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onLost(false)
                AudioManager.AUDIOFOCUS_GAIN -> onGained()
            }
        }.build()
    private var mediaPlayer: MediaPlayer? = null

    override fun setFocusCallbacks(onLost: (Boolean) -> Unit, onGained: () -> Unit) {
        this.onLost = onLost
        this.onGained = onGained
    }

    override fun prepare(file: File, onPrepared: () -> Unit, onCompleted: () -> Unit, onError: (Throwable) -> Unit) {
        stop()
        check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "audio_focus_denied" }
        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(attributes)
        player.setDataSource(file.absolutePath)
        player.setOnPreparedListener { onPrepared() }
        player.setOnCompletionListener { onCompleted() }
        player.setOnErrorListener { _, what, extra -> onError(IllegalStateException("media_error:$what/$extra")); true }
        player.prepareAsync()
    }

    override fun start() { requireNotNull(mediaPlayer).start() }
    override fun pause() { mediaPlayer?.pause() }
    override fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        audioManager.abandonAudioFocusRequest(focus)
    }
}
