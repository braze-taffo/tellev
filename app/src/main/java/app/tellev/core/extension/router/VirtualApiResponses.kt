package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder

internal fun jsonResponse(status: Int, body: JsonObject, json: Json): VirtualApiResponse =
    jsonResponse(status, body as JsonElement, json)

internal fun jsonResponse(status: Int, body: JsonElement, json: Json): VirtualApiResponse =
    VirtualApiResponse(
        status = status,
        headers = mapOf("Content-Type" to "application/json"),
        body = json.encodeToString(JsonElement.serializer(), body),
    )

internal fun errorResponse(status: Int, message: String, json: Json): VirtualApiResponse {
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

internal fun normalizePath(raw: String): String {
    val pathOnly = raw.substringBefore('?')
    return if (pathOnly.startsWith("/")) pathOnly else "/$pathOnly"
}

internal fun parseSimpleQuery(path: String): Map<String, String> {
    val query = path.substringAfter('?', "")
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { param ->
        val parts = param.split('=', limit = 2)
        if (parts.size == 2) {
            runCatching {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }.getOrElse { parts[0] to parts[1] }
        } else {
            null
        }
    }.toMap()
}

internal fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.doubleValue(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

internal inline fun <reified T> parseBody(request: VirtualApiRequest, json: Json): T {
    val bodyText = request.body
        ?: throw IllegalArgumentException("Request body is required")
    return json.decodeFromString<T>(bodyText)
}

internal fun parseBodyAsJsonObject(request: VirtualApiRequest, json: Json): JsonObject {
    val bodyText = request.body
        ?: throw IllegalArgumentException("Request body is required")
    return json.parseToJsonElement(bodyText).jsonObject
}

internal fun parseBodyAsJsonObjectOrNull(request: VirtualApiRequest, json: Json): JsonObject? {
    val bodyText = request.body ?: return null
    return runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
}
