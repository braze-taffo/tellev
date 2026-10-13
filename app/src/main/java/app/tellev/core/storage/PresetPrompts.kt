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
    // 停用条目留在 prompts 里并带 enabled=false：raw 的 prompts 数组必须保持完整，
    // 虚拟 API 与磁盘回读都按它取全量定义。编辑器要的「只用启用的」由
    // [presetPromptEditorStack] 拆分，不在这里改形状。
    return ordered to definitions.filterNot { it.identifier in orderedIds }
}

/**
 * 预设调节 → 提示词页的两份列表：启用栈（按 prompt_order 顺序）与可一键启用的
 * 备用栈。
 *
 * `prompt_order` 同时承载顺序与启停，所以解析出来的 `prompts` 里既有启用条目
 * 也有 enabled=false 的条目（生成链按 enabled 过滤，虚拟 API 要全量）。直接把它
 * 当启用列表用，界面就会列出模型根本不会注入的指令——真实预设可以借此停用大半
 * 栈，用户既看不出真实状态，也没法管理它们。
 */
internal fun presetPromptEditorStack(
    prompts: List<PresetPrompt>,
    unused: List<PresetPrompt>,
): Pair<List<PresetPrompt>, List<PresetPrompt>> {
    val disabledIds = prompts.filterNot { it.enabled }.mapTo(mutableSetOf()) { it.identifier }
    return prompts.filter { it.enabled } to
        (prompts.filterNot { it.enabled } + unused.filterNot { it.identifier in disabledIds })
}

// ── 预设提示词「可选添加」：自定义条目 ──────────────────────────────

/**
 * 用户新增提示词的 identifier 前缀。ST 原生条目（main/worldInfoBefore/…）不会
 * 用它，因此按前缀计数即「用户新增」计数：上限只约束用户加的量，不因真实预设
 * 自带 100+ 条目而挡住新增入口。
 */
internal const val PRESET_CUSTOM_PROMPT_PREFIX = "custom-"

/** 单个预设允许添加的自定义提示词上限（护栏；达到后新增入口关闭）。 */
internal const val PRESET_CUSTOM_PROMPT_LIMIT = 64

/** 生成不与 [existingIdentifiers] 冲突的 `custom-N` identifier。 */
internal fun nextCustomPromptIdentifier(existingIdentifiers: Collection<String>): String {
    val used = existingIdentifiers.toSet()
    var n = 1
    while ("$PRESET_CUSTOM_PROMPT_PREFIX$n" in used) n++
    return "$PRESET_CUSTOM_PROMPT_PREFIX$n"
}

/** 未达 [PRESET_CUSTOM_PROMPT_LIMIT]（按 custom- 前缀计数）才允许继续新增。 */
internal fun canAddCustomPrompt(prompts: Collection<PresetPrompt>): Boolean =
    prompts.count { it.identifier.startsWith(PRESET_CUSTOM_PROMPT_PREFIX) } < PRESET_CUSTOM_PROMPT_LIMIT

/**
 * 保存前清掉「加了没填」的空值行：仅针对本次编辑新增的 identifier
 * （[addedIdentifiers]），且 name 与 content 都为空的行——新增行允许编辑，
 * 但空行不落盘。既有条目一律不动（ST 默认预设里 content 为空的条目很常见，
 * 例如空 jailbreak，不是「没填」）。
 */
internal fun withoutUntouchedCustomPrompts(
    active: List<PresetPrompt>,
    unused: List<PresetPrompt>,
    addedIdentifiers: Set<String>,
): Pair<List<PresetPrompt>, List<PresetPrompt>> {
    fun keep(prompt: PresetPrompt): Boolean =
        prompt.identifier !in addedIdentifiers ||
            prompt.name.isNotBlank() ||
            prompt.content.isNotBlank()
    return active.filter(::keep) to unused.filter(::keep)
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.intValue(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()
