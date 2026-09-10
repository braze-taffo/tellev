package app.tellev.core.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

internal fun extensionGenerationError(
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
