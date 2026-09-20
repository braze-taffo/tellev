package app.tellev.core.extension

/**
 * Barrier and notification hooks for chat writes that bypass RuntimeWriteCoordinator —
 * today the extension virtual API's whole-file saves and appends. quiesce waits until
 * the active chat owner has no pending coordinated writes; notifyWritten re-adopts the
 * on-disk state so later coordinated commits do not fail as stale. Both default to
 * no-ops, preserving behavior whenever no chat runtime is registered for the session.
 */
interface ExternalChatWritePort {
    suspend fun quiesce(sessionId: String) {}

    suspend fun notifyWritten(sessionId: String) {}
}

/**
 * Late-bound port: the router owns it from construction, the chat runtime registers a
 * live implementation into it and clears it on teardown. Unregistered means no active
 * chat owner, so extension direct writes keep their standalone behavior.
 */
class MutableExternalChatWritePort : ExternalChatWritePort {
    private val unbound: ExternalChatWritePort = object : ExternalChatWritePort {}

    @Volatile
    private var delegate: ExternalChatWritePort = unbound

    fun register(port: ExternalChatWritePort?) {
        delegate = port ?: unbound
    }

    override suspend fun quiesce(sessionId: String) = delegate.quiesce(sessionId)

    override suspend fun notifyWritten(sessionId: String) = delegate.notifyWritten(sessionId)
}
