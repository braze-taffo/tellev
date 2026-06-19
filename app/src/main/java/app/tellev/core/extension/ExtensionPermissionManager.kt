package app.tellev.core.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Tracks and enforces per-extension permissions.
 *
 * State is held in memory and mirrored to a JSON file at
 * `{persistenceDir}/permissions.json` so it survives process restarts.
 *
 * Each extension's granted permissions are stored as:
 * ```json
 * {
 *   "extensionIdA": { "Network": true, "Storage": true, ... },
 *   "extensionIdB": { ... }
 * }
 * ```
 */
class ExtensionPermissionManager(
    private val persistenceDir: Path? = null,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()

    /** extensionId -> (permission -> granted) */
    private val granted: MutableMap<String, MutableSet<ExtensionPermission>> = mutableMapOf()

    // ── public API ─────────────────────────────────────────────────────

    /**
     * Load persisted permissions from disk.  Call once at startup from a
     * background scope; never block the main thread.  Failures are
     * surfaced via the returned result instead of being swallowed.
     */
    suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (persistenceDir != null) loadFromDisk()
        }
    }

    /**
     * Grant [permission] to the extension identified by [extensionId].
     */
    suspend fun grantPermission(extensionId: String, permission: ExtensionPermission) {
        mutex.withLock {
            granted.getOrPut(extensionId) { mutableSetOf() }.add(permission)
        }
        persistAsync()
    }

    /**
     * Revoke [permission] from the extension identified by [extensionId].
     */
    suspend fun revokePermission(extensionId: String, permission: ExtensionPermission) {
        mutex.withLock {
            granted[extensionId]?.remove(permission)
        }
        persistAsync()
    }

    /**
     * Check whether [extensionId] has been granted [permission].
     */
    suspend fun hasPermission(extensionId: String, permission: ExtensionPermission): Boolean =
        mutex.withLock {
            granted[extensionId]?.contains(permission) == true
        }

    /**
     * Return the full set of permissions currently granted to [extensionId].
     */
    suspend fun getGrantedPermissions(extensionId: String): Set<ExtensionPermission> =
        mutex.withLock {
            granted[extensionId]?.toSet() ?: emptySet()
        }

    /**
     * Given the [requested] permissions from an extension manifest, return
     * the subset that has **not** yet been granted — i.e. the permissions
     * that still need user approval.
     */
    suspend fun getPendingPermissions(
        extensionId: String,
        requested: List<ExtensionPermission>,
    ): List<ExtensionPermission> = mutex.withLock {
        val current = granted[extensionId] ?: emptySet()
        requested.filter { it !in current }
    }

    /**
     * Bulk-grant all permissions an extension declares in its manifest.
     * Convenience for initial install or "trust all" flows.
     */
    suspend fun grantAll(extensionId: String, permissions: Collection<ExtensionPermission>) {
        mutex.withLock {
            val set = granted.getOrPut(extensionId) { mutableSetOf() }
            set.addAll(permissions)
        }
        persistAsync()
    }

    /**
     * Remove every permission entry for [extensionId] (e.g. on uninstall).
     */
    suspend fun clearExtension(extensionId: String) {
        mutex.withLock {
            granted.remove(extensionId)
        }
        persistAsync()
    }

    // ── persistence ────────────────────────────────────────────────────

    private fun loadFromDisk() {
        val dir = persistenceDir ?: return
        val file = dir.resolve("permissions.json")
        if (!Files.exists(file)) return

        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        val root = json.parseToJsonElement(text).jsonObject

        for ((extId, element) in root) {
            val perms = mutableSetOf<ExtensionPermission>()
            val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
            for ((permName, value) in obj) {
                val isGranted = runCatching { value.jsonPrimitive.booleanOrNull }.getOrNull()
                if (isGranted == true) {
                    runCatching { ExtensionPermission.valueOf(permName) }
                        .onSuccess { perms.add(it) }
                }
            }
            if (perms.isNotEmpty()) {
                granted[extId] = perms
            }
        }
    }

    private suspend fun persistAsync(): Unit {
        val dir = persistenceDir ?: return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                Files.createDirectories(dir)
                val file = dir.resolve("permissions.json")
                val root = buildJsonObject {
                    for ((extId, perms) in granted) {
                        put(extId, buildJsonObject {
                            for (perm in ExtensionPermission.entries) {
                                put(perm.name, JsonPrimitive(perm in perms))
                            }
                        })
                    }
                }
                val text = json.encodeToString(JsonObject.serializer(), root)
                Files.write(
                    file,
                    text.toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
        }
    }
}
