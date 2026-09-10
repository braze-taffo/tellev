package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class ProviderApiHandler(
    private val providerRegistry: ProviderRegistry,
    private val secretStore: SecretStore,
    private val json: Json,
) {
    suspend fun handleListProviders(): VirtualApiResponse {
        val adapters = providerRegistry.all()
        val body = buildJsonObject {
            putJsonArray("providers") {
                for (a in adapters) {
                    add(buildJsonObject {
                        put("id", a.id)
                        put("displayName", a.displayName)
                        putJsonArray("capabilities") {
                            for (cap in a.capabilities) {
                                add(JsonPrimitive(cap.name))
                            }
                        }
                    })
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleProviderStatus(
        providerId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val adapter: ProviderAdapter = providerRegistry.require(providerId)
        val config = resolveProviderConfig(providerId, request)
        val status = adapter.checkStatus(config)
        val body = json.encodeToJsonElement(ProviderStatus.serializer(), status)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) }, json)
    }

    suspend fun handleProviderModels(
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
        return jsonResponse(200, body, json)
    }

    suspend fun handleStChatCompletionsStatus(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val apiUrl = bodyObj?.get("api_url")?.jsonPrimitive?.content
            ?: request.headers["X-Api-Url"]
            ?: request.headers["x-api-url"]
            ?: return errorResponse(400, "Missing api_url", json)

        val adapter = providerRegistry.all().firstOrNull { it.id == "openai-compatible" }
            ?: return errorResponse(503, "No provider available", json)

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
            }, json)
        }.getOrElse {
            errorResponse(502, "Provider status check failed: ${it.message}", json)
        }
    }

    suspend fun handleStChatCompletionsGenerate(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Use TavernHelper.generate() for generation; direct /api/backends/chat-completions/generate is not supported via virtual API", json)
    }

    private suspend fun resolveProviderConfig(
        providerId: String,
        request: VirtualApiRequest,
    ): ProviderConfig {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val embeddedConfig = bodyObj?.get("config")?.let { runCatching { it.jsonObject }.getOrNull() }

        if (embeddedConfig != null) {
            return json.decodeFromJsonElement(ProviderConfig.serializer(), embeddedConfig)
        }

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
}
