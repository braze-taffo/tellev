package app.tellev.feature.chat

import app.tellev.core.extension.CommitReceipt
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.MutationRequest
import app.tellev.core.extension.RuntimeToken
import app.tellev.core.extension.RuntimeWriteCoordinator
import app.tellev.core.extension.StorageOwner
import app.tellev.core.model.ChatSession
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.applyChatSessionMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/**
 * Owns the lifecycle, active runtime token, revision tracking, and write coordination
 * for the current [ChatSession].
 *
 * Ensures all session mutations flow through [RuntimeWriteCoordinator], preventing
 * concurrent overwrites and guaranteeing that accepted writes are committed even if
 * a session transition is initiated.
 */
internal class ChatSessionRuntime(
    private val dataStore: StDataStore,
    private val onSessionError: ((sessionId: String, error: String) -> Unit)? = null,
    private val onSessionRecovered: ((sessionId: String, truth: ChatSession) -> Unit)? = null,
) {
    val sessionWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val sessionWriteLock = Any()
    val sessionTransitions = Mutex()

    @Volatile
    var runtimeToken: RuntimeToken? = null
        private set

    val sessionWrites = RuntimeWriteCoordinator<ChatSession>(sessionWriteScope) { request, desired ->
        val base = Json.decodeFromJsonElement(ChatSession.serializer(), request.payload.getValue("base"))
        val committed = dataStore.commitChatMutation(base, desired, request.baseRevision, request.operationId)
        CommitReceipt(request.operationId, committed.storageRevision, true)
    }

    fun activateSessionWrites(session: ChatSession): RuntimeToken = synchronized(sessionWriteLock) {
        check(runtimeToken == null) { "Previous chat runtime was not retired" }
        sessionWrites.register(StorageOwner("chat", session.id), session, session.storageRevision)
        val token = sessionWrites.activate(session.id, "native")
        runtimeToken = token
        token
    }

    fun scheduleMetadataSave(
        base: ChatSession,
        desired: ChatSession,
        onSessionUpdated: (ChatSession) -> Unit,
    ): Deferred<CommitReceipt> {
        if (!isOwnerPoisoned(StorageOwner("chat", base.id))) {
            return submitSessionWrite(base, desired, onSessionUpdated)
        }
        // Degraded path: a previous write failed and the owner is poisoned. Re-arm it
        // from disk truth first, then submit against that state. Acceptance of the new
        // write happens after recovery instead of synchronously — only on this path.
        return sessionWriteScope.async {
            recoverPoisonedWrites()
            submitSessionWrite(base, desired, onSessionUpdated).await()
        }
    }

    private fun isOwnerPoisoned(owner: StorageOwner): Boolean = synchronized(sessionWriteLock) {
        runtimeToken?.sessionId == owner.id && sessionWrites.isPoisoned(owner)
    }

    /**
     * Re-arms a poisoned chat owner from disk truth: the coordinator snapshot is reset
     * to what is actually stored, and the UI is rolled back to the same truth — a
     * failed write never persisted, so its optimistic view must not survive either.
     * Returns false when there is nothing to recover or the disk cannot be read;
     * recovery is never retried in a loop.
     */
    suspend fun recoverPoisonedWrites(): Boolean {
        val sessionId = synchronized(sessionWriteLock) {
            val token = runtimeToken ?: return false
            if (!sessionWrites.isPoisoned(StorageOwner("chat", token.sessionId))) return false
            token.sessionId
        }
        val truth = try {
            dataStore.readChatSession(sessionId)
        } catch (error: Exception) {
            onSessionError?.invoke(sessionId, "恢复未保存的会话状态失败：${error.message}")
            return false
        }
        // A poisoned owner's queued writes are all doomed; settle their deferreds first so
        // reset cannot race one that is still flipping to its failed state.
        sessionWrites.awaitSettled(StorageOwner("chat", sessionId))
        val recovered = synchronized(sessionWriteLock) {
            val token = runtimeToken ?: return false
            if (token.sessionId != sessionId) return false
            if (!sessionWrites.isPoisoned(StorageOwner("chat", sessionId))) return false
            sessionWrites.reset(StorageOwner("chat", sessionId), truth, truth.storageRevision)
        }
        onSessionRecovered?.invoke(sessionId, recovered.value)
        return true
    }

    private fun submitSessionWrite(
        base: ChatSession,
        desired: ChatSession,
        onSessionUpdated: (ChatSession) -> Unit,
    ): Deferred<CommitReceipt> {
        val job = synchronized(sessionWriteLock) {
            val token = requireNotNull(runtimeToken) { "会话写入环境尚未初始化" }
            check(token.sessionId == base.id) { "写入所属会话已经切换：${base.id}" }
            val owner = StorageOwner("chat", base.id)
            val previous = sessionWrites.snapshot(owner)
            val request = MutationRequest(
                UUID.randomUUID().toString(),
                token,
                owner,
                "chat",
                previous.revision,
                buildJsonObject { put("base", Json.encodeToJsonElement(ChatSession.serializer(), previous.value)) },
            )
            val accepted = sessionWrites.submit(request) { current ->
                applyChatSessionMutation(base, desired, current).copy(storageRevision = previous.revision + 1)
            }
            val next = sessionWrites.snapshot(owner).value
            onSessionUpdated(next)
            accepted.committed
        }
        job.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                onSessionError?.invoke(base.id, "变量保存失败，生成已暂停：${failure.message}")
            }
        }
        return job
    }

    fun scheduleUiMutation(
        base: ChatSession,
        desired: ChatSession,
        onSessionUpdated: (ChatSession) -> Unit,
        onError: (String) -> Unit,
    ): Deferred<CommitReceipt>? =
        runCatching {
            scheduleMetadataSave(base, desired, onSessionUpdated)
        }.onFailure { error ->
            onError("消息修改未提交：${error.message}")
        }.getOrNull()

    suspend fun persistSessionMutation(
        base: ChatSession,
        desired: ChatSession,
        onSessionUpdated: (ChatSession) -> Unit,
    ) {
        scheduleMetadataSave(base, desired, onSessionUpdated).await()
    }

    suspend fun persistSessionMutation(
        base: ChatSession,
        desired: ChatSession,
        onError: ((String) -> Unit)?,
        onSessionUpdated: (ChatSession) -> Unit,
    ) {
        scheduleMetadataSave(base, desired, onSessionUpdated).await()
    }

    fun launchAfterCommit(
        scope: CoroutineScope,
        commit: Deferred<CommitReceipt>,
        onError: (String) -> Unit,
        block: suspend () -> Unit,
    ): Job = scope.launch {
        try {
            commit.await()
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError("消息更新未完成：${error.message}")
        }
    }

    suspend fun flushSessionWrites(sessionId: String?, extensionHost: ExtensionHost) {
        if (sessionId == null) return
        runtimeToken?.takeIf { it.sessionId == sessionId }?.let {
            if (sessionWrites.isPoisoned(StorageOwner("chat", sessionId))) recoverPoisonedWrites()
            sessionWrites.flushWrites(it)
        }
        extensionHost.flushWrites()
    }

    suspend fun retireSessionRuntime(extensionHost: ExtensionHost) {
        // The token is cleared first: a write racing a transition fails fast instead of
        // being accepted into a chain that release may never await.
        val token = synchronized(sessionWriteLock) {
            val current = runtimeToken
            runtimeToken = null
            current
        }
        if (token == null) {
            runCatching { extensionHost.flushWrites() }
                .onFailure { error -> onSessionError?.invoke("", "扩展写入收尾失败：${error.message}") }
            return
        }
        // Every step degrades to a single report. A poisoned chain must never abort the
        // transition itself — that is how one failed write wedged every later switch,
        // creation and close of a session until process death.
        runCatching { extensionHost.flushWrites() }
            .onFailure { error -> onSessionError?.invoke(token.sessionId, "扩展写入收尾失败：${error.message}") }
        runCatching { sessionWrites.release(token) }
            .onFailure { error -> onSessionError?.invoke(token.sessionId, "会话写入收尾失败：${error.message}") }
        // release aborts on the first failure; settle every queued write before
        // discarding so the cell can actually be removed and the owner re-registered.
        sessionWrites.awaitSettled(StorageOwner("chat", token.sessionId))
        runCatching { sessionWrites.unregister(StorageOwner("chat", token.sessionId), discardFailures = true) }
            .onFailure { error -> onSessionError?.invoke(token.sessionId, "会话写入清理失败：${error.message}") }
    }

    fun currentRuntimeToken(sessionId: String?): RuntimeToken? = runtimeToken?.takeIf {
        it.sessionId == sessionId && sessionWrites.isActive(it)
    }

    fun requireMessageRuntime(token: RuntimeToken?, currentSessionId: String?) {
        check(token != null && sessionWrites.isActive(token) && currentSessionId == token.sessionId) {
            "消息前端所属会话已失效"
        }
    }

    fun observePersisted(sessionId: String, refreshed: ChatSession): ChatSession = synchronized(sessionWriteLock) {
        if (runtimeToken?.sessionId == sessionId) {
            sessionWrites.observePersisted(
                StorageOwner("chat", sessionId),
                refreshed,
                refreshed.storageRevision,
            ).value
        } else {
            refreshed
        }
    }

    fun onCleared(
        extensionHost: ExtensionHost,
        loadedCharacterScriptExtensionId: String?,
        onError: (String) -> Unit,
    ) {
        val token = runtimeToken
        val scriptOwner = loadedCharacterScriptExtensionId
        val scriptCapability = scriptOwner?.let { extensionHost.capabilityToken(it) }
        token?.let { sessionWrites.revoke(it) }
        sessionWriteScope.launch {
            try {
                if (scriptOwner != null && scriptCapability != null && extensionHost.capabilityToken(scriptOwner) == scriptCapability) {
                    extensionHost.unload(scriptOwner)
                }
                token?.let {
                    try {
                        sessionWrites.release(it)
                    } catch (error: Exception) {
                        onError("退出时仍有未完成写入：${error.message}")
                    }
                    sessionWrites.awaitSettled(StorageOwner("chat", it.sessionId))
                    runCatching { sessionWrites.unregister(StorageOwner("chat", it.sessionId), discardFailures = true) }
                }
            } catch (error: Exception) {
                onError("退出时仍有未完成写入：${error.message}")
            } finally {
                sessionWriteScope.cancel()
            }
        }
    }
}
