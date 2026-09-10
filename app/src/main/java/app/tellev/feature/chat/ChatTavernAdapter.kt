package app.tellev.feature.chat

import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.extension.ExtensionContextProvider
import app.tellev.core.extension.ExtensionEvent
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionManifest
import app.tellev.core.extension.ExtensionPermission
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.extension.MessageVariableBackend
import app.tellev.core.extension.RuntimeToken
import app.tellev.core.extension.StEventCatalog
import app.tellev.core.extension.applyTavernChatMessages
import app.tellev.core.model.Attachment
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBook
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * Bridges Tellev chat domain state and events with the SillyTavern / TavernHelper
 * compatibility runtime and WebView extension host.
 */
internal object ChatTavernAdapter {

    const val RECENT_RENDERED_EVENT_LIMIT = 20

    fun createExtensionContextProvider(
        getCurrentState: () -> ChatUiState,
        extensionHost: ExtensionHost,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
        onSetChatMessage: suspend (index: Int, field: String, value: String) -> Boolean,
        onGenerateText: suspend (options: JsonObject) -> JsonObject?,
    ): ExtensionContextProvider = object : ExtensionContextProvider {
        override fun snapshot(): JsonObject {
            val state = getCurrentState()
            return buildTavernContext(
                state = state,
                runtimeGeneration = sessionRuntime.runtimeToken?.generation ?: -1,
                extensionHost = extensionHost,
            )
        }

        override suspend fun setChatMessage(index: Int, field: String, value: String): Boolean =
            onSetChatMessage(index, field, value)

        override suspend fun setChatMessages(messages: JsonArray, options: JsonObject): Boolean {
            val session = getCurrentState().currentSession ?: return false
            val updated = applyTavernChatMessages(session.messages, messages)
            val next = session.copy(messages = updated)
            sessionRuntime.persistSessionMutation(session, next, onSessionUpdated)
            return true
        }

        override suspend fun generateText(options: JsonObject): JsonObject? =
            onGenerateText(options)
    }

    fun createLocalVariableBackend(
        getCurrentSession: () -> ChatSession?,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
    ): LocalVariableBackend = object : LocalVariableBackend {
        override fun snapshot(): Map<String, JsonElement> {
            val vars = getCurrentSession()?.metadata?.get("variables") as? JsonObject
                ?: return emptyMap()
            return LinkedHashMap(vars)
        }

        override fun update(
            transform: (MutableMap<String, JsonElement>) -> Unit,
        ): Map<String, JsonElement> = synchronized(sessionRuntime.sessionWriteLock) {
            val session = requireNotNull(getCurrentSession()) { "当前没有会话" }
            val mutable = LinkedHashMap<String, JsonElement>(
                (session.metadata["variables"] as? JsonObject) ?: JsonObject(emptyMap()),
            )
            transform(mutable)
            val newVars = buildJsonObject {
                mutable.forEach { (k, v) -> put(k, v) }
            }
            sessionRuntime.scheduleMetadataSave(
                session,
                session.copy(metadata = JsonObject(session.metadata + ("variables" to newVars))),
                onSessionUpdated,
            )
            mutable
        }
    }

    fun createMessageVariableBackend(
        getCurrentSession: () -> ChatSession?,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
    ): MessageVariableBackend = object : MessageVariableBackend {
        override fun messageCount(): Int =
            getCurrentSession()?.messages?.size ?: 0

        override fun messageVariables(index: Int): JsonObject? {
            val message = getCurrentSession()?.messages?.getOrNull(index) ?: return null
            return message.variables.getOrNull(message.swipeIndex) as? JsonObject
        }

        override fun lastIndexWithVariables(): Int {
            val messages = getCurrentSession()?.messages ?: return -1
            return messages.indexOfLast { it.variables.getOrNull(it.swipeIndex) is JsonObject }
        }

        override fun replaceMessageVariables(index: Int, variables: JsonObject) {
            synchronized(sessionRuntime.sessionWriteLock) {
                val session = getCurrentSession() ?: error("当前没有会话")
                val messages = session.messages.toMutableList()
                val message = messages.getOrNull(index) ?: error("消息不存在：$index")
                val vars = message.variables.toMutableList()
                while (vars.size <= message.swipeIndex) vars.add(buildJsonObject { })
                vars[message.swipeIndex] = variables
                messages[index] = message.copy(variables = vars)
                sessionRuntime.scheduleMetadataSave(
                    session,
                    session.copy(messages = messages),
                    onSessionUpdated,
                )
            }
        }
    }

