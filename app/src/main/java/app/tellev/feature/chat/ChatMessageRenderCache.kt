package app.tellev.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

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
 * - each computation runs under a hard budget; past it the message degrades
 *   to regex-free parsing (still fully readable, tags shown as text) instead
 *   of freezing the app;
 * - the degraded result is cached, so a catastrophic rule burns one worker
 *   for at most its own runtime and is never retried on recomposition;
 * - results are LRU-cached across recomposition, scrolling, and re-entering
 *   the chat, keyed by every input the pipeline depends on.
 */
internal object ChatMessageRenderCache {
    /** Hard budget for one message's full regex pipeline on a worker. */
    private const val BUDGET_MS = 1_000L

    private const val MAX_ENTRIES = 400

    /** Dedicated workers: catastrophic regexes can pin these, never the UI. */
    private val workers = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { r ->
        Thread(r, "msg-regex-render").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val cache = Object() // monitor for lru
    private val lru = LinkedHashMap<RenderInputs, List<TavernRenderSegment>>(64, 0.75f, true)

    /** Latest in-flight job per group (message id, or "streaming"). */
    private val groupJobs = ConcurrentHashMap<String, Job>()

    /** One computation per inputs: concurrent bubbles await the same run. */
    private val inFlight = ConcurrentHashMap<RenderInputs, CompletableDeferred<Unit>>()

    internal fun cached(inputs: RenderInputs): List<TavernRenderSegment>? =
        synchronized(cache) { lru[inputs] }

    private fun store(inputs: RenderInputs, segments: List<TavernRenderSegment>) {
        synchronized(cache) {
            lru[inputs] = segments
            while (lru.size > MAX_ENTRIES) {
                val eldest = lru.keys.firstOrNull() ?: break
                lru.remove(eldest)
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
    ): List<TavernRenderSegment> {
        cached(inputs)?.let { return it }
        val deferred = CompletableDeferred<Unit>()
        val existing = inFlight.putIfAbsent(inputs, deferred)
        if (existing != null) {
            existing.await()
            return cached(inputs) ?: renderMessagePartsUnregulated(inputs.parts, inputs.role)
        }
        try {
            val previous = groupJobs[group]
            val job = scope.launch {
                val outcome = withTimeoutOrNull(BUDGET_MS) {
                    val future = CompletableFuture.supplyAsync(compute, workers)
                    suspendCancellableCoroutine { cont ->
                        future.whenComplete { value, error ->
                            if (error != null) cont.resume(null)
                            else cont.resume(value)
                        }
                        cont.invokeOnCancellation { future.cancel(false) }
                    }
                }
                // Timeout or crash: degrade to regex-free parsing and cache the
                // degraded result so this rule is not retried on every scroll.
                store(inputs, outcome ?: renderMessagePartsUnregulated(inputs.parts, inputs.role))
            }
            job.invokeOnCompletion { deferred.complete(Unit) }
            if (previous != null && previous.isActive && previous !== job) previous.cancel()
            groupJobs[group] = job
            job.join()
            return cached(inputs) ?: renderMessagePartsUnregulated(inputs.parts, inputs.role)
        } finally {
            inFlight.remove(inputs, deferred)
            deferred.complete(Unit)
        }
    }
}

/**
 * The message bubble's rendered segments. Synchronous on cache hit (the
 * common case: scrolling, re-entering the chat); otherwise starts at the
 * regex-free placeholder and swaps in the full pipeline result once the
 * worker finishes (or the budget degrades it).
 */
@Composable
internal fun rememberRenderedSegments(
    inputs: RenderInputs,
    group: String,
    compute: () -> List<TavernRenderSegment>,
): State<List<TavernRenderSegment>> {
    val synchronous = remember(inputs) { ChatMessageRenderCache.cached(inputs) }
    return produceState(
        initialValue = synchronous ?: renderMessagePartsUnregulated(inputs.parts, inputs.role),
        key1 = inputs, key2 = group,
    ) {
        ChatMessageRenderCache.computeAndAwait(inputs, group, compute)
        // computeAndAwait returned; publish whatever the cache now holds.
        value = ChatMessageRenderCache.cached(inputs) ?: value
    }
}
