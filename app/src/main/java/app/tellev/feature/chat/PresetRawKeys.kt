package app.tellev.feature.chat

import app.tellev.core.model.GenerationPreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 预设 raw 键值编辑（「全部可调」）：把 preset.raw 拍平成点分路径叶子行，
 * 每个叶子按 JSON 类型匹配控件；中间对象与数组叶子提供整键 JSON 编辑。
 *
 * 拍平刻意排除三类：
 *  - 类型化字段已在参数页编辑（temperature/penalties/tokens/reasoning_effort…）
 *  - prompts/prompts_unused/prompt_order 有专属提示词页
 *  - extensions 单列一节（含 SPreset 等嵌套，由调用方决定展开深度）
 *
 * 写回只动自己的路径：setPath 重建该路径，其余键逐字保留（含未知的未来键）。
 */
internal object PresetRawKeys {

    /** 一行可编辑键。 */
    data class Row(
        /** 点分路径（如 `continue_postfix`、`extensions.SPreset.MacroNest`）。 */
        val path: String,
        val value: JsonElement,
    ) {
        val kind: Kind get() = when (value) {
            is JsonPrimitive -> when {
                value.booleanOrNull != null -> Kind.Switch
                value.contentOrNull?.toLongOrNull() != null -> Kind.Number
                else -> Kind.Text
            }
            else -> Kind.Json
        }
    }

    enum class Kind { Switch, Number, Text, Json }

    /** tellev 类型化字段消费的顶层键（参数页/提示词页已覆盖，不重复编辑）。 */
    private val TYPED_KEYS = setOf(
        "temperature", "temp", "temp_openai",
        "top_p", "topP", "top_k", "topK", "top_a", "topA", "min_p", "minP",
        "repetition_penalty", "rep_pen", "repetition_penalty_range", "rep_pen_range",
        "presence_penalty", "frequency_penalty", "seed",
        "openai_max_tokens", "max_tokens", "maxTokens", "max_new_tokens",
        "openai_max_context", "max_context", "context_length",
        "reasoning_effort", "stop", "prompts", "prompts_unused", "prompt_order",
    )

