package app.tellev.core.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

@Serializable
data class RuntimeToken(val sessionId: String, val generation: Long, val source: String)

/** Owner identifies a physical storage object, not a variable namespace within it. */
@Serializable
data class StorageOwner(val kind: String, val id: String)

@Serializable
data class MutationRequest(
    val operationId: String,
    val token: RuntimeToken,
    val owner: StorageOwner,
    val scope: String,
    val baseRevision: Long,
    val payload: JsonObject,
    val messageId: String? = null,
    val swipe: Int? = null,
)

@Serializable
data class CommitReceipt(val operationId: String, val revision: Long, val persisted: Boolean)

/**
 * Accepts a state transition once, synchronously, then persists that exact state in owner order.
 * The caller must supply an immutable state and a pure transition; callbacks/events are run
 * outside this class. Revocation prevents new calls without cancelling already accepted writes.
 */
class RuntimeWriteCoordinator<T>(
    private val scope: CoroutineScope,
    private val persist: suspend (MutationRequest, T) -> CommitReceipt,
) {
    data class Snapshot<T>(val value: T, val revision: Long)
    data class Accepted(val revision: Long, val committed: Deferred<CommitReceipt>)

    private class Cell<T>(var snapshot: Snapshot<T>) {
        var tail: Deferred<CommitReceipt>? = null
        var failure: Throwable? = null
    }
    private data class Operation(val token: RuntimeToken, val fingerprint: String, val accepted: Accepted)

    private val lock = Any()
    private val cells = mutableMapOf<StorageOwner, Cell<T>>()
    private val operations = mutableMapOf<String, Operation>()
    private var generation = 0L
    private var activeSession: String? = null

    fun activate(sessionId: String, source: String): RuntimeToken = synchronized(lock) {
        activeSession = sessionId
        RuntimeToken(sessionId, ++generation, source)
    }

    fun forSource(token: RuntimeToken, source: String): RuntimeToken = synchronized(lock) {
        requireActive(token)
        token.copy(source = source)
    }

    fun isActive(token: RuntimeToken): Boolean = synchronized(lock) {
        token.sessionId == activeSession && token.generation == generation
    }

    fun revoke(token: RuntimeToken) = synchronized(lock) {
        if (token.sessionId == activeSession && token.generation == generation) {
            activeSession = null
            generation++
        }
    }

    fun register(owner: StorageOwner, value: T, revision: Long): Snapshot<T> = synchronized(lock) {
        val previous = cells[owner]
        check(previous == null) { "Owner already registered: $owner" }
        require(revision >= 0) { "Negative storage revision" }
        Snapshot(value, revision).also { cells[owner] = Cell(it) }
    }

    fun snapshot(owner: StorageOwner): Snapshot<T> = synchronized(lock) {
        cells[owner]?.snapshot ?: error("Unknown storage owner: $owner")
    }

    fun observePersisted(owner: StorageOwner, value: T, revision: Long): Snapshot<T> = synchronized(lock) {
        val cell = cells[owner] ?: error("Unknown storage owner: $owner")
        if (revision < cell.snapshot.revision) return@synchronized cell.snapshot
        check(revision == cell.snapshot.revision || cell.tail?.isCompleted != false) { "External write conflicts with pending state: $owner" }
        cell.snapshot = Snapshot(value, revision)
        cell.snapshot
    }

    fun submit(request: MutationRequest, transition: (T) -> T): Accepted {
        val cell: Cell<T>
        val previous: Deferred<CommitReceipt>?
        val completion = CompletableDeferred<CommitReceipt>()
        val accepted: Accepted
        val next: Snapshot<T>
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString(request).toByteArray())
            .joinToString("") { "%02x".format(it) }
        synchronized(lock) {
            // Retransmission of an already accepted operation is safe even after revocation.
            operations[request.operationId]?.let {
                check(it.fingerprint == fingerprint) { "Operation ID reused with another request: ${request.operationId}" }
                return it.accepted
            }
            requireActive(request.token)
            if (request.scope == "chat" || request.scope == "message") {
                require(request.owner.kind == "chat" && request.owner.id == request.token.sessionId) { "Mutation belongs to another chat" }
            }
            require(request.operationId.isNotBlank()) { "Missing mutation operation ID" }
            if (request.scope == "message") {
                require(!request.messageId.isNullOrBlank() && request.swipe != null && request.swipe >= 0) {
                    "Message mutations require a stable message ID and swipe"
                }
            }
            cell = cells[request.owner] ?: error("Unknown storage owner: ${request.owner}")
            cell.failure?.let { throw IllegalStateException("Storage owner requires recovery: ${request.owner}", it) }
            check(cell.snapshot.revision == request.baseRevision) {
                "Stale mutation ${request.operationId}: expected ${request.baseRevision}, current ${cell.snapshot.revision}"
            }
            next = Snapshot(transition(cell.snapshot.value), cell.snapshot.revision + 1)
            previous = cell.tail
            accepted = Accepted(next.revision, completion)
            cell.snapshot = next
            cell.tail = completion
            operations[request.operationId] = Operation(request.token, fingerprint, accepted)
        }
        val job = scope.launch {
            try {
                previous?.await()
                val receipt = persist(request, next.value)
                check(receipt.operationId == request.operationId && receipt.revision == accepted.revision && receipt.persisted) {
                    "Persistence returned an invalid receipt for ${request.operationId}"
                }
                completion.complete(receipt)
            } catch (error: Throwable) {
                synchronized(lock) { cell.failure = error }
                completion.completeExceptionally(error)
            }
        }
        // Also resolve callers if the supplied app lifetime scope was cancelled before launch.
        job.invokeOnCompletion { error ->
            if (error != null) {
                synchronized(lock) { cell.failure = error }
                completion.completeExceptionally(error)
            }
        }
        return accepted
    }

    /** A barrier for operations accepted from this runtime generation before this call. */
    suspend fun flushWrites(token: RuntimeToken): List<CommitReceipt> {
        val writes = synchronized(lock) {
            operations.values.filter {
                it.token.sessionId == token.sessionId && it.token.generation == token.generation
            }.map { it.accepted.committed }
        }
        return writes.map { it.await() }
    }

    suspend fun release(token: RuntimeToken) {
        revoke(token)
        flushWrites(token)
        synchronized(lock) {
            operations.entries.removeAll { (_, operation) ->
                operation.token.sessionId == token.sessionId && operation.token.generation == token.generation
            }
        }
    }

    fun unregister(owner: StorageOwner) = synchronized(lock) {
        val cell = cells[owner] ?: return@synchronized
        check(cell.failure == null && cell.tail?.isCompleted != false) { "Cannot discard uncommitted state: $owner" }
        cells.remove(owner)
    }

    private fun requireActive(token: RuntimeToken) {
        check(token.sessionId == activeSession && token.generation == generation) {
            "Expired runtime: ${token.sessionId}/${token.generation}/${token.source}"
        }
    }
}
