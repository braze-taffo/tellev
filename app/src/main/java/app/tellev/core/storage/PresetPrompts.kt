package app.tellev.core.storage

import app.tellev.core.model.PresetPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared ST preset prompt parsing (`prompts` / `prompts_unused` / `prompt_order`),
 * used by [FileStDataStore] and by the virtual API router so that extension-side
 * preset writes interpret prompt lists exactly like presets loaded from disk.
 */
internal fun parsePresetPrompts(element: JsonElement?): List<PresetPrompt> =
    (element as? JsonArray)?.mapIndexedNotNull { index, item ->
        val obj = item as? JsonObject ?: return@mapIndexedNotNull null
        val identifier = obj.stringField("identifier")
            ?: obj.stringField("id")
            ?: obj.stringField("name")
            ?: "prompt-$index"
        PresetPrompt(
            identifier = identifier,
            name = obj.stringField("name") ?: identifier,
            role = obj.stringField("role") ?: "system",
            content = obj.stringField("content") ?: obj.stringField("prompt") ?: "",
            enabled = obj["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            relative = obj["relative"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: (obj.intValue("injection_position") == 1),
            // ST PromptManager DEFAULT_DEPTH = 4 when injection_depth is absent.
            depth = obj.intValue("depth") ?: obj.intValue("injection_depth") ?: 4,
            order = obj.intValue("order") ?: index,
            injectionOrder = obj.intValue("injection_order") ?: 100,
            forbidOverrides = obj["forbid_overrides"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            raw = obj,
        )
    } ?: emptyList()

internal fun applyPromptOrder(
    definitions: List<PresetPrompt>,
    element: JsonElement?,
): Pair<List<PresetPrompt>, List<PresetPrompt>> {
    val groups = element as? JsonArray ?: return definitions to emptyList()
    val selectedGroup = groups.mapNotNull { it as? JsonObject }
        .firstOrNull { it.intValue("character_id") == 100001 }
        ?: groups.mapNotNull { it as? JsonObject }.lastOrNull()
        ?: return definitions to emptyList()
    val order = selectedGroup["order"] as? JsonArray ?: return definitions to emptyList()
    val byId = definitions.associateBy { it.identifier }
    val ordered = order.mapIndexedNotNull { index, item ->
        val orderItem = item as? JsonObject ?: return@mapIndexedNotNull null
        val identifier = orderItem.stringField("identifier") ?: return@mapIndexedNotNull null
        val definition = byId[identifier] ?: PresetPrompt(
            identifier = identifier,
            name = identifier,
            order = index,
            raw = orderItem,
        )
        definition.copy(
            enabled = orderItem["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: definition.enabled,
            order = index,
        )
    }
    val orderedIds = ordered.mapTo(mutableSetOf()) { it.identifier }
    return ordered to definitions.filterNot { it.identifier in orderedIds }
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()
