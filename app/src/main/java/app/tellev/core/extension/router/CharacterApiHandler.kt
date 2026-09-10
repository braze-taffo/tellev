package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.WorldBook
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class CharacterApiHandler(
    private val dataStore: StDataStore,
    private val json: Json,
) {
    suspend fun handleListCharacters(): VirtualApiResponse {
        val characters = dataStore.listCharacters()
        val body = buildJsonObject {
            putJsonArray("characters") {
                for (c in characters) {
                    add(json.encodeToJsonElement(CharacterSummary.serializer(), c))
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleReadCharacter(id: String): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val body = json.encodeToJsonElement(CharacterCard.serializer(), card)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) }, json)
    }

    suspend fun handleReadCharacterExtensions(id: String): VirtualApiResponse {
        val extensions = dataStore.readCharacter(id).extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("extensions", extensions)
        }, json)
    }

    suspend fun handleReadCharacterRegex(id: String): VirtualApiResponse {
        val extensions = dataStore.readCharacter(id).extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("regex_scripts", extensions["regex_scripts"] ?: JsonArray(emptyList()))
        }, json)
    }

    suspend fun handleReadCharacterTavernHelper(id: String): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val extensions = card.extensionObject()
        return jsonResponse(200, buildJsonObject {
            put("characterId", id)
            put("extensions", extensions)
            put("regex_scripts", extensions["regex_scripts"] ?: JsonArray(emptyList()))
            put("TavernHelper_scripts", extensions["TavernHelper_scripts"] ?: JsonArray(emptyList()))
            put("tavern_helper", extensions["tavern_helper"] ?: JsonArray(emptyList()))
            card.characterBook?.let { book ->
                put("character_book", json.encodeToJsonElement(WorldBook.serializer(), book))
            }
        }, json)
    }

    suspend fun handleSaveCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val card = parseBody<CharacterCard>(request, json)
        dataStore.saveCharacter(card)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleDeleteCharacter(id: String): VirtualApiResponse {
        dataStore.deleteCharacter(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleSaveCharacterTavernHelper(
        id: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val card = dataStore.readCharacter(id)
        val patch = parseBodyAsJsonObject(request, json)
        val updatedRaw = mergeTavernHelperExtensions(card.raw, patch)
        dataStore.saveCharacter(card.copy(raw = updatedRaw))
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleEditCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json) ?: return errorResponse(400, "Missing body", json)
        val id = bodyObj["ch_name"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing ch_name/id", json)

        val card = runCatching { dataStore.readCharacter(id) }.getOrNull()
            ?: return errorResponse(404, "Character not found: $id", json)

        val updatedRaw = patchCharacterFields(card.raw, bodyObj)
        dataStore.saveCharacter(card.copy(raw = updatedRaw))
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleImportCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val bodyText = request.body ?: return errorResponse(400, "Missing body", json)
        val card = runCatching {
            json.decodeFromString(CharacterCard.serializer(), bodyText)
        }.getOrElse {
            return errorResponse(400, "Invalid character JSON: ${it.message}", json)
        }
        dataStore.saveCharacter(card)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("file_name", card.id)
        }, json)
    }

    suspend fun handleStAllCharacters(): VirtualApiResponse {
        val array = buildJsonArray {
            dataStore.listCharacters().forEach {
                add(json.encodeToJsonElement(CharacterSummary.serializer(), it))
            }
        }
        return jsonResponse(200, array, json)
    }

    suspend fun handleStGetCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val id = body.stringValue("id") ?: body.stringValue("name") ?: body.stringValue("avatar_url")
            ?: return errorResponse(400, "Missing character id/name", json)
        val card = runCatching { dataStore.readCharacter(id.substringBeforeLast(".png")) }.getOrNull()
            ?: return errorResponse(404, "Character not found: $id", json)
        val bodyJson = json.encodeToJsonElement(CharacterCard.serializer(), card)
        return jsonResponse(200, bodyJson as? JsonObject ?: buildJsonObject { put("data", bodyJson) }, json)
    }

    suspend fun handleStDeleteCharacter(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val id = body.stringValue("id") ?: body.stringValue("name") ?: body.stringValue("avatar_url")
            ?: return errorResponse(400, "Missing character id/name", json)
        return runCatching {
            dataStore.deleteCharacter(id.substringBeforeLast(".png"))
            jsonResponse(200, buildJsonObject { put("ok", true) }, json)
        }.getOrElse { errorResponse(404, "Character not found: $id", json) }
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

    private fun CharacterCard.extensionObject(): JsonObject {
        val data = (raw["data"] as? JsonObject) ?: raw
        return (data["extensions"] as? JsonObject) ?: buildJsonObject { }
    }
}
