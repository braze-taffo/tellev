package app.tellev.core.extension

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Provides a synchronous snapshot of the current app state so the extension
 * host can answer `SillyTavern.getContext()` calls from JS without crossing
 * back into the UI thread.  Implementations should return a cheaply-built
 * JsonObject; the host caches the last snapshot.
 */
interface ExtensionContextProvider {
    fun snapshot(): JsonObject

    suspend fun setChatMessage(index: Int, field: String, value: String): Boolean = false

    suspend fun generateText(options: JsonObject): JsonObject? = null
}

interface ExtensionHost {
    val events: SharedFlow<ExtensionEvent>

    fun setContextProvider(provider: ExtensionContextProvider?) {}

    suspend fun load(manifest: ExtensionManifest, scriptSource: String): ExtensionHandle
    suspend fun unload(extensionId: String)
    suspend fun emit(event: ExtensionEvent)

    fun registerSlashCommand(extensionId: String, command: SlashCommand)
    suspend fun executeSlashCommand(input: SlashCommandInput): SlashCommandResult

    fun registerVirtualRoute(extensionId: String, route: VirtualApiRoute)
    suspend fun handleVirtualApi(request: VirtualApiRequest): VirtualApiResponse

    /**
     * Return every registered slash command together with its autocomplete
     * metadata so the UI can render type-ahead suggestions.
     */
    fun listSlashCommandAutocompletions(): List<SlashCommandAutocomplete>

    /**
     * Return the unique capability token that was assigned to [extensionId]
     * when it was loaded.  Returns `null` if the extension is not loaded.
     */
    fun capabilityToken(extensionId: String): String?

    /**
     * Deliver the result of an asynchronous permission request that was
     * initiated by an extension through the bridge.  The UI layer calls
     * this after the user has approved or denied the permission in a
     * permission prompt.  The host forwards the result back into the
     * extension's WebView so the pending Promise can resolve.
     */
    fun deliverPermissionResult(requestId: String, granted: Boolean)
}

// ── Manifest & permissions ─────────────────────────────────────────────

@Serializable
data class ExtensionManifest(
    // ── SillyTavern-compatible manifest fields ──────────────────────────
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("loading_order")
    val loadingOrder: Int = 0,
    val requires: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
    /** Relative path to the entry JS file (e.g. "dist/index.js"). */
    val js: String = "",
    /** Relative path to the CSS file (e.g. "dist/index.css"). */
    val css: String = "",
    val author: String? = null,
    val version: String = "",
    @SerialName("homePage")
    val homePage: String = "",
    @SerialName("auto_update")
    val autoUpdate: Boolean = true,
    @SerialName("minimum_client_version")
    val minimumClientVersion: String = "",
    val i18n: Map<String, String> = emptyMap(),

    // ── tellev-specific fields (not in standard ST manifests) ───────────
    /** Unique identifier; for installed extensions, derived from the directory name. */
    val id: String = "",
    /** Human-readable name; falls back to [displayName] then [id] via [effectiveName]. */
    val name: String = "",
    val description: String = "",
    val permissions: Set<ExtensionPermission> = emptySet(),
    val metadata: JsonObject = buildJsonObject { },
) {
    /** Resolve the display name: explicit name → ST display_name → id. */
    val effectiveName: String
        get() = name.ifBlank { displayName }.ifBlank { id }
}

@Serializable
enum class ExtensionPermission {
    Network,
    Storage,
    Secrets,
    UiPanel,
    ProviderRequest,
    Clipboard,
}

// ── Handle & capabilities ──────────────────────────────────────────────

@Serializable
data class ExtensionHandle(
    val id: String,
    val name: String,
    val loaded: Boolean,
    val version: String = "",
    val capabilities: Set<ExtensionCapability> = emptySet(),
    val capabilityToken: String = "",
)

/**
 * Fine-grained capability flags that can be granted on top of the coarse
 * [ExtensionPermission] set.  Tokens are checked by the
 * [VirtualApiRouter] and the settings bridge before servicing a request.
 */
@Serializable
enum class ExtensionCapability {
    /** Can read character / chat / world data through the virtual API. */
    ReadData,
    /** Can write characters / chats / worlds through the virtual API. */
    WriteData,
    /** Can invoke provider status / model listing endpoints. */
    QueryProviders,
    /** Can read or mutate secrets after explicit user approval. */
    ManageSecrets,
    /** Can read and write its own settings blob. */
    OwnSettings,
    /** Can register slash commands that appear in the command palette. */
    SlashCommands,
    /** Can subscribe to and emit events on the shared bus. */
    EventBus,
}

// ── Events ─────────────────────────────────────────────────────────────

@Serializable
data class ExtensionEvent(
    val name: String,
    val extensionId: String? = null,
    val payload: JsonObject = buildJsonObject { },
)

// ── Slash commands ─────────────────────────────────────────────────────

@Serializable
data class SlashCommand(
    val name: String,
    val description: String,
    val argumentSchema: JsonObject = buildJsonObject { },
    val extensionId: String = "",
)

@Serializable
data class SlashCommandInput(
    val commandName: String,
    val rawInput: String,
    val args: JsonObject = buildJsonObject { },
)

@Serializable
data class SlashCommandResult(
    val handled: Boolean,
    val output: String = "",
    val metadata: JsonObject = buildJsonObject { },
)

/**
 * Autocomplete metadata for a registered slash command, surfaced to the
 * UI so it can render type-ahead suggestions without inspecting the raw
 * argument schema.
 */
@Serializable
data class SlashCommandAutocomplete(
    val commandName: String,
    val description: String,
    val extensionId: String,
    val argHints: List<ArgHint> = emptyList(),
) {
    @Serializable
    data class ArgHint(
        val name: String,
        val description: String = "",
        val required: Boolean = false,
        val suggestions: List<String> = emptyList(),
    )
}

// ── Virtual API ────────────────────────────────────────────────────────

@Serializable
data class VirtualApiRoute(
    val method: String,
    val path: String,
)

@Serializable
data class VirtualApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

@Serializable
data class VirtualApiResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)
