package app.tellev.core.extension

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.ArrayDeque
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
    private val scriptReadyTimeoutMs: Long = DEFAULT_SCRIPT_READY_TIMEOUT_MS,
) : ExtensionHost {

    // ── state ──────────────────────────────────────────────────────────

    private val mutableEvents = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<ExtensionEvent> = mutableEvents

    private val webViews = ConcurrentHashMap<String, WebView>()
    private val capabilityTokens = ConcurrentHashMap<String, String>()
    private val declaredPermissions = ConcurrentHashMap<String, Set<ExtensionPermission>>()
    private val slashCommands = ConcurrentHashMap<String, RegisteredCommand>()
    private val virtualRoutes = ConcurrentHashMap<String, RegisteredRoute>()

    private val pendingEvaluations = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val pendingEvaluationOwners = ConcurrentHashMap<String, String>()

    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<SlashCommandResult>>()
    private val pendingCommandOwners = ConcurrentHashMap<String, String>()
    private val pendingVirtualApi = ConcurrentHashMap<String, CompletableDeferred<VirtualApiResponse>>()
    private val pendingVirtualApiOwners = ConcurrentHashMap<String, String>()
    private val pendingPermissions = ConcurrentHashMap<String, String>()
    private val pendingPermissionOwners = ConcurrentHashMap<String, String>()
    private val pendingPermissionEvents = ConcurrentHashMap<String, ExtensionEvent>()
    private val pendingLoads = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val pendingLoadFailures = ConcurrentHashMap<String, String>()
    private val recentRuntimeEvents = ArrayDeque<ExtensionEvent>()
    private val recentRuntimeEventsLock = Any()
    @Volatile
    private var latestPromptDiagnostics: ExtensionEvent? = null

    private val settingsCache = ConcurrentHashMap<String, String>()
    private val settingsWriteLock = Any()
    private val settingsWrites = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val settingsFailures = mutableMapOf<String, Throwable>()

    /**
     * Extension-injected prompts authored by loaded extensions via the ST
     * `injectPrompts` API. Outer key: extension id; inner key: the prompt id
     * passed by the extension. Collected by [collectInjectedPrompts] and
     * fed into the prompt engine through `metadata["injectedPrompts"]`.
     */
    private val injectedPrompts =
        ConcurrentHashMap<String, ConcurrentHashMap<String, InjectedPrompt>>()

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
                                args.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
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
                pendingLoads.remove(manifest.id)?.cancel()
                pendingLoadFailures.remove(manifest.id)

                val token = UUID.randomUUID().toString()
                capabilityTokens[manifest.id] = token
                declaredPermissions[manifest.id] = manifest.permissions
                pendingLoads[manifest.id] = readySignal

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
                        buildExtensionHtml(
                    manifest.id,
                    token,
                    scriptSource,
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
                    capabilities = defaultCapabilities(manifest),
                    capabilityToken = token,
                )
            }
        } catch (e: Throwable) {
            pendingLoads.remove(manifest.id, readySignal)
            pendingLoadFailures.remove(manifest.id)
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
        pendingLoads.remove(manifest.id, readySignal)
        val scriptFailure = pendingLoadFailures.remove(manifest.id)
        if (!becameReady || scriptFailure != null) {
            withContext(Dispatchers.Main) { webViews.remove(manifest.id)?.destroy() }
            capabilityTokens.remove(manifest.id)
            declaredPermissions.remove(manifest.id)
            settingsCache.remove(manifest.id)
            slashCommands.entries.removeIf { it.value.extensionId == manifest.id }
            virtualRoutes.entries.removeIf { it.value.extensionId == manifest.id }
            injectedPrompts.remove(manifest.id)
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
        cancelPending(pendingEvaluationOwners, pendingEvaluations, extensionId)
        cancelPending(pendingCommandOwners, pendingCommands, extensionId)
        cancelPending(pendingVirtualApiOwners, pendingVirtualApi, extensionId)
        pendingPermissions.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingPermissionOwners.entries.removeIf { (_, owner) -> owner == extensionId }
        pendingPermissionEvents.entries.removeIf { (_, event) -> event.extensionId == extensionId }
        pendingLoads.remove(extensionId)?.cancel()
        pendingLoadFailures.remove(extensionId)
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
        pendingEvaluations[id] = result
        pendingEvaluationOwners[id] = extensionId
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
            pendingEvaluations.remove(id)
            pendingEvaluationOwners.remove(id)
        }
    }

    override suspend fun reportHostEvent(event: ExtensionEvent) {
        publishLocalEvent(event)
    }

    override fun snapshotHostEvents(): List<ExtensionEvent> {
        val runtime = synchronized(recentRuntimeEventsLock) { recentRuntimeEvents.toList() }
        return buildList {
            addAll(runtime)
            latestPromptDiagnostics?.let(::add)
            addAll(pendingPermissionEvents.values)
        }
    }

    override fun clearHostRuntimeLogs(extensionId: String?) {
        synchronized(recentRuntimeEventsLock) {
            recentRuntimeEvents.removeIf { event ->
                event.name == "extension_log" &&
                    (extensionId == null || event.extensionId == extensionId)
            }
        }
    }

    override fun clearHostPromptDiagnostics() {
        latestPromptDiagnostics = null
    }

    private suspend fun publishLocalEvent(event: ExtensionEvent) {
        rememberHostEvent(event)
        mutableEvents.emit(event)
    }

    private fun rememberHostEvent(event: ExtensionEvent) {
        if (event.name == "prompt_diagnostics") {
            latestPromptDiagnostics = event
            return
        }
        if (event.name !in RUNTIME_HISTORY_EVENTS) return
        synchronized(recentRuntimeEventsLock) {
            recentRuntimeEvents.addLast(event)
            while (recentRuntimeEvents.size > MAX_RUNTIME_EVENT_HISTORY) {
                recentRuntimeEvents.removeFirst()
            }
        }
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
        pendingPermissionEvents.remove(requestId)
        val owner = pendingPermissionOwners.remove(requestId)
        scope.launch(Dispatchers.Main) {
            val webView = owner?.let { webViews[it] }
            if (webView != null) {
                val js = "if(window.Tellev&&window.Tellev.onPermissionResult){" +
                    "window.Tellev.onPermissionResult('" + jsEscape(requestId) + "'," + granted + ");}"
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
                        put("shouldScan", prompt.shouldScan)
                    },
                )
            }
        }
    }

    override fun updateCompatModuleSettings(
        ejsSettings: EjsTemplateSettings,
        tavernHelperSettings: TavernHelperSettings,
    ) {
        val ejsJson = json.encodeToString(EjsTemplateSettings.serializer(), ejsSettings)
        val thJson = json.encodeToString(TavernHelperSettings.serializer(), tavernHelperSettings)
        val safeEjs = ejsJson.replace("\\", "\\\\").replace("'", "\'")
        val safeTh = thJson.replace("\\", "\\\\").replace("'", "\'")
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

    /** A single prompt injected by an extension. */
    private data class InjectedPrompt(
        val value: String,
        val position: Int,
        val depth: Int,
        val role: String,
        val shouldScan: Boolean = false,
    )

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
            pendingPermissions[requestId] = extensionId
            pendingPermissionOwners[requestId] = extensionId
            scope.launch {
                val granted = permissionManager.hasPermission(extensionId, perm)
                if (granted) {
                    pendingPermissions.remove(requestId)
                    pendingPermissionOwners.remove(requestId)
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
                    pendingPermissionEvents[requestId] = event
                    mutableEvents.emit(event)
                }
            }
        }

        @JavascriptInterface
        fun getCapabilityToken(): String = token

        @JavascriptInterface
        fun extensionReady() {
            pendingLoads.remove(extensionId)?.complete(Unit)
        }

        @JavascriptInterface
        fun extensionFailed(message: String) {
            val detail = message.trim().ifBlank { "Unknown JavaScript error" }
            if (pendingLoads.containsKey(extensionId)) {
                pendingLoadFailures[extensionId] = detail
                pendingLoads[extensionId]?.complete(Unit)
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
            storeInjectedPrompt(id, content, position, depth, role, shouldScan = false)
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
            storeInjectedPrompt(id, content, position, depth, role, shouldScan)
        }

        private fun storeInjectedPrompt(
            id: String,
            content: String,
            position: Int,
            depth: Int,
            role: String,
            shouldScan: Boolean,
        ) {
            if (id.isBlank()) return
            val roleNorm = role.trim().ifBlank { "system" }.lowercase()
            val map = injectedPrompts.computeIfAbsent(extensionId) { ConcurrentHashMap() }
            map[id] = InjectedPrompt(
                value = content,
                position = position,
                depth = if (depth >= 0) depth else 0,
                role = roleNorm,
                shouldScan = shouldScan,
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
                        put("shouldScan", prompt.shouldScan)
                    })
                }
            }
            return json.encodeToString(JsonObject.serializer(), obj)
        }

        @JavascriptInterface
        fun evaluationDone(id: String, ok: Boolean, value: String) {
            if (pendingEvaluationOwners[id] != extensionId) return
            val result = pendingEvaluations[id] ?: return
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
                // 写后快照内容已变，清掉 stGetContext 缓存（键不含消息体）。
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

    // ── HTML template ──────────────────────────────────────────────────

    private fun buildExtensionHtml(
        extensionId: String,
        token: String,
        scriptSource: String,
        ejsSettingsJson: String,
        tavernHelperSettingsJson: String,
    ): String {
        val safeId = jsEscape(extensionId)
        val safeToken = jsEscape(token)
        val safeScript = sanitizeScriptSource(scriptSource)
        val safeEjsJson = ejsSettingsJson.replace("\\", "\\\\").replace("'", "\'")
        val safeThJson = tavernHelperSettingsJson.replace("\\", "\\\\").replace("'", "\'")
        // Detect ES module syntax (import/export at line start) so we can use
        // <script type="module"> and allow external CDN imports.  TavernHelper
        // scripts like MVU/ZOD use `import 'https://...'` which requires module
        // mode and a CSP that permits external script sources.
        val useModule = Regex(
            """^\s*(import\s|export\s|import\{|export\{)""",
            RegexOption.MULTILINE,
        ).containsMatchIn(scriptSource)
        // Module scripts are deferred, so the ready call must be inside the
        // module to fire after imports resolve. For regular scripts it runs in
        // a separate <script> after the extension code.
        val extensionScript = if (useModule) {
            "<script type=\"module\">\n$safeScript\nwindow.__tellevReady().then(()=>tellevNative.extensionReady()).catch(e=>tellevNative.extensionFailed(String(e.stack||e)))\n</script>"
        } else {
            "<script>\n$safeScript\n</script><script>window.__tellevReady().then(()=>tellevNative.extensionReady()).catch(e=>tellevNative.extensionFailed(String(e.stack||e)))</script>"
        }
        return HTML_TEMPLATE
            .replace("__EXTENSION_ID__", safeId)
            .replace("__HOST_ADAPTER__", CompatAssets.source(context, "chat.js") + "\n" + CompatAssets.source(context, "host.js"))
            .replace("__TOKEN__", safeToken)
            .replace("__SHOWDOWN_SOURCE__", MarkdownScripts.showdownSource(context))
            .replace("__EJS_SETTINGS_JSON__", safeEjsJson)
            .replace("__TAVERN_HELPER_SETTINGS_JSON__", safeThJson)
            .replace("__EXTENSION_SCRIPT__", extensionScript)
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
     *
     * We intentionally escape only the literal sequences that would terminate
     * the script block; replacing every "</" globally corrupts valid JS such
     * as `a < /b/.test(c)` or `</` inside regex literals.
     */
    private fun sanitizeScriptSource(src: String): String = src
        .replace(Regex("</script", RegexOption.IGNORE_CASE), "<\\/script")
        .replace("<!--", "<\\!--")

    private data class RegisteredCommand(val extensionId: String, val command: SlashCommand)
    private data class RegisteredRoute(val extensionId: String, val route: VirtualApiRoute)

    companion object {
        const val TAVERN_HELPER_VARS_KEY = "_tavern_helper_global_variables"
        private const val MAX_RUNTIME_EVENT_HISTORY = 200
        private val RUNTIME_HISTORY_EVENTS =
            setOf("extension_loaded", "extension_unloaded", "extension_load_failed", "extension_log")

        internal const val TAVERN_HELPER_CONTRACT_OVERRIDES: String =
            "var _thScopedVariables={script:{},character:{},preset:{},message:{},extension:{}};" +
            "function _thVariableScope(opt){" +
                "var type=(opt===undefined||opt===null)?'chat':(typeof opt==='string'?opt:opt.type);" +
                "type=String(type||'').toLowerCase();" +
                "if(type==='chat'||type==='local')return'local';if(type==='global')return'global';" +
                "if(Object.prototype.hasOwnProperty.call(_thScopedVariables,type))return type;" +
                "throw new Error('Unsupported TavernHelper variable scope: '+type);}" +
            "function _thMessageId(opt){var mid=(opt&&opt.message_id!==undefined)?opt.message_id:-1;if(mid==='latest')mid=-1;return Number(mid);}" +
            "TavernHelper.getVariables=function(opt){var s=_thVariableScope(opt);if(s==='message')return JSON.parse(tellevNative.stGetMessageVariables(_thMessageId(opt))||'{}');if(_thScopedVariables[s])return _thScopedVariables[s];return JSON.parse(tellevNative.stGetVariablesForScope(s)||'{}');};" +
            "TavernHelper.getAllVariables=function(opt){if(opt!==undefined&&opt!==null)return TavernHelper.getVariables(opt);return JSON.parse(tellevNative.stGetAllVariables()||'{}');};" +
            "TavernHelper.replaceVariables=function(vars,opt){var s=_thVariableScope(opt);if(s==='message'){tellevNative.stSetMessageVariables(_thMessageId(opt),JSON.stringify(vars||{}));return;}if(_thScopedVariables[s]){_thScopedVariables[s]=vars||{};return;}tellevNative.stSetVariablesForScope(s,JSON.stringify(vars||{}));};" +
            "TavernHelper.setVariables=function(vars,opt){return TavernHelper.replaceVariables(vars,opt);};" +
            // js-slash-runner exposes these as top-level TavernHelper methods;
            // tellev's object literal placed them under builtin.* (audit M9).
            "['deleteWorldbook','updateWorldbookWith','createWorldbookEntries','deleteWorldbookEntries','getGlobalWorldbookNames','rebindGlobalWorldbooks','getCharWorldbookNames','rebindCharWorldbooks','getChatWorldbookName','rebindChatWorldbook','getOrCreateChatWorldbook','createChatMessages','deleteChatMessages','rotateChatMessages','formatAsDisplayedMessage','retrieveDisplayedMessage','refreshOneMessage','createOrReplacePreset'].forEach(function(n){if(TavernHelper.builtin&&TavernHelper.builtin[n]!==undefined)TavernHelper[n]=TavernHelper.builtin[n];});" +
            "TavernHelper.updateVariablesWith=function(updater,opt){var v=TavernHelper.getVariables(opt);var r=updater(v);r=r||v;TavernHelper.replaceVariables(r,opt);return r;};" +
            // js-slash-runner merges with lodash mergeWith (variables.ts:241),
            // i.e. recursively. A shallow assign wiped every sibling key under
            // a nested object, which is exactly how variable cards do partial
            // state updates.
            "function _thDeepMerge(base,extra,newWins){if(extra===undefined||extra===null)return base;" +
                "if(typeof base!=='object'||base===null||Array.isArray(base)||typeof extra!=='object'||extra===null||Array.isArray(extra))" +
                    "return newWins?extra:(base===undefined?extra:base);" +
                "var out={};var k;for(k in base)out[k]=base[k];" +
                "for(k in extra){out[k]=Object.prototype.hasOwnProperty.call(base,k)?_thDeepMerge(base[k],extra[k],newWins):extra[k];}" +
                "return out;}" +
            "TavernHelper.insertOrAssignVariables=function(nv,opt){var v=TavernHelper.getVariables(opt);var merged=_thDeepMerge(v,nv||{},true);TavernHelper.replaceVariables(merged,opt);return merged;};" +
            // insertVariables keeps the *existing* value on conflict
            // (variables.ts:261 merges {} <- new <- old) and returns the result.
            "TavernHelper.insertVariables=function(nv,opt){var v=TavernHelper.getVariables(opt);var merged=_thDeepMerge(v,nv||{},false);TavernHelper.replaceVariables(merged,opt);return merged;};" +
            "TavernHelper.deleteVariable=function(path,opt){var v=TavernHelper.getVariables(opt);var parts=String(path).split('.');var obj=v;for(var i=0;i<parts.length-1;i++){if(obj[parts[i]]===undefined||obj[parts[i]]===null)return{variables:v,delete_occurred:false};obj=obj[parts[i]];}var occurred=Object.prototype.hasOwnProperty.call(obj,parts[parts.length-1]);delete obj[parts[parts.length-1]];TavernHelper.replaceVariables(v,opt);return{variables:v,delete_occurred:occurred};};" +
            // js-slash-runner _bind.* internal APIs (index.ts:219-265): wire to the
            // public TavernHelper/event functions so scripts touching
            // TavernHelper._bind (e.g. MVU-style variable frameworks) don't crash.
            "TavernHelper._bind={" +
                "_eventOn:TavernHelper.eventOn,_eventOnce:TavernHelper.eventOnce,_eventEmit:TavernHelper.eventEmit,_eventMakeLast:TavernHelper.eventMakeLast,_eventMakeFirst:TavernHelper.eventMakeFirst,_eventEmitAndWait:TavernHelper.eventEmitAndWait,_eventRemoveListener:TavernHelper.eventRemoveListener,_eventClearEvent:TavernHelper.eventClearEvent,_eventClearListener:TavernHelper.eventClearListener,_eventClearAll:TavernHelper.eventClearAll,_eventOnButton:function(){return '';},_getButtonEvent:TavernHelper.getButtonEvent," +
                "_getVariables:function(o){return TavernHelper.getVariables(o);},_getAllVariables:function(){return TavernHelper.getAllVariables();},_replaceVariables:function(v,o){return TavernHelper.replaceVariables(v,o);},_updateVariablesWith:function(u,o){return TavernHelper.updateVariablesWith(u,o);},_insertOrAssignVariables:function(v,o){return TavernHelper.insertOrAssignVariables(v,o);},_insertVariables:function(v,o){return TavernHelper.insertVariables(v,o);},_deleteVariable:function(p,o){return TavernHelper.deleteVariable(p,o);}," +
                "_getScriptId:function(){return TavernHelper.getTavernHelperExtensionId();},_getIframeName:function(){return TavernHelper.getTavernHelperExtensionId();},_getCurrentMessageId:function(){var c=_getContextTick();return (c.chat||[]).length-1;}," +
                "_initializeGlobal:function(){},_waitGlobalInitialized:function(){return Promise.resolve();},_reloadIframe:function(){},_errorCatched:function(fn){return fn;}," +
                "_registerMacroLike:function(){return{unregister:function(){}};}," +
                "_getScriptButtons:TavernHelper.getScriptButtons,_replaceScriptButtons:TavernHelper.replaceScriptButtons,_updateScriptButtonsWith:TavernHelper.updateScriptButtonsWith,_appendInexistentScriptButtons:function(b){return b||[];},_getScriptName:function(){return TavernHelper.getTavernHelperExtensionId();},_getScriptInfo:function(){return{};},_replaceScriptInfo:function(){}};" +
            "TavernHelper.injectPrompts=function(promptsOrId,contentOrOptions,legacyOptions){" +
                "var prompts,options,isLegacy=!Array.isArray(promptsOrId);" +
                "if(isLegacy){options=legacyOptions||{};prompts=[{id:promptsOrId,content:contentOrOptions,position:options.position,depth:options.depth,role:options.role}];}" +
                "else{prompts=promptsOrId;options=contentOrOptions||{};}" +
                "var ids=[];prompts.forEach(function(p){if(!p||typeof p!=='object')throw new Error('injectPrompts expects prompt objects');" +
                    "var id=(p.id===undefined||p.id===null||String(p.id)==='')?TavernHelper.builtin.uuidv4():String(p.id);" +
                    "var position=isLegacy?(p.position===undefined?0:(isNaN(Number(p.position))?0:Number(p.position))):(p.position==='none'?-1:1);" +
                    "var depth=p.depth===undefined?(isLegacy?4:0):Number(p.depth);var role=String(p.role||'system').toLowerCase();" +
                    "var allowed=true;" +
                    "if(typeof p.filter==='function'){" +
                        "try{allowed=Boolean(p.filter());}catch(e){allowed=true;}" +
                        "tellevNative.log('info','injectPrompts filter evaluated once at inject time for '+id+' (ST re-evaluates per generation)');" +
                    "}else if(p.filter!==undefined){allowed=Boolean(p.filter);}" +
                    "if(!allowed)return;" +
                    "var shouldScan=Boolean(p.should_scan!==undefined?p.should_scan:p.shouldScan);" +
                    "tellevNative.stInjectPromptWithOptions(id,String(p.content||''),position,depth,role,shouldScan);ids.push(id);});" +
                "var deleted=false;var uninject=function(){if(deleted)return;TavernHelper.uninjectPrompts(ids);deleted=true;};" +
                "if(options.once){eventSource.once(event_types.GENERATION_ENDED,uninject);eventSource.once(event_types.GENERATION_STOPPED,uninject);}" +
                "return{uninject:uninject};};" +
            "TavernHelper.uninjectPrompts=function(ids){if(!Array.isArray(ids))ids=[ids];ids.forEach(function(id){if(id!==undefined&&id!==null)tellevNative.stUninjectPrompt(String(id));});};" +
            "function _thPresetCategory(opt){return String((opt&&opt.category)||'openai').toLowerCase();}" +
            "TavernHelper.getPresetNames=function(opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('GET','/api/presets').then(function(r){var a=(r.body&&r.body.presets)||[];return ['in_use'].concat(a.filter(function(p){return String(p.category||'').toLowerCase()===c;}).map(function(p){return p.id||p.name;}));});};" +
            "TavernHelper.getLoadedPresetName=function(opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('GET','/api/presets').then(function(r){return (r.body&&r.body.selected&&r.body.selected[c])||'';});};" +
            "TavernHelper.getPreset=function(name,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('GET','/api/presets/'+encodeURIComponent(c)+'/'+encodeURIComponent(String(name))).then(function(r){return r.body||null;});};" +
            "TavernHelper.loadPreset=function(name,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/load',{category:c,name:String(name)}).then(function(r){eventSource.emit(event_types.PRESET_CHANGED,String(name));eventSource.emit(event_types.SETTINGS_UPDATED,'preset');return r.body||{};});};" +
            "TavernHelper.setPreset=function(name,data,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/save',{category:c,name:String(name),preset:data||{},load:true}).then(function(r){eventSource.emit(event_types.PRESET_CHANGED,String(name));eventSource.emit(event_types.SETTINGS_UPDATED,'preset');return r.body||{};});};" +
            "TavernHelper.createPreset=function(name,data,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/create',{category:c,name:String(name),preset:data||{},load:true}).then(function(r){eventSource.emit(event_types.PRESET_CHANGED,String(name));return r.body||{};});};" +
            "TavernHelper.replacePreset=function(name,data,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/replace',{category:c,name:String(name),preset:data||{},load:true}).then(function(r){eventSource.emit(event_types.PRESET_CHANGED,String(name));return r.body||{};});};" +
            "TavernHelper.updatePresetWith=function(name,updater,opt){return TavernHelper.getPreset(name,opt).then(function(p){var n=updater(p||{});return TavernHelper.replacePreset(name,n||p||{},opt);});};" +
            "TavernHelper.deletePreset=function(name,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/delete',{category:c,name:String(name)}).then(function(r){eventSource.emit(event_types.PRESET_DELETED,String(name));return !!(r.body&&r.body.ok);});};" +
            "TavernHelper.renamePreset=function(name,newName,opt){var c=_thPresetCategory(opt);return window.Tellev.apiCall('POST','/api/presets/rename',{category:c,name:String(name),newName:String(newName)}).then(function(r){eventSource.emit(event_types.PRESET_RENAMED,String(name),String(newName));return r.body||{};});};" +
            "TavernHelper.builtin.getLoadedPresetName=TavernHelper.getLoadedPresetName;" +
            "TavernHelper.builtin.createOrReplacePreset=function(name,data,opt){return TavernHelper.getPresetNames(opt).then(function(ns){return ns.indexOf(String(name))>=0?TavernHelper.replacePreset(name,data,opt):TavernHelper.createPreset(name,data,opt);});};" +
            "TavernHelper.builtin.replacePreset=TavernHelper.replacePreset;TavernHelper.builtin.updatePresetWith=TavernHelper.updatePresetWith;" +
            "eventSource.emit=function(t){var args=[].slice.call(arguments,1);var n=_normEvent(t);tellevNative.emitFromEventSource(String(n),JSON.stringify({args:args}));return _fireLocal(n,args);};" +
            // Promoting the builtin.* placeholders to the top level (above) made
            // scripts stop crashing on them, but it also turned a loud
            // TypeError into a silent no-op. Keep the promotion and make the
            // no-op observable instead: one warn per method per runtime.
            "['createChatMessages','deleteChatMessages','rotateChatMessages','deleteWorldbook','updateWorldbookWith','createWorldbookEntries','deleteWorldbookEntries','getGlobalWorldbookNames','rebindGlobalWorldbooks','getCharWorldbookNames','rebindCharWorldbooks','getChatWorldbookName','rebindChatWorldbook','getOrCreateChatWorldbook','retrieveDisplayedMessage','refreshOneMessage','formatAsDisplayedMessage','formatAsTavernRegexedString'].forEach(function(n){" +
                "var f=TavernHelper[n];if(typeof f!=='function')return;var warned=false;" +
                "TavernHelper[n]=function(){if(!warned){warned=true;try{tellevNative.log('warn','TavernHelper.'+n+'() is not implemented in tellev: the call returns an empty result instead of doing anything.');}catch(e){}}return f.apply(this,arguments);};" +
                "if(TavernHelper.builtin&&typeof TavernHelper.builtin[n]==='function')TavernHelper.builtin[n]=TavernHelper[n];});" +
            // triggerSlash resolves to the pipe string and rejects on error
            // (js-slash-runner slash.ts); handing back the raw payload object
            // broke `(await triggerSlash(...)).toUpperCase()` and made
            // try/catch on a failed command unreachable.
            "TavernHelper.triggerSlash=function(cmd){return Promise.resolve(executeSlashCommandsWithOptions(String(cmd||''))).then(function(r){" +
                "if(r&&r.isError)throw new Error(r.errorMessage||('slash command failed: '+cmd));return (r&&r.pipe)||'';});};" +
            // getChatMessages: `data` is the *current swipe's* variables, not
            // the whole per-swipe array; range accepts macros; option filters
            // (role / hide_state) were parsed and then ignored.
            "var _thRawGetChatMessages=TavernHelper.getChatMessages;" +
            "TavernHelper.getChatMessages=function(range,opt){" +
                "opt=opt||{};" +
                "if(typeof range==='string'&&range.indexOf('{{')>=0){var c=_getContextTick();var last=((c&&c.chat)||[]).length-1;" +
                    "range=range.replace(/{{lastMessageId}}/gi,String(last)).replace(/{{firstMessageId}}/gi,'0');}" +
                "function shape(list){if(!Array.isArray(list))return list;" +
                    "var out=list.map(function(m){if(!m)return m;" +
                        "if(Array.isArray(m.data))m.data=m.data[Number(m.swipe_id||0)]||{};" +
                        "if(m.data===undefined||m.data===null)m.data={};return m;});" +
                    "if(opt.role&&String(opt.role)!=='all')out=out.filter(function(m){return m&&m.role===opt.role;});" +
                    "if(opt.hide_state&&String(opt.hide_state)!=='all')" +
                        "out=out.filter(function(m){var s=m&&m.is_hidden?'hidden':'unhidden';return s===opt.hide_state;});" +
                    "return out;}" +
                "var r=_thRawGetChatMessages.call(this,range,opt);" +
                "return (r&&typeof r.then==='function')?r.then(shape):shape(r);};" +
            // waitGlobalInitialized has to actually wait — resolving only when
            // the global already exists left every `await waitGlobalInitialized('Mvu')`
            // hanging forever whenever the provider script loaded second.
            "TavernHelper.initializeGlobal=function(n,v){window[n]=v;try{eventSource.emit('tellev_global_initialized',n);}catch(e){}" +
                "var w=window._thGlobalWaiters&&window._thGlobalWaiters[n];if(w){w.forEach(function(f){try{f(v);}catch(e){}});delete window._thGlobalWaiters[n];}return v;};" +
            "TavernHelper.waitGlobalInitialized=function(n){if(window[n]!==undefined)return Promise.resolve(window[n]);" +
                "return new Promise(function(resolve){window._thGlobalWaiters=window._thGlobalWaiters||{};" +
                    "(window._thGlobalWaiters[n]=window._thGlobalWaiters[n]||[]).push(resolve);" +
                    // Scripts that assign window.X directly never call
                    // initializeGlobal, so poll as a backstop.
                    "var tries=0;var timer=setInterval(function(){tries++;" +
                        "if(window[n]!==undefined){clearInterval(timer);resolve(window[n]);}" +
                        "else if(tries>600){clearInterval(timer);try{tellevNative.log('warn','waitGlobalInitialized(\"'+n+'\") timed out after 60s');}catch(e){}}" +
                    "},100);});};" +
            "if(TavernHelper._bind){TavernHelper._bind._initializeGlobal=TavernHelper.initializeGlobal;" +
                "TavernHelper._bind._waitGlobalInitialized=TavernHelper.waitGlobalInitialized;" +
                "TavernHelper._bind._insertVariables=function(v,o){return TavernHelper.insertVariables(v,o);};}"

        internal const val EXTENSION_LOAD_GUARDS: String =
            "window.addEventListener('error',function(e){if(!e||!e.message)return;try{if(e.error&&e.error.stack)tellevNative.log('error',String(e.error.stack));else if(e.filename)tellevNative.log('error',String(e.message)+' @'+e.filename+':'+e.lineno);tellevNative.extensionFailed(String(e.message));}catch(_e){}},true);" +
                "window.addEventListener('unhandledrejection',function(e){try{var r=e&&e.reason;if(r&&r.stack)tellevNative.log('error',String(r.stack));tellevNative.extensionFailed(String((r&&r.message)||r||'Unhandled promise rejection'));}catch(_e){}},true);"

        /**
         * The readiness deadline includes downloading every script in the
         * document head as well as any static imports in an extension module.
         * Character-card modules commonly load Vue, Zod, YAML and MVU from a
         * CDN; five seconds caused healthy modules to be destroyed while those
         * dependencies were still downloading on a phone network.
         *
         * JavaScript errors still fail immediately through [EXTENSION_LOAD_GUARDS].
         */
        internal const val DEFAULT_SCRIPT_READY_TIMEOUT_MS: Long = 30_000L

        /**
         * 同一 macrotask 内多次属性读取共用一次快照：SillyTavern Proxy
         * 每次 get 都调 _getContext（= 一次 Java bridge + 一次大 JSON.parse），
         * 事件 handler 里读几个属性就付几次。tick 缓存让同一任务内只付一次；
         * 推进靠 setTimeout 包裝（同步 handler 内 token 不变，正好共用；
         * 下一任务自动失效，不影响新鲜度）。显式调用的 getContext() 保持
         * 永远新鲜，不走此缓存。
         */
        internal const val TAVERN_CONTEXT_TICK_CACHE_JS: String =
            "var _ctxTickCache=null;var _ctxTickToken=0;function _getContextTick(){var t=_ctxTickToken;if(_ctxTickCache&&_ctxTickCache.tick===t)return _ctxTickCache.ctx;var c=_getContext();_ctxTickCache={tick:t,ctx:c};return c;}" +
                "function _ctxTickAdvance(){_ctxTickToken++;if(_ctxTickToken>1000000){_ctxTickToken=0;_ctxTickCache=null;}}" +
                "if(!window.__tellevCtxTickAdvancer){window.__tellevCtxTickAdvancer=true;var _origSetTimeout=window.setTimeout;window.setTimeout=function(fn,ms){var args=[].slice.call(arguments,2);return _origSetTimeout(function(){_ctxTickAdvance();try{return fn.apply(this,args);}catch(e){throw e;}},ms);};}"

        // The HTML is stored as a plain string (not a raw """...""") so
        // that the JS /* ... */ comments inside cannot be mistaken for
        // Kotlin block comments by the compiler.
        private const val HTML_TEMPLATE: String = "<!doctype html><html data-extension-id=\"__EXTENSION_ID__\"><head>" +
            "<meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' https: blob:; style-src 'unsafe-inline' https:; connect-src 'self' https:; img-src https: data:; media-src https: data:; font-src https: data:;\">" +
            "<script src=\"https://extensions.tellev.local/compat/globals.js\"></script>" +
            "</head><body><script>\n" +
            "__SHOWDOWN_SOURCE__\n" +
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
            "function _getContext(){var r;try{r=tellevNative.stGetContext();}catch(e){r='{}';}var c;try{c=JSON.parse(r);}catch(e){c={};}if(!c.chat)c.chat=[];if(!c.characters)c.characters=[];if(!c.groups)c.groups=[];if(!c.name1)c.name1='User';if(!c.name2)c.name2='Character';if(!c.characterId)c.characterId='';if(!c.chatId)c.chatId='';if(!c.groupId)c.groupId='';if(!c.mainApi)c.mainApi='openai-compatible';if(!c.chatMetadata)c.chatMetadata={};if(!c.onlineStatus)c.onlineStatus='connected';if(!c.maxContext)c.maxContext=1000000;if(!c.extensionPrompts)c.extensionPrompts={};if(!c.eventSource)c.eventSource=eventSource;if(!c.eventTypes)c.eventTypes=event_types;if(!c.event_types)c.event_types=event_types;return c;}" +
            // 同一 macrotask 内多次属性读取共用一次快照（见 TAVERN_CONTEXT_TICK_CACHE_JS）。
            TAVERN_CONTEXT_TICK_CACHE_JS +
            // js-slash-runner iframe/predefine.js exposes the script-facing
            // SillyTavern global as a live view: {...getContext(), getContext}.
            // A static object would freeze chat/characters at load time, so
            // state reads go through a proxy that re-snapshots each access.
            // getContext() lacks the function APIs (saveChat, callGenericPopup,
            // ToolManager, ...), which are stubbed here.
            "var _stStubApi={" +
            "saveChat:function(){return Promise.resolve();}," +
            "saveSettingsDebounced:function(){}," +
            "callGenericPopup:function(){return Promise.resolve(undefined);}," +
            "POPUP_TYPE:{TEXT:'text',INPUT:'input',CONFIRM:'confirm',DISPLAY:'display',CROP:'crop'}," +
            "POPUP_RESULT:{AFFIRMATIVE:1,NEGATIVE:0,CANCELLED:-1}," +
            "ToolManager:{registerFunctionTool:function(){},unregisterFunctionTool:function(){},invokeFunctionTool:function(){return Promise.reject(new Error('Tool calling is not supported'));}}," +
            "registerMacro:function(){},unregisterMacro:function(){},unregisterFunctionTool:function(){}," +
            "getRequestHeaders:function(){return{};}," +
            "getCurrentChatId:function(){var c=_getContextTick();return c.chatId||'';}," +
            "getChatCompletionModel:function(){var c=_getContextTick();return c.model||c.chatCompletionModel||'';}," +
            "extensionSettings:{}," +
            "chatCompletionSettings:{}" +
            "};" +
            "var SillyTavern=new Proxy(_stStubApi,{get:function(t,p){if(p==='getContext')return _getContext;var c=_getContextTick();var v=c[p];return v!==undefined?v:t[p];},set:function(t,p,v){t[p]=v;return true;}});" +
            "var getContext=_getContext;" +
            "var TavernHelper={tavern_events:event_types,iframe_events:{MESSAGE_IFRAME_RENDER_STARTED:'message_iframe_render_started',MESSAGE_IFRAME_RENDER_ENDED:'message_iframe_render_ended',GENERATION_STARTED:'js_generation_started',STREAM_TOKEN_RECEIVED_FULLY:'js_stream_token_received_fully',STREAM_TOKEN_RECEIVED_INCREMENTALLY:'js_stream_token_received_incrementally',GENERATION_ENDED:'js_generation_ended'},getChatMessages:function(range,opts){opts=opts||{};var c=_getContextTick();var m=c.chat||[];function _mapMsg(msg,i){return{message_id:msg.index!==undefined?msg.index:i,name:msg.name,role:msg.is_user?'user':(msg.is_system?'system':'assistant'),is_hidden:msg.is_system||false,message:msg.mes,data:msg.variables||{},extra:msg.extra||{},swipe_id:msg.swipe_id||0,swipes:msg.swipes||[],swipes_data:[]};}if(range===undefined||range===null||range==='')return m.map(_mapMsg);var r;if(typeof range==='number'){r=range>=0&&range<m.length?{start:range,end:range}:null;}else{var s=String(range);var mt=s.match(/^(-?\\d+)(?:-(-?\\d+))?$/);if(!mt)r=null;else{var a=parseInt(mt[1],10);var b=mt[2]!==undefined?parseInt(mt[2],10):a;if(a<0)a=m.length+a;if(b<0)b=m.length+b;if(isNaN(a)||isNaN(b))r=null;else{a=Math.max(0,Math.min(a,m.length-1));b=Math.max(0,Math.min(b,m.length-1));r={start:Math.min(a,b),end:Math.max(a,b)};}}}if(!r)return[];return m.slice(r.start,r.end+1).map(function(msg,i){return _mapMsg(msg,r.start+i);});},setChatMessage:function(fv,mid,opts){fv=typeof fv==='string'?{message:fv}:fv;if(!fv)return;if(fv.message!==undefined)tellevNative.stSetChatMessage(String(mid),'message',String(fv.message));if(fv.data!==undefined)tellevNative.stSetChatMessage(String(mid),'data',JSON.stringify(fv.data));},setChatMessages:function(cms,opts){if(!Array.isArray(cms))return;cms.forEach(function(cm){var mid=cm.message_id;if(mid===undefined)return;if(cm.message!==undefined)tellevNative.stSetChatMessage(String(mid),'message',String(cm.message));if(cm.name!==undefined)tellevNative.stSetChatMessage(String(mid),'name',String(cm.name));if(cm.role!==undefined)tellevNative.stSetChatMessage(String(mid),'role',String(cm.role));if(cm.is_hidden!==undefined)tellevNative.stSetChatMessage(String(mid),'is_hidden',String(cm.is_hidden));if(cm.extra!==undefined)tellevNative.stSetChatMessage(String(mid),'extra',JSON.stringify(cm.extra));if(cm.swipe_id!==undefined)tellevNative.stSetChatMessage(String(mid),'swipe_id',String(cm.swipe_id));if(cm.swipes!==undefined)tellevNative.stSetChatMessage(String(mid),'swipes',JSON.stringify(cm.swipes));});},getVariables:function(opt){var r;try{r=tellevNative.stGetVariables();}catch(e){r='{}';}try{return JSON.parse(r);}catch(e){return{};}},getAllVariables:function(opt){return TavernHelper.getVariables(opt);},replaceVariables:function(vars,opt){tellevNative.stSetVariables(JSON.stringify(vars||{}));},updateVariablesWith:function(updater,opt){var v=TavernHelper.getVariables(opt);var r=updater(v);TavernHelper.replaceVariables(r||v,opt);},insertOrAssignVariables:function(nv,opt){var v=TavernHelper.getVariables(opt);var merged={};for(var k in v)merged[k]=v[k];for(var k in nv)merged[k]=nv[k];TavernHelper.replaceVariables(merged,opt);},insertVariables:function(nv,opt){var v=TavernHelper.getVariables(opt);var merged={};for(var k in nv)merged[k]=nv[k];for(var k in v)if(!(k in merged))merged[k]=v[k];TavernHelper.replaceVariables(merged,opt);},deleteVariable:function(path,opt){var v=TavernHelper.getVariables(opt);var parts=String(path).split('.');var obj=v;for(var i=0;i<parts.length-1;i++){if(!obj[parts[i]])return;obj=obj[parts[i]];}delete obj[parts[parts.length-1]];TavernHelper.replaceVariables(v,opt);},substitudeMacros:function(text){if(text==null)return text;try{text=tellevNative.stReplaceVariables(String(text));}catch(e){}if(window._thMacroLikes&&text){for(var name in window._thMacroLikes){var ml=window._thMacroLikes[name];try{text=text.replace(new RegExp(ml.pattern,'g'),ml.replacement);}catch(e){}}}return text;},eventOn:function(t,c){return eventSource.on(t,c);},eventMakeLast:function(t,c){return eventSource.makeLast(t,c);},eventMakeFirst:function(t,c){return eventSource.makeFirst(t,c);},eventOnce:function(t,c){return eventSource.once(t,c);},eventEmit:function(t){return eventSource.emit.apply(eventSource,arguments);},eventEmitAndWait:function(t){return eventSource.emitAndWait.apply(eventSource,arguments);},eventRemoveListener:function(t,c){return eventSource.removeListener(t,c);},eventClearEvent:function(t){var n=_normEvent(t);_stHandlers[n]=[];},eventClearAll:function(){_stHandlers={};},triggerSlash:function(text){return executeSlashCommandsWithOptions(text);},addSlashCommand:function(n,c,o){o=o||{};_commandHandlers[n]=c;tellevNative.registerCommand(String(n),String(o.help||o.description||''),JSON.stringify(o.args||{}));},registerEvent:function(t,c){return eventSource.on(t,c);},setVariables:function(v){tellevNative.stSetVariables(JSON.stringify(v||{}));},getLastMessageId:function(){var c=_getContextTick();return c.chat?c.chat.length-1:-1;},getMessageId:function(){return TavernHelper.getLastMessageId();},getTavernHelperVersion:function(){return'4.8.11';},getFrontendVersion:function(){return TavernHelper.getTavernHelperVersion();},getTavernVersion:function(){return'1.18.0';},errorCatched:function(fn){return function(){try{return fn.apply(this,arguments);}catch(e){tellevNative.log('error',String(e));}};},getExtensionPrompt:function(){try{return JSON.parse(tellevNative.stGetInjectedPrompts()||'{}');}catch(e){return{};}},firstUserMessageIndex:function(){var c=_getContextTick();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&c.chat[i].is_user)return i;}return-1;},firstBotMessageIndex:function(){var c=_getContextTick();for(var i=0;i<c.chat.length;i++){if(c.chat[i]&&!c.chat[i].is_user)return i;}return-1;},generate:function(opts){opts=opts||{};return window.Tellev.apiCall('POST','/api/backends/chat-completions/generate',opts).then(function(r){return r.body&&r.body.text?r.body.text:'';});},generateRaw:function(opts){opts=opts||{};return window.Tellev.apiCall('POST','/api/backends/chat-completions/generate',opts).then(function(r){return r.body||{};});},stopAllGeneration:function(){return Promise.resolve();},stopGenerationById:function(){return Promise.resolve();},getModelList:function(opts){opts=opts||{};var url=opts.api_url||opts.apiUrl||'';return window.Tellev.apiCall('POST','/api/backends/chat-completions/status',{api_url:url}).then(function(r){return r.body&&r.body.data?r.body.data:[];});},getCharacter:function(id){return window.Tellev.apiCall('GET','/api/characters/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getCurrentCharacterName:function(){var c=_getContextTick();return c.name2||'';},getCurrentCharacterId:function(){var c=_getContextTick();return c.characterId||'';},getCharacterNames:function(){return window.Tellev.apiCall('GET','/api/characters').then(function(r){var arr=r.body&&r.body.characters?r.body.characters:[];return arr.map(function(c){return c.name;});});},getCharacterIds:function(){return window.Tellev.apiCall('GET','/api/characters').then(function(r){var arr=r.body&&r.body.characters?r.body.characters:[];return arr.map(function(c){return c.id;});});},replaceCharacter:function(id,data){return window.Tellev.apiCall('POST','/api/characters',data).then(function(r){return r.body||{};});},updateCharacterWith:function(id,fn){return TavernHelper.getCharacter(id).then(function(c){var updated=fn(c);return TavernHelper.replaceCharacter(id,updated);});},createCharacter:function(data){return window.Tellev.apiCall('POST','/api/characters',data).then(function(r){return r.body||{};});},deleteCharacter:function(id){return window.Tellev.apiCall('DELETE','/api/characters/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getLorebooks:function(){return window.Tellev.apiCall('GET','/api/worlds').then(function(r){return r.body&&r.body.worlds?r.body.worlds:[];});},getWorldbook:function(id){return window.Tellev.apiCall('GET','/api/worlds/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},getWorldbookNames:function(){return TavernHelper.getLorebooks().then(function(books){return books.map(function(b){return b.id||b.name;});});},createWorldbook:function(data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},replaceWorldbook:function(id,data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},getLorebookEntries:function(bookId){return TavernHelper.getWorldbook(bookId).then(function(b){return b&&b.entries?b.entries:[];});},setLorebookEntries:function(bookId,entries){return TavernHelper.getWorldbook(bookId).then(function(b){b.entries=entries;return TavernHelper.replaceWorldbook(bookId,b);});},createLorebookEntry:function(bookId,entry){return TavernHelper.getLorebookEntries(bookId).then(function(entries){entries.push(entry);return TavernHelper.setLorebookEntries(bookId,entries);});},deleteLorebookEntries:function(bookId,ids){return TavernHelper.getLorebookEntries(bookId).then(function(entries){return entries.filter(function(e){return ids.indexOf(e.uid)<0;});}).then(function(kept){return TavernHelper.setLorebookEntries(bookId,kept);});},getLorebookSettings:function(){return Promise.resolve({});},setLorebookSettings:function(){return Promise.resolve();},getCharLorebooks:function(){return Promise.resolve([]);},setCurrentCharLorebooks:function(){return Promise.resolve();},getChatLorebook:function(){return Promise.resolve(null);},setChatLorebook:function(){return Promise.resolve();},getOrCreateChatLorebook:function(){return Promise.resolve(null);},getPreset:function(name){return window.Tellev.apiCall('GET','/api/settings').then(function(r){var arr=r.body&&r.body.presets?r.body.presets:[];return arr.find(function(p){return p.name===name;})||null;});},getPresetNames:function(){return window.Tellev.apiCall('GET','/api/settings').then(function(r){var arr=r.body&&r.body.presets?r.body.presets:[];return arr.map(function(p){return p.name;});});},loadPreset:function(){return Promise.resolve();},setPreset:function(){return Promise.resolve();},createPreset:function(){return Promise.resolve();},deletePreset:function(){return Promise.resolve();},renamePreset:function(){return Promise.resolve();},isPresetNormalPrompt:function(){return false;},isPresetSystemPrompt:function(){return false;},isPresetPlaceholderPrompt:function(){return false;},getPersona:function(id){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.find(function(p){return p.id===id;})||null;});},getPersonaNames:function(){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.map(function(p){return p.name;});});},getPersonaIds:function(){return window.Tellev.apiCall('GET','/api/personas').then(function(r){var arr=r.body&&r.body.personas?r.body.personas:[];return arr.map(function(p){return p.id;});});},getCurrentPersonaName:function(){var c=_getContextTick();return c.name1||'User';},getCurrentPersonaId:function(){return Promise.resolve('');},getPersonaAvatarPath:function(){return Promise.resolve('');},createPersona:function(data){return window.Tellev.apiCall('POST','/api/personas',data).then(function(r){return r.body||{};});},replacePersona:function(data){return window.Tellev.apiCall('POST','/api/personas',data).then(function(r){return r.body||{};});},updatePersonaWith:function(id,fn){return TavernHelper.getPersona(id).then(function(p){var u=fn(p);return TavernHelper.replacePersona(u);});},deletePersona:function(id){return window.Tellev.apiCall('DELETE','/api/personas/'+encodeURIComponent(id)).then(function(r){return r.body||{};});},injectPrompts:function(id,content,opts){opts=opts||{};try{tellevNative.stInjectPrompt(String(id||''),String(content||''),Number(opts.position||0),Number(opts.depth||4),String(opts.role||'system'));}catch(e){tellevNative.log('error','injectPrompts failed: '+e);}return Promise.resolve();},uninjectPrompts:function(id){try{tellevNative.stUninjectPrompt(String(id||''));}catch(e){tellevNative.log('error','uninjectPrompts failed: '+e);}return Promise.resolve();},getTavernRegexes:function(charId){return window.Tellev.apiCall('GET','/api/characters/'+encodeURIComponent(charId)+'/regex').then(function(r){return r.body&&r.body.regex_scripts?r.body.regex_scripts:[];});},replaceTavernRegexes:function(charId,regexes){return TavernHelper.getCharacter(charId).then(function(c){var data=c.data||{};var ext=data.extensions||{};ext.regex_scripts=regexes;data.extensions=ext;c.data=data;return TavernHelper.replaceCharacter(charId,c);});},formatAsTavernRegexedString:function(){return Promise.resolve('');},isCharacterTavernRegexesEnabled:function(){return Promise.resolve(false);},getScriptTrees:function(){return Promise.resolve([]);},replaceScriptTrees:function(){return Promise.resolve();},updateScriptTreesWith:function(){return Promise.resolve();},getAllEnabledScriptButtons:function(){return Promise.resolve([]);},getScriptButtons:function(){return Promise.resolve([]);},replaceScriptButtons:function(){return Promise.resolve();},importRawCharacter:function(data){return window.Tellev.apiCall('POST','/api/characters/import',data).then(function(r){return r.body||{};});},importRawPreset:function(){return Promise.resolve();},importRawChat:function(data){return window.Tellev.apiCall('POST','/api/chats/import',data).then(function(r){return r.body||{};});},importRawWorldbook:function(data){return window.Tellev.apiCall('POST','/api/worlds',data).then(function(r){return r.body||{};});},importRawTavernRegex:function(){return Promise.resolve();},playAudio:function(){return Promise.resolve();},pauseAudio:function(){return Promise.resolve();},getAudioList:function(){return Promise.resolve([]);},replaceAudioList:function(){return Promise.resolve();},appendAudioList:function(){return Promise.resolve();},getAudioSettings:function(){return Promise.resolve({});},setAudioSettings:function(){return Promise.resolve();},getCurrentAudio:function(){return Promise.resolve(null);},registerMacroLike:function(name,pattern,replacement,opts){if(!window._thMacroLikes)window._thMacroLikes={};window._thMacroLikes[name]={pattern:pattern,replacement:replacement,opts:opts||{}};return Promise.resolve();},unregisterMacroLike:function(name){if(window._thMacroLikes)delete window._thMacroLikes[name];return Promise.resolve();},getChatHistoryBrief:function(charId){return window.Tellev.apiCall('GET','/api/chats?characterId='+encodeURIComponent(charId)).then(function(r){return r.body&&r.body.chats?r.body.chats:[];});},getChatHistoryDetail:function(charId,chatId){return window.Tellev.apiCall('GET','/api/chats/'+encodeURIComponent(chatId)).then(function(r){return r.body||{};});},getCharData:function(charId,field){return TavernHelper.getCharacter(charId).then(function(c){return c[field];});},getCharAvatarPath:function(charId){return TavernHelper.getCharacter(charId).then(function(c){return c.avatar||'';});},getExtensionType:function(){return Promise.resolve('');},getExtensionStatus:function(name){return window.Tellev.apiCall('POST','/api/extensions/version',{name:name}).then(function(r){return r.body||{installed:false};});},isInstalledExtension:function(name){return TavernHelper.getExtensionStatus(name).then(function(info){return info.installed===true;});},installExtension:function(){return Promise.resolve();},uninstallExtension:function(){return Promise.resolve();},reinstallExtension:function(){return Promise.resolve();},updateExtension:function(){return Promise.resolve();},isAdmin:function(){return false;},getTavernHelperExtensionId:function(){return'tavern-helper-compat';},_th_impl:{_init:function(){},_log:function(){},_clearLog:function(){},writeExtensionField:function(){return Promise.resolve();}},_bind:{},getScriptId:function(){return '';},getCurrentMessageId:function(){var c=_getContextTick();return (c.chat&&c.chat.length)?c.chat.length-1:0;},getScriptName:function(){return '';},getScriptInfo:function(){return {};},replaceScriptInfo:function(){return Promise.resolve();},getScriptButtons:function(){return [];},replaceScriptButtons:function(){return Promise.resolve();},updateScriptButtonsWith:function(){return Promise.resolve([]);},getAllEnabledScriptButtons:function(){return {};},getButtonEvent:function(n){return 'button_'+String(n);},eventClearListener:function(){},initializeGlobal:function(n,v){window[n]=v;},waitGlobalInitialized:function(n){return new Promise(function(r){if(window[n]!==undefined)r(window[n]);});},registerVariableSchema:function(){},updateTavernHelper:function(){return Promise.resolve(false);},updateFrontendVersion:function(){return Promise.resolve(false);},builtin:{addOneMessage:function(){},copyText:function(){},duringGenerating:function(){},getImageTokenCost:function(){return 0;},getVideoTokenCost:function(){return 0;},parseRegexFromString:function(s){try{return new RegExp(s);}catch(e){return new RegExp('');}},promptManager:null,reloadAndRenderChatWithoutEvents:function(){},reloadChatWithoutEvents:function(){},reloadEditor:function(){},reloadEditorDebounced:function(){},renderMarkdown:function(s){try{if(!window._sd)window._sd=new showdown.Converter({simplifiedAutoLink:true,tables:true});return window._sd.makeHtml(s||'');}catch(e){return String(s||'');}},renderPromptManager:function(){},renderPromptManagerDebounced:function(){},saveSettings:function(){return Promise.resolve();},uuidv4:function(){return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){var r=Math.random()*16|0;var v=c==='x'?r:(r&0x3|0x8);return v.toString(16);});},getLoadedPresetName:function(){return '';},createOrReplacePreset:function(){return Promise.resolve(false);},replacePreset:function(){return Promise.resolve();},updatePresetWith:function(){return Promise.resolve({});},deleteWorldbook:function(){return Promise.resolve(false);},updateWorldbookWith:function(){return Promise.resolve([]);},createWorldbookEntries:function(){return Promise.resolve({worldbook:[],new_entries:[]});},deleteWorldbookEntries:function(){return Promise.resolve({worldbook:[],deleted_entries:[]});},getGlobalWorldbookNames:function(){return [];},rebindGlobalWorldbooks:function(){return Promise.resolve();},getCharWorldbookNames:function(){return {};},rebindCharWorldbooks:function(){return Promise.resolve();},getChatWorldbookName:function(){return null;},rebindChatWorldbook:function(){return Promise.resolve();},getOrCreateChatWorldbook:function(){return Promise.resolve('');},createChatMessages:function(){return Promise.resolve();},deleteChatMessages:function(){return Promise.resolve();},rotateChatMessages:function(){return Promise.resolve();},formatAsDisplayedMessage:function(t){return String(t||'');},retrieveDisplayedMessage:function(){return null;},refreshOneMessage:function(){return Promise.resolve();}}};" +
            TAVERN_HELPER_CONTRACT_OVERRIDES +
            "function executeSlashCommandsWithOptions(cs){var raw=typeof cs==='string'?cs:(Array.isArray(cs)?cs.join('\\n'):String(cs||''));_apiReqCounter+=1;var rid='slash_'+_apiReqCounter+'_'+Date.now();return new Promise(function(resolve){_slashCallbacks[rid]=resolve;tellevNative.executeSlashCommands(rid,raw);});}" +
            "function executeSlashCommands(cs){return executeSlashCommandsWithOptions(cs);}" +
            "var _ejsDefaultFeatures={enabled:true,generate_enabled:true,generate_loader_enabled:true,inject_loader_enabled:false,render_enabled:true,render_loader_enabled:true,code_blocks_enabled:false,raw_message_evaluation_enabled:true,filter_message_enabled:true,depth_limit:-1,autosave_enabled:false,preload_worldinfo_enabled:true,with_context_disabled:false,debug_enabled:false,invert_enabled:true,compile_workers:false,sandbox:false,cache_enabled:0,cache_size:64,cache_hasher:'h32ToString',code_editor:false};" +
            "var _ejsFeatures=Object.assign({},_ejsDefaultFeatures,__EJS_SETTINGS_JSON__);" +
            "var _tavernHelperSettings=__TAVERN_HELPER_SETTINGS_JSON__;" +
            "function _ejsPathGet(root,path,fb){if(root==null)return fb;var cur=root;var parts=String(path||'').split('.');for(var i=0;i<parts.length;i++){var p=parts[i];if(!p)continue;if(cur==null)return fb;cur=cur[p];}return cur===undefined?fb:cur;}" +
            "function _ejsPathSet(root,path,value){if(!root)return root;var cur=root;var parts=String(path||'').split('.').filter(Boolean);for(var i=0;i<parts.length-1;i++){var p=parts[i];if(typeof cur[p]!=='object'||cur[p]===null)cur[p]={};cur=cur[p];}if(parts.length)cur[parts[parts.length-1]]=value;return root;}" +
            "function _ejsLocalVars(){try{return JSON.parse(tellevNative.stGetLocalVariables())||{};}catch(e){return{};}}" +
            "function _ejsSaveLocalVars(v){try{tellevNative.stSetLocalVariables(JSON.stringify(v||{}));}catch(e){}}" +
            "function _ejsGlobalVars(){try{return TavernHelper.getVariables({type:'global'})||{};}catch(e){return{};}}" +
            "function _ejsSaveGlobalVars(v){try{TavernHelper.replaceVariables(v||{},{type:'global'});}catch(e){}}" +
            "function _ejsVars(){var g=_ejsGlobalVars();var l=_ejsLocalVars();var m={};for(var k in g)m[k]=g[k];for(var k in l)m[k]=l[k];return m;}" +
            "function _ejsLodash(){return{get:_ejsPathGet,set:function(o,p,v){return _ejsPathSet(o,p,v);},has:function(o,p){return _ejsPathGet(o,p,undefined)!==undefined;},unset:function(o,p){var parts=String(p||'').split('.').filter(Boolean);var cur=o;for(var i=0;i<parts.length-1;i++){if(cur==null)return o;cur=cur[parts[i]];}if(cur!=null)delete cur[parts[parts.length-1]];return o;},merge:function(target){target=target||{};for(var i=1;i<arguments.length;i++){var src=arguments[i]||{};for(var k in src){if(src[k]&&typeof src[k]==='object'&&!Array.isArray(src[k]))target[k]=this.merge(target[k]||{},src[k]);else target[k]=src[k];}}return target;},mergeWith:function(target){var args=[].slice.call(arguments);var customizer=args[args.length-1];target=target||{};for(var i=1;i<args.length-1;i++){var src=args[i]||{};for(var k in src){var cv=customizer?customizer(target[k],src[k],k,target,src):undefined;if(cv!==undefined)target[k]=cv;else if(src[k]&&typeof src[k]==='object'&&!Array.isArray(src[k]))target[k]=this.merge(target[k]||{},src[k]);else target[k]=src[k];}}return target;},cloneDeep:function(v){if(Array.isArray(v))return v.map(this.cloneDeep);if(v&&typeof v==='object'){var o={};for(var k in v)o[k]=this.cloneDeep(v[k]);return o;}return v;},find:function(arr,fn){if(!Array.isArray(arr))return undefined;for(var i=0;i<arr.length;i++){if(fn(arr[i],i,arr))return arr[i];}return undefined;},findLastIndex:function(arr,fn){if(!Array.isArray(arr))return -1;for(var i=arr.length-1;i>=0;i--){if(fn(arr[i],i,arr))return i;}return -1;},groupBy:function(arr,fn){var r={};if(!Array.isArray(arr))return r;arr.forEach(function(v,i){var k=fn(v,i,arr);(r[k]=r[k]||[]).push(v);});return r;},castArray:function(v){return Array.isArray(v)?v:[v];},compact:function(arr){return Array.isArray(arr)?arr.filter(Boolean):[];},clamp:function(n,lo,hi){return Math.max(lo,Math.min(hi,n));},escapeRegExp:function(s){return String(s||'').replace(/[.*+?^\${}()|[\\]\\\\]/g,'\\\\\$&');},defaults:function(o){for(var i=1;i<arguments.length;i++){var s=arguments[i]||{};for(var k in s){if(o[k]===undefined)o[k]=s[k];}}return o;},isEqual:function(a,b){return JSON.stringify(a)===JSON.stringify(b);},isPlainObject:function(v){return v!==null&&typeof v==='object'&&!Array.isArray(v);},isArray:function(v){return Array.isArray(v);},isObject:function(v){return v!==null&&typeof v==='object';},isString:function(v){return typeof v==='string';},isFunction:function(v){return typeof v==='function';},random:function(n){return Math.floor(Math.random()*(n||1));},sum:function(arr){return Array.isArray(arr)?arr.reduce(function(a,b){return a+(+b||0);},0):0;},entries:function(o){var r=[];if(o&&typeof o==='object'){for(var k in o)r.push([k,o[k]]);}return r;}};}" +
            "function _ejsHelpers(ctx){var vars=_ejsVars();var helpers={SillyTavern:SillyTavern,TavernHelper:TavernHelper,getContext:_getContext,variables:vars,_:_ejsLodash(),getvar:function(k,d){var v=_ejsPathGet(_ejsLocalVars(),k,d);return v===undefined?'':v;},getchatvar:function(k,d){var v=_ejsPathGet(_ejsLocalVars(),k,d);return v===undefined?'':v;},getglobalvar:function(k,d){var v=_ejsPathGet(_ejsGlobalVars(),k,d);return v===undefined?'':v;},setvar:function(k,v){var lv=_ejsLocalVars();_ejsPathSet(lv,k,v);_ejsSaveLocalVars(lv);return'';},setchatvar:function(k,v){var lv=_ejsLocalVars();_ejsPathSet(lv,k,v);_ejsSaveLocalVars(lv);return'';},setglobalvar:function(k,v){var gv=_ejsGlobalVars();_ejsPathSet(gv,k,v);_ejsSaveGlobalVars(gv);return'';},incvar:function(k){var lv=_ejsLocalVars();var v=Number(_ejsPathGet(lv,k,0)||0)+1;_ejsPathSet(lv,k,v);_ejsSaveLocalVars(lv);return v;},decvar:function(k){var lv=_ejsLocalVars();var v=Number(_ejsPathGet(lv,k,0)||0)-1;_ejsPathSet(lv,k,v);_ejsSaveLocalVars(lv);return v;},print:function(){return Array.prototype.join.call(arguments,'');},getGlobalVar:function(k,d){var v=_ejsPathGet(_ejsGlobalVars(),k,d);return v===undefined?'':v;},setGlobalVar:function(k,v){var gv=_ejsGlobalVars();_ejsPathSet(gv,k,v);_ejsSaveGlobalVars(gv);return'';},incGlobalVar:function(k){var gv=_ejsGlobalVars();var v=Number(_ejsPathGet(gv,k,0)||0)+1;_ejsPathSet(gv,k,v);_ejsSaveGlobalVars(gv);return v;},decGlobalVar:function(k){var gv=_ejsGlobalVars();var v=Number(_ejsPathGet(gv,k,0)||0)-1;_ejsPathSet(gv,k,v);_ejsSaveGlobalVars(gv);return v;}};return helpers;}" +            "function _ejsCompile(code){var src=\"var __out='';var print=function(){__out+=Array.prototype.join.call(arguments,'');};\";var re=/<%([=-]?)([\\s\\S]*?)%>/g;var cursor=0;var m;function addText(t){if(t)src+='__out+='+JSON.stringify(t)+';';}while((m=re.exec(String(code||'')))!==null){addText(String(code||'').slice(cursor,m.index));var marker=m[1];var body=String(m[2]||'').trim();if(body.charAt(0)==='_')body=body.slice(1).trim();if(body.charAt(body.length-1)==='_')body=body.slice(0,-1).trim();if(marker==='='||marker==='-'){src+='__out+=(('+body+')==null?\\'\\':String('+body+'));';}else{src+=body+'\\n';}cursor=m.index+m[0].length;}addText(String(code||'').slice(cursor));src+='return __out;';return new Function('ctx','helpers',\"return (async function(){with(helpers){with(ctx||{}){\"+src+\"}}}).call(ctx);\");}" +
            "function _ejsPrepareContext(additional){var c=_getContextTick();var vars=_ejsVars();var env={context:c,SillyTavern:SillyTavern,TavernHelper:TavernHelper,variables:vars,vars:vars,name1:c.name1||'User',user:c.name1||'User',userName:c.name1||'User',name2:c.name2||'Character',char:c.name2||'Character',charName:c.name2||'Character',chat:c.chat||[],characters:c.characters||[],groups:c.groups||[],chatId:c.chatId||'',characterId:c.characterId||'',groupId:c.groupId||'',lastUserMessage:(function(){var m=c.chat||[];for(var i=m.length-1;i>=0;i--){if(m[i].is_user)return m[i].mes||'';}return'';})(),lastCharMessage:(function(){var m=c.chat||[];for(var i=m.length-1;i>=0;i--){if(!m[i].is_user&&!m[i].is_system)return m[i].mes||'';}return'';})(),assistantName:c.name2||'Character',charAvatar:'',userAvatar:''};var helpers=_ejsHelpers(env);for(var k in helpers)if(env[k]===undefined)env[k]=helpers[k];if(additional){for(var ak in additional)env[ak]=additional[ak];}return env;}" +            "var EjsTemplate={evaltemplate:function(code,context,options){try{return Promise.resolve(_ejsCompile(code)(context||_ejsPrepareContext({}),_ejsHelpers(context||{})));}catch(e){return Promise.reject(e);}},evalTemplate:function(code,context,options){return EjsTemplate.evaltemplate(code,context,options);},prepareContext:function(additional_context,last_message_id){return Promise.resolve(_ejsPrepareContext(additional_context||{}));},getSyntaxErrorInfo:function(code,lineCount){try{_ejsCompile(code);return Promise.resolve('');}catch(e){return Promise.resolve(String(e&&e.message?e.message:e));}},allVariables:function(end_message_id){return _ejsVars();},getFeatures:function(){return Object.assign({},_ejsFeatures);},setFeatures:function(features){_ejsFeatures=Object.assign({},_ejsFeatures,features||{});},resetFeatures:function(){_ejsFeatures=Object.assign({},_ejsDefaultFeatures);}};" +
            "TavernHelper.generateRaw=function(opts){opts=opts||{};return window.Tellev.apiCall('POST','/api/backends/chat-completions/generate',opts).then(function(r){if(r.status<200||r.status>=300){var e=new Error((r.body&&r.body.error)||'Generation failed');e.status=r.status;e.code=r.body&&r.body.code;throw e;}return r.body||{};});};" +
            "TavernHelper.generate=function(opts){return TavernHelper.generateRaw(opts).then(function(body){return body&&body.text?body.text:'';});};" +
            "function _isApiPath(u){var p=u.split('?')[0];if(p.indexOf('/')===0)return true;if(p.indexOf('extensions.tellev.local')>=0)return true;return false;}" +
            "var _originalFetch=window.fetch.bind(window);" +
            "window.fetch=function(input,init){try{var u=typeof input==='string'?input:((input&&input.url)||'');if(_isApiPath(u)){var p=u.split('extensions.tellev.local')[1]||u;var m=(init&&init.method)||'GET';var b=init&&init.body;var bo=null;if(b!==undefined&&b!==null){if(typeof b==='string'){try{bo=JSON.parse(b);}catch(e){bo=b;}}else{bo=b;}}return window.Tellev.apiCall(m,p,bo).then(function(r){var bt=(r.body!==undefined&&r.body!==null)?(typeof r.body==='string'?r.body:JSON.stringify(r.body)):'';return new Response(bt,{status:r.status,headers:{'Content-Type':'application/json'}});});}if(u&&u.indexOf('https:')===0){return _originalFetch(input,init);}}catch(e){return Promise.reject(e);}return Promise.reject(new Error('Network access is not permitted for extensions'));};" +
            "window.SillyTavern=SillyTavern;window.getContext=getContext;window.eventSource=eventSource;window.event_types=event_types;window.eventTypes=event_types;window.TavernHelper=TavernHelper;window.EjsTemplate=EjsTemplate;window.tavern_events=event_types;window.executeSlashCommandsWithOptions=executeSlashCommandsWithOptions;window.executeSlashCommands=executeSlashCommands;window.eventOn=TavernHelper.eventOn;window.eventMakeLast=TavernHelper.eventMakeLast;window.eventMakeFirst=TavernHelper.eventMakeFirst;window.eventOnce=TavernHelper.eventOnce;window.eventEmit=TavernHelper.eventEmit;window.eventEmitAndWait=TavernHelper.eventEmitAndWait;window.eventRemoveListener=TavernHelper.eventRemoveListener;window.getVariables=TavernHelper.getVariables;window.replaceVariables=TavernHelper.replaceVariables;window.updateVariablesWith=TavernHelper.updateVariablesWith;window.insertOrAssignVariables=TavernHelper.insertOrAssignVariables;window.insertVariables=TavernHelper.insertVariables;window.deleteVariable=TavernHelper.deleteVariable;window.substitudeMacros=TavernHelper.substitudeMacros;window.triggerSlash=TavernHelper.triggerSlash;window.getLastMessageId=TavernHelper.getLastMessageId;window.getChatMessages=TavernHelper.getChatMessages;window.setChatMessages=TavernHelper.setChatMessages;window.generate=TavernHelper.generate;window.generateRaw=TavernHelper.generateRaw;window.stopAllGeneration=TavernHelper.stopAllGeneration;window.getCharacter=TavernHelper.getCharacter;window.replaceCharacter=TavernHelper.replaceCharacter;window.updateCharacterWith=TavernHelper.updateCharacterWith;window.getLorebooks=TavernHelper.getLorebooks;window.getWorldbook=TavernHelper.getWorldbook;window.getWorldbookNames=TavernHelper.getWorldbookNames;window.getLorebookEntries=TavernHelper.getLorebookEntries;window.setLorebookEntries=TavernHelper.setLorebookEntries;window.getPreset=TavernHelper.getPreset;window.getPresetNames=TavernHelper.getPresetNames;window.getLoadedPresetName=TavernHelper.getLoadedPresetName;window.loadPreset=TavernHelper.loadPreset;window.setPreset=TavernHelper.setPreset;window.createPreset=TavernHelper.createPreset;window.replacePreset=TavernHelper.replacePreset;window.updatePresetWith=TavernHelper.updatePresetWith;window.deletePreset=TavernHelper.deletePreset;window.renamePreset=TavernHelper.renamePreset;window.getPersona=TavernHelper.getPersona;window.injectPrompts=TavernHelper.injectPrompts;window.uninjectPrompts=TavernHelper.uninjectPrompts;window.getTavernRegexes=TavernHelper.getTavernRegexes;window.substitudeMacros=TavernHelper.substitudeMacros;window.getModelList=TavernHelper.getModelList;window.getAllVariables=TavernHelper.getAllVariables;window.getChatHistoryBrief=TavernHelper.getChatHistoryBrief;window.getChatHistoryDetail=TavernHelper.getChatHistoryDetail;window.getRawCharacter=TavernHelper.getCharacter;window.getCharData=TavernHelper.getCharData;" +
            // js-slash-runner exposes these as bare globals for card scripts (the
            // MVU bundle calls getTavernHelperVersion() directly, not via TavernHelper).
            "window.getTavernHelperVersion=TavernHelper.getTavernHelperVersion;window.getFrontendVersion=TavernHelper.getFrontendVersion;window.getTavernVersion=TavernHelper.getTavernVersion;window.getMessageId=TavernHelper.getMessageId;window.errorCatched=TavernHelper.errorCatched;window.registerVariableSchema=function(){return function(){}};" +
            // js-slash-runner iframe/predefine.js additionally merges every
            // TavernHelper method and every _bind entry (with the leading
            // underscore stripped, e.g. _getScriptId -> getScriptId) as bare
            // globals. Fill in whatever the explicit assignments above missed.
            "(function(){var n;for(n in TavernHelper){if(n!=='_bind'&&window[n]===undefined)window[n]=TavernHelper[n];}if(TavernHelper._bind){for(n in TavernHelper._bind){if(typeof TavernHelper._bind[n]==='function'){var g=n.replace(/^_/,'');if(window[g]===undefined)window[g]=TavernHelper._bind[n];}}}})();" +
            "window.__tellevInvalidateContext=_ctxTickAdvance;" +
            EXTENSION_LOAD_GUARDS +
            "})();" +
            "\n</script><script>__HOST_ADAPTER__</script>\n__EXTENSION_SCRIPT__\n</body></html>"
    }
}

internal suspend fun dispatchExtensionGeneration(
    contextProvider: ExtensionContextProvider?,
    options: JsonObject,
    json: Json = Json,
): VirtualApiResponse {
    val provider = contextProvider ?: return extensionGenerationError(
        json = json,
        status = 503,
        code = "chat_context_unavailable",
        message = "No active chat context is available",
    )
    val result = try {
        provider.generateText(options)
    } catch (error: IllegalStateException) {
        return extensionGenerationError(
            json = json,
            status = 409,
            code = "chat_context_incomplete",
            message = error.message ?: "The active chat cannot generate text",
        )
    } catch (error: Exception) {
        return extensionGenerationError(
            json = json,
            status = 502,
            code = "provider_generation_failed",
            message = error.message ?: "Provider generation failed",
        )
    } ?: return extensionGenerationError(
        json = json,
        status = 503,
        code = "generation_unavailable",
        message = "Text generation is unavailable in the active context",
    )
    return VirtualApiResponse(
        status = 200,
        headers = mapOf("Content-Type" to "application/json"),
        body = json.encodeToString(JsonObject.serializer(), result),
    )
}

private fun extensionGenerationError(
    json: Json,
    status: Int,
    code: String,
    message: String,
): VirtualApiResponse = VirtualApiResponse(
    status = status,
    headers = mapOf("Content-Type" to "application/json"),
    body = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("error", message)
            put("code", code)
            put("status", status)
        },
    ),
)
