package app.tellev.core.extension

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

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

            method == "POST" && segments.size == 3 && segments[0] == "characters" && segments[2] == "tavern-helper" ->
                handleSaveCharacterTavernHelper(segments[1], request)

            // ── chats ──────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "chats" ->
                handleListChats(request)

            method == "GET" && segments.size == 2 && segments[0] == "chats" ->
                handleReadChat(segments[1])

            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "messages" ->
                handleAppendMessage(segments[1], request)

            // 酒馆助手 TavernHelper.setChatMessage target.  The actual
            // mutation is owned by the UI layer (which holds the active
            // chat); the router surfaces 501 so callers don't silently
            // succeed.
            method == "POST" && segments.size == 3 && segments[0] == "chats" && segments[2] == "message-field" ->
                errorResponse(501, "Chat message field mutation is handled by the UI layer")

            // ── worlds ─────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "worlds" ->
                handleListWorlds()

            method == "GET" && segments.size == 2 && segments[0] == "worlds" ->
                handleReadWorld(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "worlds" ->
                handleSaveWorld(request)

            // ── settings / presets ─────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "settings" ->
                handleListPresets()

            // ── secrets ────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "secrets" ->
                handleListSecrets()

            method == "GET" && segments.size == 2 && segments[0] == "secrets" ->
                handleReadSecret(segments[1])

            method == "POST" && segments.size == 1 && segments[0] == "secrets" ->
                handlePutSecret(request)

            method == "DELETE" && segments.size == 2 && segments[0] == "secrets" ->
                handleDeleteSecret(segments[1])

            // ── providers ──────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "providers" ->
                handleListProviders()

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "status" ->
                handleProviderStatus(segments[1], request)

            method == "GET" && segments.size == 3 && segments[0] == "providers" && segments[2] == "models" ->
                handleProviderModels(segments[1], request)

            // ── groups ─────────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "groups" ->
                handleListGroups()

            // ── personas ───────────────────────────────────────────
            method == "GET" && segments.size == 1 && segments[0] == "personas" ->
                handleListPersonas()

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
        val body = buildJsonObject {
            putJsonArray("presets") {
                for (p in presets) {
                    add(json.encodeToJsonElement(GenerationPreset.serializer(), p))
                }
            }
        }
        return jsonResponse(200, body)
    }

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

        // Fall back to building a minimal config from headers.
        val apiKey = request.headers["X-Api-Key"]
            ?: request.headers["x-api-key"]
            ?: runCatching { secretStore.readSecret(providerId) }.getOrNull()
        val baseUrl = request.headers["X-Base-Url"]
            ?: request.headers["x-base-url"]
            ?: ""

        return ProviderConfig(
            providerType = providerId,
            baseUrl = baseUrl,
            apiKey = apiKey,
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
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    private fun jsonResponse(status: Int, body: JsonObject): VirtualApiResponse =
        VirtualApiResponse(
            status = status,
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(JsonObject.serializer(), body),
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
}
