package app.tellev.core.extension.host

import app.tellev.core.extension.ExtensionEvent
import java.util.ArrayDeque

internal class ExtensionDiagnostics {
    private val recentRuntimeEvents = ArrayDeque<ExtensionEvent>()
    private val recentRuntimeEventsLock = Any()

    @Volatile
    private var latestPromptDiagnostics: ExtensionEvent? = null

    fun rememberHostEvent(event: ExtensionEvent) {
        if (event.name == "prompt_diagnostics") {
            latestPromptDiagnostics = event
            return
        }
        if (event.name !in RUNTIME_HISTORY_EVENTS) return
        synchronized(recentRuntimeEventsLock) {
            recentRuntimeEvents.addLast(event)
            while (recentRuntimeEvents.size > MAX_RUNTIME_EVENT_HISTORY) {
                recentRuntimeEvents.removeFirst()
            }
        }
    }

    fun snapshotHostEvents(pendingPermissionEvents: Collection<ExtensionEvent>): List<ExtensionEvent> {
        val runtime = synchronized(recentRuntimeEventsLock) { recentRuntimeEvents.toList() }
        return buildList {
            addAll(runtime)
            latestPromptDiagnostics?.let(::add)
            addAll(pendingPermissionEvents)
        }
    }

    fun clearHostRuntimeLogs(extensionId: String?) {
        synchronized(recentRuntimeEventsLock) {
            recentRuntimeEvents.removeIf { event ->
                event.name == "extension_log" &&
                    (extensionId == null || event.extensionId == extensionId)
            }
        }
    }

    fun clearHostPromptDiagnostics() {
        latestPromptDiagnostics = null
    }

    companion object {
        private const val MAX_RUNTIME_EVENT_HISTORY = 200
        private val RUNTIME_HISTORY_EVENTS =
            setOf("extension_loaded", "extension_unloaded", "extension_load_failed", "extension_log")
    }
}
