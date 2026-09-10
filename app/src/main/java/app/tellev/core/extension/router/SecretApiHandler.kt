package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.security.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class SecretApiHandler(
    private val secretStore: SecretStore,
    private val json: Json,
) {
    suspend fun handleListSecrets(): VirtualApiResponse {
        val ids = secretStore.listSecretIds()
        val body = buildJsonObject {
            putJsonArray("secretIds") {
                for (id in ids) add(JsonPrimitive(id))
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleReadSecret(id: String): VirtualApiResponse {
        val value = secretStore.readSecret(id)
        return if (value != null) {
            jsonResponse(200, buildJsonObject {
                put("id", id)
                put("value", value)
            }, json)
        } else {
            errorResponse(404, "Secret not found: $id", json)
        }
    }

    suspend fun handlePutSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request, json)
        val id = bodyObj["id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'id' in request body")
        val value = bodyObj["value"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'value' in request body")
        secretStore.putSecret(id, value)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleDeleteSecret(id: String): VirtualApiResponse {
        secretStore.deleteSecret(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStWriteSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request, json)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id", json)
        val value = bodyObj["value"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing value", json)
        secretStore.putSecret(id, value)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStReadSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request, json)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id", json)
        val value = secretStore.readSecret(id)
            ?: return errorResponse(404, "Secret not found: $id", json)
        return jsonResponse(200, buildJsonObject {
            put("value", value)
        }, json)
    }

    suspend fun handleStDeleteSecret(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request, json)
        val id = bodyObj["key"]?.jsonPrimitive?.content
            ?: bodyObj["id"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing key/id", json)
        secretStore.deleteSecret(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }
}