    fun tavernMessageContextJson(
        token: RuntimeToken?,
        state: ChatUiState,
        sessionRuntime: ChatSessionRuntime,
        promptEngine: PromptEngine,
        extensionHost: ExtensionHost,
    ): String {
        sessionRuntime.requireMessageRuntime(token, state.currentSession?.id)
        val vars = promptEngine.snapshotPromptTemplateVariables()
        return buildJsonObject {
            buildTavernContext(
                state = state,
                runtimeGeneration = sessionRuntime.runtimeToken?.generation ?: -1,
                extensionHost = extensionHost,
            ).forEach { (key, value) -> put(key, value) }
            putJsonObject("variableScopes") {
                put("global", vars.global)
                put("chat", state.currentSession?.metadata?.get("variables") ?: vars.local)
                put(
                    "character",
                    state.selectedCharacter?.let { CharacterTavernHelperScripts.extractCharacterVariables(it) }
                        ?: buildJsonObject {},
                )
            }
        }.toString()
    }

    fun tavernMessageVariablesJson(
        token: RuntimeToken?,
        state: ChatUiState,
        sessionRuntime: ChatSessionRuntime,
        promptEngine: PromptEngine,
    ): String {
        sessionRuntime.requireMessageRuntime(token, state.currentSession?.id)
        val snapshot = promptEngine.snapshotPromptTemplateVariables()
        val local = (state.currentSession?.metadata?.get("variables") as? JsonObject)
            ?: snapshot.local
        val merged = buildJsonObject {
            snapshot.global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
            state.messages.forEach { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            }
        }
        return decodeEmbeddedJsonValues(merged).toString()
    }

    suspend fun reloadCharacterTavernHelperScripts(
        character: CharacterCard,
        preset: app.tellev.core.model.GenerationPreset?,
        extensionHost: ExtensionHost,
        permissionManager: ExtensionPermissionManager,
        currentLoadedId: String?,
        onLoadedIdChanged: (String?) -> Unit,
        onError: (String) -> Unit,
    ) {
        unloadCharacterTavernHelperScripts(extensionHost, currentLoadedId, onLoadedIdChanged)

        val scriptSource = CharacterTavernHelperScripts.buildIsolatedScriptSource(character, preset)
        if (scriptSource.isBlank()) return

        val extensionId = characterScriptExtensionId(character.id)
        val manifest = ExtensionManifest(
            id = extensionId,
            name = "Character TavernHelper: ${character.name}",
            version = "character-card",
            author = "character-card",
            description = "Scripts embedded in the selected character card.",
            permissions = setOf(
                ExtensionPermission.Storage,
                ExtensionPermission.ProviderRequest,
                ExtensionPermission.Clipboard,
                ExtensionPermission.UiPanel,
            ),
        )

        runCatching {
            val safePerms = manifest.permissions.filter {
                it != ExtensionPermission.ProviderRequest && it != ExtensionPermission.Secrets
            }
            permissionManager.grantAll(extensionId, safePerms)
            extensionHost.load(manifest, scriptSource)
            onLoadedIdChanged(extensionId)
            emitStEvent(extensionHost, StEventCatalog.APP_INITIALIZED)
            emitStEvent(extensionHost, StEventCatalog.APP_READY)
        }.onFailure { e ->
            onError("加载角色卡脚本失败：${e.message}")
        }
    }

