package app.tellev.core.extension

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.tellev.core.extension.host.ExtensionDiagnostics
import app.tellev.core.extension.host.ExtensionPromptStore
import app.tellev.core.extension.host.ExtensionRequestManager
import app.tellev.core.extension.host.ExtensionScriptTemplate
import app.tellev.core.prompt.DefaultMacroEngine
import app.tellev.core.prompt.MacroContext
import app.tellev.core.prompt.MacroEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    private val variableStore: VariableStore? = null,
    contextProvider: ExtensionContextProvider? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val commandTimeoutMs: Long = 10_000L,
    private val apiCallTimeoutMs: Long = 30_000L,
    private val scriptReadyTimeoutMs: Long = ExtensionScriptTemplate.DEFAULT_SCRIPT_READY_TIMEOUT_MS,
) : ExtensionHost {

    // ── state ──────────────────────────────────────────────────────────

    private val mutableEvents = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ExtensionEvent> = mutableEvents

    private val webViews = ConcurrentHashMap<String, WebView>()
    private val capabilityTokens = ConcurrentHashMap<String, String>()
    private val declaredPermissions = ConcurrentHashMap<String, Set<ExtensionPermission>>()
    private val slashCommands = ConcurrentHashMap<String, RegisteredCommand>()
    private val virtualRoutes = ConcurrentHashMap<String, RegisteredRoute>()

    private val requests = ExtensionRequestManager()
    private val diagnostics = ExtensionDiagnostics()
    private val promptStore = ExtensionPromptStore()

    private val settingsCache = ConcurrentHashMap<String, String>()
    private val settingsWriteLock = Any()
    private val settingsWrites = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val settingsFailures = mutableMapOf<String, Throwable>()

    init {
        // Wire the variable store into the macro engine so {{getvar::}} and
        // friends resolve through the same per-scope store as slash commands.
        (macroEngine as? DefaultMacroEngine)?.variableStore = variableStore
    }

    /** Built-in STScript command engine; handles /echo, /setvar, /getvar, etc. */
    private val slashCommandEngine = SlashCommandEngine(
        variableStore = variableStore,
        eventEmitter = { name, args ->
            scope.launch {
                mutableEvents.emit(
                    ExtensionEvent(
                        name = name,
                        payload = buildJsonObject {
                            putJsonArray("args") {
                                args.forEach { add(JsonPrimitive(it)) }
                            }
                        },
                    ),
                )
            }
        },
        // ST substitutes macros into every slash-command argument right before
        // execution (SlashCommandClosure.js:544,582). The variable store is
        // already wired into macroEngine above, so {{getvar::}}, {{char}},
        // {{user}} and friends resolve through the same state the prompt
        // builder sees.
        macroExpander = macroEngine?.let { engine ->
            { text: String ->
                runCatching { engine.expand(text, slashMacroContext()) }.getOrDefault(text)
            }
        },
        onUnimplementedCommand = { name ->
            scope.launch {
                mutableEvents.emit(
                    ExtensionEvent(
                        name = "extension_log",
                        payload = buildJsonObject {
                            put("level", "warn")
                            put(
                                "message",
                                "/$name 在 tellev 中没有实际实现：命令返回空结果，脚本会继续执行。",
                            )
                        },
                    ),
                )
            }
        },
    )

    /**
     * Macro context for slash-command arguments, built from the live
     * `getContext()` snapshot. Kept deliberately small — the prompt builder
     * owns the full context; this only needs the identity/chat macros that
     * scripts actually use in command arguments.
     */
    private fun slashMacroContext(): MacroContext {
        val snapshot = runCatching { _contextProvider?.snapshot() }.getOrNull()
            ?: return MacroContext()
        fun str(key: String): String =
            (snapshot[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val chat = snapshot["chat"] as? JsonArray
        fun messageText(predicate: (JsonObject) -> Boolean): String =
            chat?.asReversed()
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull(predicate)
                ?.get("mes")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        val character = snapshot["character"] as? JsonObject
        fun charField(key: String): String =
            (character?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return MacroContext(
            characterName = str("name2"),
            userName = str("name1"),
            characterDescription = charField("description"),
            characterPersonality = charField("personality"),
            characterScenario = charField("scenario"),
            lastMessage = messageText { true },
            lastUserMessage = messageText { (it["is_user"] as? JsonPrimitive)?.content == "true" },
            lastCharMessage = messageText { (it["is_user"] as? JsonPrimitive)?.content != "true" },
            lastMessageId = (snapshot["lastMessageId"] as? JsonPrimitive)?.content.orEmpty(),
            maxContextTokens = (snapshot["maxContext"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    /** Mutable so the UI layer can plug in a live context snapshot. */
    private var _contextProvider: ExtensionContextProvider? = contextProvider

    /** Update the context provider used to answer getContext() from JS. */
    override fun setContextProvider(provider: ExtensionContextProvider?) {
        _contextProvider = provider
    }

    /** Update the LOCAL-scope variable backend (current chat's variables). */
    override fun setLocalVariableBackend(backend: LocalVariableBackend?) {
        variableStore?.setLocalBackend(backend)
    }

    /** Per-message variable backend (chat[i].variables[swipe_id]) for the
     * TavernHelper `message` scope; plugged in by the chat UI layer. */
    @Volatile
    private var messageVariableBackend: MessageVariableBackend? = null

    /** Update the per-message variable backend (active chat's messages). */
    override fun setMessageVariableBackend(backend: MessageVariableBackend?) {
        messageVariableBackend = backend
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
                requests.pendingLoads.remove(manifest.id)?.cancel()
                requests.pendingLoadFailures.remove(manifest.id)

                val token = UUID.randomUUID().toString()
                capabilityTokens[manifest.id] = token
                declaredPermissions[manifest.id] = manifest.permissions
                requests.pendingLoads[manifest.id] = readySignal

                val settingsJson = settingsStore.getSettings(manifest.id)
                settingsCache[manifest.id] = json.encodeToString(JsonObject.serializer(), settingsJson)

                // Read built-in compat-module settings so they can be
                // injected into the WebView's _ejsFeatures and
                // _tavernHelperSettings globals.
                val ejsSettings = settingsStore.readEjsTemplateSettings()
                val ejsSettingsStr = json.encodeToString(
                    EjsTemplateSettings.serializer(), ejsSettings,
                )
                val tavernHelperSettings = settingsStore.readTavernHelperSettings()
                val tavernHelperSettingsStr = json.encodeToString(
                    TavernHelperSettings.serializer(), tavernHelperSettings,
                )

                val webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            CompatAssets.intercept(context, request.url.toString())

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            if (!request.isForMainFrame) return false
                            val blocked = !isAllowedExtensionNavigation(manifest.id, request.url.toString())
                            if (blocked) reportExtensionLog(
                                manifest.id,
                                "warning",
                                "Blocked module navigation to ${request.url}",
                            )
                            return blocked
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                            val target = url ?: return true
                            val blocked = !isAllowedExtensionNavigation(manifest.id, target)
                            if (blocked) reportExtensionLog(
                                manifest.id,
                                "warning",
                                "Blocked module navigation to $target",
                            )
                            return blocked
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            val level = if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "error" else "debug"
                            reportExtensionLog(manifest.id, level, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                            return true
                        }
                    }

                    addJavascriptInterface(Bridge(manifest.id, token), "tellevNative")

                    loadDataWithBaseURL(
                        "https://extensions.tellev.local/${manifest.id}/",
                        ExtensionScriptTemplate.buildExtensionHtml(
                            context = context,
                            extensionId = manifest.id,
                            token = token,
                            scriptSource = scriptSource,
                            ejsSettingsJson = ejsSettingsStr,
                            tavernHelperSettingsJson = tavernHelperSettingsStr,
                        ),
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
                    capabilities = ExtensionScriptTemplate.defaultCapabilities(manifest),
                    capabilityToken = token,
                )
            }
        } catch (e: Throwable) {
            requests.pendingLoads.remove(manifest.id, readySignal)
            requests.pendingLoadFailures.remove(manifest.id)
            capabilityTokens.remove(manifest.id)
            declaredPermissions.remove(manifest.id)
            settingsCache.remove(manifest.id)
            withContext(Dispatchers.Main) { webViews.remove(manifest.id)?.destroy() }
            throw e
        }

        val becameReady = withTimeoutOrNull(scriptReadyTimeoutMs) {
            readySignal.await()
            true
        } ?: false
        requests.pendingLoads.remove(manifest.id, readySignal)
        val scriptFailure = requests.pendingLoadFailures.remove(manifest.id)
        if (!becameReady || scriptFailure != null) {
            withContext(Dispatchers.Main) { webViews.remove(manifest.id)?.destroy() }
            capabilityTokens.remove(manifest.id)
            declaredPermissions.remove(manifest.id)
            settingsCache.remove(manifest.id)
            slashCommands.entries.removeIf { it.value.extensionId == manifest.id }
            virtualRoutes.entries.removeIf { it.value.extensionId == manifest.id }
            promptStore.clearExtension(manifest.id)
            val message = scriptFailure ?: "Module did not report ready within ${scriptReadyTimeoutMs} ms"
            publishLocalEvent(
                ExtensionEvent(
                    name = "extension_load_failed",
                    extensionId = manifest.id,
                    payload = buildJsonObject { put("message", message) },
                ),
            )
            throw IllegalStateException(message)
        }
        emit(ExtensionEvent(name = "extension_loaded", extensionId = manifest.id))
        return handle
    }

    override suspend fun unload(extensionId: String) {
        withContext(Dispatchers.Main) {
            webViews.remove(extensionId)?.destroy()
        }
        capabilityTokens.remove(extensionId)
        declaredPermissions.remove(extensionId)
        settingsCache.remove(extensionId)
        slashCommands.entries.removeIf { it.value.extensionId == extensionId }
        virtualRoutes.entries.removeIf { it.value.extensionId == extensionId }
        requests.cancelPendingForExtension(extensionId)
        promptStore.clearExtension(extensionId)
        permissionManager.clearExtension(extensionId)
        emit(ExtensionEvent(name = "extension_unloaded", extensionId = extensionId))
    }

    override suspend fun emit(event: ExtensionEvent) {
        publishExtensionEvent(event, excludeExtensionId = null)
    }

    override suspend fun flushWrites() {
        variableStore?.flushWrites()
        val accepted = synchronized(settingsWriteLock) { settingsWrites.values.toList() }
        accepted.forEach { it.await() }
    }

    private suspend fun publishExtensionEvent(
        event: ExtensionEvent,
        excludeExtensionId: String?,
    ) {
        publishLocalEvent(event)
        val payload = json.encodeToString(JsonObject.serializer(), event.payload)
        for (id in webViews.keys.toList()) {
            if (id == excludeExtensionId) continue
            evaluateRuntime(id, "window.__tellevDispatch(" + JsonPrimitive(event.name) + "," + JsonPrimitive(payload) + ")")
        }
    }

    suspend fun evaluateRuntime(extensionId: String, expression: String): String {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        requests.pendingEvaluations[id] = result
        requests.pendingEvaluationOwners[id] = extensionId
        try {
            withContext(Dispatchers.Main) {
                val view = webViews[extensionId] ?: error("Runtime unloaded: $extensionId")
                view.evaluateJavascript("Promise.resolve().then(()=>($expression)).then(" +
                    "v=>tellevNative.evaluationDone('$id',true,JSON.stringify(v??null))," +
                    "e=>tellevNative.evaluationDone('$id',false,String(e.stack||e)))", null)
            }
            return withTimeoutOrNull(apiCallTimeoutMs) { result.await() }
                ?: error("Runtime operation timed out: $extensionId")
        } finally {
            requests.pendingEvaluations.remove(id)
            requests.pendingEvaluationOwners.remove(id)
        }
    }

    override suspend fun reportHostEvent(event: ExtensionEvent) {
        publishLocalEvent(event)
    }

    override fun snapshotHostEvents(): List<ExtensionEvent> =
        diagnostics.snapshotHostEvents(requests.pendingPermissionEvents.values)

    override fun clearHostRuntimeLogs(extensionId: String?) {
        diagnostics.clearHostRuntimeLogs(extensionId)
    }

    override fun clearHostPromptDiagnostics() {
        diagnostics.clearHostPromptDiagnostics()
    }

    private suspend fun publishLocalEvent(event: ExtensionEvent) {
        diagnostics.rememberHostEvent(event)
        mutableEvents.emit(event)
    }

    override fun registerSlashCommand(extensionId: String, command: SlashCommand) {
        slashCommands[command.name] = RegisteredCommand(
            extensionId = extensionId,
            command = command.copy(extensionId = extensionId),
        )
    }

    override suspend fun executeStScript(script: String): SlashCommandResult {
        val result = runCatching { slashCommandEngine.execute(script) }
            .getOrElse { SlashCommandEngine.Result.error(it.message ?: "execution error") }
        return SlashCommandResult(
            handled = result.handled && !result.isError,
            output = result.output,
            metadata = buildJsonObject {
                put("isError", result.isError)
                put("isAborted", result.isAborted)
                if (result.errorMessage.isNotEmpty()) put("errorMessage", result.errorMessage)
            },
        )
    }

    override suspend fun executeSlashCommand(input: SlashCommandInput): SlashCommandResult {
        val registered = slashCommands[input.commandName]
            ?: return SlashCommandResult(handled = false, output = "Unknown command: " + input.commandName)

        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<SlashCommandResult>()
        requests.pendingCommands[requestId] = deferred
        requests.pendingCommandOwners[requestId] = registered.extensionId

        withContext(Dispatchers.Main) {
            val webView = webViews[registered.extensionId]
            if (webView != null) {
                val argsStr = json.encodeToString(JsonObject.serializer(), input.args)
                val js = "if(window.Tellev&&window.Tellev.onCommandExecute){" +
                    "window.Tellev.onCommandExecute(" +
                    "'" + ExtensionScriptTemplate.jsEscape(requestId) + "'," +
                    "'" + ExtensionScriptTemplate.jsEscape(input.commandName) + "'," +
                    "'" + ExtensionScriptTemplate.jsEscape(input.rawInput) + "'," +
                    "'" + ExtensionScriptTemplate.jsEscape(argsStr) + "'" +
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

        val result = try {
            withTimeoutOrNull(commandTimeoutMs) { deferred.await() }
        } finally {
            requests.pendingCommands.remove(requestId)
            requests.pendingCommandOwners.remove(requestId)
            deferred.cancel()
        }

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
            requests.pendingVirtualApi[requestId] = deferred
            requests.pendingVirtualApiOwners[requestId] = registered.extensionId

            withContext(Dispatchers.Main) {
                val webView = webViews[registered.extensionId]
                if (webView != null) {
                    val body = request.body ?: ""
                    val js = "if(window.Tellev&&window.Tellev.onVirtualApiRequest){" +
                        "window.Tellev.onVirtualApiRequest(" +
                        "'" + ExtensionScriptTemplate.jsEscape(requestId) + "'," +
                        "'" + ExtensionScriptTemplate.jsEscape(request.method) + "'," +
                        "'" + ExtensionScriptTemplate.jsEscape(request.path) + "'," +
                        "'" + ExtensionScriptTemplate.jsEscape(body) + "'" +
                        ");}"
                    webView.evaluateJavascript(js, null)
                } else {
                    deferred.complete(VirtualApiResponse(status = 503, body = "{\"error\":\"Extension not loaded\"}"))
                }
            }

            val result = try {
                withTimeoutOrNull(apiCallTimeoutMs) { deferred.await() }
            } finally {
                requests.pendingVirtualApi.remove(requestId)
                requests.pendingVirtualApiOwners.remove(requestId)
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
                argHints = ExtensionScriptTemplate.parseArgHints(registered.command.argumentSchema),
            )
        }

    override fun capabilityToken(extensionId: String): String? = capabilityTokens[extensionId]

    override fun deliverPermissionResult(requestId: String, granted: Boolean) {
        requests.pendingPermissions.remove(requestId)
        requests.pendingPermissionEvents.remove(requestId)
        val owner = requests.pendingPermissionOwners.remove(requestId)
        scope.launch(Dispatchers.Main) {
            val webView = owner?.let { webViews[it] }
            if (webView != null) {
                val js = "if(window.Tellev&&window.Tellev.onPermissionResult){" +
                    "window.Tellev.onPermissionResult('" + ExtensionScriptTemplate.jsEscape(requestId) + "'," + granted + ");}"
                runCatching { webView.evaluateJavascript(js, null) }
            }
        }
        scope.launch {
            mutableEvents.emit(
                ExtensionEvent(
                    name = "permission_resolved",
                    extensionId = owner,
                    payload = buildJsonObject {
                        put("requestId", requestId)
                        put("granted", granted)
                    },
                ),
            )
        }
    }

    override fun snapshotExtensionSettings(): JsonObject = buildJsonObject {
        for ((id, raw) in settingsCache) {
            val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: buildJsonObject { }
            put(id, parsed)
        }
    }

    override fun collectInjectedPrompts(): JsonObject = promptStore.collectInjectedPrompts()

    override fun updateCompatModuleSettings(
        ejsSettings: EjsTemplateSettings,
        tavernHelperSettings: TavernHelperSettings,
    ) {
        val ejsJson = json.encodeToString(EjsTemplateSettings.serializer(), ejsSettings)
        val thJson = json.encodeToString(TavernHelperSettings.serializer(), tavernHelperSettings)
        val safeEjs = ejsJson.replace("\\", "\\\\").replace("'", "\\'")
        val safeTh = thJson.replace("\\", "\\\\").replace("'", "\\'")
        val js = "if(typeof _ejsFeatures!=='undefined')" +
            "_ejsFeatures=Object.assign({},_ejsDefaultFeatures," + safeEjs + ");" +
            "if(typeof _tavernHelperSettings!=='undefined')" +
            "_tavernHelperSettings=" + safeTh + ";"
        scope.launch(Dispatchers.Main) {
            for ((_, webView) in webViews) {
                runCatching { webView.evaluateJavascript(js, null) }
            }
        }
    }

    // ── Bridge (called from JS via @JavascriptInterface) ───────────────

    inner class Bridge(
        private val extensionId: String,
        private val token: String,
    ) {
        private val runtimeGeneration = _contextProvider?.snapshot()?.get("__runtimeGeneration")

        private fun requireCurrentRuntime() {
            check(capabilityTokens[extensionId] == token &&
                _contextProvider?.snapshot()?.get("__runtimeGeneration") == runtimeGeneration) {
                "Expired runtime call from $extensionId"
            }
        }
        @JavascriptInterface
        fun emit(name: String, payloadJson: String) {
            val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            scope.launch { publishExtensionEvent(
                ExtensionEvent(name = name, extensionId = extensionId, payload = payload),
                excludeExtensionId = null,
            ) }
        }

        /** eventSource.emit already fired handlers synchronously in its source WebView. */
        @JavascriptInterface
        fun emitFromEventSource(name: String, payloadJson: String) {
            val payload = runCatching { json.parseToJsonElement(payloadJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            scope.launch { publishExtensionEvent(
                ExtensionEvent(name = name, extensionId = extensionId, payload = payload),
                excludeExtensionId = extensionId,
            ) }
        }

        @JavascriptInterface
        fun log(level: String, message: String) {
            scope.launch {
                publishLocalEvent(
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
            requests.pendingCommandOwners.remove(requestId)
            val deferred = requests.pendingCommands.remove(requestId) ?: return
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
            requests.pendingVirtualApiOwners.remove(requestId)
            val deferred = requests.pendingVirtualApi.remove(requestId) ?: return
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
                return dispatchExtensionGeneration(_contextProvider, options, json)
            }

            return apiRouter.route(request)
        }

        private suspend fun checkApiPermissions(path: String, requestId: String): Boolean {
            val required = requiredExtensionPermissionForPath(path) ?: return true
            if (required !in declaredPermissions[extensionId].orEmpty()) {
                deliverApiResponseToJs(
                    requestId,
                    VirtualApiResponse(403, body = "{\"error\":\"${required.name} permission is not declared by this module\"}"),
                )
                reportExtensionLog(extensionId, "warning", "Denied $path: ${required.name} is not declared")
                return false
            }
            if (permissionManager.hasPermission(extensionId, required)) return true
            deliverApiResponseToJs(
                requestId,
                VirtualApiResponse(403, body = "{\"error\":\"${required.name} permission not granted\"}"),
            )
            reportExtensionLog(extensionId, "warning", "Denied $path: ${required.name} permission not granted")
            return false
        }

        @JavascriptInterface
        fun getSettings(): String = settingsCache[extensionId] ?: "{}"

        @JavascriptInterface
        fun saveSettings(settingsJson: String) {
            requireCurrentRuntime()
            val obj = json.parseToJsonElement(settingsJson).jsonObject
            synchronized(settingsWriteLock) {
                settingsFailures[extensionId]?.let { throw IllegalStateException("扩展设置需要恢复：$extensionId", it) }
                val previous = settingsWrites[extensionId]
                val completion = CompletableDeferred<Unit>()
                settingsWrites[extensionId] = completion
                settingsCache[extensionId] = settingsJson
                val job = scope.launch {
                    try {
                        previous?.await()
                        settingsStore.saveSettings(extensionId, obj)
                        completion.complete(Unit)
                    } catch (error: Throwable) {
                        synchronized(settingsWriteLock) { settingsFailures[extensionId] = error }
                        completion.completeExceptionally(error)
                    }
                }
                job.invokeOnCompletion { error ->
                    if (error != null) {
                        synchronized(settingsWriteLock) { settingsFailures[extensionId] = error }
                        completion.completeExceptionally(error)
                    }
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
            if (perm !in declaredPermissions[extensionId].orEmpty()) return false
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
            if (perm !in declaredPermissions[extensionId].orEmpty()) {
                reportExtensionLog(
                    extensionId,
                    "warning",
                    "Denied undeclared permission request: ${perm.name}",
                )
                scope.launch(Dispatchers.Main) { deliverPermissionResultJs(requestId, false) }
                return
            }
            requests.pendingPermissions[requestId] = extensionId
            requests.pendingPermissionOwners[requestId] = extensionId
            scope.launch {
                val granted = permissionManager.hasPermission(extensionId, perm)
                if (granted) {
                    requests.pendingPermissions.remove(requestId)
                    requests.pendingPermissionOwners.remove(requestId)
                    withContext(Dispatchers.Main) { deliverPermissionResultJs(requestId, true) }
                } else {
                    val event =
                        ExtensionEvent(
                            name = "permission_requested",
                            extensionId = extensionId,
                            payload = buildJsonObject {
                                put("permission", perm.name)
                                put("requestId", requestId)
                            },
                        )
                    requests.pendingPermissionEvents[requestId] = event
                    mutableEvents.emit(event)
                }
            }
        }

        @JavascriptInterface
        fun getCapabilityToken(): String = token

        @JavascriptInterface
        fun extensionReady() {
            requests.pendingLoads.remove(extensionId)?.complete(Unit)
        }

        @JavascriptInterface
        fun extensionFailed(message: String) {
            val detail = message.trim().ifBlank { "Unknown JavaScript error" }
            if (requests.pendingLoads.containsKey(extensionId)) {
                requests.pendingLoadFailures[extensionId] = detail
                requests.pendingLoads[extensionId]?.complete(Unit)
            }
            reportExtensionLog(extensionId, "error", detail)
        }

        // ── SillyTavern / 酒馆助手 shim bridge methods ──────────────────

        private fun hasStorageBridgeAccess(operation: String): Boolean {
            requireCurrentRuntime()
            val allowed = hasPermission(ExtensionPermission.Storage.name)
            if (!allowed) {
                reportExtensionLog(extensionId, "warning", "Denied $operation: Storage permission not declared or granted")
            }
            return allowed
        }

        @JavascriptInterface
        fun stGetContext(): String {
            if (!hasStorageBridgeAccess("stGetContext")) return "{}"
            val snapshot = _contextProvider?.snapshot() ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), snapshot)
        }

        @JavascriptInterface
        fun stReplaceVariables(input: String): String {
            if (!hasStorageBridgeAccess("stReplaceVariables")) return input
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
            if (!hasStorageBridgeAccess("stGetVariables")) return "{}"
            val vars = variableStore?.globalObject() ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stSetVariables(varsJson: String) {
            if (!hasStorageBridgeAccess("stSetVariables")) return
            val obj = runCatching { json.parseToJsonElement(varsJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            variableStore?.replaceGlobal(obj)
        }

        @JavascriptInterface
        fun stGetVariablesForScope(scopeName: String): String {
            if (!hasStorageBridgeAccess("stGetVariablesForScope")) return "{}"
            val vars = when (scopeName.trim().lowercase()) {
                "chat", "local" -> variableStore?.localObject()
                "global" -> variableStore?.globalObject()
                else -> throw IllegalArgumentException("Unsupported variable scope: $scopeName")
            } ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stSetVariablesForScope(scopeName: String, varsJson: String) {
            if (!hasStorageBridgeAccess("stSetVariablesForScope")) return
            val obj = runCatching { json.parseToJsonElement(varsJson).jsonObject }
                .getOrElse { throw IllegalArgumentException("Variables must be a JSON object") }
            when (scopeName.trim().lowercase()) {
                "chat", "local" -> variableStore?.replaceLocal(obj)
                "global" -> variableStore?.replaceGlobal(obj)
                else -> throw IllegalArgumentException("Unsupported variable scope: $scopeName")
            }
        }

        /**
         * Resolve a TavernHelper message_id to a chat message index.
         * Negative numeric IDs count from the complete chat end. The JavaScript
         * adapter resolves the distinct default/latest read semantics.
         */
        private fun resolveMessageIndex(messageId: Int): Int? {
            val backend = messageVariableBackend ?: return null
            val count = backend.messageCount()
            if (count <= 0) return null
            return when {
                messageId < 0 -> (count + messageId).takeIf { it >= 0 }
                messageId < count -> messageId
                else -> null
            }
        }

        @JavascriptInterface
        fun stGetMessageVariables(messageId: Int): String {
            if (!hasStorageBridgeAccess("stGetMessageVariables")) return "{}"
            val index = resolveMessageIndex(messageId)
                ?: throw IllegalArgumentException("Invalid message_id: $messageId")
            val vars = messageVariableBackend?.messageVariables(index) ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stSetMessageVariables(messageId: Int, varsJson: String) {
            if (!hasStorageBridgeAccess("stSetMessageVariables")) return
            val obj = runCatching { json.parseToJsonElement(varsJson).jsonObject }
                .getOrElse { throw IllegalArgumentException("Variables must be a JSON object") }
            val index = resolveMessageIndex(messageId)
                ?: throw IllegalArgumentException("Invalid message_id: $messageId")
            messageVariableBackend?.replaceMessageVariables(index, obj)
        }

        @JavascriptInterface
        fun stGetAllVariables(): String {
            if (!hasStorageBridgeAccess("stGetAllVariables")) return "{}"
            // js-slash-runner's aggregate view includes the most recent
            // message-scope variables. MVU cards keep stat_data there; leaving
            // it out made front-end scripts see an empty state even though the
            // prompt macro could read the message snapshot.
            val latestMessageVariables = messageVariableBackend
                ?.lastIndexWithVariables()
                ?.takeIf { it >= 0 }
                ?.let { messageVariableBackend?.messageVariables(it) }
            val vars = buildJsonObject {
                variableStore?.mergedObject()?.forEach { (key, value) -> put(key, value) }
                latestMessageVariables?.forEach { (key, value) -> put(key, value) }
            }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stGetLocalVariables(): String {
            if (!hasStorageBridgeAccess("stGetLocalVariables")) return "{}"
            val vars = variableStore?.localObject() ?: buildJsonObject { }
            return json.encodeToString(JsonObject.serializer(), vars)
        }

        @JavascriptInterface
        fun stSetLocalVariables(varsJson: String) {
            if (!hasStorageBridgeAccess("stSetLocalVariables")) return
            val obj = runCatching { json.parseToJsonElement(varsJson).jsonObject }
                .getOrElse { buildJsonObject { } }
            variableStore?.replaceLocal(obj)
        }

        @JavascriptInterface
        fun stInjectPrompt(
            id: String,
            content: String,
            position: Int,
            depth: Int,
            role: String,
        ) {
            promptStore.storeInjectedPrompt(
                extensionId = extensionId,
                id = id,
                content = content,
                position = position,
                depth = depth,
                role = role,
                shouldScan = false,
            )
        }

        @JavascriptInterface
        fun stInjectPromptWithOptions(
            id: String,
            content: String,
            position: Int,
            depth: Int,
            role: String,
            shouldScan: Boolean,
        ) {
            promptStore.storeInjectedPrompt(
                extensionId = extensionId,
                id = id,
                content = content,
                position = position,
                depth = depth,
                role = role,
                shouldScan = shouldScan,
            )
        }

        @JavascriptInterface
        fun stUninjectPrompt(id: String) {
            promptStore.uninjectPrompt(extensionId, id)
        }

        @JavascriptInterface
        fun stGetInjectedPrompts(): String = promptStore.getInjectedPromptsJson(extensionId, json)

        @JavascriptInterface
        fun evaluationDone(id: String, ok: Boolean, value: String) {
            if (requests.pendingEvaluationOwners[id] != extensionId) return
            val result = requests.pendingEvaluations[id] ?: return
            if (ok) result.complete(value) else result.completeExceptionally(IllegalStateException(value))
        }

        @JavascriptInterface
        fun stSetChatMessages(requestId: String, messagesJson: String, optionsJson: String) {
            if (!hasStorageBridgeAccess("stSetChatMessages")) return
            val provider = _contextProvider
            val chatId = provider?.snapshot()?.get("chatId")
            scope.launch {
                val failure = runCatching {
                    requireCurrentRuntime()
                    check(provider === _contextProvider && provider?.snapshot()?.get("chatId") == chatId) { "Chat changed during write" }
                    val messages = json.parseToJsonElement(messagesJson).jsonArray
                    val options = json.parseToJsonElement(optionsJson).jsonObject
                    check(provider?.setChatMessages(messages, options) == true) { "Message update failed" }
                }.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    webViews[extensionId]?.evaluateJavascript(
                        "window.__tellevWriteDone(" + JsonPrimitive(requestId) + "," +
                            (failure?.message?.let { JsonPrimitive(it).toString() } ?: "null") + ")", null)
                }
            }
        }

        @JavascriptInterface
        fun stSetChatMessage(index: String, field: String, value: String) {
            if (!hasStorageBridgeAccess("stSetChatMessage")) return
            val provider = _contextProvider
            scope.launch {
                requireCurrentRuntime()
                val messageIndex = index.toIntOrNull()
                if (messageIndex != null && provider?.setChatMessage(messageIndex, field, value) == true) {
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
                    val escaped = ExtensionScriptTemplate.jsEscape(json.encodeToString(JsonObject.serializer(), payload))
                    val webView = webViews[extensionId]
                    webView?.evaluateJavascript(
                        "if(window.Tellev&&window.Tellev.onSlashCommandsResult){" +
                            "window.Tellev.onSlashCommandsResult('" + ExtensionScriptTemplate.jsEscape(requestId) + "','" + escaped + "');}",
                        null,
                    )
                }
            }
        }

        private suspend fun deliverApiResponseToJs(requestId: String, response: VirtualApiResponse) {
            withContext(Dispatchers.Main) {
                val webView = webViews[extensionId] ?: return@withContext
                val escapedReqId = ExtensionScriptTemplate.jsEscape(requestId)
                val escapedBody = ExtensionScriptTemplate.jsEscape(response.body)
                val js = "if(window.Tellev&&window.Tellev.onApiResponse){" +
                    "window.Tellev.onApiResponse('" + escapedReqId + "'," + response.status + ",'" + escapedBody + "');}"
                webView.evaluateJavascript(js, null)
            }
        }

        private suspend fun deliverPermissionResultJs(requestId: String, granted: Boolean) {
            withContext(Dispatchers.Main) {
                val webView = webViews[extensionId] ?: return@withContext
                val js = "if(window.Tellev&&window.Tellev.onPermissionResult){" +
                    "window.Tellev.onPermissionResult('" + ExtensionScriptTemplate.jsEscape(requestId) + "'," + granted + ");}"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun reportExtensionLog(extensionId: String, level: String, message: String) {
        scope.launch {
            publishLocalEvent(
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

    private data class RegisteredCommand(val extensionId: String, val command: SlashCommand)
    private data class RegisteredRoute(val extensionId: String, val route: VirtualApiRoute)

    companion object {
        const val TAVERN_HELPER_VARS_KEY = "_tavern_helper_global_variables"

        @JvmField
        internal val HTML_TEMPLATE: String = ExtensionScriptTemplate.HTML_TEMPLATE

        internal val TAVERN_HELPER_CONTRACT_OVERRIDES: String =
            ExtensionScriptTemplate.TAVERN_HELPER_CONTRACT_OVERRIDES

        internal val EXTENSION_LOAD_GUARDS: String =
            ExtensionScriptTemplate.EXTENSION_LOAD_GUARDS

        internal const val DEFAULT_SCRIPT_READY_TIMEOUT_MS: Long = 30_000L

        internal val TAVERN_CONTEXT_TICK_CACHE_JS: String =
            ExtensionScriptTemplate.TAVERN_CONTEXT_TICK_CACHE_JS
    }
}
