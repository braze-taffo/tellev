package app.tellev.core.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RuntimeWriteCoordinatorTest {
    private val owner = StorageOwner("chat", "a")
    private fun request(token: RuntimeToken, revision: Long, id: String = "op-$revision", target: StorageOwner = owner) =
        MutationRequest(id, token, target, "chat", revision, buildJsonObject { put("delta", 1) })

    @Test fun `synchronous state advances while persistence is ordered and barrier waits`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val persisted = mutableListOf<Int>()
        val coordinator = RuntimeWriteCoordinator<Int>(this) { request, value ->
            if (value == 1) gate.await()
            persisted.add(value)
            CommitReceipt(request.operationId, request.baseRevision + 1, true)
        }
        coordinator.register(owner, 0, 0)
        val token = coordinator.activate("a", "script-one")
        val first = coordinator.submit(request(token, 0)) { it + 1 }
        coordinator.submit(request(coordinator.forSource(token, "message-iframe"), 1)) { it + 1 }
        assertEquals(2, coordinator.snapshot(owner).value)
        val barrier = async { coordinator.flushWrites(token) }
        yield()
        assertFalse(first.committed.isCompleted)
        assertFalse(barrier.isCompleted)
        assertTrue(persisted.isEmpty())
        gate.complete(Unit)
        assertEquals(listOf(1L, 2L), barrier.await().map { it.revision })
        assertEquals(listOf(1, 2), persisted)
    }

    @Test fun `switch revokes late calls while accepted writes keep the original owner`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val targets = mutableListOf<StorageOwner>()
        val coordinator = RuntimeWriteCoordinator<Int>(this) { request, _ ->
            if (request.owner == owner) gate.await()
            targets.add(request.owner)
            CommitReceipt(request.operationId, request.baseRevision + 1, true)
        }
        val other = StorageOwner("chat", "b")
        coordinator.register(owner, 0, 0); coordinator.register(other, 0, 0)
        val token = coordinator.activate("a", "script")
        coordinator.submit(request(token, 0)) { it + 1 }
        val next = coordinator.activate("b", "script")
        assertThrows(IllegalStateException::class.java) { coordinator.submit(request(token, 1)) { it + 1 } }
        coordinator.submit(request(next, 0, "other", other)) { it + 1 }
        coordinator.flushWrites(next)
        assertEquals(listOf(other), targets)
        gate.complete(Unit)
        coordinator.flushWrites(token)
        assertEquals(listOf(other, owner), targets)
    }

    @Test fun `duplicates run no transition twice and stale or colliding operations are rejected`() = runBlocking {
        val coordinator = RuntimeWriteCoordinator<Int>(this) { request, _ -> CommitReceipt(request.operationId, request.baseRevision + 1, true) }
        coordinator.register(owner, 0, 0)
        val token = coordinator.activate("a", "script")
        val request = request(token, 0)
        val first = coordinator.submit(request) { it + 1 }
        assertSame(first, coordinator.submit(request) { error("Duplicate transition executed") })
        assertThrows(IllegalStateException::class.java) { coordinator.submit(request.copy(operationId = "late")) { it + 1 } }
        assertThrows(IllegalStateException::class.java) { coordinator.submit(request.copy(baseRevision = 1)) { it + 1 } }
        coordinator.flushWrites(token)
        assertEquals(1, coordinator.snapshot(owner).value)
    }

    @Test fun `failed persistence prevents dependent commits and barrier reports failure`() = runBlocking {
        val calls = mutableListOf<Int>()
        val coordinator = RuntimeWriteCoordinator<Int>(this) { _, value -> calls.add(value); error("disk failed") }
        coordinator.register(owner, 0, 0)
        val token = coordinator.activate("a", "script")
        coordinator.submit(request(token, 0)) { it + 1 }
        coordinator.submit(request(token, 1)) { it + 1 }
        yield()
        try { coordinator.flushWrites(token); fail("Expected persistence failure") } catch (e: IllegalStateException) { assertEquals("disk failed", e.message) }
        assertEquals(listOf(1), calls)
        assertThrows(IllegalStateException::class.java) { coordinator.submit(request(token, 2)) { it + 1 } }
        assertEquals(2, coordinator.snapshot(owner).value) // Dirty recoverable memory is retained.
    }
}
