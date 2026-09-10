package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.model.WorldBook
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class WorldBookApiHandler(
    private val dataStore: StDataStore,
    private val json: Json,
) {
    suspend fun handleListWorlds(): VirtualApiResponse {
        val worlds = dataStore.listWorldBooks()
        val body = buildJsonObject {
            putJsonArray("worlds") {
                for (w in worlds) {
                    add(json.encodeToJsonElement(WorldBook.serializer(), w))
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleReadWorld(id: String): VirtualApiResponse {
        val book = dataStore.readWorldBook(id)
        val body = json.encodeToJsonElement(WorldBook.serializer(), book)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) }, json)
    }

    suspend fun handleSaveWorld(request: VirtualApiRequest): VirtualApiResponse {
        val book = parseBody<WorldBook>(request, json)
        dataStore.saveWorldBook(book)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStGetWorldInfo(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val name = bodyObj?.get("name")?.jsonPrimitive?.content
            ?: bodyObj?.get("wim_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing name", json)
        val file = dataStore.layout.worlds.resolve("$name.json")
        if (!file.exists()) return errorResponse(404, "World info not found: $name", json)
        val raw = runCatching { json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
            ?: return errorResponse(500, "Failed to read world info: $name", json)
        return jsonResponse(200, raw, json)
    }

    suspend fun handleStListWorldInfo(): VirtualApiResponse {
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
        return jsonResponse(200, array, json)
    }

    suspend fun handleStEditWorldInfo(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val name = body.stringValue("name")?.takeIf { it.isNotBlank() }
            ?: return errorResponse(400, "World file must have a name", json)
        val data = body["data"] as? JsonObject
            ?: return errorResponse(400, "Is not a valid world info file", json)
        if (data["entries"] !is JsonObject) {
            return errorResponse(400, "Is not a valid world info file", json)
        }
        dataStore.layout.worlds.createDirectories()
        dataStore.layout.worlds.resolve("$name.json").writeText(
            json.encodeToString(JsonObject.serializer(), data),
        )
        return jsonResponse(200, buildJsonObject { put("name", name) }, json)
    }
}
