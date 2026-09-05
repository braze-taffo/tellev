package app.tellev.core.extension

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
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
    /**
     * Cheap snapshot of the current local variables.
     *
     * Values are [JsonElement], not String: variable cards (MVU and friends)
     * keep structured state here, and stringifying it on the way in or out
     * made `getVariables().stat_data.hp` undefined and let any scalar write
     * collapse the whole table into flat strings.
     */
    fun snapshot(): Map<String, JsonElement>

    /**
     * Atomically mutate the local variables via [transform] and return the
     * resulting snapshot.  Implementations are responsible for persisting
     * the change (debounced) to the chat JSONL.
     */
    fun update(transform: (MutableMap<String, JsonElement>) -> Unit): Map<String, JsonElement>
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
    private val scopedLocalBackend = ThreadLocal<LocalVariableBackend?>()

    fun setLocalBackend(backend: LocalVariableBackend?) {
        localBackend = backend
    }

    /** Use a generation/session-bound LOCAL backend on this thread only. */
    fun <T> withLocalBackend(backend: LocalVariableBackend, block: () -> T): T {
        val previous = scopedLocalBackend.get()
        scopedLocalBackend.set(backend)
        return try {
            block()
        } finally {
            if (previous == null) scopedLocalBackend.remove()
            else scopedLocalBackend.set(previous)
        }
    }

    private fun activeLocalBackend(): LocalVariableBackend? = scopedLocalBackend.get() ?: localBackend

    // GLOBAL in-memory mirror. Values are JsonElement so TavernHelper.getVariables
    // can hand back the raw object shape extensions expect.
    private val global = ConcurrentHashMap<String, JsonElement>()
    private val globalLock = Any()
    private var globalTail: Deferred<Unit>? = null
    private var globalFailure: Throwable? = null
    @Volatile private var globalLoaded = false
    private val initialization = Mutex()

    /** Load the persisted global object once at startup. Idempotent. */
    fun loadGlobal(obj: JsonObject) = synchronized(globalLock) {
        check(globalTail == null) { "Global variables already have accepted writes" }
        global.clear()
        obj.forEach { (k, v) -> global[k] = v }
        globalLoaded = true
    }

    suspend fun initialize() = initialization.withLock {
        if (!globalLoaded) loadGlobal(settingsStore.getSettings(settingsKey))
    }

    private fun <T> withGlobalState(block: () -> T): T {
        // Public variable APIs are synchronous. Host/generation initialization preloads this;
        // first direct native access also waits instead of overwriting an unloaded file.
        if (!globalLoaded) runBlocking(Dispatchers.IO) { initialize() }
        return synchronized(globalLock, block)
    }

    // ── LOCAL (String API) ───────────────────────────────────────────────

    fun getLocal(name: String): String? =
        activeLocalBackend()?.snapshot()?.get(name)?.let { elementToString(it) }

    fun setLocal(name: String, value: String) {
        if (name.isBlank()) return
        activeLocalBackend()?.update { it[name] = JsonPrimitive(value) }
    }

    fun addLocal(name: String, increment: String): String {
        val b = activeLocalBackend() ?: return "0"
        val snap = b.update { m ->
            val current = m[name]?.let { elementToString(it) } ?: "0"
            m[name] = JsonPrimitive(addStrings(current, increment))
        }
        return snap[name]?.let { elementToString(it) } ?: "0"
    }

    fun incLocal(name: String): String = addLocal(name, "1")
    fun decLocal(name: String): String = addLocal(name, "-1")

    fun deleteLocal(name: String) {
        activeLocalBackend()?.update { it.remove(name) }
    }

    fun hasLocal(name: String): Boolean = activeLocalBackend()?.snapshot()?.containsKey(name) == true

    fun listLocal(): List<String> = activeLocalBackend()?.snapshot()?.keys?.sorted() ?: emptyList()

    // ── GLOBAL (String API) ──────────────────────────────────────────────

    fun getGlobal(name: String): String? = withGlobalState { global[name]?.let { elementToString(it) } }

    fun setGlobal(name: String, value: String) = withGlobalState {
        requireGlobalWritable()
        if (name.isBlank()) return@withGlobalState
        global[name] = JsonPrimitive(value)
        persistGlobal()
    }

    fun addGlobal(name: String, increment: String): String = withGlobalState {
        requireGlobalWritable()
        val current = global[name]?.let { elementToString(it) } ?: "0"
        val result = addStrings(current, increment)
        global[name] = JsonPrimitive(result)
        persistGlobal()
        result
    }

    fun incGlobal(name: String): String = addGlobal(name, "1")
    fun decGlobal(name: String): String = addGlobal(name, "-1")

    fun deleteGlobal(name: String) = withGlobalState {
        requireGlobalWritable()
        global.remove(name)
        persistGlobal()
    }

    fun hasGlobal(name: String): Boolean = withGlobalState { global.containsKey(name) }

    fun listGlobal(): List<String> = withGlobalState { global.keys().toList().sorted() }

    // ── Raw JsonObject API (TavernHelper / EJS) ──────────────────────────

    /** The full global object as extensions see it via `getVariables()`. */
    fun globalObject(): JsonObject = withGlobalState { JsonObject(global.toMap()) }

    /** Overwrite the entire global store and persist. */
    fun replaceGlobal(obj: JsonObject) = withGlobalState {
        requireGlobalWritable()
        global.clear()
        obj.forEach { (k, v) -> global[k] = v }
        persistGlobal()
    }

    /** Snapshot of the current local variables as a JsonObject. */
    fun localObject(): JsonObject {
        val snap = activeLocalBackend()?.snapshot() ?: emptyMap()
        return buildJsonObject {
            snap.forEach { (k, v) -> put(k, v) }
        }
    }

    /** Overwrite the entire local store through the backend. */
    fun replaceLocal(obj: JsonObject) {
        activeLocalBackend()?.update { m ->
            m.clear()
            obj.forEach { (k, v) -> m[k] = v }
        }
    }

    /**
     * Merge view with GLOBAL as the base and LOCAL overriding — matches the
     * ST-Prompt-Template precedence (global < local).  Used for the EJS
     * `variables`/`vars` environment property.
     */
    fun mergedObject(): JsonObject = buildJsonObject {
        globalObject().forEach { (k, v) -> put(k, v) }
        activeLocalBackend()?.snapshot()?.forEach { (k, v) -> put(k, v) }
    }

    /** Wait for writes accepted before this call; failure must reach generation/switch callers. */
    suspend fun flushWrites() {
        initialize()
        val pending = withGlobalState { globalTail }
        pending?.await()
    }

    private fun requireGlobalWritable() {
        globalFailure?.let { throw IllegalStateException("全局变量保存失败，需要恢复后才能继续写入", it) }
    }

    private fun persistGlobal() {
        val snapshot = globalObject()
        val previous = globalTail
        val completion = CompletableDeferred<Unit>()
        globalTail = completion
        val job = scope.launch {
            try {
                previous?.await()
                settingsStore.saveSettings(settingsKey, snapshot)
                completion.complete(Unit)
            } catch (error: Throwable) {
                synchronized(globalLock) { globalFailure = error }
                completion.completeExceptionally(error)
            }
        }
        job.invokeOnCompletion { error ->
            if (error != null) {
                synchronized(globalLock) { globalFailure = error }
                completion.completeExceptionally(error)
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun elementToString(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    /**
     * ST addvar semantics: numeric add when both parse, else string concat.
     * Decimals count as numbers — restricting this to Long turned
     * `/addvar 好感度 0.5` into string concatenation, so the value grew into
     * `"00.50.5…"` instead of adding up.
     */
    private fun addStrings(current: String, increment: String): String {
        val a = current.toLongOrNull()
        val b = increment.toLongOrNull()
        if (a != null && b != null) return (a + b).toString()
        val x = current.toDoubleOrNull()
        val y = increment.toDoubleOrNull()
        if (x != null && y != null) {
            val sum = x + y
            // Keep integral results integral so {{getvar}} doesn't start
            // rendering "5.0" where ST renders "5".
            return if (sum == Math.floor(sum) && !sum.isInfinite()) sum.toLong().toString()
            else sum.toString()
        }
        return current + increment
    }
}