    suspend fun unloadCharacterTavernHelperScripts(
        extensionHost: ExtensionHost,
        currentLoadedId: String?,
        onLoadedIdChanged: (String?) -> Unit,
    ) {
        val extensionId = currentLoadedId ?: return
        onLoadedIdChanged(null)
        runCatching { extensionHost.unload(extensionId) }
    }

    fun characterScriptExtensionId(characterId: String): String =
        "character-tavern-helper-" + characterId.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    suspend fun emitCharacterSelected(extensionHost: ExtensionHost, character: CharacterCard) {
        emitStEvent(
            extensionHost,
            StEventCatalog.CHARACTER_SELECTED,
            buildJsonObject {
                put("id", character.id)
                put("name", character.name)
            },
        )
    }

    suspend fun emitChatChanged(extensionHost: ExtensionHost, session: ChatSession) {
        emitStEvent(extensionHost, StEventCatalog.CHAT_CHANGED, session.id)
        emitStEvent(extensionHost, StEventCatalog.CHAT_LOADED, session.id)
        emitStEvent(extensionHost, StEventCatalog.WORLD_INFO_CHANGED, session.id)
    }

    suspend fun emitRenderedEventsForMessages(extensionHost: ExtensionHost, messages: List<ChatMessage>) {
        val start = (messages.size - RECENT_RENDERED_EVENT_LIMIT).coerceAtLeast(0)
        for (index in start until messages.size) {
            emitRenderedEventForMessage(extensionHost, index, messages[index], "load")
            if ((index - start) % 8 == 7) yield()
        }
    }

    suspend fun emitRenderedEventForMessage(
        extensionHost: ExtensionHost,
        index: Int,
        message: ChatMessage,
        type: String = "normal",
    ) {
        when (message.role) {
            MessageRole.User -> emitStEvent(extensionHost, StEventCatalog.USER_MESSAGE_RENDERED, index)
            MessageRole.Character,
            MessageRole.Assistant -> emitStEvent(extensionHost, StEventCatalog.CHARACTER_MESSAGE_RENDERED, index, type)
            else -> Unit
        }
    }

    suspend fun emitStEvent(extensionHost: ExtensionHost, name: String, vararg args: Any?) {
        extensionHost.emit(
            ExtensionEvent(
                name = name,
                payload = buildJsonObject {
                    putJsonArray("args") {
                        args.forEach { add(jsonElementOf(it)) }
                    }
                },
            ),
        )
    }

