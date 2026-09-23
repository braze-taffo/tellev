package app.tellev.core.provider

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/** Cancel a blocking OkHttp call as soon as its coroutine is cancelled, including body reads. */
internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T {
    val context = coroutineContext
    context.ensureActive()
    val guard = Job(context[Job])
    guard.invokeOnCompletion { if (!isCanceled()) cancel() }
    try {
        return execute().use(block)
    } catch (failure: Exception) {
        // OkHttp reports a cancelled socket read as IOException; preserve coroutine cancellation.
        context.ensureActive()
        throw failure
    } finally {
        guard.complete()
    }
}
