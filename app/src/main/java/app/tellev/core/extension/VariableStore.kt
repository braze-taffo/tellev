package app.tellev.core.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap

/** SillyTavern-compatible variable scopes. See [VariableStore]. */
enum class VariableScope { LOCAL, GLOBAL }

/**
 * Live read/write access to the current chat's `chat_metadata.variables`
 * (the LOCAL scope).  Implemented by the UI layer ([app.tellev.feature.chat.ChatViewModel])
 * because the local store is per-chat while [WebViewJsExtensionHost] is a
 * process-wide singleton.  This is the same "runtime-injected backend"
 * pattern used by [ExtensionContextProvider].
 */
interface LocalVariableBackend {
    /** Cheap snapshot of the current local variables as flat String→String. */
    fun snapshot(): Map<String, String>

    /**
     * Atomically mutate the local variables via [transform] and return the
     * resulting snapshot.  Implementations are responsible for persisting
     * the change (debounced) to the chat JSONL.
     */
    fun update(transform: (MutableMap<String, String>) -> Unit): Map<String, String>
}

/**
 * Per-scope variable store backing the SillyTavern variable model.
 *
 * - LOCAL scope lives in `chat_metadata.variables` of the active chat and is
 *   reached through a [LocalVariableBackend] plugged in by the UI.
 * - GLOBAL scope lives in a single JsonObject persisted via
 *   [ExtensionSettingsStore] (key [TAVERN_HELPER_VARS_KEY]) and is shared by
 *   slash commands, macros, and `TavernHelper.getVariables()` from JS.
 *
 * Both scopes are flat `String → String` to match ST semantics; compound
 * values are stringified by callers (ST does the same).
 */
class VariableStore(
    private val scope: CoroutineScope,
    private val settingsStore: ExtensionSettingsStore,
    private val settingsKey: String,
) {
    @Volatile
    private var localBackend: LocalVariableBackend? = null

    fun setLocalBackend(backend: LocalVariableBackend?) {
        localBackend = backend
    }

    // GLOBAL in-memory mirror. Values are JsonElement so TavernHelper.getVariables
    // can hand back the raw object shape extensions expect.
    private val global = ConcurrentHashMap<String, JsonElement>()
    private val saveMutex = Mutex()

    /** Load the persisted global object once at startup. Idempotent. */
    fun loadGlobal(obj: JsonObject) {
        global.clear()
        obj.forEach { (k, v) -> global[k] = v }
    }

    // ── LOCAL (String API) ───────────────────────────────────────────────

    fun getLocal(name: String): String? = localBackend?.snapshot()?.get(name)

    fun setLocal(name: String, value: String) {
        if (name.isBlank()) return
        localBackend?.update { it[name] = value }
    }

    fun addLocal(name: String, increment: String): String {
        val b = localBackend ?: return "0"
        val snap = b.update { m ->
            val current = m[name] ?: "0"
            m[name] = addStrings(current, increment)
        }
        return snap[name] ?: "0"
    }

    fun incLocal(name: String): String = addLocal(name, "1")
    fun decLocal(name: String): String = addLocal(name, "-1")

    fun deleteLocal(name: String) {
        localBackend?.update { it.remove(name) }
    }

    fun hasLocal(name: String): Boolean = localBackend?.snapshot()?.containsKey(name) == true

    fun listLocal(): List<String> = localBackend?.snapshot()?.keys?.sorted() ?: emptyList()

    // ── GLOBAL (String API) ──────────────────────────────────────────────

    fun getGlobal(name: String): String? = global[name]?.let { elementToString(it) }

    fun setGlobal(name: String, value: String) {
        if (name.isBlank()) return
        global[name] = JsonPrimitive(value)
        persistGlobal()
    }

    fun addGlobal(name: String, increment: String): String {
        val current = global[name]?.let { elementToString(it) } ?: "0"
        val result = addStrings(current, increment)
        global[name] = JsonPrimitive(result)
        persistGlobal()
        return result
    }

    fun incGlobal(name: String): String = addGlobal(name, "1")
    fun decGlobal(name: String): String = addGlobal(name, "-1")

    fun deleteGlobal(name: String) {
        global.remove(name)
        persistGlobal()
    }

    fun hasGlobal(name: String): Boolean = global.containsKey(name)

    fun listGlobal(): List<String> = global.keys().toList().sorted()

    // ── Raw JsonObject API (TavernHelper / EJS) ──────────────────────────

    /** The full global object as extensions see it via `getVariables()`. */
    fun globalObject(): JsonObject = buildJsonObject {
        global.forEach { (k, v) -> put(k, v) }
    }

    /** Overwrite the entire global store and persist. */
    fun replaceGlobal(obj: JsonObject) {
        global.clear()
        obj.forEach { (k, v) -> global[k] = v }
        persistGlobal()
    }

    /** Snapshot of the current local variables as a JsonObject. */
    fun localObject(): JsonObject {
        val snap = localBackend?.snapshot() ?: emptyMap()
        return buildJsonObject {
            snap.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
    }

    /** Overwrite the entire local store through the backend. */
    fun replaceLocal(obj: JsonObject) {
        localBackend?.update { m ->
            m.clear()
            obj.forEach { (k, v) -> m[k] = elementToString(v) }
        }
    }

    /**
     * Merge view with GLOBAL as the base and LOCAL overriding — matches the
     * ST-Prompt-Template precedence (global < local).  Used for the EJS
     * `variables`/`vars` environment property.
     */
    fun mergedObject(): JsonObject = buildJsonObject {
        global.forEach { (k, v) -> put(k, v) }
        localBackend?.snapshot()?.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    private fun persistGlobal() {
        scope.launch {
            saveMutex.withLock {
                settingsStore.saveSettings(settingsKey, globalObject())
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun elementToString(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    /** ST addvar semantics: numeric add when both parse, else string concat. */
    private fun addStrings(current: String, increment: String): String {
        val a = current.toLongOrNull()
        val b = increment.toLongOrNull()
        return if (a != null && b != null) (a + b).toString() else current + increment
    }
}
