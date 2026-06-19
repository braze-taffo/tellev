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
 * seeded with a SillyTavern / 閰掗鍔╂墜 compatibility shim that exposes
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
) : ExtensionHost {

    // 鈹€鈹€ state 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

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

    private val settingsCache = ConcurrentHashMap<String, String>()
    private val settingsWriteMutex = Mutex()

    private val tavernHelperVariables = ConcurrentHashMap<String, JsonObject>()

    /** Mutable so the UI layer can plug in a live context snapshot. */
    private var _contextProvider: ExtensionContextProvider? = contextProvider

    /** Update the context provider used to answer getContext() from JS. */
    fun setContextProvider(provider: ExtensionContextProvider?) {
        _contextProvider = provider
    }

    // 鈹€鈹€ ExtensionHost implementation 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    override suspend fun load(
        manifest: ExtensionManifest,
        scriptSource: String,
    ): ExtensionHandle = withContext(Dispatchers.Main) {
        webViews.remove(manifest.id)?.destroy()

        val token = UUID.randomUUID().toString()
        capabilityTokens[manifest.id] = token

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

        emit(ExtensionEvent(name = "extension_loaded", extensionId = manifest.id))

        ExtensionHandle(
            id = manifest.id,
            name = manifest.name,
            loaded = true,
            version = manifest.version,
            capabilities = defaultCapabilities(manifest),
            capabilityToken = token,
        )
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
                "}if(window.eventSource&&window.eventSource._fireNative){" +
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

        val result = withTimeoutOrNull(commandTimeoutMs) { deferred.await() }
        pendingCommands.remove(requestId)
        pendingCommandOwners.remove(requestId)

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

            val result = withTimeoutOrNull(apiCallTimeoutMs) { deferred.await() }
            pendingVirtualApi.remove(requestId)
            pendingVirtualApiOwners.remove(requestId)
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

    // 鈹€鈹€ Bridge (called from JS via @JavascriptInterface) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

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
                val response = apiRouter.route(request)
                deliverApiResponseToJs(requestId, response)
            }
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

        @JavascriptInterface
        fun requestPermission(permission: String): Boolean {
            val perm = runCatching { ExtensionPermission.valueOf(permission) }.getOrNull() ?: return false
            return runBlocking { permissionManager.hasPermission(extensionId, perm) }
        }

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

        // 鈹€鈹€ SillyTavern / 閰掗鍔╂墜 shim bridge methods 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

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
        fun stSetChatMessage(index: String, field: String, value: String) {
            scope.launch {
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
        fun executeSlashCommands(requestId: String, commandsJson: String) {
            scope.launch {
                val commands = runCatching {
                    json.parseToJsonElement(commandsJson).jsonArray.map { it.jsonObject }
                }.getOrDefault(emptyList())
                val results = mutableListOf<SlashCommandResult>()
                for (cmd in commands) {
                    val name = cmd["name"]?.jsonPrimitive?.content ?: cmd["command"]?.jsonPrimitive?.content ?: ""
                    val rawInput = cmd["value"]?.jsonPrimitive?.content ?: cmd["args"]?.jsonPrimitive?.content ?: ""
                    val result = if (name.isBlank()) {
                        SlashCommandResult(handled = false, output = "Missing command name")
                    } else {
                        runCatching { executeSlashCommand(SlashCommandInput(commandName = name, rawInput = rawInput)) }
                            .getOrElse { SlashCommandResult(handled = false, output = it.message ?: "error") }
                    }
                    results.add(result)
                }
                withContext(Dispatchers.Main) {
                    val payload = buildJsonObject {
                        putJsonArray("results") {
                            for (r in results) {
                                add(buildJsonObject {
                                    put("handled", r.handled)
                                    put("output", r.output)
                                })
                            }
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

    // 鈹€鈹€ HTML template 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private fun buildExtensionHtml(extensionId: String, token: String, scriptSource: String): String {
        val safeId = jsEscape(extensionId)
        val safeToken = jsEscape(token)
        val safeScript = sanitizeScriptSource(scriptSource)
        return HTML_TEMPLATE
            .replace("__EXTENSION_ID__", safeId)
            .replace("__TOKEN__", safeToken)
            .replace("__SCRIPT_SOURCE__", safeScript)
    }

    // 鈹€鈹€ helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

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
            "onEvent:function(n,pj){var p;try{p=JSON.parse(pj);}catch(e){p={};}var hs=_eventHandlers[n];if(hs){for(var i=0;i<hs.length;i++){try{hs[i](n,p);}catch(e){tellevNative.log('error','Event handler error: '+e);}}}var wc=_eventHandlers['*'];if(wc){for(var j=0;j<wc.length;j++){try{wc[j](n,p);}catch(e){}}}if(window.eventSource)window.eventSource._fireLocal(n,p);}," +
            "registerCommand:function(n,d,a,h){_commandHandlers[n]=h;tellevNative.registerCommand(String(n),String(d),JSON.stringify(a||{}));}," +
            "finishCommand:function(rid,r){tellevNative.commandResult(String(rid),JSON.stringify(r||{handled:true}));}," +
            "onCommandExecute:function(rid,cn,ri,aj){var a;try{a=JSON.parse(aj);}catch(e){a={};}var h=_commandHandlers[cn];if(h){try{h(rid,ri,a);}catch(e){tellevNative.log('error','Command handler error: '+e);tellevNative.commandResult(rid,JSON.stringify({handled:false,output:'',metadata:{error:String(e)}}));}}else{tellevNative.commandResult(rid,JSON.stringify({handled:false}));}}," +
            "apiCall:function(m,p,b){_apiReqCounter+=1;var rid='api_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve,reject){_apiCallbacks[rid]={resolve:resolve,reject:reject};var bs=(b!==undefined&&b!==null)?JSON.stringify(b):'';tellevNative.apiCall(rid,String(m),String(p),bs);setTimeout(function(){if(_apiCallbacks[rid]){_apiCallbacks[rid].reject(new Error('API call timeout'));delete _apiCallbacks[rid];}},35000);});}," +
            "onApiResponse:function(rid,s,bj){var cb=_apiCallbacks[rid];if(!cb)return;delete _apiCallbacks[rid];var b;try{b=JSON.parse(bj);}catch(e){b={raw:bj};}cb.resolve({status:s,body:b});}," +
            "onVirtualApiRequest:function(rid,m,p,b){var parsed=null;try{parsed=b?JSON.parse(b):null;}catch(e){parsed=b;}var req={method:m,path:p,body:parsed};if(typeof window.onTellevVirtualApiRequest==='function'){Promise.resolve().then(function(){return window.onTellevVirtualApiRequest(req);}).then(function(r){r=r||{status:200,body:{}};tellevNative.virtualApiResult(rid,String(r.status||200),JSON.stringify(r.body||{}));}).catch(function(e){tellevNative.virtualApiResult(rid,'500',JSON.stringify({error:String(e)}));});}else{tellevNative.virtualApiResult(rid,'404',JSON.stringify({error:'No handler registered'}));}}," +
            "getSettings:function(){var r=tellevNative.getSettings();try{return JSON.parse(r);}catch(e){return{};}}," +
            "saveSettings:function(s){tellevNative.saveSettings(JSON.stringify(s||{}));}," +
            "requestPermission:function(p){return tellevNative.requestPermission(String(p));}," +
            "requestPermissionAsync:function(p){_apiReqCounter+=1;var rid='perm_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve){_permissionCallbacks[rid]=resolve;tellevNative.requestPermissionAsync(rid,String(p));});}," +
            "onPermissionResult:function(rid,g){var cb=_permissionCallbacks[rid];if(!cb)return;delete _permissionCallbacks[rid];cb(g);}," +
            "onSlashCommandsResult:function(rid,pj){var cb=_slashCallbacks[rid];if(!cb)return;delete _slashCallbacks[rid];var p;try{p=JSON.parse(pj);}catch(e){p={results:[]};}cb(p);}," +
            "log:function(l,m){tellevNative.log(String(l),String(m));}" +
            "};" +
            "var event_types={MESSAGE_RECEIVED:'MESSAGE_RECEIVED',MESSAGE_SENT:'MESSAGE_SENT',MESSAGE_EDITED:'MESSAGE_EDITED',MESSAGE_DELETED:'MESSAGE_DELETED',MESSAGE_SWIPED:'MESSAGE_SWIPED',CHARACTER_SELECTED:'CHARACTER_SELECTED',CHARACTER_CREATED:'CHARACTER_CREATED',CHARACTER_EDITED:'CHARACTER_EDITED',CHARACTER_DELETED:'CHARACTER_DELETED',CHARACTER_IMPORTED:'CHARACTER_IMPORTED',CHARACTER_EXPORTED:'CHARACTER_EXPORTED',CHAT_CHANGED:'CHAT_CHANGED',CHAT_CREATED:'CHAT_CREATED',CHAT_DELETED:'CHAT_DELETED',CHAT_IMPORTED:'CHAT_IMPORTED',CHAT_EXPORTED:'CHAT_EXPORTED',GENERATION_STARTED:'GENERATION_STARTED',GENERATION_ENDED:'GENERATION_ENDED',GENERATION_STOPPED:'GENERATION_STOPPED',WORLD_INFO_ACTIVATED:'WORLD_INFO_ACTIVATED',WORLD_INFO_CHANGED:'WORLD_INFO_CHANGED',EXTENSION_SETTINGS_OPENED:'EXTENSION_SETTINGS_OPENED',EXTENSION_SETTINGS_CLOSED:'EXTENSION_SETTINGS_CLOSED',GROUP_SELECTED:'GROUP_SELECTED',GROUP_CHAT_STARTED:'GROUP_CHAT_STARTED',APP_READY:'APP_READY',SETTINGS_CHANGED:'SETTINGS_CHANGED'};" +
            "var _stHandlers={};function _fireLocal(t,d){var a=_stHandlers[t];if(a){var e={type:t,detail:d};for(var i=0;i<a.length;i++){try{a[i](e);}catch(e){tellevNative.log('error','eventSource handler error: '+e);}}}}" +
            "var eventSource={on:function(t,c){if(!_stHandlers[t])_stHandlers[t]=[];_stHandlers[t].push(c);return c;},off:function(t,c){var a=_stHandlers[t];if(!a)return;var i=a.indexOf(c);if(i>=0)a.splice(i,1);},once:function(t,c){var w=function(e){eventSource.off(t,w);c(e);};return eventSource.on(t,w);},make:function(t,d){return{type:t,detail:d};},emit:function(t,d){tellevNative.emit(String(t),JSON.stringify(d||{}));_fireLocal(t,d);},_fireNative:function(n,pj){var p;try{p=JSON.parse(pj);}catch(e){p={};}_fireLocal(n,p);},_fireLocal:_fireLocal};" +
            "function _getContext(){var r;try{r=tellevNative.stGetContext();}catch(e){r='{}';}var c;try{c=JSON.parse(r);}catch(e){c={};}if(!c.chat)c.chat=[];if(!c.characters)c.characters=[];if(!c.name1)c.name1='User';if(!c.name2)c.name2='Character';if(!c.characterId)c.characterId='';if(!c.chatId)c.chatId='';return c;}" +
            "var SillyTavern={getContext:_getContext};" +
            "var getContext=_getContext;" +
            "var TavernHelper={addSlashCommand:function(n,c,o){o=o||{};_commandHandlers[n]=c;tellevNative.registerCommand(String(n),String(o.help||o.description||''),JSON.stringify(o.args||{}));},registerEvent:function(t,c){return eventSource.on(t,c);},getChatMessages:function(a){var c=_getContext();var m=c.chat||[];if(a===undefined||a===null||a==='')return m;if(typeof a==='number')return m[a]!==undefined?[m[a]]:[];if(typeof a==='string'){var ps=a.split('-');var s=parseInt(ps[0],10);var e=ps[1]!==undefined?parseInt(ps[1],10):s;if(isNaN(s))return[];if(isNaN(e))e=s;return m.slice(s,e+1);}return m;},setChatMessage:function(i,f,v){tellevNative.stSetChatMessage(String(i),String(f),String(v));},getVariables:function(){var r;try{r=tellevNative.stGetVariables();}catch(e){r='{}';}try{return JSON.parse(r);}catch(e){return{};}},setVariables:function(v){tellevNative.stSetVariables(JSON.stringify(v||{}));},replaceVariables:function(s){try{return tellevNative.stReplaceVariables(String(s));}catch(e){return String(s);}},getExtensionPrompt:function(){return'';},firstUserMessageIndex:function(){var c=_getContext();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&c.chat[i].is_user)return i;}return-1;},firstBotMessageIndex:function(){var c=_getContext();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&!c.chat[i].is_user)return i;}return-1;}};" +
            "function executeSlashCommandsWithOptions(cs){var a=Array.isArray(cs)?cs:[cs];_apiReqCounter+=1;var rid='slash_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve){_slashCallbacks[rid]=resolve;tellevNative.executeSlashCommands(rid,JSON.stringify(a));});}" +
            "function executeSlashCommands(cs){return executeSlashCommandsWithOptions(cs);}" +
            "function _isApiPath(u){var p=u.split('?')[0];if(p.indexOf('/api/')===0)return true;if(p.indexOf('extensions.tellev.local')>=0&&p.indexOf('/api/')>=0)return true;return false;}" +
            "window.fetch=function(input,init){try{var u=typeof input==='string'?input:((input&&input.url)||'');if(_isApiPath(u)){var p=u.split('extensions.tellev.local')[1]||u;var m=(init&&init.method)||'GET';var b=init&&init.body;var bo=null;if(b!==undefined&&b!==null){if(typeof b==='string'){try{bo=JSON.parse(b);}catch(e){bo=b;}}else{bo=b;}}return window.Tellev.apiCall(m,p,bo).then(function(r){var bt=(r.body!==undefined&&r.body!==null)?(typeof r.body==='string'?r.body:JSON.stringify(r.body)):'';return new Response(bt,{status:r.status,headers:{'Content-Type':'application/json'}});});}}catch(e){return Promise.reject(e);}return Promise.reject(new Error('Network access is not permitted for extensions'));};" +
            "window.SillyTavern=SillyTavern;window.getContext=getContext;window.eventSource=eventSource;window.event_types=event_types;window.TavernHelper=TavernHelper;window.executeSlashCommandsWithOptions=executeSlashCommandsWithOptions;window.executeSlashCommands=executeSlashCommands;" +
            "})();" +
            "\n</script>\n<script>\n__SCRIPT_SOURCE__\n</script></body></html>"
    }
}
