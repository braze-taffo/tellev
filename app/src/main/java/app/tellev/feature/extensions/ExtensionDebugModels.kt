package app.tellev.feature.extensions

import app.tellev.core.extension.ExtensionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A bounded, user-visible record produced by the real extension event stream. */
data class ExtensionRuntimeLog(
    val timestampMillis: Long,
    val extensionId: String?,
    val level: ExtensionRuntimeLogLevel,
    val message: String,
)

enum class ExtensionRuntimeLogLevel {
    Debug,
    Info,
    Warning,
    Error,
}

data class ExtensionRuntimeStatus(
    val loaded: Boolean = false,
    val lastEvent: String? = null,
    val lastError: String? = null,
    val updatedAtMillis: Long? = null,
)

/**
 * Final prompt data reported by the generation pipeline.  This model lives
 * in the feature layer deliberately: the extensions screen can render a
 * truthful snapshot without reaching into ChatViewModel or rebuilding a
 * prompt with potentially different inputs.
 */
data class PromptDebugSnapshot(
    val messages: List<PromptDebugMessage>,
    val estimatedTokenCount: Int?,
    val activatedWorldEntryIds: List<String>,
    val warnings: List<String>,
    val capturedAtMillis: Long,
)

data class PromptDebugMessage(
    val role: String,
    val name: String? = null,
    val content: String,
)

data class PendingExtensionPermission(
    val requestId: String,
    val extensionId: String,
    val permission: app.tellev.core.extension.ExtensionPermission,
)

data class ExtensionRuntimeOverview(
    val loadedExtensionIds: Set<String> = emptySet(),
    val statusByExtensionId: Map<String, ExtensionRuntimeStatus> = emptyMap(),
    val logs: List<ExtensionRuntimeLog> = emptyList(),
    val promptDebugSnapshot: PromptDebugSnapshot? = null,
)

internal const val PROMPT_DIAGNOSTICS_EVENT = "prompt_diagnostics"
private const val MAX_RUNTIME_LOGS = 200

/** Pure event reducer, kept separate from the ViewModel so its contracts are unit-testable. */
internal fun reduceExtensionRuntimeEvent(
    current: ExtensionRuntimeOverview,
    event: ExtensionEvent,
    nowMillis: Long,
): ExtensionRuntimeOverview {
    if (event.name == PROMPT_DIAGNOSTICS_EVENT) {
        val snapshot = event.payload.toPromptDebugSnapshot(nowMillis) ?: return current
        return current.copy(promptDebugSnapshot = snapshot)
    }

    val extensionId = event.extensionId
    return when (event.name) {
        "extension_loaded" -> {
            if (extensionId == null) return current
            current
                .withLoadedStatus(extensionId, loaded = true, nowMillis = nowMillis)
                .appendLog(
                    ExtensionRuntimeLog(nowMillis, extensionId, ExtensionRuntimeLogLevel.Info, "扩展已加载"),
                )
        }

        "extension_unloaded" -> {
            if (extensionId == null) return current
            current
                .withLoadedStatus(extensionId, loaded = false, nowMillis = nowMillis)
                .appendLog(
                    ExtensionRuntimeLog(nowMillis, extensionId, ExtensionRuntimeLogLevel.Info, "扩展已卸载"),
                )
        }

        "extension_load_failed" -> {
            if (extensionId == null) return current
            val message = event.payload.stringValue("message") ?: "扩展加载失败"
            val unloaded = current.withLoadedStatus(extensionId, loaded = false, nowMillis = nowMillis)
            val previous = unloaded.statusByExtensionId.getValue(extensionId)
            unloaded.copy(
                statusByExtensionId = unloaded.statusByExtensionId + (
                    extensionId to previous.copy(
                        loaded = false,
                        lastEvent = event.name,
                        lastError = message,
                        updatedAtMillis = nowMillis,
                    )
                ),
            ).appendLog(
                ExtensionRuntimeLog(
                    nowMillis,
                    extensionId,
                    ExtensionRuntimeLogLevel.Error,
                    message,
                ),
            )
        }

        "extension_log" -> {
            val message = event.payload.stringValue("message") ?: return current
            val level = event.payload.stringValue("level").toRuntimeLogLevel()
            val existing = extensionId?.let { current.statusByExtensionId[it] }
            val updatedStatus = if (extensionId == null) {
                current.statusByExtensionId
            } else {
                current.statusByExtensionId + (
                    extensionId to (existing ?: ExtensionRuntimeStatus()).copy(
                        lastEvent = event.name,
                        lastError = if (level == ExtensionRuntimeLogLevel.Error) message else existing?.lastError,
                        updatedAtMillis = nowMillis,
                    )
                )
            }
            current.copy(statusByExtensionId = updatedStatus).appendLog(
                ExtensionRuntimeLog(nowMillis, extensionId, level, message),
            )
        }

        else -> current
    }
}

