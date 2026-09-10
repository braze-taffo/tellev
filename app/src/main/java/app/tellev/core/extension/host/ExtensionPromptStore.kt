package app.tellev.core.extension.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

internal data class InjectedPrompt(
    val value: String,
    val position: Int,
    val depth: Int,
    val role: String,
    val shouldScan: Boolean = false,
)

internal class ExtensionPromptStore {
    private val injectedPrompts =
        ConcurrentHashMap<String, ConcurrentHashMap<String, InjectedPrompt>>()

    fun storeInjectedPrompt(
        extensionId: String,
        id: String,
        content: String,
        position: Int,
        depth: Int,
        role: String,
        shouldScan: Boolean = false,
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

    fun uninjectPrompt(extensionId: String, id: String) {
        injectedPrompts[extensionId]?.remove(id)
    }

    fun clearExtension(extensionId: String) {
        injectedPrompts.remove(extensionId)
    }

    fun getInjectedPromptsJson(extensionId: String, json: Json): String {
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

    fun collectInjectedPrompts(): JsonObject = buildJsonObject {
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
}
