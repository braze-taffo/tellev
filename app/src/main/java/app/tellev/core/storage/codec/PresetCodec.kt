package app.tellev.core.storage.codec

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.storage.applyPromptOrder
import app.tellev.core.storage.parsePresetPrompts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

internal object PresetCodec {

    fun parsePreset(
        path: Path,
        raw: JsonObject,
        category: PresetCategory,
        providerType: String,
    ): GenerationPreset {
        val completionTokens = raw.intValue("openai_max_tokens")
            ?: raw.intValue("max_tokens")
            ?: raw.intValue("maxTokens")
            ?: raw.intValue("max_new_tokens")
        val definitions = parsePresetPrompts(raw["prompts"])
        val (orderedPrompts, inferredUnused) = applyPromptOrder(definitions, raw["prompt_order"])
        val explicitUnused = parsePresetPrompts(raw["prompts_unused"] ?: raw["promptsUnused"])
        return GenerationPreset(
            id = path.nameWithoutExtension,
            name = path.nameWithoutExtension,
            providerType = providerType,
            category = category,
            temperature = raw.doubleValue("temperature") ?: raw.doubleValue("temp")
                ?: raw.doubleValue("temp_openai"),
            topP = raw.doubleValue("top_p") ?: raw.doubleValue("topP"),
            topK = raw.intValue("top_k") ?: raw.intValue("topK"),
            topA = raw.doubleValue("top_a") ?: raw.doubleValue("topA"),
            minP = raw.doubleValue("min_p") ?: raw.doubleValue("minP"),
            repetitionPenalty = raw.doubleValue("repetition_penalty")
                ?: raw.doubleValue("rep_pen"),
            repetitionPenaltyRange = raw.intValue("repetition_penalty_range")
                ?: raw.intValue("rep_pen_range"),
            maxTokens = completionTokens,
            maxContextTokens = raw.intValue("openai_max_context")
                ?: raw.intValue("max_context")
                ?: raw.intValue("context_length"),
            maxCompletionTokens = completionTokens,
            presencePenalty = raw.doubleValue("presence_penalty"),
            frequencyPenalty = raw.doubleValue("frequency_penalty"),
            seed = raw["seed"]?.jsonPrimitive?.content?.toLongOrNull(),
            stop = raw.stringList("stop"),
            prompts = orderedPrompts,
            promptsUnused = (inferredUnused + explicitUnused).distinctBy { it.identifier },
            extensions = raw["extensions"] as? JsonObject ?: buildJsonObject { },
            raw = raw,
        )
    }

    fun serializePresetPrompt(prompt: PresetPrompt): JsonObject {
        val merged = prompt.raw.toMutableMap()
        merged["identifier"] = JsonPrimitive(prompt.identifier)
        merged["name"] = JsonPrimitive(prompt.name)
        merged["role"] = JsonPrimitive(prompt.role)
        merged["content"] = JsonPrimitive(prompt.content)
        merged["enabled"] = JsonPrimitive(prompt.enabled)
        merged["relative"] = JsonPrimitive(prompt.relative)
        merged["depth"] = JsonPrimitive(prompt.depth)
        merged["order"] = JsonPrimitive(prompt.order)
        return JsonObject(merged)
    }

    fun serializePromptOrder(existing: JsonElement?, prompts: List<PresetPrompt>): JsonArray {
        val replacement = buildJsonObject {
            put("character_id", JsonPrimitive(100001))
            put("order", JsonArray(prompts.sortedBy { it.order }.map { prompt ->
                buildJsonObject {
                    put("identifier", JsonPrimitive(prompt.identifier))
                    put("enabled", JsonPrimitive(prompt.enabled))
                }
            }))
        }
        val groups = (existing as? JsonArray)?.toMutableList() ?: mutableListOf()
        val targetIndex = groups.indexOfFirst {
            (it as? JsonObject)?.intValue("character_id") == 100001
        }
        if (targetIndex >= 0) groups[targetIndex] = replacement else groups += replacement
        return JsonArray(groups)
    }

    private fun JsonObject.doubleValue(key: String): Double? =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            ?: emptyList()
}
