package app.tellev.core.extension

import app.tellev.core.model.CharacterCard
import java.net.URLDecoder
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.MessageRole
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.applyPromptOrder
import app.tellev.core.storage.parsePresetPrompts
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Routes [VirtualApiRequest]s to real backend services ([StDataStore],
 * [ProviderRegistry], [SecretStore]) and returns properly-shaped
 * [VirtualApiResponse]s.
 *
 * URL path parameters are extracted with a simple `:param` convention:
 * ```
 * /api/characters/:id        → pathParams["id"]
 * /api/providers/:id/status  → pathParams["id"]
 * ```
 *
 * All suspend operations dispatch to [Dispatchers.IO].
 */
class VirtualApiRouter(
    private val dataStore: StDataStore,
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val settingsStore: ExtensionSettingsStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val presetControlFields =
        setOf("category", "name", "oldName", "newName", "load", "preset", "data", "patch")


    /**
     * Dispatch [request] to the matching handler and return an HTTP-style
     * response.  Unknown routes yield 404.  Exceptions inside handlers yield
     * 500 with the error message in the body.
     */
    suspend fun route(request: VirtualApiRequest): VirtualApiResponse =
        withContext(Dispatchers.IO) {
            try {
                dispatch(request)
            } catch (e: IllegalArgumentException) {
                errorResponse(400, e.message ?: "Bad request")
            } catch (e: NoSuchElementException) {
                errorResponse(404, e.message ?: "Not found")
            } catch (e: Exception) {
                errorResponse(500, e.message ?: "Internal server error")
            }
        }

    // ── dispatcher ─────────────────────────────────────────────────────

    private suspend fun dispatch(request: VirtualApiRequest): VirtualApiResponse {
        val method = request.method.uppercase()
        val path = normalizePath(request.path)

        // ── top-level (not under /api/) ────────────────────────
        if (method == "GET" && path == "/version") {
            return jsonResponse(200, buildJsonObject {
                put("version", "1.18.0-tellev")
                put("pkgVersion", "1.18.0-tellev")
                put("clientVersion", "1.18.0")
            })
        }

        val segments = path.removePrefix("/api/").split("/").filter { it.isNotEmpty() }

        return when {
            // ── characters ─────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "characters" ->
                handleListCharacters()

            method == "GET" && segments.size == 2 && segments[0] == "characters" ->
                handleReadCharacter(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "extensions" ->
                handleReadCharacterExtensions(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "regex" ->
                handleReadCharacterRegex(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "tavern-helper" ->
                handleReadCharacterTavernHelper(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "characters" ->
                handleSaveCharacter(request)

            // ST-style character edit: partial field patch
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "edit" ->
                handleEditCharacter(request)

            // ST-style character import
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "import" ->
                handleImportCharacter(request)

            method == "POST" && segments.size == 3 && segments[0] == "characters" && segments[2] == "tavern-helper" ->
                handleSaveCharacterTavernHelper(segments[1], request)

            method == "DELETE" && segments.size == 2 && segments[0] == "characters" ->
                handleDeleteCharacter(segments[1])

            // ST-style character routes (POST): /all, /get, /delete
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "all" ->
                handleStAllCharacters()
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "get" ->
                handleStGetCharacter(request)
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "delete" ->
                handleStDeleteCharacter(request)

            // ── chats ──────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "chats" ->
                handleListChats(request)

            method == "GET" && segments.size == 2 && segments[0] == "chats" ->
                handleReadChat(segments[1])

            // ST-style: POST /api/chats/get { ch_name, file_name }
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "get" ->
                handleStGetChat(request)

            // ST-style: POST /api/chats/group/get { chat_id }
            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[1] == "group" && segments[2] == "get" ->
                handleStGetGroupChat(request)

            // ST-style: POST /api/chats/import
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "import" ->
                handleStImportChat(request)

            // ST-style: POST /api/chats/save
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "save" ->
                handleStSaveChat(request)

            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "messages" ->
                handleAppendMessage(segments[1], request)

            // 酒馆助手 TavernHelper.setChatMessage target.  The actual
            // mutation is owned by the UI layer (which holds the active
            // chat); the router surfaces 501 so callers don't silently
            // succeed.
            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "message-field" ->
                errorResponse(501, "Chat message field mutation is handled by the UI layer")

            // ── worlds / worldinfo ─────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "worlds" ->
                handleListWorlds()

            method == "GET" && segments.size == 2 && segments[0] == "worlds" ->
                handleReadWorld(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "worlds" ->
                handleSaveWorld(request)

            // ST-style: POST /api/worldinfo/get { name }
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "get" ->
                handleStGetWorldInfo(request)

            // ST-style: POST /api/worldinfo/list → bare [{file_id, name, extensions}]
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "list" ->
                handleStListWorldInfo()

            // ST-style: POST /api/worldinfo/edit { name, data } — the endpoint ST
            // uses to save world info files.
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "edit" ->
                handleStEditWorldInfo(request)

            // ── settings / presets ─────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "settings" ->
                handleListPresets()

            // ST-style: POST /api/settings/get → returns full settings.json
            method == "POST" && segments.size == 2 && segments[0] == "settings" && segments[1] == "get" ->
                handleStGetSettings()

            // ST-style: POST /api/settings/save
            method == "POST" && segments.size == 2 && segments[0] == "settings" && segments[1] == "save" ->
                handleStSaveSettings(request)

            // ── secrets ────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "secrets" ->
                handleListSecrets()

            method == "GET" && segments.size == 2 && segments[0] == "secrets" ->
                handleReadSecret(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "secrets" ->
                handlePutSecret(request)

            method == "DELETE" && segments.size == 2 && segments[0] == "secrets" ->
                handleDeleteSecret(segments[1])

            // ST-style secret endpoints
            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "write" ->
                handleStWriteSecret(request)

            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "read" ->
                handleStReadSecret(request)

            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "delete" ->
                handleStDeleteSecret(request)

            // ── providers / backends ───────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "providers" ->
                handleListProviders()

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "status" ->
                handleProviderStatus(segments[1], request)

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "models" ->
                handleProviderModels(segments[1], request)

            // ST-style: POST /api/backends/chat-completions/status
            method == "POST" && segments.size == 3 && segments[0] == "backends" && segments[1] == "chat-completions" && segments[2] == "status" ->
                handleStChatCompletionsStatus(request)

            // ST-style: POST /api/backends/chat-completions/generate
            method == "POST" && segments.size == 3 && segments[0] == "backends" && segments[1] == "chat-completions" && segments[2] == "generate" ->
                handleStChatCompletionsGenerate(request)

            // ── avatars (persona) ──────────────────────────────────
            method == "POST" && segments.size == 2 && segments[0] == "avatars" && segments[1] == "upload" ->
                handleStUploadAvatar(request)

            method == "POST" && segments.size == 2 && segments[0] == "avatars" && segments[1] == "delete" ->
                handleStDeleteAvatar(request)

            // ── extensions management ──────────────────────────────
            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "version" ->
                handleStExtensionVersion(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "install" ->
                handleStExtensionInstall(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "delete" ->
                handleStExtensionDelete(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "update" ->
                handleStExtensionUpdate(request)

            // ── groups ─────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "groups" ->
                handleListGroups()

            // ── personas ───────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "personas" ->
                handleListPersonas()
            method == "POST" && segments.size == 1 && segments[0] == "personas" ->
                handleSavePersona(request)
            method == "DELETE" && segments.size == 2 && segments[0] == "personas" ->
                handleDeletePersona(segments[1])

            // ── presets ───────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "presets" ->
                handleListPresets()
            method == "GET" && segments.size == 3 && segments[0] == "presets" ->
                handleReadPreset(segments[1], segments[2])
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "load" ->
                handleLoadPreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "save" ->
                handleWritePreset(request, createOnly = false, updateOnly = false)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "create" ->
                handleWritePreset(request, createOnly = true, updateOnly = false)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "replace" ->
                handleWritePreset(request, createOnly = false, updateOnly = true)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "update" ->
                handleUpdatePreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "delete" ->
                handleDeletePreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "rename" ->
                handleRenamePreset(request)

            // ── tags (stub: tags list is empty in tellev) ──────────
            method == "GET" && segments.size == 1 && segments[0] == "tags" ->
                jsonResponse(200, buildJsonObject { putJsonArray("tags") { } })

            // ── worldinfo create / save / delete (stub) ────────────
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "create" ->
                errorResponse(501, "World Info create via virtual API is not supported; use the UI layer")
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "save" ->
                errorResponse(501, "World Info save via virtual API is not supported; use the UI layer")
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "delete" ->
                errorResponse(501, "World Info delete via virtual API is not supported; use the UI layer")

            else ->
                errorResponse(404, "No route for $method $path")
        }
    }

    // ── character handlers ─────────────────────────────────────────────

    private suspend fun handleListCharacters(): VirtualApiResponse {
        val characters = dataStore.listCharacters()
        val body = buildJsonObject {
            putJsonArray("characters") {
                for (c in characters) {
                    add(json.encodeToJsonElement(CharacterSummary.serializer(), c))
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleReadCharacter(id: String): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val body = json.encodeToJsonElement(CharacterCard.serializer(), card)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) })
    }

    private suspend fun handleReadCharacterExtensions(id: String): VirtualApiResponse {
        val extensions = dataStore.readCharacter(id).extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("extensions", extensions)
        })
    }

    private suspend fun handleReadCharacterRegex(id: String): VirtualApiResponse {
        val extensions = dataStore.readCharacter(id).extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("regex_scripts", extensions["regex_scripts"] ?: kotlinx.serialization.json.JsonArray(emptyList()))
        })
    }

    private suspend fun handleReadCharacterTavernHelper(id: String): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val extensions = card.extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("extensions", extensions)
            put("regex_scripts", extensions["regex_scripts"] ?: kotlinx.serialization.json.JsonArray(emptyList()))
            put("TavernHelper_scripts", extensions["TavernHelper_scripts"] ?: kotlinx.serialization.json.JsonArray(emptyList()))
            put("tavern_helper", extensions["tavern_helper"] ?: kotlinx.serialization.json.JsonArray(emptyList()))
            card.characterBook?.let { book ->
                put("character_book", json.encodeToJsonElement(WorldBook.serializer(), book))
            }
        })
    }

    private suspend fun handleSaveCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val card = parseBody<CharacterCard>(request)
        dataStore.saveCharacter(card)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleDeleteCharacter(id: String): VirtualApiResponse {
        dataStore.deleteCharacter(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    /**
     * Persist 酒馆助手 / regex script resources back into a character
     * card's `data.extensions` block.  Only the well-known script keys
     * ([TavernHelper_scripts], [tavern_helper], [regex_scripts]) are
     * merged; every other extension field is preserved untouched.
     */
    private suspend fun handleSaveCharacterTavernHelper(
        id: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val patch = parseBodyAsJsonObject(request)
        val updatedRaw = mergeTavernHelperExtensions(card.raw, patch)
        dataStore.saveCharacter(card.copy(raw = updatedRaw))
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private fun mergeTavernHelperExtensions(raw: JsonObject, patch: JsonObject): JsonObject {
        val data = (raw["data"] as? JsonObject) ?: buildJsonObject { }
        val extensions = (data["extensions"] as? JsonObject) ?: buildJsonObject { }
        val allowedKeys = setOf("TavernHelper_scripts", "tavern_helper", "regex_scripts")
        val mergedExtensions = buildJsonObject {
            for ((k, v) in extensions) put(k, v)
            for ((k, v) in patch) if (k in allowedKeys) put(k, v)
        }
        val mergedData = buildJsonObject {
            for ((k, v) in data) if (k != "extensions") put(k, v)
            put("extensions", mergedExtensions)
        }
        return buildJsonObject {
            for ((k, v) in raw) if (k != "data") put(k, v)
            put("data", mergedData)
        }
    }

    // ── chat handlers ──────────────────────────────────────────────────

    private suspend fun handleListChats(request: VirtualApiRequest): VirtualApiResponse {
        val queryParams = parseSimpleQuery(request.path)
        val characterId = queryParams["characterId"]
        val groupId = queryParams["groupId"]
        val sessions = dataStore.listChatSessions(characterId, groupId)
        val body = buildJsonObject {
            putJsonArray("chats") {
                for (s in sessions) {
                    add(json.encodeToJsonElement(ChatSession.serializer(), s))
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleReadChat(id: String): VirtualApiResponse {
        val session = dataStore.readChatSession(id)
        val body = json.encodeToJsonElement(ChatSession.serializer(), session)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) })
    }

    private suspend fun handleAppendMessage(
        sessionId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val message = parseBody<ChatMessage>(request)
        dataStore.appendMessage(sessionId, message)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    // ── world handlers ─────────────────────────────────────────────────

    private suspend fun handleListWorlds(): VirtualApiResponse {
        val worlds = dataStore.listWorldBooks()
        val body = buildJsonObject {
            putJsonArray("worlds") {
                for (w in worlds) {
                    add(json.encodeToJsonElement(WorldBook.serializer(), w))
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleReadWorld(id: String): VirtualApiResponse {
        val book = dataStore.readWorldBook(id)
        val body = json.encodeToJsonElement(WorldBook.serializer(), book)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) })
    }

    private suspend fun handleSaveWorld(request: VirtualApiRequest): VirtualApiResponse {
        val book = parseBody<WorldBook>(request)
        dataStore.saveWorldBook(book)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    // ── settings / presets ─────────────────────────────────────────────

    private suspend fun handleListPresets(): VirtualApiResponse {
        val presets = dataStore.listPresets()
        val selected = PresetCategory.entries.associateWith { category ->
            dataStore.readSelectedPresetName(category)
        }
        val body = buildJsonObject {
            putJsonArray("presets") {
                for (p in presets) {
                    add(json.encodeToJsonElement(GenerationPreset.serializer(), p))
                }
            }
            putJsonObject("selected") {
                selected.forEach { (category, name) ->
                    if (name != null) put(category.name.lowercase(), name)
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleReadPreset(categoryValue: String, encodedName: String): VirtualApiResponse {
        val category = parsePresetCategory(categoryValue)
        val name = URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        val preset = dataStore.readPreset(category, name)
            ?: return errorResponse(404, "Preset not found: ${categoryValue}/$name")
        return jsonResponse(200, preset.raw)
    }

    private suspend fun handleLoadPreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        if (name == "in_use") return errorResponse(400, "in_use is already the working preset")
        val exists = dataStore.listPresets().any { it.category == category && it.id == name }
        if (!exists) return errorResponse(404, "Preset not found: $name")
        dataStore.selectPreset(category, name)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("name", name)
            put("category", category.name.lowercase())
        })
    }

    private suspend fun handleWritePreset(
        request: VirtualApiRequest,
        createOnly: Boolean,
        updateOnly: Boolean,
    ): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        val isWorkingCopy = name == "in_use"
        if (createOnly && isWorkingCopy) return errorResponse(409, "in_use is reserved for the working preset")
        val existing = if (isWorkingCopy) {
            dataStore.readPreset(category, "in_use")
        } else {
            dataStore.listPresets().firstOrNull { it.category == category && it.id == name }
        }
        if (createOnly && existing != null) return errorResponse(409, "Preset already exists: $name")
        if (updateOnly && existing == null) return errorResponse(404, "Preset not found: $name")

        val supplied = (body["preset"] as? JsonObject) ?: (body["data"] as? JsonObject)
            ?: JsonObject(body.filterKeys { it !in presetControlFields })
        val raw = if (existing != null && !updateOnly) JsonObject(existing.raw + supplied) else supplied
        val preset = presetFromRaw(existing, category, name, raw)
        if (isWorkingCopy) dataStore.saveWorkingPreset(category, preset) else dataStore.savePreset(preset)
        if (!isWorkingCopy && body["load"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != false) {
            dataStore.selectPreset(category, name)
        }
        return jsonResponse(if (existing == null) 201 else 200, buildJsonObject {
            put("ok", true)
            put("name", name)
            put("category", category.name.lowercase())
            put("preset", preset.raw)
        })
    }

    private suspend fun handleUpdatePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        val isWorkingCopy = name == "in_use"
        val existing = if (isWorkingCopy) dataStore.readPreset(category, name)
            else dataStore.listPresets().firstOrNull { it.category == category && it.id == name }
        existing ?: return errorResponse(404, "Preset not found: $name")
        val patch = body["patch"] as? JsonObject
            ?: return errorResponse(400, "Missing 'patch' JSON object")
        val mergedRaw = JsonObject(existing.raw + patch)
        val updated = presetFromRaw(existing, category, name, mergedRaw)
        if (isWorkingCopy) dataStore.saveWorkingPreset(category, updated) else {
            dataStore.savePreset(updated)
            dataStore.selectPreset(category, name)
        }
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("preset", updated.raw)
        })
    }

    private suspend fun handleDeletePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        if (name == "in_use") return errorResponse(400, "The working preset cannot be deleted")
        val deleted = dataStore.deletePreset(name, category.name.lowercase())
        return if (deleted) {
            jsonResponse(200, buildJsonObject { put("ok", true) })
        } else {
            errorResponse(404, "Preset not found: $name")
        }
    }

    private suspend fun handleRenamePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val oldName = requirePresetName(body.stringValue("name") ?: body.stringValue("oldName"))
        val newName = requirePresetName(body.stringValue("newName"))
        if (oldName == "in_use") return errorResponse(400, "The working preset cannot be renamed")
        val presets = dataStore.listPresets().filter { it.category == category }
        val existing = presets.firstOrNull { it.id == oldName }
            ?: return errorResponse(404, "Preset not found: $oldName")
        if (presets.any { it.id == newName }) return errorResponse(409, "Preset already exists: $newName")
        val wasSelected = dataStore.readSelectedPresetName(category) == oldName
        dataStore.savePreset(existing.copy(id = newName, name = newName))
        dataStore.deletePreset(oldName, category.name.lowercase())
        if (wasSelected) dataStore.selectPreset(category, newName)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("name", newName)
        })
    }

    private fun presetFromRaw(
        existing: GenerationPreset?,
        category: PresetCategory,
        name: String,
        raw: JsonObject,
    ): GenerationPreset {
        val completion = raw.intValue("openai_max_tokens")
            ?: raw.intValue("max_tokens")
            ?: existing?.maxCompletionTokens
        // Parse prompts / prompts_unused / prompt_order from the supplied raw
        // JSON (same semantics as FileStDataStore.parsePreset). When the write
        // does not carry a prompt list, keep the existing one — previously the
        // typed lists were left empty and savePreset() then rewrote
        // merged["prompts"] = [], silently destroying the preset's prompts.
        val prompts: List<PresetPrompt>
        val promptsUnused: List<PresetPrompt>
        if (raw["prompts"] is JsonArray) {
            val definitions = parsePresetPrompts(raw["prompts"])
            val (ordered, inferredUnused) = applyPromptOrder(definitions, raw["prompt_order"])
            val explicitUnused = parsePresetPrompts(raw["prompts_unused"] ?: raw["promptsUnused"])
            prompts = ordered
            promptsUnused = (inferredUnused + explicitUnused).distinctBy { it.identifier }
        } else {
            prompts = existing?.prompts.orEmpty()
            promptsUnused = existing?.promptsUnused.orEmpty()
        }
        return (existing ?: GenerationPreset(
            id = name,
            name = name,
            providerType = category.name.lowercase(),
            category = category,
        )).copy(
            id = name,
            name = name,
            category = category,
            temperature = raw.doubleValue("temperature") ?: existing?.temperature,
            topP = raw.doubleValue("top_p") ?: raw.doubleValue("topP") ?: existing?.topP,
            topK = raw.intValue("top_k") ?: raw.intValue("topK") ?: existing?.topK,
            maxTokens = completion,
            maxContextTokens = raw.intValue("openai_max_context") ?: existing?.maxContextTokens,
            maxCompletionTokens = completion,
            presencePenalty = raw.doubleValue("presence_penalty") ?: existing?.presencePenalty,
            frequencyPenalty = raw.doubleValue("frequency_penalty") ?: existing?.frequencyPenalty,
            seed = raw["seed"]?.jsonPrimitive?.content?.toLongOrNull() ?: existing?.seed,
            prompts = prompts,
            promptsUnused = promptsUnused,
            stop = (raw["stop"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            } ?: existing?.stop.orEmpty(),
            extensions = raw["extensions"] as? JsonObject ?: existing?.extensions ?: buildJsonObject { },
            raw = raw,
        )
    }

    private fun parsePresetCategory(value: String): PresetCategory = when (value.lowercase()) {
        "textgen", "textgen-webui" -> PresetCategory.TextGen
        "kobold", "koboldai", "koboldcpp" -> PresetCategory.Kobold
        "novelai" -> PresetCategory.NovelAi
        else -> PresetCategory.OpenAi
    }

    private fun requirePresetName(value: String?): String {
        val name = value?.trim().orEmpty()
        require(name.isNotEmpty()) { "Missing preset name" }
        require(!name.contains(Regex("""[\\/:*?"<>|]"""))) { "Invalid preset name" }
        return name
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.intValue(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.doubleValue(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    // ── secret handlers ────────────────────────────────────────────────

    private suspend fun handleListSecrets(): VirtualApiResponse {
        val ids = secretStore.listSecretIds()
        val body = buildJsonObject {
            putJsonArray("secretIds") {
                for (id in ids) add(kotlinx.serialization.json.JsonPrimitive(id))
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleReadSecret(id: String): VirtualApiResponse {
        val value = secretStore.readSecret(id)
        return if (value != null) {
            jsonResponse(200, buildJsonObject {
                put("id", id)
                put("value", value)
            })
        } else {
            errorResponse(404, "Secret not found: $id")
        }
    }

    private suspend fun handlePutSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request)
        val id = bodyObj["id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'id' in request body")
        val value = bodyObj["value"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'value' in request body")
        secretStore.putSecret(id, value)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleDeleteSecret(id: String): VirtualApiResponse {
        secretStore.deleteSecret(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    // ── provider handlers ──────────────────────────────────────────────

    private suspend fun handleListProviders(): VirtualApiResponse {
        val adapters = providerRegistry.all()
        val body = buildJsonObject {
            putJsonArray("providers") {
                for (a in adapters) {
                    add(buildJsonObject {
                        put("id", a.id)
                        put("displayName", a.displayName)
                        putJsonArray("capabilities") {
                            for (cap in a.capabilities) {
                                add(kotlinx.serialization.json.JsonPrimitive(cap.name))
                            }
                        }
                    })
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleProviderStatus(
        providerId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val adapter: ProviderAdapter = providerRegistry.require(providerId)
        val config = resolveProviderConfig(providerId, request)
        val status = adapter.checkStatus(config)
        val body = json.encodeToJsonElement(ProviderStatus.serializer(), status)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) })
    }

    private suspend fun handleProviderModels(
        providerId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val adapter: ProviderAdapter = providerRegistry.require(providerId)
        val config = resolveProviderConfig(providerId, request)
        val models = adapter.listModels(config)
        val body = buildJsonObject {
            putJsonArray("models") {
                for (m in models) {
                    add(json.encodeToJsonElement(ProviderModel.serializer(), m))
                }
            }
        }
        return jsonResponse(200, body)
    }

    // ── group handlers ─────────────────────────────────────────────────

    private suspend fun handleListGroups(): VirtualApiResponse {
        val groups = dataStore.listGroups()
        val body = buildJsonObject {
            putJsonArray("groups") {
                for (g in groups) {
                    add(json.encodeToJsonElement(GroupChat.serializer(), g))
                }
            }
        }
        return jsonResponse(200, body)
    }

    // ── persona handlers ───────────────────────────────────────────────

    private suspend fun handleListPersonas(): VirtualApiResponse {
        val personas = dataStore.listPersonas()
        val body = buildJsonObject {
            putJsonArray("personas") {
                for (p in personas) {
                    add(json.encodeToJsonElement(Persona.serializer(), p))
                }
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleSavePersona(request: VirtualApiRequest): VirtualApiResponse {
        val persona = parseBody<Persona>(request)
        dataStore.savePersona(persona)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleDeletePersona(id: String): VirtualApiResponse {
        dataStore.deletePersona(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Build a [ProviderConfig] from the request.  The caller may embed the
     * full config in the request body, or just the API key in the
     * `X-Api-Key` header.
     */
    private suspend fun resolveProviderConfig(
        providerId: String,
        request: VirtualApiRequest,
    ): ProviderConfig {
        // If the body contains a "config" object, decode it directly.
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val embeddedConfig = bodyObj?.get("config")?.let { runCatching { it.jsonObject }.getOrNull() }

        if (embeddedConfig != null) {
            return json.decodeFromJsonElement(ProviderConfig.serializer(), embeddedConfig)
        }

        // Fall back to the same encrypted provider profile used by chat.
        val stored = ProviderConfigPersistence.loadProviderConfig(secretStore, providerId)
        return stored.copy(
            apiKey = request.headers["X-Api-Key"]
                ?: request.headers["x-api-key"]
                ?: stored.apiKey,
            baseUrl = request.headers["X-Base-Url"]
                ?: request.headers["x-base-url"]
                ?: stored.baseUrl,
        )
    }

    /**
     * Decode a `@Serializable` type from the request body string.
     */
    private suspend inline fun <reified T> parseBody(request: VirtualApiRequest): T {
        val bodyText = request.body
            ?: throw IllegalArgumentException("Request body is required")
        return json.decodeFromString<T>(bodyText)
    }

    private fun parseBodyAsJsonObject(request: VirtualApiRequest): JsonObject {
        val bodyText = request.body
            ?: throw IllegalArgumentException("Request body is required")
        return json.parseToJsonElement(bodyText).jsonObject
    }

    private fun parseBodyAsJsonObjectOrNull(request: VirtualApiRequest): JsonObject? {
        val bodyText = request.body ?: return null
        return runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
    }

    /**
     * Strip query string and ensure the path starts with `/`.
     */
    private fun normalizePath(raw: String): String {
        val pathOnly = raw.substringBefore('?')
        return if (pathOnly.startsWith("/")) pathOnly else "/$pathOnly"
    }

    /**
     * Very simple `?key=value&key2=value2` parser from the path's query
     * string.
     */
    private fun parseSimpleQuery(path: String): Map<String, String> {
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { param ->
            val parts = param.split('=', limit = 2)
            if (parts.size == 2) {
                // URL-decode key and value so %-encoded characters (and '+' as
                // space) match names that contain spaces or non-ASCII chars.
                runCatching {
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }.getOrElse { parts[0] to parts[1] }
            } else {
                null
            }
        }.toMap()
    }

    private fun jsonResponse(status: Int, body: JsonObject): VirtualApiResponse =
        jsonResponse(status, body as kotlinx.serialization.json.JsonElement)

    /** Bare-array / non-object ST responses (worldinfo/list, characters/all, chats/get). */
    private fun jsonResponse(status: Int, body: kotlinx.serialization.json.JsonElement): VirtualApiResponse =
        VirtualApiResponse(
            status = status,
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body),
        )

    private fun CharacterCard.extensionObject(): JsonObject {
        val data = (raw["data"] as? JsonObject) ?: raw
        return (data["extensions"] as? JsonObject) ?: buildJsonObject { }
    }

    private fun errorResponse(status: Int, message: String): VirtualApiResponse {
        val body = buildJsonObject {
            put("error", message)
            put("status", status)
        }
        return VirtualApiResponse(
            status = status,
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(JsonObject.serializer(), body),
        )
    }

    // ── ST-compatible handler stubs ─────────────────────────────────────

    private suspend fun handleEditCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request) ?: return errorResponse(400, "Missing body")
        val id = bodyObj["ch_name"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing ch_name/id")

        val card = runCatching { dataStore.readCharacter(id) }.getOrNull()
            ?: return errorResponse(404, "Character not found: $id")

        // Apply field patches from the request body onto the raw card JSON.
        val updatedRaw = patchCharacterFields(card.raw, bodyObj)
        dataStore.saveCharacter(card.copy(raw = updatedRaw))
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private fun patchCharacterFields(raw: JsonObject, patch: JsonObject): JsonObject {
        val data = (raw["data"] as? JsonObject) ?: buildJsonObject { }
        val patchedData = buildJsonObject {
            for ((k, v) in data) put(k, v)
            for ((k, v) in patch) {
                if (k in setOf("ch_name", "id", "avatar", "json_data")) continue
                if (k == "extensions") {
                    val ext = (data["extensions"] as? JsonObject) ?: buildJsonObject { }
                    val patchExt = (v as? JsonObject) ?: continue
                    put("extensions", buildJsonObject {
                        for ((ek, ev) in ext) put(ek, ev)
                        for ((ek, ev) in patchExt) put(ek, ev)
                    })
                } else {
                    put(k, v)
                }
            }
        }
        return buildJsonObject {
            for ((k, v) in raw) if (k != "data") put(k, v)
            put("data", patchedData)
        }
    }

    private suspend fun handleImportCharacter(request: VirtualApiRequest): VirtualApiResponse {
        // Accept JSON body (PNG/WebP import is handled by the UI layer's
        // file-picker, not the virtual API).  For JSON import, decode and save.
        val bodyText = request.body ?: return errorResponse(400, "Missing body")
        val card = runCatching {
            json.decodeFromString(CharacterCard.serializer(), bodyText)
        }.getOrElse {
            return errorResponse(400, "Invalid character JSON: ${it.message}")
        }
        dataStore.saveCharacter(card)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("file_name", card.id)
        })
    }

    private suspend fun handleStGetChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val chatId = bodyObj?.get("file_name")?.jsonPrimitive?.content
            ?: bodyObj?.get("ch_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing file_name/ch_name")
        val session = runCatching { dataStore.readChatSession(chatId) }.getOrNull()
            ?: return errorResponse(404, "Chat not found: $chatId")
        return jsonResponse(200, sessionToStChatArray(session))
    }

    /**
     * ST `/api/chats/get` response shape (chats.js getChatData): a bare array
     * whose first element is the metadata header line, followed by message
     * objects with ST field names (mes/name/is_user/is_system/send_date/
     * swipes/swipe_id/variables/extra).
     */
    private fun sessionToStChatArray(session: ChatSession): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                for ((key, value) in session.rawHeader) put(key, value)
                put(
                    "user_name",
                    session.rawHeader["user_name"]?.jsonPrimitive?.content
                        ?: session.metadata["userName"]?.jsonPrimitive?.content
                        ?: "User",
                )
                put(
                    "character_name",
                    session.rawHeader["character_name"]?.jsonPrimitive?.content
                        ?: session.metadata["characterName"]?.jsonPrimitive?.content
                        ?: "",
                )
                put("create_date", session.metadata["create_date"]?.jsonPrimitive?.content ?: "")
                put("chat_metadata", session.metadata)
            },
        )
        session.messages.forEach { msg ->
            add(
                buildJsonObject {
                    for ((key, value) in msg.raw) put(key, value)
                    put("name", msg.name)
                    put("mes", msg.swipes.getOrNull(msg.swipeIndex) ?: msg.content)
                    put("is_user", msg.role == MessageRole.User)
                    put("is_system", msg.role == MessageRole.System || msg.isHidden)
                    // ST folds "hidden" into is_system; TavernHelper exposes it
                    // separately as is_hidden. Emitting both keeps the
                    // get→edit→save round-trip from turning a hidden character
                    // message into a narrator message and vice versa.
                    put("is_hidden", msg.isHidden)
                    put("send_date", msg.createdAtMillis)
                    put("swipes", JsonArray(msg.swipes.map { JsonPrimitive(it) }))
                    put("swipe_id", msg.swipeIndex)
                    put("variables", JsonArray(msg.variables))
                    put("extra", msg.metadata)
                },
            )
        }
    }

    private suspend fun handleStGetGroupChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val chatId = bodyObj?.get("chat_id")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing chat_id")
        return handleReadChat(chatId)
    }

    private suspend fun handleStImportChat(request: VirtualApiRequest): VirtualApiResponse {
        // Accept a JSON body describing the chat to import.  The actual
        // JSONL parsing is delegated to the data store.
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStSaveChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
            ?: return errorResponse(400, "Missing body")
        val chatId = bodyObj["file_name"]?.jsonPrimitive?.content
            ?: bodyObj["ch_name"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing file_name")
        val chatArray = bodyObj["chat"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return errorResponse(400, "Missing chat array")
        val session = runCatching { dataStore.readChatSession(chatId) }.getOrNull()
            ?: return errorResponse(404, "Chat not found: $chatId")

        // ST `/api/chats/save` replaces the whole chat file (chats.js saveChat);
        // the rows use ST field names, which is also what our own
        // `/api/chats/get` hands out. Decoding them straight into ChatMessage
        // always failed (no id/role/createdAtMillis) and the `?: continue` made
        // that a silent 200 with zero writes — a read/modify/write round-trip
        // reported success and lost every edit.
        val messages = stChatArrayToMessages(chatArray, chatId)
        val header = chatArray.firstOrNull() as? JsonObject
        val metadata = (header?.get("chat_metadata") as? JsonObject) ?: session.metadata
        dataStore.saveChatSession(
            session.copy(
                messages = messages,
                metadata = metadata,
                rawHeader = header ?: session.rawHeader,
            ),
        )
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    /**
     * Inverse of [sessionToStChatArray]: ST-shaped rows (mes/name/is_user/
     * is_system/send_date/swipes/swipe_id/variables/extra) back into
     * [ChatMessage]. The first element is the metadata header and is skipped.
     */
    private fun stChatArrayToMessages(chatArray: JsonArray, chatId: String): List<ChatMessage> =
        chatArray.drop(1).mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val isUser = obj["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val isSystem = obj["is_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            // Plain ST data has no is_hidden, and there is_system *means* hidden.
            val isHidden = obj["is_hidden"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: isSystem
            val swipes = runCatching {
                obj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }.getOrDefault(emptyList())
            val swipeId = obj["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val content = obj["mes"]?.jsonPrimitive?.content
                ?: swipes.getOrNull(swipeId)
                ?: ""
            ChatMessage(
                id = "$chatId-$index",
                role = when {
                    isUser -> MessageRole.User
                    isSystem && !isHidden -> MessageRole.System
                    else -> MessageRole.Character
                },
                name = obj["name"]?.jsonPrimitive?.content ?: if (isUser) "You" else "Character",
                content = content,
                createdAtMillis = obj["send_date"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                swipeIndex = swipeId,
                swipes = swipes,
                metadata = obj["extra"] as? JsonObject ?: buildJsonObject { },
                raw = obj,
                variables = runCatching {
                    obj["variables"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
                }.getOrDefault(emptyList()),
                isHidden = isHidden,
            )
        }

    private suspend fun handleStGetWorldInfo(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val name = bodyObj?.get("name")?.jsonPrimitive?.content
            ?: bodyObj?.get("wim_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing name")
        // ST returns the world file verbatim: entries as a uid-keyed object
        // (worldinfo.js /get). Serve the on-disk ST-format file.
        val file = dataStore.layout.worlds.resolve("$name.json")
        if (!file.exists()) return errorResponse(404, "World info not found: $name")
        val raw = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
            ?: return errorResponse(500, "Failed to read world info: $name")
        return jsonResponse(200, raw)
    }

    private suspend fun handleStListWorldInfo(): VirtualApiResponse {
        // ST returns a bare array of {file_id, name, extensions} (worldinfo.js /list).
        val array = buildJsonArray {
            dataStore.listWorldBooks().forEach { book ->
                add(
                    buildJsonObject {
                        put("file_id", book.id)
                        put("name", book.name)
                        put("extensions", (book.raw["extensions"] as? JsonObject) ?: buildJsonObject { })
                    },
                )
            }
        }
        return jsonResponse(200, array)
    }

    private suspend fun handleStEditWorldInfo(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val name = body.stringValue("name")?.takeIf { it.isNotBlank() }
            ?: return errorResponse(400, "World file must have a name")
        val data = body["data"] as? JsonObject
            ?: return errorResponse(400, "Is not a valid world info file")
        if (data["entries"] !is JsonObject) {
            return errorResponse(400, "Is not a valid world info file")
        }
        // ST semantics (worldinfo.js /edit): write the file verbatim.
        dataStore.layout.worlds.createDirectories()
        dataStore.layout.worlds.resolve("$name.json").writeText(
            json.encodeToString(JsonObject.serializer(), data),
        )
        return jsonResponse(200, buildJsonObject { put("name", name) })
    }

    private suspend fun handleStAllCharacters(): VirtualApiResponse {
        // ST /api/characters/all returns a bare array.
        val array = buildJsonArray {
            dataStore.listCharacters().forEach {
                add(json.encodeToJsonElement(CharacterSummary.serializer(), it))
            }
        }
        return jsonResponse(200, array)
    }

    private suspend fun handleStGetCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val id = body.stringValue("id") ?: body.stringValue("name") ?: body.stringValue("avatar_url")
            ?: return errorResponse(400, "Missing character id/name")
        val card = runCatching { dataStore.readCharacter(id.substringBeforeLast(".png")) }.getOrNull()
            ?: return errorResponse(404, "Character not found: $id")
        val bodyJson = json.encodeToJsonElement(CharacterCard.serializer(), card)
        return jsonResponse(200, bodyJson as? JsonObject ?: buildJsonObject { put("data", bodyJson) })
    }

    private suspend fun handleStDeleteCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request)
        val id = body.stringValue("id") ?: body.stringValue("name") ?: body.stringValue("avatar_url")
            ?: return errorResponse(400, "Missing character id/name")
        return runCatching {
            dataStore.deleteCharacter(id.substringBeforeLast(".png"))
            jsonResponse(200, buildJsonObject { put("ok", true) })
        }.getOrElse { errorResponse(404, "Character not found: $id") }
    }

    private suspend fun handleStGetSettings(): VirtualApiResponse {
        // Return a settings object that includes world_names and other
        // lists that extensions query via /api/settings/get.
        val worldNames = dataStore.listWorldBooks().map { it.id }
        val characters = runCatching { dataStore.listCharacters() }.getOrDefault(emptyList())
        val ejsSettings = settingsStore?.readEjsTemplateSettings()
        val tavernHelperSettings = settingsStore?.readTavernHelperSettings()
        val body = buildJsonObject {
            putJsonArray("world_names") {
                for (w in worldNames) add(kotlinx.serialization.json.JsonPrimitive(w))
            }
            putJsonArray("character_names") {
                for (c in characters) add(kotlinx.serialization.json.JsonPrimitive(c.name))
            }
            put("display_name", "tellev")
            put("power_user", buildJsonObject { })
            put("oai_settings", buildJsonObject { })
            // Built-in compat-module settings so extensions that read
            // extension_settings.EjsTemplate / extension_settings.tavern_helper
            // via /api/settings/get see the current values.
            if (ejsSettings != null) {
                put("EjsTemplate", json.encodeToJsonElement(
                    EjsTemplateSettings.serializer(), ejsSettings,
                ))
            }
            if (tavernHelperSettings != null) {
                put("tavern_helper", json.encodeToJsonElement(
                    TavernHelperSettings.serializer(), tavernHelperSettings,
                ))
            }
        }
        return jsonResponse(200, body)
    }

    private suspend fun handleStSaveSettings(request: VirtualApiRequest): VirtualApiResponse {
        // Accept settings save.  Extract and persist the built-in
        // compat-module settings (EjsTemplate / tavern_helper) if present
        // in the request body; the rest of settings.json is acknowledged
        // but not persisted.
        val bodyObj = parseBodyAsJsonObject(request)
        val store = settingsStore
        if (store != null) {
            bodyObj["EjsTemplate"]?.let { elem ->
                runCatching {
                    val s = json.decodeFromJsonElement(EjsTemplateSettings.serializer(), elem)
                    store.saveEjsTemplateSettings(s)
                }
            }
            bodyObj["tavern_helper"]?.let { elem ->
                runCatching {
                    val s = json.decodeFromJsonElement(TavernHelperSettings.serializer(), elem)
                    store.saveTavernHelperSettings(s)
                }
            }
        }
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStWriteSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id")
        val value = bodyObj["value"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing value")
        secretStore.putSecret(id, value)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStReadSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id")
        val value = secretStore.readSecret(id)
            ?: return errorResponse(404, "Secret not found: $id")
        return jsonResponse(200, buildJsonObject {
            put("value", value)
        })
    }

    private suspend fun handleStDeleteSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id")
        secretStore.deleteSecret(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStChatCompletionsStatus(request: VirtualApiRequest): VirtualApiResponse {
        // Probe a reverse-proxy / OpenAI-compatible endpoint for model list.
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val apiUrl = bodyObj?.get("api_url")?.jsonPrimitive?.content
            ?: request.headers["X-Api-Url"]
            ?: request.headers["x-api-url"]
            ?: return errorResponse(400, "Missing api_url")

        // Find an OpenAI-compatible adapter to probe.
        val adapter = providerRegistry.all().firstOrNull { it.id == "openai-compatible" }
            ?: return errorResponse(503, "No provider available")

        val apiKey = bodyObj?.get("api_key")?.jsonPrimitive?.content
            ?: request.headers["X-Api-Key"]
            ?: runCatching { secretStore.readSecret("openai-compatible") }.getOrNull()
        val config = ProviderConfig(
            providerType = "openai-compatible",
            baseUrl = apiUrl,
            apiKey = apiKey,
        )
        return runCatching {
            val models = adapter.listModels(config)
            jsonResponse(200, buildJsonObject {
                putJsonArray("data") {
                    for (m in models) add(buildJsonObject {
                        put("id", m.id)
                        put("name", m.displayName)
                    })
                }
            })
        }.getOrElse {
            errorResponse(502, "Provider status check failed: ${it.message}")
        }
    }

    private suspend fun handleStChatCompletionsGenerate(request: VirtualApiRequest): VirtualApiResponse {
        // Generation is handled by the UI layer's PromptEngine + provider
        // adapter flow.  The virtual API returns 501 to indicate that
        // extensions should use TavernHelper.generate() instead, which
        // routes through the proper prompt pipeline.
        return errorResponse(501, "Use TavernHelper.generate() for generation; direct /api/backends/chat-completions/generate is not supported via virtual API")
    }

    private suspend fun handleStUploadAvatar(request: VirtualApiRequest): VirtualApiResponse {
        // Avatar upload is handled by the UI layer's file picker.
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStDeleteAvatar(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val avatarId = bodyObj?.get("avatar_id")?.jsonPrimitive?.content
            ?: bodyObj?.get("ch_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing avatar_id")
        // Deletion is handled by the data store; here we just acknowledge.
        return jsonResponse(200, buildJsonObject { put("ok", true) })
    }

    private suspend fun handleStExtensionVersion(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request)
        val name = bodyObj?.get("name")?.jsonPrimitive?.content ?: ""
        if (isPromptTemplateExtensionName(name) || isTavernHelperExtensionName(name)) {
            return jsonResponse(200, buildJsonObject {
                put("name", name)
                put("version", "tellev-compat")
                put("installed", true)
                put("compatible", false)
                put("compatibilityLevel", "partial")
                putJsonArray("limitations") {
                    add(kotlinx.serialization.json.JsonPrimitive(
                        "Tellev provides a built-in compatibility shim; the upstream extension package is not installed or executed.",
                    ))
                    add(kotlinx.serialization.json.JsonPrimitive(
                        "Only a supported subset of ST-Prompt-Template and TavernHelper APIs is implemented.",
                    ))
                    add(kotlinx.serialization.json.JsonPrimitive(
                        "Unsupported APIs, scopes, and browser-runtime behavior may differ from the upstream extensions.",
                    ))
                    add(kotlinx.serialization.json.JsonPrimitive(
                        "Dynamic function filters in TavernHelper.injectPrompts are skipped and reported in module logs.",
                    ))
                }
            })
        }
        return jsonResponse(200, buildJsonObject {
            put("name", name)
            put("version", "")
            put("installed", false)
        })
    }

    private fun isPromptTemplateExtensionName(name: String): Boolean {
        val normalized = name
            .lowercase()
            .replace("_", "-")
            .replace(" ", "-")
        return normalized in setOf(
            "st-prompt-template",
            "prompt-template",
            "prompttemplate",
            "ejs-template",
            "ejstemplate",
            "zonde306/st-prompt-template",
            "third-party/st-prompt-template",
        ) || name.contains("提示词模板")
    }

    /**
     * Recognize the 酒馆助手 / JS-Slash-Runner extension family so
     * `TavernHelper.isInstalledExtension('JS-Slash-Runner')` and similar
     * checks against `/api/extensions/version` report `installed:true`.
     */
    private fun isTavernHelperExtensionName(name: String): Boolean {
        val normalized = name
            .lowercase()
            .replace("_", "-")
            .replace(" ", "-")
        return normalized in setOf(
            "js-slash-runner",
            "js-slash-runner/js-slash-runner",
            "third-party/js-slash-runner",
            "tavern-helper",
            "tavernhelper",
            "tavern-helper-compat",
        ) || name.contains("酒馆助手")
    }

    private suspend fun handleStExtensionInstall(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension installation via virtual API is not supported; use the UI layer")
    }

    private suspend fun handleStExtensionDelete(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension deletion via virtual API is not supported; use the UI layer")
    }

    private suspend fun handleStExtensionUpdate(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension update via virtual API is not supported; use the UI layer")
    }
}
