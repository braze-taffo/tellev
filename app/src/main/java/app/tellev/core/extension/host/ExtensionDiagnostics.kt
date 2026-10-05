package app.tellev.core.extension.host

import app.tellev.core.extension.ExtensionErrorRecord
import app.tellev.core.extension.ExtensionEvent
import java.util.ArrayDeque

internal class ExtensionDiagnostics {
    private val recentRuntimeEvents = ArrayDeque<ExtensionEvent>()
    private val recentRuntimeEventsLock = Any()

    @Volatile
    private var latestPromptDiagnostics: ExtensionEvent? = null

    private val recentErrors = ArrayDeque<MutableErrorRecord>()
    private val recentErrorsLock = Any()

    private class MutableErrorRecord(
        val extensionId: String,
        val message: String,
        val stack: String?,
        var firstSeenAtMillis: Long,
        var lastSeenAtMillis: Long,
        var occurrences: Int,
    )

    /**
     * Record a script error with its stack. Deduplication key is extension +
     * message + first stack line, so a crashing loop becomes one record with
     * a counter instead of flooding the ring.
     */
    fun rememberExtensionError(extensionId: String, message: String, stack: String?) {
        val now = System.currentTimeMillis()
        val trimmedStack = stack?.takeIf { it.isNotBlank() }
        val firstFrame = trimmedStack?.lineSequence()?.firstOrNull()
        synchronized(recentErrorsLock) {
            val existing = recentErrors.firstOrNull {
                it.extensionId == extensionId && it.message == message && it.stack?.lineSequence()?.firstOrNull() == firstFrame
            }
            if (existing != null) {
                existing.lastSeenAtMillis = now
                existing.occurrences++
                // Keep the record at its position (most recent first is handled
                // by callers sorting on lastSeenAtMillis).
                return
            }
            recentErrors.addFirst(
                MutableErrorRecord(extensionId, message, trimmedStack, now, now, 1),
            )
            while (recentErrors.size > MAX_ERROR_HISTORY) recentErrors.removeLast()
        }
    }

    fun snapshotExtensionErrors(): List<ExtensionErrorRecord> = synchronized(recentErrorsLock) {
        recentErrors.sortedByDescending { it.lastSeenAtMillis }.map {
            ExtensionErrorRecord(it.extensionId, it.message, it.stack, it.firstSeenAtMillis, it.lastSeenAtMillis, it.occurrences)
        }
    }

    fun clearExtensionErrors(extensionId: String?) {
        synchronized(recentErrorsLock) {
            if (extensionId == null) recentErrors.clear()
            else recentErrors.removeIf { it.extensionId == extensionId }
        }
    }

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
        private const val MAX_ERROR_HISTORY = 50
        private val RUNTIME_HISTORY_EVENTS =
            setOf("extension_loaded", "extension_unloaded", "extension_load_failed", "extension_log")
    }
}
