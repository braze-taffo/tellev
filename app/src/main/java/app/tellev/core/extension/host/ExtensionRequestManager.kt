package app.tellev.core.extension.host

import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.SlashCommandResult
import app.tellev.core.extension.VirtualApiResponse
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

internal class ExtensionRequestManager {
    val pendingEvaluations = ConcurrentHashMap<String, CompletableDeferred<String>>()
    val pendingEvaluationOwners = ConcurrentHashMap<String, String>()

    val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<SlashCommandResult>>()
    val pendingCommandOwners = ConcurrentHashMap<String, String>()

    val pendingVirtualApi = ConcurrentHashMap<String, CompletableDeferred<VirtualApiResponse>>()
    val pendingVirtualApiOwners = ConcurrentHashMap<String, String>()

    val pendingPermissions = ConcurrentHashMap<String, String>()
    val pendingPermissionOwners = ConcurrentHashMap<String, String>()
    val pendingPermissionEvents = ConcurrentHashMap<String, ExtensionEvent>()

    val pendingLoads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val pendingLoadFailures = ConcurrentHashMap<String, String>()

    fun cancelPendingForExtension(extensionId: String) {
        cancelPending(pendingEvaluationOwners, pendingEvaluations, extensionId)
        cancelPending(pendingCommandOwners, pendingCommands, extensionId)
        cancelPending(pendingVirtualApiOwners, pendingVirtualApi, extensionId)
        pendingPermissions.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingPermissionOwners.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingPermissionEvents.entries.removeIf { (_, event) -> event.extensionId == extensionId }
        pendingLoads.remove(extensionId)?.cancel()
        pendingLoadFailures.remove(extensionId)
    }

    private fun <T> cancelPending(
        owners: ConcurrentHashMap<String, String>,
        pending: ConcurrentHashMap<String, CompletableDeferred<T>>,
        extensionId: String,
    ) {
        val owned = owners.entries.filter { it.value == extensionId }.map { it.key }
        for (id in owned) {
            owners.remove(id)
            pending.remove(id)?.cancel()
        }
    }
}
