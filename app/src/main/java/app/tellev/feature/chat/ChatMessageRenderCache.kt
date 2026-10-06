package app.tellev.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

/**
 * All inputs of the display regex pipeline for one message bubble. Used as a
 * cache key, so it must change whenever the rendered result can change
 * (message content via [parts], card, preset, user name, depth, phase).
 */
internal data class RenderInputs(
    val parts: MessageReasoning.Parts,
    val role: MessageRole,
    val character: CharacterCard?,
    val preset: GenerationPreset?,
    val userName: String,
    val depth: Int,
    val includeNormal: Boolean,
    val macroContext: app.tellev.core.prompt.MacroContext? = null,
)

internal enum class MessageRenderPhase { Pending, Ready, Degraded }

internal data class MessageRenderState(
    val inputs: RenderInputs,
    val phase: MessageRenderPhase,
    val segments: List<TavernRenderSegment> = emptyList(),
) {
    // A pending update can keep the last committed display of this same bubble.
    val hasDisplay: Boolean get() = phase != MessageRenderPhase.Pending || segments.isNotEmpty()
}

/**
 * Off-main-thread cache for message display rendering (the preset/card regex
 * pipeline in [renderMessageParts]).
 *
 * Preset- and card-embedded regex scripts are untrusted third-party code
 * running on ICU, a backtracking engine with no complexity guarantee. Real
 * cards ship catastrophic rules — e.g. 玄浑纪's
 * `/(<update…>)(?!.*<\/update…>)([\s\S]*)/gsi`, which pinned the compose
 * thread at 100% CPU with quadratic case-folded scans until the system
 * killed the app. No static precheck can cover every such shape, so this
 * cache makes the guarantee structurally instead:
 *
 * - rendering runs on background workers, never on the compose thread;
 * - the hard budget measures execution only: the deadline is armed when the
 *   worker actually starts the compute, never during queue wait — a finalized
 *   message no longer degrades just because scroll/stream renders occupy the
 *   workers (Izumi preset: the newest floor rendered regex-free whenever the
 *   user scrolled while a long generation finished). A separate admission
 *   ceiling bounds how long a fully busy pool may delay the start;
 * - past the execution budget the message degrades to regex-free parsing
 *   (still fully readable, tags shown as text) instead of freezing the app;
 * - the degraded result is cached for a short retry window only (a
 *   catastrophic rule is not re-run on every scroll), then dropped: the next
 *   lookup recomputes, so a transient timeout gets a second chance;
 * - results are LRU-cached across recomposition, scrolling, and re-entering
 *   the chat, keyed by every input the pipeline depends on.
 */
internal object ChatMessageRenderCache {
    /** Hard budget for one message's full regex pipeline on a worker. */
    private const val BUDGET_MS = 1_000L

    /** Upper bound on waiting for a free worker before degrading outright. */
    private const val ADMISSION_CEILING_MS = 15_000L

    /** How long a degraded render is served before the next lookup recomputes. */
    private const val DEGRADED_RETRY_MS = 10_000L

    private const val MAX_ENTRIES = 400

