package app.tellev.core.extension.router

import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.applyPromptOrder
import app.tellev.core.storage.parsePresetPrompts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder

internal class PresetApiHandler(
    private val dataStore: StDataStore,
    private val json: Json,
) {
    private val presetControlFields =
        setOf("category", "name", "oldName", "newName", "load", "preset", "data", "patch")

    suspend fun handleListPresets(): VirtualApiResponse {
        val presets = dataStore.listPresets()
        val selected = PresetCategory.entries.associateWith { category ->
            dataStore.readSelectedPresetName(category)
        }
        val body = buildJsonObject {
            putJsonArray("presets") {
                for (p in presets) {
                    add(json.encodeToJsonElement(GenerationPreset.serializer(), p))
                }
            }
            putJsonObject("selected") {
                selected.forEach { (category, name) ->
                    if (name != null) put(category.name.lowercase(), name)
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleReadPreset(categoryValue: String, encodedName: String): VirtualApiResponse {
        val category = parsePresetCategory(categoryValue)
        val name = URLDecoder.decode(encodedName, Charsets.UTF_8.name())
        val preset = dataStore.readPreset(category, name)
            ?: return errorResponse(404, "Preset not found: $categoryValue/$name", json)
        return jsonResponse(200, preset.raw, json)
    }

    suspend fun handleLoadPreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        if (name == "in_use") return errorResponse(400, "in_use is already the working preset", json)
        val exists = dataStore.listPresets().any { it.category == category && it.id == name }
        if (!exists) return errorResponse(404, "Preset not found: $name", json)
        dataStore.selectPreset(category, name)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("name", name)
            put("category", category.name.lowercase())
        }, json)
    }

    suspend fun handleWritePreset(
        request: VirtualApiRequest,
        createOnly: Boolean,
        updateOnly: Boolean,
    ): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        val isWorkingCopy = name == "in_use"
        if (createOnly && isWorkingCopy) return errorResponse(409, "in_use is reserved for the working preset", json)
        val existing = if (isWorkingCopy) {
            dataStore.readPreset(category, "in_use")
        } else {
            dataStore.listPresets().firstOrNull { it.category == category && it.id == name }
        }
        if (createOnly && existing != null) return errorResponse(409, "Preset already exists: $name", json)
        if (updateOnly && existing == null) return errorResponse(404, "Preset not found: $name", json)

        val supplied = (body["preset"] as? JsonObject) ?: (body["data"] as? JsonObject)
            ?: JsonObject(body.filterKeys { it !in presetControlFields })
        val raw = if (existing != null && !updateOnly) JsonObject(existing.raw + supplied) else supplied
        val preset = presetFromRaw(existing, category, name, raw)
        if (isWorkingCopy) dataStore.saveWorkingPreset(category, preset) else dataStore.savePreset(preset)
        if (!isWorkingCopy && body["load"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() != false) {
            dataStore.selectPreset(category, name)
        }
        return jsonResponse(if (existing == null) 201 else 200, buildJsonObject {
            put("ok", true)
            put("name", name)
            put("category", category.name.lowercase())
            put("preset", preset.raw)
        }, json)
    }

    suspend fun handleUpdatePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        val isWorkingCopy = name == "in_use"
        val existing = if (isWorkingCopy) dataStore.readPreset(category, name)
            else dataStore.listPresets().firstOrNull { it.category == category && it.id == name }
        existing ?: return errorResponse(404, "Preset not found: $name", json)
        val patch = body["patch"] as? JsonObject
            ?: return errorResponse(400, "Missing 'patch' JSON object", json)
        val mergedRaw = JsonObject(existing.raw + patch)
        val updated = presetFromRaw(existing, category, name, mergedRaw)
        if (isWorkingCopy) dataStore.saveWorkingPreset(category, updated) else {
            dataStore.savePreset(updated)
            dataStore.selectPreset(category, name)
        }
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("preset", updated.raw)
        }, json)
    }

    suspend fun handleDeletePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val name = requirePresetName(body.stringValue("name"))
        if (name == "in_use") return errorResponse(400, "The working preset cannot be deleted", json)
        val deleted = dataStore.deletePreset(name, category.name.lowercase())
        return if (deleted) {
            jsonResponse(200, buildJsonObject { put("ok", true) }, json)
        } else {
            errorResponse(404, "Preset not found: $name", json)
        }
    }

    suspend fun handleRenamePreset(request: VirtualApiRequest): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val category = parsePresetCategory(body.stringValue("category") ?: "openai")
        val oldName = requirePresetName(body.stringValue("name") ?: body.stringValue("oldName"))
        val newName = requirePresetName(body.stringValue("newName"))
        if (oldName == "in_use") return errorResponse(400, "The working preset cannot be renamed", json)
        val presets = dataStore.listPresets().filter { it.category == category }
        val existing = presets.firstOrNull { it.id == oldName }
            ?: return errorResponse(404, "Preset not found: $oldName", json)
        if (presets.any { it.id == newName }) return errorResponse(409, "Preset already exists: $newName", json)
        val wasSelected = dataStore.readSelectedPresetName(category) == oldName
        dataStore.savePreset(existing.copy(id = newName, name = newName))
        dataStore.deletePreset(oldName, category.name.lowercase())
        if (wasSelected) dataStore.selectPreset(category, newName)
        return jsonResponse(200, buildJsonObject {
            put("ok", true)
            put("name", newName)
        }, json)
    }

    private fun presetFromRaw(
        existing: GenerationPreset?,
        category: PresetCategory,
        name: String,
        raw: JsonObject,
    ): GenerationPreset {
        val completion = raw.intValue("openai_max_tokens")
            ?: raw.intValue("max_tokens")
            ?: existing?.maxCompletionTokens
        val prompts: List<PresetPrompt>
        val promptsUnused: List<PresetPrompt>
        if (raw["prompts"] is JsonArray) {
            val definitions = parsePresetPrompts(raw["prompts"])
            val (ordered, inferredUnused) = applyPromptOrder(definitions, raw["prompt_order"])
            val explicitUnused = parsePresetPrompts(raw["prompts_unused"] ?: raw["promptsUnused"])
            prompts = ordered
            promptsUnused = (inferredUnused + explicitUnused).distinctBy { it.identifier }
        } else {
            prompts = existing?.prompts.orEmpty()
            promptsUnused = existing?.promptsUnused.orEmpty()
        }
        return (existing ?: GenerationPreset(
            id = name,
            name = name,
            providerType = category.name.lowercase(),
            category = category,
        )).copy(
            id = name,
            name = name,
            category = category,
            temperature = raw.doubleValue("temperature") ?: existing?.temperature,
            topP = raw.doubleValue("top_p") ?: raw.doubleValue("topP") ?: existing?.topP,
            topK = raw.intValue("top_k") ?: raw.intValue("topK") ?: existing?.topK,
            maxTokens = completion,
            maxContextTokens = raw.intValue("openai_max_context") ?: existing?.maxContextTokens,
            maxCompletionTokens = completion,
            presencePenalty = raw.doubleValue("presence_penalty") ?: existing?.presencePenalty,
            frequencyPenalty = raw.doubleValue("frequency_penalty") ?: existing?.frequencyPenalty,
            seed = raw["seed"]?.jsonPrimitive?.content?.toLongOrNull() ?: existing?.seed,
            prompts = prompts,
            promptsUnused = promptsUnused,
            stop = (raw["stop"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.content
            } ?: existing?.stop.orEmpty(),
            extensions = raw["extensions"] as? JsonObject ?: existing?.extensions ?: buildJsonObject { },
            raw = raw,
        )
    }

    private fun parsePresetCategory(value: String): PresetCategory = when (value.lowercase()) {
        "textgen", "textgen-webui" -> PresetCategory.TextGen
        "kobold", "koboldai", "koboldcpp" -> PresetCategory.Kobold
        "novelai" -> PresetCategory.NovelAi
        else -> PresetCategory.OpenAi
    }

    private fun requirePresetName(value: String?): String {
        val name = value?.trim().orEmpty()
        require(name.isNotEmpty()) { "Missing preset name" }
        require(!name.contains(Regex("""[\\/:*?"<>|]"""))) { "Invalid preset name" }
        return name
    }
}