    fun buildTavernContext(
        state: ChatUiState,
        runtimeGeneration: Long,
        extensionHost: ExtensionHost,
        defaultContextTokens: Int = 1_000_000,
    ): JsonObject {
        val character = state.selectedCharacter
        val session = state.currentSession
        val personaName = state.selectedPersona?.name ?: "User"
        val characterName = character?.name ?: "Character"

        return buildJsonObject {
            put("name1", personaName)
            put("__runtimeGeneration", runtimeGeneration)
            put("name2", characterName)
            put("chatId", session?.id ?: "")
            put("characterId", character?.id ?: "")
            put("this_chid", if (character != null) 0 else -1)
            put("groupId", session?.groupId ?: "")
            put("selected_group", session?.groupId ?: "")
            put("mainApi", state.providerConfig?.providerType ?: ProviderConfigPersistence.adapterIdFor(state.selectedProvider))
            put("main_api", state.providerConfig?.providerType ?: ProviderConfigPersistence.adapterIdFor(state.selectedProvider))
            put("onlineStatus", "connected")
            put(
                "maxContext",
                state.selectedPreset?.maxContextTokens ?: defaultContextTokens,
            )
            put("lastMessageId", state.messages.lastIndex)
            put("chatMetadata", session?.metadata ?: buildJsonObject { })
            put("chat_metadata", session?.metadata ?: buildJsonObject { })
            putJsonArray("chat") {
                for (index in 0 until state.messages.size) {
                    add(state.messages[index].toTavernJson(index))
                }
            }
            val books = (state.worldBooks + listOfNotNull(character?.characterBook)).distinctBy { it.id }
            putJsonArray("worldBooks") {
                books.forEach { book ->
                    add(buildJsonObject {
                        put("id", book.id)
                        put("name", book.name)
                        putJsonArray("entries") {
                            book.entries.forEach { entry ->
                                add(buildJsonObject {
                                    entry.raw.forEach { (key, value) -> put(key, value) }
                                    put("uid", entry.id)
                                    put("comment", entry.comment)
                                    put("content", entry.content)
                                    put("disable", !entry.enabled)
                                })
                            }
                        }
                    })
                }
            }
            putJsonArray("characterWorldBooks") {
                character?.characterBook?.let { add(JsonPrimitive(it.name)) }
                character?.let {
                    app.tellev.core.model.CharacterWorldBinding.linkedWorldBookNames(it).forEach { name ->
                        add(JsonPrimitive(name))
                    }
                }
            }
            putJsonArray("globalWorldBooks") {
                state.worldBooks.filter { it.id !in state.disabledWorldIds }.forEach { add(JsonPrimitive(it.name)) }
            }
            putJsonArray("characters") {
                character?.let { add(it.toTavernJson()) }
            }
            putJsonArray("groups") { }
            character?.let {
                put("character", it.toTavernJson())
            }
            putJsonObject("extensionPrompts") { }
            put("extensionPrompts", buildJsonObject { })
            val extSettings = extensionHost.snapshotExtensionSettings()
            put("extensionSettings", extSettings)
            put("extension_settings", extSettings)
            putJsonArray("tags") { }
            put("tagMap", buildJsonObject { })
            put("tag_map", buildJsonObject { })
            put("chatCompletionSettings", buildJsonObject { })
            put("oai_settings", buildJsonObject { })
            put("textCompletionSettings", buildJsonObject { })
            put("powerUserSettings", buildJsonObject { })
            put("power_user", buildJsonObject { })
        }
    }