    /**
     * 拍平 [raw] 的可编辑叶子行。对象递归到叶子；空对象/数组本身也是一行
     * （用户可能想往里写内容）。[includeTyped] 为 true 时连采样键一起列出
     * （诊断视图用）；默认 false——参数页已有更好的控件。
     */
    fun rows(raw: JsonObject, includeTyped: Boolean = false): List<Row> {
        val out = mutableListOf<Row>()
        fun walk(prefix: String, element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    if (element.isEmpty()) {
                        out += Row(prefix, element)
                        return
                    }
                    element.forEach { (key, child) ->
                        val path = if (prefix.isEmpty()) key else "$prefix.$key"
                        if (!includeTyped && prefix.isEmpty() && key in TYPED_KEYS) return@forEach
                        walk(path, child)
                    }
                }
                is JsonArray -> out += Row(prefix, element)
                is JsonPrimitive -> out += Row(prefix, element)
            }
        }
        walk("", raw)
        return out
    }

    /** 在 [path] 处写 [value]，重建路径上的对象；其余键原样保留。 */
    fun setPath(raw: JsonObject, path: String, value: JsonElement): JsonObject {
        val segments = path.split('.')
        val head = segments.first()
        if (segments.size == 1) return JsonObject(raw + (head to value))
        val child = (raw[head] as? JsonObject) ?: JsonObject(emptyMap())
        val updated = setPath(child, segments.drop(1).joinToString("."), value)
        return JsonObject(raw + (head to updated))
    }

    /** 删除 [path]；中间空对象顺手清掉，避免留下一层无意义空壳。 */
    fun removePath(raw: JsonObject, path: String): JsonObject {
        val segments = path.split('.')
        val head = segments.first()
        if (segments.size == 1) return JsonObject(raw - head)
        val child = (raw[head] as? JsonObject) ?: return raw
        val updated = removePath(child, segments.drop(1).joinToString("."))
        return if (updated.isEmpty()) JsonObject(raw - head) else JsonObject(raw + (head to updated))
    }

    // ── 预设键「可选添加」：新增键校验与整单应用 ─────────────────────

    /** 新增键名（点分路径）的校验结果。 */
    enum class NewKeyCheck { Ok, Blank, Duplicate, Reserved }

    /**
     * 校验新键名能否安全写入 [raw]：
     *  - [NewKeyCheck.Blank]：空路径或含空段（`a..b`）；
     *  - [NewKeyCheck.Reserved]：顶层是类型化/提示词键（temperature/prompts/…），
     *    由参数页与提示词页管理，raw 侧写回会被 savePreset 的合并覆盖或清掉；
     *  - [NewKeyCheck.Duplicate]：该路径已是叶子行、已是容器（子叶在列，写入会
     *    整段覆盖子树）、或路径中段已被叶子/数组占用（写入会把它们改写成对象）；
     *  - 其余为 [NewKeyCheck.Ok]（对象容器下的新叶或者全新路径，setPath 只创建缺失层级）。
     */
    fun checkNewKey(raw: JsonObject, path: String): NewKeyCheck {
        val normalized = path.trim()
        val segments = normalized.split('.')
        if (normalized.isEmpty() || segments.any { it.isBlank() }) return NewKeyCheck.Blank
        if (segments.first() in TYPED_KEYS) return NewKeyCheck.Reserved
        val existing = rows(raw).map { it.path }
        if (normalized in existing) return NewKeyCheck.Duplicate
        if (existing.any { it.startsWith("$normalized.") }) return NewKeyCheck.Duplicate
        if ((1 until segments.size).any { i -> segments.take(i).joinToString(".") in existing }) {
            return NewKeyCheck.Duplicate
        }
        return NewKeyCheck.Ok
    }

    /**
     * 单键编辑的完整应用：[value] 为 null 删除，否则 setPath 写入；返回的预设
     * 里 raw 与 extensions 类型化引用保持一致。
     *
     * extensions 是 raw 里唯一没有类型化编辑器的对象，savePreset 会拿内存里的
     * typed extensions 覆写 raw 的同名键——不同步就会把刚写入/删除的
     * `extensions.*` 改动静默顶回去（编辑器列出这些叶子行，用户看得见改得动，
     * 落盘却回滚）。
     */
    fun applyEdit(preset: GenerationPreset, path: String, value: JsonElement?): GenerationPreset {
        val raw = if (value == null) removePath(preset.raw, path) else setPath(preset.raw, path, value)
        val extensions = raw["extensions"] as? JsonObject ?: preset.extensions
        return preset.copy(raw = raw, extensions = extensions)
    }

    /**
     * 把用户输入解析成 JsonElement：`true/false` 布尔、整数/小数数字、
     * `{…}`/`[…]` JSON、其余按字符串保留。空串 = 空字符串（不是删除——
     * 删除走 removePath；空串在很多 ST 键里是有意义的值）。
     */
    fun parseUserValue(text: String): JsonElement {
        val trimmed = text.trim()
        if (trimmed.equals("true", true) || trimmed.equals("false", true)) {
            return JsonPrimitive(trimmed.equals("true", true))
        }
        trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
        trimmed.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            runCatching {
                return kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            }
        }
        return JsonPrimitive(text)
    }

    /** 一行值的展示文本（控件值 ↔ 文本）。 */
    fun display(value: JsonElement): String = when (value) {
        is JsonPrimitive -> value.contentOrNull ?: ""
        else -> value.toString()
    }

    /** 数字行的当前数值（非数字按键值原样，如字符串型数字键）。 */
    fun numberOrNull(value: JsonElement): Double? =
        (value as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    fun intOrNull(value: JsonElement): Long? =
        (value as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    fun boolOrNull(value: JsonElement): Boolean? =
        (value as? JsonPrimitive)?.booleanOrNull

    /** 未知键的本地化名提示（键名原样 + 序号由 UI 处理）。 */
    fun label(path: String): String = path

    private inline fun buildRow(path: String, value: JsonElement): Row = Row(path, value)
}