    /** Dedicated workers: catastrophic regexes can pin these, never the UI. */
    private val workers = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { r ->
        Thread(r, "msg-regex-render").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cache = Object() // monitor for lru
    private val lru = LinkedHashMap<RenderInputs, MessageRenderState>(64, 0.75f, true)

    /** Degraded entries: when they were stored, so lookups can expire them. */
    private val degradedAtMillis = HashMap<RenderInputs, Long>()

    /** Latest in-flight job per group (message id, or "streaming"). */
    private val groupJobs = ConcurrentHashMap<String, Deferred<MessageRenderState>>()

    /** One computation per inputs: concurrent bubbles await the same run. */
    private val inFlight = ConcurrentHashMap<RenderInputs, Deferred<MessageRenderState>>()

    internal fun cachedState(inputs: RenderInputs): MessageRenderState? =
        synchronized(cache) {
            val state = lru[inputs]
            if (state != null && state.phase == MessageRenderPhase.Degraded &&
                System.currentTimeMillis() - (degradedAtMillis[inputs] ?: 0L) >= degradedRetryMs
            ) {
                // A degraded render sticks only for the retry window; drop it
                // so the next caller recomputes. The bubble keeps showing the
                // degraded segments it already committed until then.
                lru.remove(inputs)
                degradedAtMillis.remove(inputs)
                null
            } else {
                state
            }
        }

    /** Test hook: retry window for degraded entries (production: [DEGRADED_RETRY_MS]). */
    @Volatile
    internal var degradedRetryMs: Long = DEGRADED_RETRY_MS

    internal fun cached(inputs: RenderInputs): List<TavernRenderSegment>? =
        cachedState(inputs)?.segments

    private fun store(inputs: RenderInputs, result: MessageRenderState) {
        synchronized(cache) {
            lru[inputs] = result
            if (result.phase == MessageRenderPhase.Degraded) {
                degradedAtMillis[inputs] = System.currentTimeMillis()
            } else {
                degradedAtMillis.remove(inputs)
            }
            while (lru.size > MAX_ENTRIES) {
                val eldest = lru.keys.firstOrNull() ?: break
                lru.remove(eldest)
                degradedAtMillis.remove(eldest)
            }
        }
    }

    /**
     * Queues [compute] for [inputs], coalescing concurrent callers awaiting
     * the same inputs into one run. Cancels the previous job of [group]
     * (its result is dropped) so streaming bubbles always render the newest
     * text. The suspended caller waits until a cached result exists.
     */
    internal suspend fun computeAndAwait(
        inputs: RenderInputs,
        group: String,
        compute: () -> List<TavernRenderSegment>,
    ): List<TavernRenderSegment> = computeStateAndAwait(inputs, group, compute).segments

    internal suspend fun computeStateAndAwait(
        inputs: RenderInputs,
        group: String,
        compute: () -> List<TavernRenderSegment>,
    ): MessageRenderState {
        cachedState(inputs)?.let { return it }
        val job = synchronized(cache) {
            cachedState(inputs)?.let { return it }
            inFlight[inputs]?.let { return@synchronized it }
            val created = scope.async(start = CoroutineStart.LAZY) {
                // The budget measures execution, not queue wait: the deadline is
                // only armed once the worker actually starts the compute. Before
                // that, the admission ceiling bounds how long a busy pool may
                // delay the start (timing from submission degraded a healthy
                // message without its pipeline ever running).
                val submittedAt = System.nanoTime()
                val startedAt = java.util.concurrent.atomic.AtomicLong()
                val future = CompletableFuture.supplyAsync<List<TavernRenderSegment>?>({
                    // Never zero: 0 is the "not started yet" sentinel below.
                    startedAt.set(System.nanoTime().coerceAtLeast(1L))
                    compute()
                }, workers)
                // One completion signal; the loop below may wait on it several
                // times (admission phase, then execution phase).
                val doneSignal = CompletableFuture<Void>()
                future.whenComplete { _, _ -> doneSignal.complete(null) }
                var outcome: List<TavernRenderSegment>? = null
                var degrade = false
                var started = false
                var deadlineNanos = submittedAt + ADMISSION_CEILING_MS * 1_000_000
                try {
                    while (true) {
                        if (!started && startedAt.get() != 0L) {
                            // The worker just picked the compute up: re-arm the
                            // deadline from execution start — queue wait no
                            // longer counts against the budget. Checked every
                            // quantum, because the wait below cannot be
                            // interrupted by the start alone.
                            started = true
                            deadlineNanos = startedAt.get() + BUDGET_MS * 1_000_000
                        }
                        val remainingMs = (deadlineNanos - System.nanoTime() + 999_999) / 1_000_000
                        if (remainingMs <= 0) {
                            future.cancel(false)
                            degrade = true
                            break
                        }
                        // Before the compute starts, wait in short quanta so the
                        // execution deadline is armed promptly once it starts.
                        // No invokeOnCancellation here: a quantum timeout must
                        // NOT retract a queued task (it would then never run);
                        // the deadline check above is what gives up.
                        val quantumMs = if (started) remainingMs else minOf(remainingMs, 100L)
                        val fired = withTimeoutOrNull(quantumMs) {
                            suspendCancellableCoroutine<Unit> { cont ->
                                doneSignal.whenComplete { _, _ -> cont.resume(Unit) }
                            }
                        }
                        if (fired != null && future.isDone) {
                            outcome = try {
                                if (future.isCompletedExceptionally) null else future.get()
                            } catch (_: Exception) {
                                null
                            }
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    // Caller went away (superseded streaming render, closed
                    // chat): still free the worker queue like the original
                    // single-wait version did.
                    future.cancel(false)
                    throw e
                }
                // Timeout or crash: degrade to regex-free parsing. The degraded
                // result is cached only for the retry window, so a transient
                // timeout is recomputed on a later lookup instead of sticking
                // for the entry's lifetime.
                coroutineContext.ensureActive()
                val result = MessageRenderState(inputs,
                    if (degrade || outcome == null) MessageRenderPhase.Degraded else MessageRenderPhase.Ready,
                    outcome ?: renderMessagePartsUnregulated(inputs.parts, inputs.role))
                coroutineContext.ensureActive()
                store(inputs, result)
                result
            }
            inFlight[inputs] = created
            val previous = groupJobs.put(group, created)
            previous?.cancel()
            created.invokeOnCompletion {
                inFlight.remove(inputs, created)
                groupJobs.remove(group, created)
            }
            created.start()
            created
        }
        return job.await()
    }
}

/**
 * The message bubble's rendered segments. Synchronous on cache hit (the
 * common case: scrolling, re-entering the chat). A cache miss has no body
 * segments until the worker commits a ready or final degraded result.
 */
@Composable
internal fun rememberRenderedSegments(
    inputs: RenderInputs,
    group: String,
    sizes: ChatPanelSizes? = null,
    compute: () -> List<TavernRenderSegment>,
): State<MessageRenderState> {
    val committed = remember(group) { mutableStateOf(MessageRenderState(inputs, MessageRenderPhase.Pending)) }
    val synchronous = remember(inputs) { ChatMessageRenderCache.cachedState(inputs) }
    val display = synchronous ?: if (committed.value.inputs == inputs) committed.value
        else MessageRenderState(inputs, MessageRenderPhase.Pending, committed.value.segments)
    val binding = remember(inputs, group) { Any() }
    DisposableEffect(binding, sizes) { onDispose { sizes?.remove(binding) } }
    LaunchedEffect(inputs, group) {
        committed.value = display
        val result = ChatMessageRenderCache.computeStateAndAwait(inputs, group, compute)
        coroutineContext.ensureActive()
        if (sizes == null) committed.value = result
        else sizes.deliver(binding) { committed.value = result }
    }
    return rememberUpdatedState(display)
}
