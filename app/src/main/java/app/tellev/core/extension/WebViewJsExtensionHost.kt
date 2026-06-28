package app.tellev.core.extension

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.tellev.core.prompt.MacroContext
import app.tellev.core.prompt.MacroEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Full-featured [ExtensionHost] backed by an Android [WebView] per
 * extension.  Each extension runs inside its own sandboxed WebView with
 * JavaScript enabled and a `tellevNative` bridge object that exposes the
 * platform capabilities.
 *
 * In addition to the tellev-native `window.Tellev` API, the WebView is
 * seeded with a SillyTavern / 酒馆助手 compatibility shim that exposes
 * the globals real SillyTavern frontend extensions and JS-Slash-Runner
 * scripts depend on: `SillyTavern`, `getContext`, `eventSource`,
 * `event_types`, `TavernHelper`, `executeSlashCommandsWithOptions`,
 * `executeSlashCommands`, and a `fetch` override that routes same-origin
 * `/api/` requests through the native virtual API.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewJsExtensionHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val apiRouter: VirtualApiRouter,
    private val settingsStore: ExtensionSettingsStore,
    private val permissionManager: ExtensionPermissionManager,
    private val macroEngine: MacroEngine? = null,
    contextProvider: ExtensionContextProvider? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val commandTimeoutMs: Long = 10_000L,
    private val apiCallTimeoutMs: Long = 30_000L,
    private val scriptReadyTimeoutMs: Long = 5_000L,
) : ExtensionHost {

    // ── state ──────────────────────────────────────────────────────────

    private val mutableEvents = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ExtensionEvent> = mutableEvents

    private val webViews = ConcurrentHashMap<String, WebView>()
    private val capabilityTokens = ConcurrentHashMap<String, String>()
    private val slashCommands = ConcurrentHashMap<String, RegisteredCommand>()
    private val virtualRoutes = ConcurrentHashMap<String, RegisteredRoute>()

    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<SlashCommandResult>>()
    private val pendingCommandOwners = ConcurrentHashMap<String, String>()
    private val pendingVirtualApi = ConcurrentHashMap<String, CompletableDeferred<VirtualApiResponse>>()
    private val pendingVirtualApiOwners = ConcurrentHashMap<String, String>()
    private val pendingPermissions = ConcurrentHashMap<String, String>()
    private val pendingPermissionOwners = ConcurrentHashMap<String, String>()
    private val pendingLoads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val settingsCache = ConcurrentHashMap<String, String>()
    private val settingsWriteMutex = Mutex()

    private val tavernHelperVariables = ConcurrentHashMap<String, JsonObject>()

    /**
     * Extension-injected prompts authored by loaded extensions via the ST
     * `injectPrompts` API. Outer key: extension id; inner key: the prompt id
     * passed by the extension. Collected by [collectInjectedPrompts] and
     * fed into the prompt engine through `metadata["injectedPrompts"]`.
     */
    private val injectedPrompts =
        ConcurrentHashMap<String, ConcurrentHashMap<String, InjectedPrompt>>()

    /** Variables for built-in slash commands (ST chat-scope variables). */
    private val slashVariables = ConcurrentHashMap<String, String>()

    /** Built-in STScript command engine; handles /echo, /setvar, /getvar, etc. */
    private val slashCommandEngine = SlashCommandEngine(
        variables = slashVariables,
        eventEmitter = { name, args ->
            scope.launch {
                mutableEvents.emit(
                    ExtensionEvent(
                        name = name,
                        payload = buildJsonObject {
                            putJsonArray("args") {
                                args.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                        },
                    ),
                )
            }
        },
    )

    /** Mutable so the UI layer can plug in a live context snapshot. */
    private var _contextProvider: ExtensionContextProvider? = contextProvider

    /** Update the context provider used to answer getContext() from JS. */
    override fun setContextProvider(provider: ExtensionContextProvider?) {
        _contextProvider = provider
    }

    // ── ExtensionHost implementation ───────────────────────────────────

    override suspend fun load(
        manifest: ExtensionManifest,
        scriptSource: String,
    ): ExtensionHandle {
        val readySignal = CompletableDeferred<Unit>()
        val handle = try {
            withContext(Dispatchers.Main) {
                webViews.remove(manifest.id)?.destroy()
                pendingLoads.remove(manifest.id)?.cancel()

                val token = UUID.randomUUID().toString()
                capabilityTokens[manifest.id] = token
                pendingLoads[manifest.id] = readySignal

                val settingsJson = settingsStore.getSettings(manifest.id)
                settingsCache[manifest.id] = json.encodeToString(JsonObject.serializer(), settingsJson)

                if (tavernHelperVariables.isEmpty()) {
                    runCatching {
                        tavernHelperVariables[TAVERN_HELPER_VARS_KEY] = settingsStore.getSettings(TAVERN_HELPER_VARS_KEY)
                    }
                }

                val webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false

                    addJavascriptInterface(Bridge(manifest.id, token), "tellevNative")

                    loadDataWithBaseURL(
                        "https://extensions.tellev.local/${manifest.id}/",
                        buildExtensionHtml(manifest.id, token, scriptSource),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }

                webViews[manifest.id] = webView

                ExtensionHandle(
                    id = manifest.id,
                    name = manifest.effectiveName,
                    loaded = true,
                    version = manifest.version,
                    capabilities = defaultCapabilities(manifest),
                    capabilityToken = token,
                )
            }
        } catch (e: Throwable) {
            pendingLoads.remove(manifest.id, readySignal)
            throw e
        }

        withTimeoutOrNull(scriptReadyTimeoutMs) { readySignal.await() }
        pendingLoads.remove(manifest.id, readySignal)
        emit(ExtensionEvent(name = "extension_loaded", extensionId = manifest.id))
        return handle
    }

    override suspend fun unload(extensionId: String) {
        withContext(Dispatchers.Main) {
            webViews.remove(extensionId)?.destroy()
        }
        capabilityTokens.remove(extensionId)
        settingsCache.remove(extensionId)
        slashCommands.entries.removeIf { it.value.extensionId == extensionId }
        virtualRoutes.entries.removeIf { it.value.extensionId == extensionId }
        cancelPending(pendingCommandOwners, pendingCommands, extensionId)
        cancelPending(pendingVirtualApiOwners, pendingVirtualApi, extensionId)
        pendingPermissions.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingPermissionOwners.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingLoads.remove(extensionId)?.cancel()
        injectedPrompts.remove(extensionId)
        permissionManager.clearExtension(extensionId)
        emit(ExtensionEvent(name = "extension_unloaded", extensionId = extensionId))
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

    override suspend fun emit(event: ExtensionEvent) {
        mutableEvents.emit(event)
        withContext(Dispatchers.Main) {
            val payloadStr = json.encodeToString(JsonObject.serializer(), event.payload)
            val escapedName = jsEscape(event.name)
            val escapedPayload = jsEscape(payloadStr)
            val js = "if(window.Tellev&&window.Tellev.onEvent){" +
                "window.Tellev.onEvent('" + escapedName + "','" + escapedPayload + "');" +
                "}else if(window.eventSource&&window.eventSource._fireNative){" +
                "window.eventSource._fireNative('" + escapedName + "','" + escapedPayload + "');}"
            for ((_, webView) in webViews) {
                runCatching { webView.evaluateJavascript(js, null) }
            }
        }
    }

    override fun registerSlashCommand(extensionId: String, command: SlashCommand) {
        slashCommands[command.name] = RegisteredCommand(
            extensionId = extensionId,
            command = command.copy(extensionId = extensionId),
        )
    }

    override suspend fun executeSlashCommand(input: SlashCommandInput): SlashCommandResult {
        val registered = slashCommands[input.commandName]
            ?: return SlashCommandResult(handled = false, output = "Unknown command: " + input.commandName)

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SlashCommandResult>()
        pendingCommands[requestId] = deferred
        pendingCommandOwners[requestId] = registered.extensionId

        withContext(Dispatchers.Main) {
            val webView = webViews[registered.extensionId]
            if (webView != null) {
                val argsStr = json.encodeToString(JsonObject.serializer(), input.args)
                val js = "if(window.Tellev&&window.Tellev.onCommandExecute){" +
                    "window.Tellev.onCommandExecute(" +
                    "'" + jsEscape(requestId) + "'," +
                    "'" + jsEscape(input.commandName) + "'," +
                    "'" + jsEscape(input.rawInput) + "'," +
                    "'" + jsEscape(argsStr) + "'" +
                    ");}"
                webView.evaluateJavascript(js, null)
            } else {
                deferred.complete(SlashCommandResult(handled = false, output = "Extension WebView not available"))
            }
        }

        emit(
            ExtensionEvent(
                name = "slash_command",
                extensionId = registered.extensionId,
                payload = buildJsonObject {
                    put("command", input.commandName)
                    put("rawInput", input.rawInput)
                },
            ),
        )

        // withTimeoutOrNull only swallows TimeoutCancellationException; a parent
        // cancellation surfaces from await() as a JobCancellationException. Use a
        // finally block so the pending maps are always cleaned up (no entry leak),
        // and cancel the deferred so a late WebView-side callback finds it dead.
        val result = try {
            withTimeoutOrNull(commandTimeoutMs) { deferred.await() }
        } finally {
            pendingCommands.remove(requestId)
            pendingCommandOwners.remove(requestId)
            deferred.cancel()
        }

        // A timeout is a failure: the command was NOT handled.
        return result ?: SlashCommandResult(
            handled = false,
            output = "",
            metadata = buildJsonObject { put("timeout", true) },
        )
    }

    override fun registerVirtualRoute(extensionId: String, route: VirtualApiRoute) {
        val key = route.method.uppercase() + " " + route.path
        virtualRoutes[key] = RegisteredRoute(extensionId, route)
    }

    override suspend fun handleVirtualApi(request: VirtualApiRequest): VirtualApiResponse {
        val routeKey = request.method.uppercase() + " " + request.path
        val registered = virtualRoutes[routeKey]
        if (registered != null) {
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<VirtualApiResponse>()
            pendingVirtualApi[requestId] = deferred
            pendingVirtualApiOwners[requestId] = registered.extensionId

            withContext(Dispatchers.Main) {
                val webView = webViews[registered.extensionId]
                if (webView != null) {
                    val body = request.body ?: ""
                    val js = "if(window.Tellev&&window.Tellev.onVirtualApiRequest){" +
                        "window.Tellev.onVirtualApiRequest(" +
                        "'" + jsEscape(requestId) + "'," +
                        "'" + jsEscape(request.method) + "'," +
                        "'" + jsEscape(request.path) + "'," +
                        "'" + jsEscape(body) + "'" +
                        ");}"
                    webView.evaluateJavascript(js, null)
                } else {
                    deferred.complete(VirtualApiResponse(status = 503, body = "{\"error\":\"Extension not loaded\"}"))
                }
            }

            val result = try {
                withTimeoutOrNull(apiCallTimeoutMs) { deferred.await() }
            } finally {
                pendingVirtualApi.remove(requestId)
                pendingVirtualApiOwners.remove(requestId)
                deferred.cancel()
            }
            return result ?: VirtualApiResponse(
                status = 504,
                body = "{\"error\":\"Extension route timed out\"}",
            )
        }

        return apiRouter.route(request)
    }

    override fun listSlashCommandAutocompletions(): List<SlashCommandAutocomplete> =
        slashCommands.values.map { registered ->
            SlashCommandAutocomplete(
                commandName = registered.command.name,
                description = registered.command.description,
                extensionId = registered.extensionId,
                argHints = parseArgHints(registered.command.argumentSchema),
            )
        }

    override fun capabilityToken(extensionId: String): String? = capabilityTokens[extensionId]

    override fun deliverPermissionResult(requestId: String, granted: Boolean) {
        pendingPermissions.remove(requestId)
        val owner = pendingPermissionOwners.remove(requestId)
        scope.launch(Dispatchers.Main) {
            val webView = owner?.let { webViews[it] }
            if (webView != null) {
                val js = "if(window.Tellev&&window.Tellev.onPermissionResult){" +
                    "window.Tellev.onPermissionResult('" + jsEscape(requestId) + "'," + granted + ");}"
                runCatching { webView.evaluateJavascript(js, null) }
            }
        }
    }

    override fun snapshotExtensionSettings(): JsonObject = buildJsonObject {
        for ((id, raw) in settingsCache) {
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: buildJsonObject { }
            put(id, parsed)
        }
    }

    override fun collectInjectedPrompts(): JsonObject = buildJsonObject {
        for ((extensionId, byPromptId) in injectedPrompts) {
            for ((promptId, prompt) in byPromptId) {
                put(
                    "$extensionId/$promptId",
                    buildJsonObject {
                        put("extensionId", extensionId)
                        put("promptId", promptId)
                        put("value", prompt.value)
                        put("position", prompt.position)
                        put("depth", prompt.depth)
                        put("role", prompt.role)
                    },
                )
            }
        }
    }

    /** A single prompt injected by an extension. */
    private data class InjectedPrompt(
        val value: String,
        val position: Int,
        val depth: Int,
        val role: String,
    )

    // ── Bridge (called from JS via @JavascriptInterface) ───────────────

    inner class Bridge(
        private val extensionId: String,
        private val token: String,
    ) {
        @JavascriptInterface
        fun emit(name: String, payloadJson: String) {
            val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            scope.launch {
                mutableEvents.emit(ExtensionEvent(name = name, extensionId = extensionId, payload = payload))
            }
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            scope.launch {
                mutableEvents.emit(
                    ExtensionEvent(
                        name = "extension_log",
                        extensionId = extensionId,
                        payload = buildJsonObject {
                            put("level", level)
                            put("message", message)
                        },
                    ),
                )
            }
        }

        @JavascriptInterface
        fun registerCommand(name: String, description: String, argsJson: String) {
            val schema = runCatching { json.parseToJsonElement(argsJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            val command = SlashCommand(name = name, description = description, argumentSchema = schema, extensionId = extensionId)
            slashCommands[name] = RegisteredCommand(extensionId, command)
            scope.launch {
                mutableEvents.emit(
                    ExtensionEvent(
                        name = "command_registered",
                        extensionId = extensionId,
                        payload = buildJsonObject {
                            put("command", name)
                            put("description", description)
                        },
                    ),
                )
            }
        }

        @JavascriptInterface
        fun commandResult(requestId: String, resultJson: String) {
            pendingCommandOwners.remove(requestId)
            val deferred = pendingCommands.remove(requestId) ?: return
            val result = runCatching {
                val obj = json.parseToJsonElement(resultJson).jsonObject
                SlashCommandResult(
                    handled = obj["handled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    output = obj["output"]?.jsonPrimitive?.content ?: "",
                    metadata = obj["metadata"]?.let { runCatching { it.jsonObject }.getOrDefault(buildJsonObject { }) }
                        ?: buildJsonObject { },
                )
            }.getOrElse { SlashCommandResult(handled = true, output = resultJson) }
            deferred.complete(result)
        }

        @JavascriptInterface
        fun virtualApiResult(requestId: String, status: String, bodyJson: String) {
            pendingVirtualApiOwners.remove(requestId)
            val deferred = pendingVirtualApi.remove(requestId) ?: return
            deferred.complete(VirtualApiResponse(status = status.toIntOrNull() ?: 200, body = bodyJson))
        }

        @JavascriptInterface
        fun apiCall(requestId: String, method: String, path: String, bodyJson: String) {
            scope.launch {
                val request = VirtualApiRequest(
                    method = method,
                    path = path,
                    body = bodyJson.ifBlank { null },
                    headers = mapOf("X-Extension-Id" to extensionId, "X-Capability-Token" to token),
                )
                if (!checkApiPermissions(path, requestId)) return@launch
                val response = runCatching {
                    routeApiRequestForExtension(request)
                }.getOrElse { error ->
                    VirtualApiResponse(
                        status = 500,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("error", error.message ?: "Extension API call failed")
                                put("status", 500)
                            },
                        ),
                    )
                }
                deliverApiResponseToJs(requestId, response)
            }
        }

        private suspend fun routeApiRequestForExtension(request: VirtualApiRequest): VirtualApiResponse {
            if (
                request.method.equals("POST", ignoreCase = true) &&
                request.path.substringBefore("?") == "/api/backends/chat-completions/generate"
            ) {
                val options = request.body
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    ?: buildJsonObject { }
                val result = _contextProvider?.generateText(options)
                if (result != null) {
                    return VirtualApiResponse(
                        status = 200,
                        headers = mapOf("Content-Type" to "application/json"),
                        body = json.encodeToString(JsonObject.serializer(), result),
                    )
                }
            }

            return apiRouter.route(request)
        }

        private suspend fun checkApiPermissions(path: String, requestId: String): Boolean {
            val needsStorage = path.startsWith("/api/characters") ||
                path.startsWith("/api/chats") ||
                path.startsWith("/api/worlds") ||
                path.startsWith("/api/settings") ||
                path.startsWith("/api/groups") ||
                path.startsWith("/api/personas")
            val needsProvider = path.startsWith("/api/providers")
            val needsSecrets = path.startsWith("/api/secrets")
            if (needsStorage && !permissionManager.hasPermission(extensionId, ExtensionPermission.Storage)) {
                deliverApiResponseToJs(requestId, VirtualApiResponse(403, body = "{\"error\":\"Storage permission not granted\"}"))
                return false
            }
            if (needsProvider && !permissionManager.hasPermission(extensionId, ExtensionPermission.ProviderRequest)) {
                deliverApiResponseToJs(requestId, VirtualApiResponse(403, body = "{\"error\":\"ProviderRequest permission not granted\"}"))
                return false
            }
            if (needsSecrets && !permissionManager.hasPermission(extensionId, ExtensionPermission.Secrets)) {
                deliverApiResponseToJs(requestId, VirtualApiResponse(403, body = "{\"error\":\"Secrets permission not granted\"}"))
                return false
            }
            return true
        }

        @JavascriptInterface
        fun getSettings(): String = settingsCache[extensionId] ?: "{}"

        @JavascriptInterface
        fun saveSettings(settingsJson: String) {
            val obj = runCatching { json.parseToJsonElement(settingsJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            scope.launch {
                settingsWriteMutex.withLock {
                    settingsCache[extensionId] = settingsJson
                    settingsStore.saveSettings(extensionId, obj)
                }
            }
        }

        /**
         * Check whether a permission is currently granted.  Does NOT trigger
         * a UI prompt.  Use [requestPermissionAsync] for the interactive flow.
         */
        @JavascriptInterface
        fun hasPermission(permission: String): Boolean {
            val perm = runCatching { ExtensionPermission.valueOf(permission) }.getOrNull() ?: return false
            return runBlocking { permissionManager.hasPermission(extensionId, perm) }
        }

        /**
         * Synchronous permission check — alias for [hasPermission] kept for
         * backward compatibility with older extension scripts that call
         * `Tellev.requestPermission(name)` expecting a boolean.
         */
        @JavascriptInterface
        fun requestPermission(permission: String): Boolean = hasPermission(permission)

        @JavascriptInterface
        fun requestPermissionAsync(requestId: String, permission: String) {
            val perm = runCatching { ExtensionPermission.valueOf(permission) }.getOrNull()
            if (perm == null) {
                scope.launch(Dispatchers.Main) { deliverPermissionResultJs(requestId, false) }
                return
            }
            pendingPermissions[requestId] = extensionId
            pendingPermissionOwners[requestId] = extensionId
            scope.launch {
                val granted = permissionManager.hasPermission(extensionId, perm)
                if (granted) {
                    pendingPermissions.remove(requestId)
                    pendingPermissionOwners.remove(requestId)
                    withContext(Dispatchers.Main) { deliverPermissionResultJs(requestId, true) }
                } else {
                    mutableEvents.emit(
                        ExtensionEvent(
                            name = "permission_requested",
                            extensionId = extensionId,
                            payload = buildJsonObject {
                                put("permission", perm.name)
                                put("requestId", requestId)
                            },
                        ),
                    )
                }
            }
        }

        @JavascriptInterface
        fun getCapabilityToken(): String = token

        @JavascriptInterface
        fun extensionReady() {
            pendingLoads.remove(extensionId)?.complete(Unit)
        }

        // ── SillyTavern / 酒馆助手 shim bridge methods ──────────────────

        @JavascriptInterface
        fun stGetContext(): String {
            val snapshot = _contextProvider?.snapshot() ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), snapshot)
        }

        @JavascriptInterface
        fun stReplaceVariables(input: String): String {
            val engine = macroEngine ?: return input
            return runCatching {
                val snap = _contextProvider?.snapshot()
                val ctx = if (snap != null) {
                    MacroContext(
                        characterName = snap["name2"]?.jsonPrimitive?.content ?: "",
                        userName = snap["name1"]?.jsonPrimitive?.content ?: "",
                    )
                } else {
                    MacroContext()
                }
                engine.expand(input, ctx)
            }.getOrDefault(input)
        }

        @JavascriptInterface
        fun stGetVariables(): String {
            val vars = tavernHelperVariables[TAVERN_HELPER_VARS_KEY] ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stSetVariables(varsJson: String) {
            val obj = runCatching { json.parseToJsonElement(varsJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            tavernHelperVariables[TAVERN_HELPER_VARS_KEY] = obj
            scope.launch {
                settingsWriteMutex.withLock { settingsStore.saveSettings(TAVERN_HELPER_VARS_KEY, obj) }
            }
        }

        @JavascriptInterface
        fun stInjectPrompt(
            id: String,
            content: String,
            position: Int,
            depth: Int,
            role: String,
        ) {
            if (id.isBlank()) return
            val roleNorm = role.trim().ifBlank { "system" }.lowercase()
            val map = injectedPrompts.computeIfAbsent(extensionId) { ConcurrentHashMap() }
            map[id] = InjectedPrompt(
                value = content,
                position = position,
                depth = if (depth >= 0) depth else 0,
                role = roleNorm,
            )
        }

        @JavascriptInterface
        fun stUninjectPrompt(id: String) {
            injectedPrompts[extensionId]?.remove(id)
        }

        @JavascriptInterface
        fun stGetInjectedPrompts(): String {
            val map = injectedPrompts[extensionId] ?: emptyMap()
            val obj = buildJsonObject {
                for ((id, prompt) in map) {
                    put(id, buildJsonObject {
                        put("value", prompt.value)
                        put("position", prompt.position)
                        put("depth", prompt.depth)
                        put("role", prompt.role)
                    })
                }
            }
            return json.encodeToString(JsonObject.serializer(), obj)
        }

        @JavascriptInterface
        fun stSetChatMessage(index: String, field: String, value: String) {
            scope.launch {
                val messageIndex = index.toIntOrNull()
                if (messageIndex != null && _contextProvider?.setChatMessage(messageIndex, field, value) == true) {
                    return@launch
                }
                val body = buildJsonObject {
                    put("index", index)
                    put("field", field)
                    put("value", value)
                }
                apiRouter.route(
                    VirtualApiRequest(
                        method = "POST",
                        path = "/api/chats/current/message-field",
                        body = json.encodeToString(JsonObject.serializer(), body),
                        headers = mapOf("X-Extension-Id" to extensionId, "X-Capability-Token" to token),
                    ),
                )
            }
        }

        @JavascriptInterface
        fun executeSlashCommands(requestId: String, scriptText: String) {
            scope.launch {
                // The JS side sends the raw STScript text (e.g. "/echo hello | /getvar name").
                // Run it through the built-in SlashCommandEngine first; if the engine
                // reports an unhandled command, fall back to extension-registered commands.
                val engineResult = runCatching {
                    slashCommandEngine.execute(scriptText)
                }.getOrElse {
                    SlashCommandEngine.Result.error(it.message ?: "execution error")
                }

                val result = if (engineResult.handled) {
                    SlashCommandResult(
                        handled = true,
                        output = engineResult.output,
                        metadata = buildJsonObject {
                            put("isError", engineResult.isError)
                            if (engineResult.isError) put("errorMessage", engineResult.errorMessage)
                        },
                    )
                } else {
                    // Fall back: try to dispatch the first command token to an
                    // extension-registered command.
                    val firstToken = scriptText.trim().split(Regex("\\s+")).firstOrNull()?.removePrefix("/") ?: ""
                    if (firstToken.isNotBlank() && slashCommands.containsKey(firstToken)) {
                        executeSlashCommand(
                            SlashCommandInput(commandName = firstToken, rawInput = scriptText),
                        )
                    } else {
                        SlashCommandResult(handled = false, output = engineResult.output.ifBlank { "Unknown command" })
                    }
                }

                withContext(Dispatchers.Main) {
                    val payload = buildJsonObject {
                        put("pipe", result.output)
                        put("isError", engineResult.isError)
                        put("isAborted", engineResult.isAborted)
                        put("handled", result.handled)
                        if (engineResult.isError) put("errorMessage", engineResult.errorMessage)
                        putJsonArray("results") {
                            add(buildJsonObject {
                                put("handled", result.handled)
                                put("output", result.output)
                                if (result.metadata.isNotEmpty()) put("metadata", result.metadata)
                            })
                        }
                    }
                    val escaped = jsEscape(json.encodeToString(JsonObject.serializer(), payload))
                    val webView = webViews[extensionId]
                    webView?.evaluateJavascript(
                        "if(window.Tellev&&window.Tellev.onSlashCommandsResult){" +
                            "window.Tellev.onSlashCommandsResult('" + jsEscape(requestId) + "','" + escaped + "');}",
                        null,
                    )
                }
            }
        }

        private suspend fun deliverApiResponseToJs(requestId: String, response: VirtualApiResponse) {
            withContext(Dispatchers.Main) {
                val webView = webViews[extensionId] ?: return@withContext
                val escapedReqId = jsEscape(requestId)
                val escapedBody = jsEscape(response.body)
                val js = "if(window.Tellev&&window.Tellev.onApiResponse){" +
                    "window.Tellev.onApiResponse('" + escapedReqId + "'," + response.status + ",'" + escapedBody + "');}"
                webView.evaluateJavascript(js, null)
            }
        }

        private suspend fun deliverPermissionResultJs(requestId: String, granted: Boolean) {
            withContext(Dispatchers.Main) {
                val webView = webViews[extensionId] ?: return@withContext
                val js = "if(window.Tellev&&window.Tellev.onPermissionResult){" +
                    "window.Tellev.onPermissionResult('" + jsEscape(requestId) + "'," + granted + ");}"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    // ── HTML template ──────────────────────────────────────────────────

    private fun buildExtensionHtml(extensionId: String, token: String, scriptSource: String): String {
        val safeId = jsEscape(extensionId)
        val safeToken = jsEscape(token)
        val safeScript = sanitizeScriptSource(scriptSource)
        return HTML_TEMPLATE
            .replace("__EXTENSION_ID__", safeId)
            .replace("__TOKEN__", safeToken)
            .replace("__SCRIPT_SOURCE__", safeScript)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun defaultCapabilities(manifest: ExtensionManifest): Set<ExtensionCapability> {
        val caps = mutableSetOf(ExtensionCapability.EventBus, ExtensionCapability.OwnSettings)
        if (ExtensionPermission.Storage in manifest.permissions) {
            caps.add(ExtensionCapability.ReadData)
            caps.add(ExtensionCapability.WriteData)
        }
        if (ExtensionPermission.ProviderRequest in manifest.permissions) {
            caps.add(ExtensionCapability.QueryProviders)
        }
        if (ExtensionPermission.Secrets in manifest.permissions) {
            caps.add(ExtensionCapability.ManageSecrets)
        }
        caps.add(ExtensionCapability.SlashCommands)
        return caps
    }

    private fun parseArgHints(schema: JsonObject): List<SlashCommandAutocomplete.ArgHint> {
        val argsElement = schema["args"] ?: return emptyList()
        val argsArray = runCatching { argsElement.jsonArray }.getOrNull() ?: return emptyList()
        return argsArray.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.content ?: ""
            val required = obj["required"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val suggestions = obj["enum"]?.let { enumElement ->
                runCatching { enumElement.jsonArray }.getOrNull()
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            } ?: emptyList()
            SlashCommandAutocomplete.ArgHint(name = name, description = desc, required = required, suggestions = suggestions)
        }
    }

    private fun jsEscape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")

    /**
     * Sanitize raw script source before embedding it in a <script> element.
     * The HTML parser closes the script block on the first </script
     * (case-insensitive) or HTML-comment-open sequence. Escape those so
     * attacker-controlled character-card scripts cannot break out.
     */
    private fun sanitizeScriptSource(src: String): String = src
        .replace("</", "<\\/")
        .replace("<!--", "<\\!--")

    private data class RegisteredCommand(val extensionId: String, val command: SlashCommand)
    private data class RegisteredRoute(val extensionId: String, val route: VirtualApiRoute)

    companion object {
        const val TAVERN_HELPER_VARS_KEY = "_tavern_helper_global_variables"

        // The HTML is stored as a plain string (not a raw """...""") so
        // that the JS /* ... */ comments inside cannot be mistaken for
        // Kotlin block comments by the compiler.
        private const val HTML_TEMPLATE: String = "<!doctype html><html><head>" +
            "<meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self';\">" +
            "</head><body><script>\n" +
            "(function(){" +
            "'use strict';" +
            "var _extensionId='__EXTENSION_ID__';" +
            "var _token='__TOKEN__';" +
            "var _eventHandlers={};var _commandHandlers={};var _apiCallbacks={};var _slashCallbacks={};var _permissionCallbacks={};var _apiReqCounter=0;" +
            "window.Tellev={" +
            "extensionId:_extensionId,capabilityToken:_token," +
            "on:function(n,h){if(!_eventHandlers[n])_eventHandlers[n]=[];_eventHandlers[n].push(h);}," +
            "off:function(n,h){var a=_eventHandlers[n];if(!a)return;var i=a.indexOf(h);if(i>=0)a.splice(i,1);}," +
            "emit:function(n,p){tellevNative.emit(String(n),JSON.stringify(p||{}));}," +
            "onEvent:function(n,pj){var p;try{p=JSON.parse(pj);}catch(e){p={};}var hs=_eventHandlers[n];if(hs){for(var i=0;i<hs.length;i++){try{hs[i](n,p);}catch(e){tellevNative.log('error','Event handler error: '+e);}}}var wc=_eventHandlers['*'];if(wc){for(var j=0;j<wc.length;j++){try{wc[j](n,p);}catch(e){}}}if(window.eventSource)window.eventSource._fireNative(n,pj);}," +
            "registerCommand:function(n,d,a,h){_commandHandlers[n]=h;tellevNative.registerCommand(String(n),String(d),JSON.stringify(a||{}));}," +
            "finishCommand:function(rid,r){tellevNative.commandResult(String(rid),JSON.stringify(r||{handled:true}));}," +
            "onCommandExecute:function(rid,cn,ri,aj){var a;try{a=JSON.parse(aj);}catch(e){a={};}var h=_commandHandlers[cn];if(h){try{h(rid,ri,a);}catch(e){tellevNative.log('error','Command handler error: '+e);tellevNative.commandResult(rid,JSON.stringify({handled:false,output:'',metadata:{error:String(e)}}));}}else{tellevNative.commandResult(rid,JSON.stringify({handled:false}));}}," +
            "apiCall:function(m,p,b){_apiReqCounter+=1;var rid='api_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve,reject){_apiCallbacks[rid]={resolve:resolve,reject:reject};var bs=(b!==undefined&&b!==null)?JSON.stringify(b):'';tellevNative.apiCall(rid,String(m),String(p),bs);setTimeout(function(){if(_apiCallbacks[rid]){_apiCallbacks[rid].reject(new Error('API call timeout'));delete _apiCallbacks[rid];}},35000);});}," +
            "onApiResponse:function(rid,s,bj){var cb=_apiCallbacks[rid];if(!cb)return;delete _apiCallbacks[rid];var b;try{b=JSON.parse(bj);}catch(e){b={raw:bj};}cb.resolve({status:s,body:b});}," +
            "onVirtualApiRequest:function(rid,m,p,b){var parsed=null;try{parsed=b?JSON.parse(b):null;}catch(e){parsed=b;}var req={method:m,path:p,body:parsed};if(typeof window.onTellevVirtualApiRequest==='function'){Promise.resolve().then(function(){return window.onTellevVirtualApiRequest(req);}).then(function(r){r=r||{status:200,body:{}};tellevNative.virtualApiResult(rid,String(r.status||200),JSON.stringify(r.body||{}));}).catch(function(e){tellevNative.virtualApiResult(rid,'500',JSON.stringify({error:String(e)}));});}else{tellevNative.virtualApiResult(rid,'404',JSON.stringify({error:'No handler registered'}));}}," +
            "getSettings:function(){var r=tellevNative.getSettings();try{return JSON.parse(r);}catch(e){return{};}}," +
            "saveSettings:function(s){tellevNative.saveSettings(JSON.stringify(s||{}));}," +
            "requestPermission:function(p){return tellevNative.requestPermission(String(p));}," +
            "hasPermission:function(p){return tellevNative.hasPermission(String(p));}," +
            "requestPermissionAsync:function(p){_apiReqCounter+=1;var rid='perm_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve){_permissionCallbacks[rid]=resolve;tellevNative.requestPermissionAsync(rid,String(p));});}," +
            "onPermissionResult:function(rid,g){var cb=_permissionCallbacks[rid];if(!cb)return;delete _permissionCallbacks[rid];cb(g);}," +
            "onSlashCommandsResult:function(rid,pj){var cb=_slashCallbacks[rid];if(!cb)return;delete _slashCallbacks[rid];var p;try{p=JSON.parse(pj);}catch(e){p={results:[]};}cb(p);}," +
            "log:function(l,m){tellevNative.log(String(l),String(m));}" +
            "};" +
            "var event_types={APP_INITIALIZED:'app_initialized',APP_READY:'app_ready',EXTRAS_CONNECTED:'extras_connected',MESSAGE_SWIPED:'message_swiped',MESSAGE_SENT:'message_sent',MESSAGE_RECEIVED:'message_received',MESSAGE_EDITED:'message_edited',MESSAGE_DELETED:'message_deleted',MESSAGE_UPDATED:'message_updated',MESSAGE_FILE_EMBEDDED:'message_file_embedded',MESSAGE_REASONING_EDITED:'message_reasoning_edited',MESSAGE_REASONING_DELETED:'message_reasoning_deleted',MESSAGE_SWIPE_DELETED:'message_swipe_deleted',MORE_MESSAGES_LOADED:'more_messages_loaded',IMPERSONATE_READY:'impersonate_ready',CHAT_CHANGED:'chat_id_changed',CHAT_LOADED:'chatLoaded',GENERATION_AFTER_COMMANDS:'GENERATION_AFTER_COMMANDS',GENERATION_STARTED:'generation_started',GENERATION_STOPPED:'generation_stopped',GENERATION_ENDED:'generation_ended',SD_PROMPT_PROCESSING:'sd_prompt_processing',EXTENSIONS_FIRST_LOAD:'extensions_first_load',EXTENSION_SETTINGS_LOADED:'extension_settings_loaded',SETTINGS_LOADED:'settings_loaded',SETTINGS_UPDATED:'settings_updated',GROUP_UPDATED:'group_updated',MOVABLE_PANELS_RESET:'movable_panels_reset',SETTINGS_LOADED_BEFORE:'settings_loaded_before',SETTINGS_LOADED_AFTER:'settings_loaded_after',CHATCOMPLETION_SOURCE_CHANGED:'chatcompletion_source_changed',CHATCOMPLETION_MODEL_CHANGED:'chatcompletion_model_changed',OAI_PRESET_CHANGED_BEFORE:'oai_preset_changed_before',OAI_PRESET_CHANGED_AFTER:'oai_preset_changed_after',OAI_PRESET_EXPORT_READY:'oai_preset_export_ready',OAI_PRESET_IMPORT_READY:'oai_preset_import_ready',WORLDINFO_SETTINGS_UPDATED:'worldinfo_settings_updated',WORLDINFO_UPDATED:'worldinfo_updated',CHARACTER_EDITOR_OPENED:'character_editor_opened',CHARACTER_EDITED:'character_edited',CHARACTER_PAGE_LOADED:'character_page_loaded',CHARACTER_GROUP_OVERLAY_STATE_CHANGE_BEFORE:'character_group_overlay_state_change_before',CHARACTER_GROUP_OVERLAY_STATE_CHANGE_AFTER:'character_group_overlay_state_change_after',USER_MESSAGE_RENDERED:'user_message_rendered',CHARACTER_MESSAGE_RENDERED:'character_message_rendered',FORCE_SET_BACKGROUND:'force_set_background',CHAT_DELETED:'chat_deleted',CHAT_CREATED:'chat_created',CHAT_RENAMED:'chat_renamed',GROUP_CHAT_DELETED:'group_chat_deleted',GROUP_CHAT_CREATED:'group_chat_created',GENERATE_BEFORE_COMBINE_PROMPTS:'generate_before_combine_prompts',GENERATE_AFTER_COMBINE_PROMPTS:'generate_after_combine_prompts',GENERATE_AFTER_DATA:'generate_after_data',GROUP_MEMBER_DRAFTED:'group_member_drafted',GROUP_WRAPPER_STARTED:'group_wrapper_started',GROUP_WRAPPER_FINISHED:'group_wrapper_finished',WORLD_INFO_ACTIVATED:'world_info_activated',TEXT_COMPLETION_SETTINGS_READY:'text_completion_settings_ready',CHAT_COMPLETION_SETTINGS_READY:'chat_completion_settings_ready',CHAT_COMPLETION_PROMPT_READY:'chat_completion_prompt_ready',CHARACTER_FIRST_MESSAGE_SELECTED:'character_first_message_selected',CHARACTER_DELETED:'characterDeleted',CHARACTER_DUPLICATED:'character_duplicated',CHARACTER_RENAMED:'character_renamed',CHARACTER_RENAMED_IN_PAST_CHAT:'character_renamed_in_past_chat',SMOOTH_STREAM_TOKEN_RECEIVED:'stream_token_received',STREAM_TOKEN_RECEIVED:'stream_token_received',STREAM_REASONING_DONE:'stream_reasoning_done',FILE_ATTACHMENT_DELETED:'file_attachment_deleted',WORLDINFO_FORCE_ACTIVATE:'worldinfo_force_activate',OPEN_CHARACTER_LIBRARY:'open_character_library',ONLINE_STATUS_CHANGED:'online_status_changed',IMAGE_SWIPED:'image_swiped',CONNECTION_PROFILE_LOADED:'connection_profile_loaded',CONNECTION_PROFILE_CREATED:'connection_profile_created',CONNECTION_PROFILE_DELETED:'connection_profile_deleted',CONNECTION_PROFILE_UPDATED:'connection_profile_updated',TOOL_CALLS_PERFORMED:'tool_calls_performed',TOOL_CALLS_RENDERED:'tool_calls_rendered',CHARACTER_MANAGEMENT_DROPDOWN:'charManagementDropdown',SECRET_WRITTEN:'secret_written',SECRET_DELETED:'secret_deleted',SECRET_ROTATED:'secret_rotated',SECRET_EDITED:'secret_edited',PRESET_CHANGED:'preset_changed',PRESET_DELETED:'preset_deleted',PRESET_RENAMED:'preset_renamed',PRESET_RENAMED_BEFORE:'preset_renamed_before',MAIN_API_CHANGED:'main_api_changed',WORLDINFO_ENTRIES_LOADED:'worldinfo_entries_loaded',WORLDINFO_SCAN_DONE:'worldinfo_scan_done',MEDIA_ATTACHMENT_DELETED:'media_attachment_deleted',PERSONA_CHANGED:'persona_changed',PERSONA_CREATED:'persona_created',PERSONA_UPDATED:'persona_updated',PERSONA_RENAMED:'persona_renamed',PERSONA_DELETED:'persona_deleted',TTS_JOB_STARTED:'tts_job_started',TTS_AUDIO_READY:'tts_audio_ready',TTS_JOB_COMPLETE:'tts_job_complete',ITEMIZED_PROMPTS_LOADED:'itemized_prompts_loaded',ITEMIZED_PROMPTS_SAVED:'itemized_prompts_saved',ITEMIZED_PROMPTS_DELETED:'itemized_prompts_deleted',CHARACTER_SELECTED:'character_selected',CHARACTER_CREATED:'character_created',CHARACTER_IMPORTED:'character_imported',CHARACTER_EXPORTED:'character_exported',CHAT_IMPORTED:'chat_imported',CHAT_EXPORTED:'chat_exported',EXTENSION_SETTINGS_OPENED:'extension_settings_opened',EXTENSION_SETTINGS_CLOSED:'extension_settings_closed',GROUP_SELECTED:'group_selected',GROUP_CHAT_STARTED:'group_chat_started',SETTINGS_CHANGED:'settings_updated'};" +
            "var _stHandlers={};var _autoFire={app_ready:true,app_initialized:true};var _lastArgs={};function _normEvent(t){if(event_types[t])return event_types[t];return String(t||'');}function _argsFromPayload(p){if(p&&Array.isArray(p.args))return p.args;if(p&&Object.prototype.hasOwnProperty.call(p,'detail'))return[p.detail];if(p&&Object.keys&&Object.keys(p).length)return[p];return[];}async function _fireLocal(t,args){t=_normEvent(t);args=args||[];var a=_stHandlers[t];if(a){var copy=a.slice();for(var i=0;i<copy.length;i++){try{var r=copy[i].apply(eventSource,args);if(r&&typeof r.then==='function')await r;}catch(e){tellevNative.log('error','eventSource handler error: '+e);}}}if(_autoFire[t])_lastArgs[t]=args;}" +
            "var eventSource={on:function(t,c){t=_normEvent(t);if(!_stHandlers[t])_stHandlers[t]=[];_stHandlers[t].push(c);if(_autoFire[t]&&Object.prototype.hasOwnProperty.call(_lastArgs,t)){try{c.apply(eventSource,_lastArgs[t]);}catch(e){tellevNative.log('error','eventSource handler error: '+e);}}return c;},makeFirst:function(t,c){t=_normEvent(t);if(!_stHandlers[t])_stHandlers[t]=[];var a=_stHandlers[t];var i=a.indexOf(c);if(i>=0)a.splice(i,1);a.unshift(c);if(_autoFire[t]&&Object.prototype.hasOwnProperty.call(_lastArgs,t)){try{c.apply(eventSource,_lastArgs[t]);}catch(e){}}return c;},makeLast:function(t,c){t=_normEvent(t);if(!_stHandlers[t])_stHandlers[t]=[];var a=_stHandlers[t];var i=a.indexOf(c);if(i>=0)a.splice(i,1);a.push(c);if(_autoFire[t]&&Object.prototype.hasOwnProperty.call(_lastArgs,t)){try{c.apply(eventSource,_lastArgs[t]);}catch(e){}}return c;},removeListener:function(t,c){t=_normEvent(t);var a=_stHandlers[t];if(!a)return;var i=a.indexOf(c);if(i>=0)a.splice(i,1);},off:function(t,c){return eventSource.removeListener(t,c);},once:function(t,c){var w=function(){eventSource.removeListener(t,w);return c.apply(eventSource,arguments);};return eventSource.on(t,w);},make:function(t,d){return{type:_normEvent(t),detail:d};},emit:function(t){var args=[].slice.call(arguments,1);var n=_normEvent(t);tellevNative.emit(String(n),JSON.stringify({args:args}));return _fireLocal(n,args);},emitAndWait:function(t){return eventSource.emit.apply(eventSource,arguments);},_fireNative:function(n,pj){var p;try{p=JSON.parse(pj);}catch(e){p={};}return _fireLocal(n,_argsFromPayload(p));},_fireLocal:function(n,p){return _fireLocal(n,Array.isArray(p)?p:_argsFromPayload(p));}};" +
            "function _getContext(){var r;try{r=tellevNative.stGetContext();}catch(e){r='{}';}var c;try{c=JSON.parse(r);}catch(e){c={};}if(!c.chat)c.chat=[];if(!c.characters)c.characters=[];if(!c.groups)c.groups=[];if(!c.name1)c.name1='User';if(!c.name2)c.name2='Character';if(!c.characterId)c.characterId='';if(!c.chatId)c.chatId='';if(!c.groupId)c.groupId='';if(!c.mainApi)c.mainApi='openai-compatible';if(!c.chatMetadata)c.chatMetadata={};if(!c.onlineStatus)c.onlineStatus='connected';if(!c.maxContext)c.maxContext=8192;if(!c.extensionPrompts)c.extensionPrompts={};if(!c.eventSource)c.eventSource=eventSource;if(!c.eventTypes)c.eventTypes=event_types;if(!c.event_types)c.event_types=event_types;return c;}" +
            "var SillyTavern={getContext:_getContext};" +
            "var getContext=_getContext;" +
            "var TavernHelper={tavern_events:event_types,iframe_events:{MESSAGE_IFRAME_RENDER_STARTED:'message_iframe_render_started',MESSAGE_IFRAME_RENDER_ENDED:'message_iframe_render_ended',GENERATION_STARTED:'js_generation_started',STREAM_TOKEN_RECEIVED_FULLY:'js_stream_token_received_fully',STREAM_TOKEN_RECEIVED_INCREMENTALLY:'js_stream_token_received_incrementally',GENERATION_ENDED:'js_generation_ended'},getChatMessages:function(range,opts){opts=opts||{};var c=_getContext();var m=c.chat||[];function _mapMsg(msg,i){return{message_id:msg.index!==undefined?msg.index:i,name:msg.name,role:msg.is_user?'user':(msg.is_system?'system':'assistant'),is_hidden:msg.is_system||false,message:msg.mes,data:msg.variables||{},extra:msg.extra||{},swipe_id:msg.swipe_id||0,swipes:msg.swipes||[],swipes_data:[]};}if(range===undefined||range===null||range==='')return m.map(_mapMsg);var r;if(typeof range==='number'){r=range>=0&&range<m.length?{start:range,end:range}:null;}else{var s=String(range);var mt=s.match(/^(-?\\d+)(?:-(-?\\d+))?$/);if(!mt)r=null;else{var a=parseInt(mt[1],10);var b=mt[2]!==undefined?parseInt(mt[2],10):a;if(a<0)a=m.length+a;if(b<0)b=m.length+b;if(isNaN(a)||isNaN(b))r=null;else{a=Math.max(0,Math.min(a,m.length-1));b=Math.max(0,Math.min(b,m.length-1));r={start:Math.min(a,b),end:Math.max(a,b)};}}}if(!r)return[];return m.slice(r.start,r.end+1).map(function(msg,i){return _mapMsg(msg,r.start+i);});},setChatMessage:function(fv,mid,opts){fv=typeof fv==='string'?{message:fv}:fv;if(!fv)return;if(fv.message!==undefined)tellevNative.stSetChatMessage(String(mid),'message',String(fv.message));if(fv.data!==undefined)tellevNative.stSetChatMessage(String(mid),'data',JSON.stringify(fv.data));},setChatMessages:function(cms,opts){if(!Array.isArray(cms))return;cms.forEach(function(cm){var mid=cm.message_id;if(mid===undefined)return;if(cm.message!==undefined)tellevNative.stSetChatMessage(String(mid),'message',String(cm.message));if(cm.name!==undefined)tellevNative.stSetChatMessage(String(mid),'name',String(cm.name));if(cm.role!==undefined)tellevNative.stSetChatMessage(String(mid),'role',String(cm.role));if(cm.is_hidden!==undefined)tellevNative.stSetChatMessage(String(mid),'is_hidden',String(cm.is_hidden));if(cm.extra!==undefined)tellevNative.stSetChatMessage(String(mid),'extra',JSON.stringify(cm.extra));if(cm.swipe_id!==undefined)tellevNative.stSetChatMessage(String(mid),'swipe_id',String(cm.swipe_id));if(cm.swipes!==undefined)tellevNative.stSetChatMessage(String(mid),'swipes',JSON.stringify(cm.swipes));});},getVariables:function(opt){var r;try{r=tellevNative.stGetVariables();}catch(e){r='{}';}try{return JSON.parse(r);}catch(e){return{};}},getAllVariables:function(opt){return TavernHelper.getVariables(opt);},replaceVariables:function(vars,opt){tellevNative.stSetVariables(JSON.stringify(vars||{}));},updateVariablesWith:function(updater,opt){var v=TavernHelper.getVariables(opt);var r=updater(v);TavernHelper.replaceVariables(r||v,opt);},insertOrAssignVariables:function(nv,opt){var v=TavernHelper.getVariables(opt);var merged={};for(var k in v)merged[k]=v[k];for(var k in nv)merged[k]=nv[k];TavernHelper.replaceVariables(merged,opt);},insertVariables:function(nv,opt){var v=TavernHelper.getVariables(opt);var merged={};for(var k in nv)merged[k]=nv[k];for(var k in v)if(!(k in merged))merged[k]=v[k];TavernHelper.replaceVariables(merged,opt);},deleteVariable:function(path,opt){var v=TavernHelper.getVariables(opt);var parts=String(path).split('.');var obj=v;for(var i=0;i<parts.length-1;i++){if(!obj[parts[i]])return;obj=obj[parts[i]];}delete obj[parts[parts.length-1]];TavernHelper.replaceVariables(v,opt);},substitudeMacros:function(text){if(text==null)return text;try{text=tellevNative.stReplaceVariables(String(text));}catch(e){}if(window._thMacroLikes&&text){for(var name in window._thMacroLikes){var ml=window._thMacroLikes[name];try{text=text.replace(new RegExp(ml.pattern,'g'),ml.replacement);}catch(e){}}}return text;},eventOn:function(t,c){return eventSource.on(t,c);},eventMakeLast:function(t,c){return eventSource.makeLast(t,c);},eventMakeFirst:function(t,c){return eventSource.makeFirst(t,c);},eventOnce:function(t,c){return eventSource.once(t,c);},eventEmit:function(t){return eventSource.emit.apply(eventSource,arguments);},eventEmitAndWait:function(t){return eventSource.emitAndWait.apply(eventSource,arguments);},eventRemoveListener:function(t,c){return eventSource.removeListener(t,c);},eventClearEvent:function(t){var n=_normEvent(t);_stHandlers[n]=[];},eventClearAll:function(){_stHandlers={};},triggerSlash:function(text){return executeSlashCommandsWithOptions(text);},addSlashCommand:function(n,c,o){o=o||{};_commandHandlers[n]=c;tellevNative.registerCommand(String(n),String(o.help||o.description||''),JSON.stringify(o.args||{}));},registerEvent:function(t,c){return eventSource.on(t,c);},setVariables:function(v){tellevNative.stSetVariables(JSON.stringify(v||{}));},getLastMessageId:function(){var c=_getContext();return c.chat?c.chat.length-1:-1;},getMessageId:function(){return TavernHelper.getLastMessageId();},getTavernHelperVersion:function(){return'4.8.11';},getFrontendVersion:function(){return TavernHelper.getTavernHelperVersion();},getTavernVersion:function(){return'1.18.0';},errorCatched:function(fn){return function(){try{return fn.apply(this,arguments);}catch(e){tellevNative.log('error',String(e));}};},getExtensionPrompt:function(){try{return JSON.parse(tellevNative.stGetInjectedPrompts()||'{}');}catch(e){return{};}},firstUserMessageIndex:function(){var c=_getContext();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&c.chat[i].is_user)return i;}return-1;},firstBotMessageIndex:function(){var c=_getContext();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&!c.chat[i].is_user)return i;}return-1;},generate:function(opts){opts=opts||{};return window.Tellev.apiCall('POST','/api/backends/chat-completions/generate',opts).then(function(r){return r.body&&r.body.text?r.body.text:'';});},generateRaw:function(opts){opts=opts||{};return window.Tellev.apiCall('POST','/api/backends/chat-completions/generate',opts).then(function(r){return r.body||{};});},stopAllGeneration:function(){return Promise.resolve();},stopGenerationById:function(){return Promise.resolve();},getModelList:function(opts){opts=opts||{};var url=opts.api_url||opts.apiUrl||'';return window.Tellev.apiCall('POST','/api/backends/chat-completions/status',{api_url:url}).then(function(r){return r.body&&r.body.data?r.body.data:[];});},getCharacter:function(id){return window.Tellev.apiCall('GET','/api/characters/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getCurrentCharacterName:function(){var c=_getContext();return c.name2||'';},getCurrentCharacterId:function(){var c=_getContext();return c.characterId||'';},getCharacterNames:function(){return window.Tellev.apiCall('GET','/api/characters').then(function(r){var arr=r.body&&r.body.characters?r.body.characters:[];return arr.map(function(c){return c.name;});});},getCharacterIds:function(){return window.Tellev.apiCall('GET','/api/characters').then(function(r){var arr=r.body&&r.body.characters?r.body.characters:[];return arr.map(function(c){return c.id;});});},replaceCharacter:function(id,data){return window.Tellev.apiCall('POST','/api/characters',data).then(function(r){return r.body||{};});},updateCharacterWith:function(id,fn){return TavernHelper.getCharacter(id).then(function(c){var updated=fn(c);return TavernHelper.replaceCharacter(id,updated);});},createCharacter:function(data){return window.Tellev.apiCall('POST','/api/characters',data).then(function(r){return r.body||{};});},deleteCharacter:function(id){return window.Tellev.apiCall('DELETE','/api/characters/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getLorebooks:function(){return window.Tellev.apiCall('GET','/api/worlds').then(function(r){return r.body&&r.body.worlds?r.body.worlds:[];});},getWorldbook:function(id){return window.Tellev.apiCall('GET','/api/worlds/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getWorldbookNames:function(){return TavernHelper.getLorebooks().then(function(books){return books.map(function(b){return b.id||b.name;});});},createWorldbook:function(data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},replaceWorldbook:function(id,data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},getLorebookEntries:function(bookId){return TavernHelper.getWorldbook(bookId).then(function(b){return b&&b.entries?b.entries:[];});},setLorebookEntries:function(bookId,entries){return TavernHelper.getWorldbook(bookId).then(function(b){b.entries=entries;return TavernHelper.replaceWorldbook(bookId,b);});},createLorebookEntry:function(bookId,entry){return TavernHelper.getLorebookEntries(bookId).then(function(entries){entries.push(entry);return TavernHelper.setLorebookEntries(bookId,entries);});},deleteLorebookEntries:function(bookId,ids){return TavernHelper.getLorebookEntries(bookId).then(function(entries){return entries.filter(function(e){return ids.indexOf(e.uid)<0;});}).then(function(kept){return TavernHelper.setLorebookEntries(bookId,kept);});},getLorebookSettings:function(){return Promise.resolve({});},setLorebookSettings:function(){return Promise.resolve();},getCharLorebooks:function(){return Promise.resolve([]);},setCurrentCharLorebooks:function(){return Promise.resolve();},getChatLorebook:function(){return Promise.resolve(null);},setChatLorebook:function(){return Promise.resolve();},getOrCreateChatLorebook:function(){return Promise.resolve(null);},getPreset:function(name){return window.Tellev.apiCall('GET','/api/settings').then(function(r){var arr=r.body&&r.body.presets?r.body.presets:[];return arr.find(function(p){return p.name===name;})||null;});},getPresetNames:function(){return window.Tellev.apiCall('GET','/api/settings').then(function(r){var arr=r.body&&r.body.presets?r.body.presets:[];return arr.map(function(p){return p.name;});});},loadPreset:function(){return Promise.resolve();},setPreset:function(){return Promise.resolve();},createPreset:function(){return Promise.resolve();},deletePreset:function(){return Promise.resolve();},renamePreset:function(){return Promise.resolve();},isPresetNormalPrompt:function(){return false;},isPresetSystemPrompt:function(){return false;},isPresetPlaceholderPrompt:function(){return false;},getPersona:function(id){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.find(function(p){return p.id===id;})||null;});},getPersonaNames:function(){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.map(function(p){return p.name;});});},getPersonaIds:function(){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.map(function(p){return p.id;});});},getCurrentPersonaName:function(){var c=_getContext();return c.name1||'User';},getCurrentPersonaId:function(){return Promise.resolve('');},getPersonaAvatarPath:function(){return Promise.resolve('');},createPersona:function(){return Promise.resolve();},replacePersona:function(){return Promise.resolve();},updatePersonaWith:function(){return Promise.resolve();},deletePersona:function(){return Promise.resolve();},injectPrompts:function(id,content,opts){opts=opts||{};try{tellevNative.stInjectPrompt(String(id||''),String(content||''),Number(opts.position||0),Number(opts.depth||4),String(opts.role||'system'));}catch(e){tellevNative.log('error','injectPrompts failed: '+e);}return Promise.resolve();},uninjectPrompts:function(id){try{tellevNative.stUninjectPrompt(String(id||''));}catch(e){tellevNative.log('error','uninjectPrompts failed: '+e);}return Promise.resolve();},getTavernRegexes:function(charId){return window.Tellev.apiCall('GET','/api/characters/'+encodeURIComponent(charId)+'/regex').then(function(r){return r.body&&r.body.regex_scripts?r.body.regex_scripts:[];});},replaceTavernRegexes:function(charId,regexes){return TavernHelper.getCharacter(charId).then(function(c){var data=c.data||{};var ext=data.extensions||{};ext.regex_scripts=regexes;data.extensions=ext;c.data=data;return TavernHelper.replaceCharacter(charId,c);});},formatAsTavernRegexedString:function(){return Promise.resolve('');},isCharacterTavernRegexesEnabled:function(){return Promise.resolve(false);},getScriptTrees:function(){return Promise.resolve([]);},replaceScriptTrees:function(){return Promise.resolve();},updateScriptTreesWith:function(){return Promise.resolve();},getAllEnabledScriptButtons:function(){return Promise.resolve([]);},getScriptButtons:function(){return Promise.resolve([]);},replaceScriptButtons:function(){return Promise.resolve();},importRawCharacter:function(data){return window.Tellev.apiCall('POST','/api/characters/import',data).then(function(r){return r.body||{};});},importRawPreset:function(){return Promise.resolve();},importRawChat:function(data){return window.Tellev.apiCall('POST','/api/chats/import',data).then(function(r){return r.body||{};});},importRawWorldbook:function(data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},importRawTavernRegex:function(){return Promise.resolve();},playAudio:function(){return Promise.resolve();},pauseAudio:function(){return Promise.resolve();},getAudioList:function(){return Promise.resolve([]);},replaceAudioList:function(){return Promise.resolve();},appendAudioList:function(){return Promise.resolve();},getAudioSettings:function(){return Promise.resolve({});},setAudioSettings:function(){return Promise.resolve();},getCurrentAudio:function(){return Promise.resolve(null);},registerMacroLike:function(name,pattern,replacement,opts){if(!window._thMacroLikes)window._thMacroLikes={};window._thMacroLikes[name]={pattern:pattern,replacement:replacement,opts:opts||{}};return Promise.resolve();},unregisterMacroLike:function(name){if(window._thMacroLikes)delete window._thMacroLikes[name];return Promise.resolve();},getChatHistoryBrief:function(charId){return window.Tellev.apiCall('GET','/api/chats?characterId='+encodeURIComponent(charId)).then(function(r){return r.body&&r.body.chats?r.body.chats:[];});},getChatHistoryDetail:function(charId,chatId){return window.Tellev.apiCall('GET','/api/chats/'+encodeURIComponent(chatId)).then(function(r){return r.body||{};});},getCharData:function(charId,field){return TavernHelper.getCharacter(charId).then(function(c){return c[field];});},getCharAvatarPath:function(charId){return TavernHelper.getCharacter(charId).then(function(c){return c.avatar||'';});},getExtensionType:function(){return Promise.resolve('');},getExtensionStatus:function(name){return window.Tellev.apiCall('POST','/api/extensions/version',{name:name}).then(function(r){return r.body||{installed:false};});},isInstalledExtension:function(name){return TavernHelper.getExtensionStatus(name).then(function(info){return info.installed===true;});},installExtension:function(){return Promise.resolve();},uninstallExtension:function(){return Promise.resolve();},reinstallExtension:function(){return Promise.resolve();},updateExtension:function(){return Promise.resolve();},isAdmin:function(){return false;},getTavernHelperExtensionId:function(){return'tavern-helper-compat';},_th_impl:{_init:function(){},_log:function(){},_clearLog:function(){},writeExtensionField:function(){return Promise.resolve();}},_bind:{},getScriptId:function(){return '';},getCurrentMessageId:function(){var c=_getContext();return (c.chat&&c.chat.length)?c.chat.length-1:0;},getScriptName:function(){return '';},getScriptInfo:function(){return {};},replaceScriptInfo:function(){return Promise.resolve();},getScriptButtons:function(){return [];},replaceScriptButtons:function(){return Promise.resolve();},updateScriptButtonsWith:function(){return Promise.resolve([]);},getAllEnabledScriptButtons:function(){return {};},getButtonEvent:function(n){return 'button_'+String(n);},eventClearListener:function(){},initializeGlobal:function(n,v){window[n]=v;},waitGlobalInitialized:function(n){return new Promise(function(r){if(window[n]!==undefined)r(window[n]);});},registerVariableSchema:function(){},updateTavernHelper:function(){return Promise.resolve(false);},updateFrontendVersion:function(){return Promise.resolve(false);},builtin:{addOneMessage:function(){},copyText:function(){},duringGenerating:function(){},getImageTokenCost:function(){return 0;},getVideoTokenCost:function(){return 0;},parseRegexFromString:function(s){try{return new RegExp(s);}catch(e){return new RegExp('');}},promptManager:null,reloadAndRenderChatWithoutEvents:function(){},reloadChatWithoutEvents:function(){},reloadEditor:function(){},reloadEditorDebounced:function(){},renderMarkdown:function(s){return s;},renderPromptManager:function(){},renderPromptManagerDebounced:function(){},saveSettings:function(){return Promise.resolve();},uuidv4:function(){return '';},getLoadedPresetName:function(){return '';},createOrReplacePreset:function(){return Promise.resolve(false);},replacePreset:function(){return Promise.resolve();},updatePresetWith:function(){return Promise.resolve({});},deleteWorldbook:function(){return Promise.resolve(false);},updateWorldbookWith:function(){return Promise.resolve([]);},createWorldbookEntries:function(){return Promise.resolve({worldbook:[],new_entries:[]});},deleteWorldbookEntries:function(){return Promise.resolve({worldbook:[],deleted_entries:[]});},getGlobalWorldbookNames:function(){return [];},rebindGlobalWorldbooks:function(){return Promise.resolve();},getCharWorldbookNames:function(){return {};},rebindCharWorldbooks:function(){return Promise.resolve();},getChatWorldbookName:function(){return null;},rebindChatWorldbook:function(){return Promise.resolve();},getOrCreateChatWorldbook:function(){return Promise.resolve('');},createChatMessages:function(){return Promise.resolve();},deleteChatMessages:function(){return Promise.resolve();},rotateChatMessages:function(){return Promise.resolve();},formatAsDisplayedMessage:function(t){return String(t||'');},retrieveDisplayedMessage:function(){return null;},refreshOneMessage:function(){return Promise.resolve();}};" +
            "function executeSlashCommandsWithOptions(cs){var raw=typeof cs==='string'?cs:(Array.isArray(cs)?cs.join('\\n'):String(cs||''));_apiReqCounter+=1;var rid='slash_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve){_slashCallbacks[rid]=resolve;tellevNative.executeSlashCommands(rid,raw);});}" +
            "function executeSlashCommands(cs){return executeSlashCommandsWithOptions(cs);}" +
            "var _ejsDefaultFeatures={enabled:true,generate_enabled:true,generate_loader_enabled:true,inject_loader_enabled:false,render_enabled:true,render_loader_enabled:true,code_blocks_enabled:false,raw_message_evaluation_enabled:true,filter_message_enabled:true,depth_limit:-1,autosave_enabled:false,preload_worldinfo_enabled:true,with_context_disabled:false,debug_enabled:false,invert_enabled:true,compile_workers:false,sandbox:false,cache_enabled:0,cache_size:64,cache_hasher:'h32ToString',code_editor:false};" +
            "var _ejsFeatures=Object.assign({},_ejsDefaultFeatures);" +
            "function _ejsPathGet(root,path,fb){if(root==null)return fb;var cur=root;var parts=String(path||'').split('.');for(var i=0;i<parts.length;i++){var p=parts[i];if(!p)continue;if(cur==null)return fb;cur=cur[p];}return cur===undefined?fb:cur;}" +
            "function _ejsPathSet(root,path,value){if(!root)return root;var cur=root;var parts=String(path||'').split('.').filter(Boolean);for(var i=0;i<parts.length-1;i++){var p=parts[i];if(typeof cur[p]!=='object'||cur[p]===null)cur[p]={};cur=cur[p];}if(parts.length)cur[parts[parts.length-1]]=value;return root;}" +
            "function _ejsVars(){try{return TavernHelper.getVariables()||{};}catch(e){return{};}}" +
            "function _ejsSaveVars(vars){try{TavernHelper.replaceVariables(vars||{});}catch(e){}}" +
            "function _ejsLodash(){return{get:_ejsPathGet,set:function(o,p,v){return _ejsPathSet(o,p,v);},has:function(o,p){return _ejsPathGet(o,p,undefined)!==undefined;},unset:function(o,p){var parts=String(p||'').split('.').filter(Boolean);var cur=o;for(var i=0;i<parts.length-1;i++){if(cur==null)return o;cur=cur[parts[i]];}if(cur!=null)delete cur[parts[parts.length-1]];return o;},merge:function(target){target=target||{};for(var i=1;i<arguments.length;i++){var src=arguments[i]||{};for(var k in src){if(src[k]&&typeof src[k]==='object'&&!Array.isArray(src[k]))target[k]=this.merge(target[k]||{},src[k]);else target[k]=src[k];}}return target;},mergeWith:function(target){var args=[].slice.call(arguments);var customizer=args[args.length-1];target=target||{};for(var i=1;i<args.length-1;i++){var src=args[i]||{};for(var k in src){var cv=customizer?customizer(target[k],src[k],k,target,src):undefined;if(cv!==undefined)target[k]=cv;else if(src[k]&&typeof src[k]==='object'&&!Array.isArray(src[k]))target[k]=this.merge(target[k]||{},src[k]);else target[k]=src[k];}}return target;},cloneDeep:function(v){if(Array.isArray(v))return v.map(this.cloneDeep);if(v&&typeof v==='object'){var o={};for(var k in v)o[k]=this.cloneDeep(v[k]);return o;}return v;},find:function(arr,fn){if(!Array.isArray(arr))return undefined;for(var i=0;i<arr.length;i++){if(fn(arr[i],i,arr))return arr[i];}return undefined;},findLastIndex:function(arr,fn){if(!Array.isArray(arr))return -1;for(var i=arr.length-1;i>=0;i--){if(fn(arr[i],i,arr))return i;}return -1;},groupBy:function(arr,fn){var r={};if(!Array.isArray(arr))return r;arr.forEach(function(v,i){var k=fn(v,i,arr);(r[k]=r[k]||[]).push(v);});return r;},castArray:function(v){return Array.isArray(v)?v:[v];},compact:function(arr){return Array.isArray(arr)?arr.filter(Boolean):[];},clamp:function(n,lo,hi){return Math.max(lo,Math.min(hi,n));},escapeRegExp:function(s){return String(s||'').replace(/[.*+?^\${}()|[\\]\\\\]/g,'\\\\\$&');},defaults:function(o){for(var i=1;i<arguments.length;i++){var s=arguments[i]||{};for(var k in s){if(o[k]===undefined)o[k]=s[k];}}return o;},isEqual:function(a,b){return JSON.stringify(a)===JSON.stringify(b);},isPlainObject:function(v){return v!==null&&typeof v==='object'&&!Array.isArray(v);},isArray:function(v){return Array.isArray(v);},isObject:function(v){return v!==null&&typeof v==='object';},isString:function(v){return typeof v==='string';},isFunction:function(v){return typeof v==='function';},random:function(n){return Math.floor(Math.random()*(n||1));},sum:function(arr){return Array.isArray(arr)?arr.reduce(function(a,b){return a+(+b||0);},0):0;},entries:function(o){var r=[];if(o&&typeof o==='object'){for(var k in o)r.push([k,o[k]]);}return r;}};}" +
            "function _ejsHelpers(ctx){var vars=_ejsVars();var helpers={SillyTavern:SillyTavern,TavernHelper:TavernHelper,getContext:_getContext,variables:vars,_:_ejsLodash(),getvar:function(k,d){var v=_ejsPathGet(vars,k,d);return v===undefined?'':v;},getchatvar:function(k,d){var v=_ejsPathGet(vars,k,d);return v===undefined?'':v;},getglobalvar:function(k,d){var v=_ejsPathGet(vars,k,d);return v===undefined?'':v;},setvar:function(k,v){_ejsPathSet(vars,k,v);_ejsSaveVars(vars);return'';},setchatvar:function(k,v){_ejsPathSet(vars,k,v);_ejsSaveVars(vars);return'';},setglobalvar:function(k,v){_ejsPathSet(vars,k,v);_ejsSaveVars(vars);return'';},incvar:function(k){var v=Number(_ejsPathGet(vars,k,0)||0)+1;_ejsPathSet(vars,k,v);_ejsSaveVars(vars);return v;},decvar:function(k){var v=Number(_ejsPathGet(vars,k,0)||0)-1;_ejsPathSet(vars,k,v);_ejsSaveVars(vars);return v;},print:function(){return Array.prototype.join.call(arguments,'');}};return helpers;}" +
            "function _ejsCompile(code){var src=\"var __out='';var print=function(){__out+=Array.prototype.join.call(arguments,'');};\";var re=/<%([=-]?)([\\s\\S]*?)%>/g;var cursor=0;var m;function addText(t){if(t)src+='__out+='+JSON.stringify(t)+';';}while((m=re.exec(String(code||'')))!==null){addText(String(code||'').slice(cursor,m.index));var marker=m[1];var body=String(m[2]||'').trim();if(body.charAt(0)==='_')body=body.slice(1).trim();if(body.charAt(body.length-1)==='_')body=body.slice(0,-1).trim();if(marker==='='||marker==='-'){src+='__out+=(('+body+')==null?\\'\\':String('+body+'));';}else{src+=body+'\\n';}cursor=m.index+m[0].length;}addText(String(code||'').slice(cursor));src+='return __out;';return new Function('ctx','helpers',\"return (async function(){with(helpers){with(ctx||{}){\"+src+\"}}}).call(ctx);\");}" +
            "function _ejsPrepareContext(additional){var c=_getContext();var vars=_ejsVars();var env={context:c,SillyTavern:SillyTavern,TavernHelper:TavernHelper,variables:vars,vars:vars,name1:c.name1||'User',user:c.name1||'User',userName:c.name1||'User',name2:c.name2||'Character',char:c.name2||'Character',charName:c.name2||'Character',chat:c.chat||[],characters:c.characters||[],groups:c.groups||[]};var helpers=_ejsHelpers(env);for(var k in helpers)if(env[k]===undefined)env[k]=helpers[k];if(additional){for(var ak in additional)env[ak]=additional[ak];}return env;}" +
            "var EjsTemplate={evaltemplate:function(code,context,options){try{return Promise.resolve(_ejsCompile(code)(context||_ejsPrepareContext({}),_ejsHelpers(context||{})));}catch(e){return Promise.reject(e);}},evalTemplate:function(code,context,options){return EjsTemplate.evaltemplate(code,context,options);},prepareContext:function(additional_context,last_message_id){return Promise.resolve(_ejsPrepareContext(additional_context||{}));},getSyntaxErrorInfo:function(code,lineCount){try{_ejsCompile(code);return Promise.resolve('');}catch(e){return Promise.resolve(String(e&&e.message?e.message:e));}},allVariables:function(end_message_id){return _ejsVars();},getFeatures:function(){return Object.assign({},_ejsFeatures);},setFeatures:function(features){_ejsFeatures=Object.assign({},_ejsFeatures,features||{});},resetFeatures:function(){_ejsFeatures=Object.assign({},_ejsDefaultFeatures);}};" +
            "function _isApiPath(u){var p=u.split('?')[0];if(p.indexOf('/api/')===0)return true;if(p.indexOf('extensions.tellev.local')>=0&&p.indexOf('/api/')>=0)return true;return false;}" +
            "window.fetch=function(input,init){try{var u=typeof input==='string'?input:((input&&input.url)||'');if(_isApiPath(u)){var p=u.split('extensions.tellev.local')[1]||u;var m=(init&&init.method)||'GET';var b=init&&init.body;var bo=null;if(b!==undefined&&b!==null){if(typeof b==='string'){try{bo=JSON.parse(b);}catch(e){bo=b;}}else{bo=b;}}return window.Tellev.apiCall(m,p,bo).then(function(r){var bt=(r.body!==undefined&&r.body!==null)?(typeof r.body==='string'?r.body:JSON.stringify(r.body)):'';return new Response(bt,{status:r.status,headers:{'Content-Type':'application/json'}});});}}catch(e){return Promise.reject(e);}return Promise.reject(new Error('Network access is not permitted for extensions'));};" +
            "window.SillyTavern=SillyTavern;window.getContext=getContext;window.eventSource=eventSource;window.event_types=event_types;window.eventTypes=event_types;window.TavernHelper=TavernHelper;window.EjsTemplate=EjsTemplate;window.tavern_events=event_types;window.executeSlashCommandsWithOptions=executeSlashCommandsWithOptions;window.executeSlashCommands=executeSlashCommands;window.eventOn=TavernHelper.eventOn;window.eventMakeLast=TavernHelper.eventMakeLast;window.eventMakeFirst=TavernHelper.eventMakeFirst;window.eventOnce=TavernHelper.eventOnce;window.eventEmit=TavernHelper.eventEmit;window.eventEmitAndWait=TavernHelper.eventEmitAndWait;window.eventRemoveListener=TavernHelper.eventRemoveListener;window.getVariables=TavernHelper.getVariables;window.replaceVariables=TavernHelper.replaceVariables;window.updateVariablesWith=TavernHelper.updateVariablesWith;window.insertOrAssignVariables=TavernHelper.insertOrAssignVariables;window.insertVariables=TavernHelper.insertVariables;window.deleteVariable=TavernHelper.deleteVariable;window.substitudeMacros=TavernHelper.substitudeMacros;window.triggerSlash=TavernHelper.triggerSlash;window.getLastMessageId=TavernHelper.getLastMessageId;window.getChatMessages=TavernHelper.getChatMessages;window.setChatMessages=TavernHelper.setChatMessages;window.generate=TavernHelper.generate;window.generateRaw=TavernHelper.generateRaw;window.stopAllGeneration=TavernHelper.stopAllGeneration;window.getCharacter=TavernHelper.getCharacter;window.replaceCharacter=TavernHelper.replaceCharacter;window.updateCharacterWith=TavernHelper.updateCharacterWith;window.getLorebooks=TavernHelper.getLorebooks;window.getWorldbook=TavernHelper.getWorldbook;window.getWorldbookNames=TavernHelper.getWorldbookNames;window.getLorebookEntries=TavernHelper.getLorebookEntries;window.setLorebookEntries=TavernHelper.setLorebookEntries;window.getPreset=TavernHelper.getPreset;window.getPresetNames=TavernHelper.getPresetNames;window.getPersona=TavernHelper.getPersona;window.injectPrompts=TavernHelper.injectPrompts;window.uninjectPrompts=TavernHelper.uninjectPrompts;window.getTavernRegexes=TavernHelper.getTavernRegexes;window.substitudeMacros=TavernHelper.substitudeMacros;window.getModelList=TavernHelper.getModelList;window.getAllVariables=TavernHelper.getAllVariables;window.getChatHistoryBrief=TavernHelper.getChatHistoryBrief;window.getChatHistoryDetail=TavernHelper.getChatHistoryDetail;window.getRawCharacter=TavernHelper.getCharacter;window.getCharData=TavernHelper.getCharData;" +
            "})();" +
            "\n</script>\n<script>\n__SCRIPT_SOURCE__\n</script><script>try{tellevNative.extensionReady();}catch(e){}</script></body></html>"
    }
}