internal fun resolveExtensionLoaded(
    extensionId: String,
    runtime: ExtensionRuntimeOverview,
    hostHasCapability: Boolean,
): Boolean = extensionId in runtime.loadedExtensionIds || hostHasCapability

internal fun ExtensionRuntimeOverview.withLocalFailure(
    extensionId: String,
    message: String,
    nowMillis: Long,
): ExtensionRuntimeOverview {
    val previous = statusByExtensionId[extensionId] ?: ExtensionRuntimeStatus()
    return copy(
        statusByExtensionId = statusByExtensionId + (
            extensionId to previous.copy(
                lastEvent = "host_error",
                lastError = message,
                updatedAtMillis = nowMillis,
            )
        ),
    ).appendLog(ExtensionRuntimeLog(nowMillis, extensionId, ExtensionRuntimeLogLevel.Error, message))
}

internal fun ExtensionRuntimeOverview.withLoadedState(
    extensionId: String,
    loaded: Boolean,
    nowMillis: Long,
): ExtensionRuntimeOverview = withLoadedStatus(extensionId, loaded, nowMillis)

private fun ExtensionRuntimeOverview.withLoadedStatus(
    extensionId: String,
    loaded: Boolean,
    nowMillis: Long,
): ExtensionRuntimeOverview {
    val previous = statusByExtensionId[extensionId] ?: ExtensionRuntimeStatus()
    return copy(
        loadedExtensionIds = if (loaded) loadedExtensionIds + extensionId else loadedExtensionIds - extensionId,
        statusByExtensionId = statusByExtensionId + (
            extensionId to previous.copy(
                loaded = loaded,
                lastEvent = if (loaded) "extension_loaded" else "extension_unloaded",
                lastError = if (loaded) null else previous.lastError,
                updatedAtMillis = nowMillis,
            )
        ),
    )
}

private fun ExtensionRuntimeOverview.appendLog(log: ExtensionRuntimeLog): ExtensionRuntimeOverview =
    copy(logs = (logs + log).takeLast(MAX_RUNTIME_LOGS))

private fun JsonObject.toPromptDebugSnapshot(nowMillis: Long): PromptDebugSnapshot? {
    val messageArray = this["messages"] as? JsonArray ?: return null
    val messages = messageArray.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val role = obj.stringValue("role") ?: return@mapNotNull null
        val content = obj.stringValue("content") ?: ""
        PromptDebugMessage(role = role, name = obj.stringValue("name"), content = content)
    }
    return PromptDebugSnapshot(
        messages = messages,
        estimatedTokenCount = this["estimatedTokenCount"]?.jsonPrimitive?.intOrNull,
        activatedWorldEntryIds = stringArray("activatedWorldEntryIds"),
        warnings = stringArray("warnings"),
        capturedAtMillis = nowMillis,
    )
}

private fun JsonObject.stringArray(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        .orEmpty()

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun String?.toRuntimeLogLevel(): ExtensionRuntimeLogLevel = when (this?.lowercase()) {
    "debug", "trace" -> ExtensionRuntimeLogLevel.Debug
    "warn", "warning" -> ExtensionRuntimeLogLevel.Warning
    "error", "fatal" -> ExtensionRuntimeLogLevel.Error
    else -> ExtensionRuntimeLogLevel.Info
}
