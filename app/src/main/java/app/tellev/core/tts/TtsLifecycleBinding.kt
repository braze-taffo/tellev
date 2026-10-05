package app.tellev.core.tts

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.StEventCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.lang.ref.WeakReference

/** Page/session ownership outlives lazy-list items. There is deliberately no item-dispose action. */
class TtsLifecycleBinding(
    scope: CoroutineScope,
    private val service: TtsSpeechService,
    sessionEvents: Flow<ExtensionEvent>,
) {
    private var owner = WeakReference<LifecycleOwner>(null)
    private val observer = LifecycleEventObserver { source, event ->
        if (source === owner.get() && (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY)) {
            service.stop()
        }
    }
    private val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        sessionEvents.collect { event ->
            if (event.name == StEventCatalog.CHAT_CHANGED) {
                val sessionId = ((event.payload["args"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                service.onSessionChanged(sessionId)
            }
        }
    }

    fun bind(pageOwner: LifecycleOwner, sessionId: String?) {
        if (owner.get() !== pageOwner) {
            owner.get()?.lifecycle?.removeObserver(observer)
            service.stop()
            owner = WeakReference(pageOwner)
            pageOwner.lifecycle.addObserver(observer)
        }
        service.onSessionChanged(sessionId)
    }

    fun close() {
        owner.get()?.lifecycle?.removeObserver(observer)
        owner.clear()
        eventJob.cancel()
        service.release()
    }
}
