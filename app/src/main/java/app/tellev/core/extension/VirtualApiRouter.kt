package app.tellev.core.extension

import app.tellev.core.extension.router.CharacterApiHandler
import app.tellev.core.extension.router.ChatApiHandler
import app.tellev.core.extension.router.PersonaAndExtensionApiHandler
import app.tellev.core.extension.router.PresetApiHandler
import app.tellev.core.extension.router.ProviderApiHandler
import app.tellev.core.extension.router.SecretApiHandler
import app.tellev.core.extension.router.WorldBookApiHandler
import app.tellev.core.extension.router.errorResponse
import app.tellev.core.extension.router.jsonResponse
import app.tellev.core.extension.router.normalizePath
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    private val characterHandler = CharacterApiHandler(dataStore, json)
    private val chatHandler = ChatApiHandler(dataStore, json)
    private val worldBookHandler = WorldBookApiHandler(dataStore, json)
    private val presetHandler = PresetApiHandler(dataStore, json)
    private val providerHandler = ProviderApiHandler(providerRegistry, secretStore, json)
    private val secretHandler = SecretApiHandler(secretStore, json)
    private val miscHandler = PersonaAndExtensionApiHandler(dataStore, settingsStore, json)

    /**
     * Dispatch [request] to the matching handler and return an HTTP-style
     * response. Unknown routes yield 404. Exceptions inside handlers yield
     * 500 with the error message in the body.
     */
    suspend fun route(request: VirtualApiRequest): VirtualApiResponse =
        withContext(Dispatchers.IO) {
            try {
                dispatch(request)
            } catch (e: IllegalArgumentException) {
                errorResponse(400, e.message ?: "Bad request", json)
            } catch (e: NoSuchElementException) {
                errorResponse(404, e.message ?: "Not found", json)
            } catch (e: Exception) {
                errorResponse(500, e.message ?: "Internal server error", json)
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
            }, json)
        }

        val segments = path.removePrefix("/api/").split("/").filter { it.isNotEmpty() }

        return when {
            // ── characters ─────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "characters" ->
                characterHandler.handleListCharacters()

            method == "GET" && segments.size == 2 && segments[0] == "characters" ->
                characterHandler.handleReadCharacter(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "extensions" ->
                characterHandler.handleReadCharacterExtensions(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "regex" ->
                characterHandler.handleReadCharacterRegex(segments[1])

            method == "GET" && segments.size == 3 && segments[0] == "characters" && segments[2] == "tavern-helper" ->
                characterHandler.handleReadCharacterTavernHelper(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "characters" ->
                characterHandler.handleSaveCharacter(request)

            // ST-style character edit: partial field patch
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "edit" ->
                characterHandler.handleEditCharacter(request)

            // ST-style character import
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "import" ->
                characterHandler.handleImportCharacter(request)

            method == "POST" && segments.size == 3 && segments[0] == "characters" && segments[2] == "tavern-helper" ->
                characterHandler.handleSaveCharacterTavernHelper(segments[1], request)

            method == "DELETE" && segments.size == 2 && segments[0] == "characters" ->
                characterHandler.handleDeleteCharacter(segments[1])

            // ST-style character routes (POST): /all, /get, /delete
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "all" ->
                characterHandler.handleStAllCharacters()
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "get" ->
                characterHandler.handleStGetCharacter(request)
            method == "POST" && segments.size == 2 && segments[0] == "characters" && segments[1] == "delete" ->
                characterHandler.handleStDeleteCharacter(request)

            // ── chats ──────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "chats" ->
                chatHandler.handleListChats(request)

            method == "GET" && segments.size == 2 && segments[0] == "chats" ->
                chatHandler.handleReadChat(segments[1])

            // ST-style: POST /api/chats/get { ch_name, file_name }
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "get" ->
                chatHandler.handleStGetChat(request)

            // ST-style: POST /api/chats/group/get { chat_id }
            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[1] == "group" && segments[2] == "get" ->
                chatHandler.handleStGetGroupChat(request)

            // ST-style: POST /api/chats/import
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "import" ->
                chatHandler.handleStImportChat(request)

            // ST-style: POST /api/chats/save
            method == "POST" && segments.size == 2 && segments[0] == "chats" && segments[1] == "save" ->
                chatHandler.handleStSaveChat(request)

            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "messages" ->
                chatHandler.handleAppendMessage(segments[1], request)

            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "message-field" ->
                errorResponse(501, "Chat message field mutation is handled by the UI layer", json)

            // ── worlds / worldinfo ─────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "worlds" ->
                worldBookHandler.handleListWorlds()

            method == "GET" && segments.size == 2 && segments[0] == "worlds" ->
                worldBookHandler.handleReadWorld(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "worlds" ->
                worldBookHandler.handleSaveWorld(request)

            // ST-style: POST /api/worldinfo/get { name }
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "get" ->
                worldBookHandler.handleStGetWorldInfo(request)

            // ST-style: POST /api/worldinfo/list
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "list" ->
                worldBookHandler.handleStListWorldInfo()

            // ST-style: POST /api/worldinfo/edit { name, data }
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "edit" ->
                worldBookHandler.handleStEditWorldInfo(request)

            // ── settings / presets ─────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "settings" ->
                presetHandler.handleListPresets()

            // ST-style: POST /api/settings/get
            method == "POST" && segments.size == 2 && segments[0] == "settings" && segments[1] == "get" ->
                miscHandler.handleStGetSettings()

            // ST-style: POST /api/settings/save
            method == "POST" && segments.size == 2 && segments[0] == "settings" && segments[1] == "save" ->
                miscHandler.handleStSaveSettings(request)

            // ── secrets ────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "secrets" ->
                secretHandler.handleListSecrets()

            method == "GET" && segments.size == 2 && segments[0] == "secrets" ->
                secretHandler.handleReadSecret(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "secrets" ->
                secretHandler.handlePutSecret(request)

            method == "DELETE" && segments.size == 2 && segments[0] == "secrets" ->
                secretHandler.handleDeleteSecret(segments[1])

            // ST-style secret endpoints
            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "write" ->
                secretHandler.handleStWriteSecret(request)

            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "read" ->
                secretHandler.handleStReadSecret(request)

            method == "POST" && segments.size == 2 && segments[0] == "secrets" && segments[1] == "delete" ->
                secretHandler.handleStDeleteSecret(request)

            // ── providers / backends ───────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "providers" ->
                providerHandler.handleListProviders()

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "status" ->
                providerHandler.handleProviderStatus(segments[1], request)

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "models" ->
                providerHandler.handleProviderModels(segments[1], request)

            // ST-style: POST /api/backends/chat-completions/status
            method == "POST" && segments.size == 3 && segments[0] == "backends" && segments[1] == "chat-completions" && segments[2] == "status" ->
                providerHandler.handleStChatCompletionsStatus(request)

            // ST-style: POST /api/backends/chat-completions/generate
            method == "POST" && segments.size == 3 && segments[0] == "backends" && segments[1] == "chat-completions" && segments[2] == "generate" ->
                providerHandler.handleStChatCompletionsGenerate(request)

            // ── avatars (persona) ──────────────────────────────────
            method == "POST" && segments.size == 2 && segments[0] == "avatars" && segments[1] == "upload" ->
                miscHandler.handleStUploadAvatar(request)

            method == "POST" && segments.size == 2 && segments[0] == "avatars" && segments[1] == "delete" ->
                miscHandler.handleStDeleteAvatar(request)

            // ── extensions management ──────────────────────────────
            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "version" ->
                miscHandler.handleStExtensionVersion(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "install" ->
                miscHandler.handleStExtensionInstall(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "delete" ->
                miscHandler.handleStExtensionDelete(request)

            method == "POST" && segments.size == 2 && segments[0] == "extensions" && segments[1] == "update" ->
                miscHandler.handleStExtensionUpdate(request)

            // ── groups ─────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "groups" ->
                miscHandler.handleListGroups()

            // ── personas ───────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "personas" ->
                miscHandler.handleListPersonas()
            method == "POST" && segments.size == 1 && segments[0] == "personas" ->
                miscHandler.handleSavePersona(request)
            method == "DELETE" && segments.size == 2 && segments[0] == "personas" ->
                miscHandler.handleDeletePersona(segments[1])

            // ── presets ───────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "presets" ->
                presetHandler.handleListPresets()
            method == "GET" && segments.size == 3 && segments[0] == "presets" ->
                presetHandler.handleReadPreset(segments[1], segments[2])
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "load" ->
                presetHandler.handleLoadPreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "save" ->
                presetHandler.handleWritePreset(request, createOnly = false, updateOnly = false)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "create" ->
                presetHandler.handleWritePreset(request, createOnly = true, updateOnly = false)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "replace" ->
                presetHandler.handleWritePreset(request, createOnly = false, updateOnly = true)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "update" ->
                presetHandler.handleUpdatePreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "delete" ->
                presetHandler.handleDeletePreset(request)
            method == "POST" && segments.size == 2 && segments[0] == "presets" && segments[1] == "rename" ->
                presetHandler.handleRenamePreset(request)

            // ── tags (stub: tags list is empty in tellev) ──────────
            method == "GET" && segments.size == 1 && segments[0] == "tags" ->
                jsonResponse(200, buildJsonObject { putJsonArray("tags") { } }, json)

            // ── worldinfo create / save / delete (stub) ────────────
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "create" ->
                errorResponse(501, "World Info create via virtual API is not supported; use the UI layer", json)
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "save" ->
                errorResponse(501, "World Info save via virtual API is not supported; use the UI layer", json)
            method == "POST" && segments.size == 2 && segments[0] == "worldinfo" && segments[1] == "delete" ->
                errorResponse(501, "World Info delete via virtual API is not supported; use the UI layer", json)

            else ->
                errorResponse(404, "No route for $method $path", json)
        }
    }
}