    fun ChatMessage.toTavernJson(index: Int): JsonObject =
        buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            val user = role == MessageRole.User
            put("id", id)
            put("index", index)
            put("name", name)
            put("mes", content)
            put("is_user", user)
            put("is_system", isHidden)
            put("role", role.name.lowercase())
            put("send_date", createdAtMillis.toString())
            put("send_date_unix", createdAtMillis)
            put("swipe_id", swipeIndex)
            putJsonArray("swipes") {
                val values = swipes.ifEmpty { listOf(content) }
                values.forEach { add(JsonPrimitive(it)) }
            }
            put(
                "extra",
                if (role == MessageRole.System && metadata["type"] == null) {
                    JsonObject(metadata + ("type" to JsonPrimitive("narrator")))
                } else metadata,
            )
            if (swipeInfo.isNotEmpty() || raw["swipe_info"] is JsonArray) put("swipe_info", JsonArray(swipeInfo))
            if (variables.isNotEmpty()) {
                putJsonArray("variables") { variables.forEach { add(it) } }
            }
            if (isEjsProcessed.isNotEmpty()) {
                putJsonArray("is_ejs_processed") { isEjsProcessed.forEach { add(it) } }
            }
            if (variablesInitialized.isNotEmpty()) {
                putJsonArray("variables_initialized") { variablesInitialized.forEach { add(it) } }
            }
        }

    fun CharacterCard.toTavernJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("personality", personality)
            put("scenario", scenario)
            put("first_mes", firstMessage)
            put("mes_example", exampleMessages)
            put("avatar", avatarRelativePath ?: "")
            putJsonArray("tags") {
                tags.forEach { add(JsonPrimitive(it)) }
            }
            put("data", (raw["data"] as? JsonObject) ?: raw)
        }

    fun jsonElementOf(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Float -> JsonPrimitive(value.toDouble())
            is Double -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value.toDouble())
            is List<*> -> buildJsonArray {
                value.forEach { add(jsonElementOf(it)) }
            }
            is Map<*, *> -> buildJsonObject {
                value.forEach { (key, element) ->
                    if (key != null) put(key.toString(), jsonElementOf(element))
                }
            }
            else -> JsonPrimitive(value.toString())
        }

    fun updateChatVariables(
        transform: (MutableMap<String, JsonElement>) -> Unit,
        state: ChatUiState,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
    ): Map<String, JsonElement> = synchronized(sessionRuntime.sessionWriteLock) {
        val session = requireNotNull(state.currentSession) { "当前没有会话" }
        val mutable = LinkedHashMap<String, JsonElement>(
            (session.metadata["variables"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
        transform(mutable)
        val newVars = buildJsonObject {
            mutable.forEach { (k, v) -> put(k, v) }
        }
        val desired = session.copy(metadata = JsonObject(session.metadata + ("variables" to newVars)))
        sessionRuntime.scheduleMetadataSave(session, desired, onSessionUpdated)
        mutable
    }

    fun tavernMessageContextJson(
        token: RuntimeToken?,
        state: ChatUiState,
        promptEngine: PromptEngine,
        sessionRuntime: ChatSessionRuntime,
        extensionHost: ExtensionHost,
    ): String {
        sessionRuntime.requireMessageRuntime(token, state.currentSession?.id)
        val vars = promptEngine.snapshotPromptTemplateVariables()
        return buildJsonObject {
            buildTavernContext(state, token?.generation ?: -1, extensionHost).forEach { (key, value) -> put(key, value) }
            putJsonObject("variableScopes") {
                put("global", vars.global)
                put("chat", state.currentSession?.metadata?.get("variables") ?: vars.local)
                put("character", state.selectedCharacter?.let { CharacterTavernHelperScripts.extractCharacterVariables(it) } ?: buildJsonObject {})
            }
        }.toString()
    }

    fun tavernMessageVariablesJson(
        token: RuntimeToken?,
        state: ChatUiState,
        promptEngine: PromptEngine,
        sessionRuntime: ChatSessionRuntime,
    ): String {
        sessionRuntime.requireMessageRuntime(token, state.currentSession?.id)
        val snapshot = promptEngine.snapshotPromptTemplateVariables()
        val local = (state.currentSession?.metadata?.get("variables") as? JsonObject)
            ?: snapshot.local
        val merged = buildJsonObject {
            snapshot.global.forEach { (key, value) -> put(key, value) }
            local.forEach { (key, value) -> put(key, value) }
            state.messages.forEach { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            }
        }
        return decodeEmbeddedJsonValues(merged).toString()
    }

    fun handleTavernMessageRequest(
        operation: String,
        payloadJson: String,
        onSetInput: (String) -> Unit,
        callback: (Boolean, String) -> Unit,
        token: RuntimeToken?,
        uiState: MutableStateFlow<ChatUiState>,
        scope: CoroutineScope,
        sessionRuntime: ChatSessionRuntime,
        extensionHost: ExtensionHost,
        promptEngine: PromptEngine,
        dataStore: StDataStore,
        characterScriptJob: Job?,
        loadedCharacterScriptExtensionId: String?,
        messageActions: ChatMessageActions,
        onDeleteMessage: (Int) -> Unit,
        onSendMessage: (String) -> Boolean,
        onSendMessageWithRole: (String, List<Attachment>, MessageRole) -> Boolean,
    ) {
        scope.launch {
            runCatching {
                sessionRuntime.requireMessageRuntime(token, uiState.value.currentSession?.id)
                val payload = (Json.parseToJsonElement(payloadJson) as? JsonObject) ?: buildJsonObject { }
                when (operation) {
                    "mvuReady", "mvuCall" -> {
                        characterScriptJob?.join()
                        sessionRuntime.requireMessageRuntime(token, uiState.value.currentSession?.id)
                        val runtime = extensionHost as? app.tellev.core.extension.WebViewJsExtensionHost ?: error("MVU runtime unavailable")
                        val owner = loadedCharacterScriptExtensionId ?: error("Character scripts are not ready")
                        val expression = if (operation == "mvuReady") "window.__tellevReady().then(()=>{if(!window.Mvu)throw Error('MVU is not initialized');return window.Mvu.events})"
                        else {
                            val method = payload["method"]?.jsonPrimitive?.content ?: error("Missing MVU method")
                            require(method in setOf("parseMessage", "parseMessages"))
                            "window.Mvu[" + JsonPrimitive(method) + "](..." + (payload["args"] ?: JsonArray(emptyList())) + ")"
                        }
                        Json.parseToJsonElement(runtime.evaluateRuntime(owner, expression))
                    }
                    "setChatMessages" -> {
                        val session = uiState.value.currentSession ?: error("No chat")
                        val updates = payload["messages"] as? JsonArray ?: error("Missing messages")
                        val messages = applyTavernChatMessages(session.messages, updates)
                        val next = session.copy(messages = messages)
                        sessionRuntime.persistSessionMutation(session, next) { updated ->
                            uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                        }
                        buildJsonObject { put("ok", true) }
                    }
                    "replaceVariables" -> {
                        val options = payload["options"] as? JsonObject ?: buildJsonObject { put("type", "chat") }
                        val variables = payload["variables"] as? JsonObject ?: error("Invalid variables")
                        when (options["type"]?.jsonPrimitive?.content ?: "chat") {
                            "chat" -> updateChatVariables({ it.clear(); it.putAll(variables) }, uiState.value, sessionRuntime) { updated ->
                                uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                            }
                            "global" -> promptEngine.persistGlobalPromptTemplateVariables(variables)
                            "message" -> {
                                val session = uiState.value.currentSession ?: error("No chat")
                                val id = options["message_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                                val updates = buildJsonArray { add(buildJsonObject { put("message_id", id); put("data", variables) }) }
                                val messages = applyTavernChatMessages(session.messages, updates)
                                val next = session.copy(messages = messages)
                                sessionRuntime.persistSessionMutation(session, next) { updated ->
                                    uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                                }
                            }
                            else -> error("Unsupported writable variable scope")
                        }
                        buildJsonObject { put("ok", true) }
                    }
                    "getChatMessages" -> {
                        val state = uiState.value
                        val requested = payload["messageId"]?.jsonPrimitive?.content?.toIntOrNull()
                        buildJsonArray {
                            state.messages.forEachIndexed { index, message ->
                                if (requested == null || requested == index) add(message.toTavernJson(index))
                            }
                        }
                    }
                    "setChatMessage" -> {
                        val index = payload["messageId"]?.jsonPrimitive?.content?.toIntOrNull()
                            ?: error("消息索引无效")
                        val content = payload["message"]?.jsonPrimitive?.content.orEmpty()
                        val options = payload["options"] as? JsonObject ?: buildJsonObject { }
                        val state = uiState.value
                        val session = state.currentSession ?: error("当前没有会话")
                        require(index in state.messages.indices) { "消息不存在：$index" }
                        val messages = state.messages.toMutableList()
                        val original = messages[index]
                        val requestedSwipe = options["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull()
                        val updated = setTavernMessageSwipe(original, content, requestedSwipe)
                        messages[index] = updated
                        val updatedSession = session.copy(messages = messages)
                        sessionRuntime.persistSessionMutation(session, updatedSession) { updatedSessionVal ->
                            uiState.update { if (it.currentSession?.id == updatedSessionVal.id) it.copy(currentSession = updatedSessionVal, messages = updatedSessionVal.messages) else it }
                        }
                        emitStEvent(extensionHost, StEventCatalog.MESSAGE_SWIPED, index)
                        emitStEvent(extensionHost, StEventCatalog.MESSAGE_UPDATED, index)
                        emitRenderedEventForMessage(extensionHost, index, updated, "swipe")
                        buildJsonObject { put("ok", true); put("message_id", index); put("swipe_id", updated.swipeIndex) }
                    }
                    "getLorebooks" -> buildJsonArray {
                        dataStore.listWorldBooks().forEach { add(JsonPrimitive(it.name)) }
                    }
                    "createLorebook" -> {
                        val name = payload["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        require(name.isNotEmpty()) { "世界书名称不能为空" }
                        val existing = dataStore.listWorldBooks().firstOrNull { it.name == name || it.id == name }
                        val book = existing ?: WorldBook(
                            id = "wb_${UUID.randomUUID()}",
                            name = name,
                            entries = emptyList(),
                        ).also { dataStore.saveWorldBook(it) }
                        buildJsonObject {
                            put("id", book.id)
                            put("name", book.name)
                        }
                    }
                    "createLorebookEntry" -> {
                        val name = payload["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                        val entryJson = payload["entry"] as? JsonObject
                            ?: error("世界书条目格式无效")
                        val book = dataStore.listWorldBooks().firstOrNull { it.name == name || it.id == name }
                            ?: error("世界书不存在：$name")
                        val entry = tavernWorldBookEntry(entryJson, book.entries.size.toString())
                        val updated = book.copy(entries = book.entries + entry)
                        dataStore.saveWorldBook(updated)
                        buildJsonObject {
                            put("id", entry.id)
                            put("ok", true)
                        }
                    }
                    "triggerSlash" -> {
                        val script = payload["script"]?.jsonPrimitive?.content.orEmpty()
                        val action = parseTavernMessageSlashCommand(script)
                        action.setInputText?.let(onSetInput)
                        if (action.systemText != null) {
                            val worlds = dataStore.listWorldBooks()
                            uiState.update { it.copy(worldBooks = worlds) }
                            action.deleteMessageIndex?.let { index ->
                                if (messageActions.removeTavernMessageWithoutSaving(index, uiState.value) { updated ->
                                    uiState.update { if (it.currentSession?.id == updated.id) it.copy(currentSession = updated, messages = updated.messages) else it }
                                }) {
                                    emitStEvent(extensionHost, StEventCatalog.MESSAGE_DELETED, index)
                                }
                            }
                        } else {
                            action.deleteMessageIndex?.let(onDeleteMessage)
                        }
                        val handled = when {
                            action.systemText != null -> onSendMessageWithRole(
                                action.systemText,
                                emptyList(),
                                MessageRole.System,
                            )
                            action.sendText != null -> onSendMessage(action.sendText)
                            action.setInputText != null -> true
                            action.deleteMessageIndex != null -> true
                            else -> false
                        }
                        if (action.systemText != null && !handled) {
                            sessionRuntime.flushSessionWrites(uiState.value.currentSession?.id, extensionHost)
                        }
                        if (handled) {
                            buildJsonObject {
                                put("handled", true)
                                put("pipe", "")
                            }
                        } else {
                            val engineResult = extensionHost.executeStScript(script)
                            buildJsonObject {
                                put("handled", engineResult.handled)
                                put("pipe", engineResult.output)
                                engineResult.metadata["isError"]?.let { put("isError", it) }
                                engineResult.metadata["errorMessage"]?.let { put("errorMessage", it) }
                            }
                        }
                    }
                    else -> error("不支持的消息渲染操作：$operation")
                }
            }.onSuccess { result ->
                callback(true, result.toString())
            }.onFailure { error ->
                callback(
                    false,
                    buildJsonObject { put("error", error.message ?: "消息渲染操作失败") }.toString(),
                )
            }
        }
    }
}
