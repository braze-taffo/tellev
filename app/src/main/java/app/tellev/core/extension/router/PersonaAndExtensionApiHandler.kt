package app.tellev.core.extension.router

import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.ExtensionSettingsStore
import app.tellev.core.extension.TavernHelperSettings
import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.model.GroupChat
import app.tellev.core.model.Persona
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class PersonaAndExtensionApiHandler(
    private val dataStore: StDataStore,
    private val settingsStore: ExtensionSettingsStore?,
    private val json: Json,
) {
    suspend fun handleListGroups(): VirtualApiResponse {
        val groups = dataStore.listGroups()
        val body = buildJsonObject {
            putJsonArray("groups") {
                for (g in groups) {
                    add(json.encodeToJsonElement(GroupChat.serializer(), g))
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleListPersonas(): VirtualApiResponse {
        val personas = dataStore.listPersonas()
        val body = buildJsonObject {
            putJsonArray("personas") {
                for (p in personas) {
                    add(json.encodeToJsonElement(Persona.serializer(), p))
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleSavePersona(request: VirtualApiRequest): VirtualApiResponse {
        val persona = parseBody<Persona>(request, json)
        dataStore.savePersona(persona)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleDeletePersona(id: String): VirtualApiResponse {
        dataStore.deletePersona(id)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStUploadAvatar(request: VirtualApiRequest): VirtualApiResponse {
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStDeleteAvatar(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val avatarId = bodyObj?.get("avatar_id")?.jsonPrimitive?.content
            ?: bodyObj?.get("ch_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing avatar_id", json)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStExtensionVersion(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val name = bodyObj?.get("name")?.jsonPrimitive?.content ?: ""
        if (isPromptTemplateExtensionName(name) || isTavernHelperExtensionName(name)) {
            return jsonResponse(200, buildJsonObject {
                put("name", name)
                put("version", "tellev-compat")
                put("installed", true)
                put("compatible", false)
                put("compatibilityLevel", "partial")
                putJsonArray("limitations") {
                    add(JsonPrimitive(
                        "Tellev provides a built-in compatibility shim; the upstream extension package is not installed or executed.",
                    ))
                    add(JsonPrimitive(
                        "Only a supported subset of ST-Prompt-Template and TavernHelper APIs is implemented.",
                    ))
                    add(JsonPrimitive(
                        "Unsupported APIs, scopes, and browser-runtime behavior may differ from the upstream extensions.",
                    ))
                    add(JsonPrimitive(
                        "Dynamic function filters in TavernHelper.injectPrompts are skipped and reported in module logs.",
                    ))
                }
            }, json)
        }
        return jsonResponse(200, buildJsonObject {
            put("name", name)
            put("version", "")
            put("installed", false)
        }, json)
    }

    suspend fun handleStExtensionInstall(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension installation via virtual API is not supported; use the UI layer", json)
    }

    suspend fun handleStExtensionDelete(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension deletion via virtual API is not supported; use the UI layer", json)
    }

    suspend fun handleStExtensionUpdate(request: VirtualApiRequest): VirtualApiResponse {
        return errorResponse(501, "Extension update via virtual API is not supported; use the UI layer", json)
    }

    suspend fun handleStGetSettings(): VirtualApiResponse {
        val worldNames = dataStore.listWorldBooks().map { it.id }
        val characters = runCatching { dataStore.listCharacters() }.getOrDefault(emptyList())
        val ejsSettings = settingsStore?.readEjsTemplateSettings()
        val tavernHelperSettings = settingsStore?.readTavernHelperSettings()
        val body = buildJsonObject {
            putJsonArray("world_names") {
                for (w in worldNames) add(JsonPrimitive(w))
            }
            putJsonArray("character_names") {
                for (c in characters) add(JsonPrimitive(c.name))
            }
            put("display_name", "tellev")
            put("power_user", buildJsonObject { })
            put("oai_settings", buildJsonObject { })
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
        return jsonResponse(200, body, json)
    }

    suspend fun handleStSaveSettings(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObject(request, json)
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
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
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
}
