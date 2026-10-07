package app.tellev.core.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 端点 /models 原始条目的能力信号探测（bre detection.ts 的字段约定移植）。
 *
 * 各家披露约定不同：reasoning 信号可能是布尔、capabilities 数组、modalities、
 * "supports_reasoning" 等；tri-state 纪律同 bre——「没说」与「明确不支持」
 * 是两个状态，沉默不是拒绝。
 */
object EndpointSignalDetector {

    /** 一条模型条目的探测结果。每个部分独立可缺：缺席 = 列表没说。 */
    data class Signal(
        /** true 会思考 / false 明确不会 / null 列表没说。 */
        val reasoning: Boolean?,
        /** 产生 reasoning 信号的字段名（诊断用），null = 无。 */
        val reasoningSource: String?,
        /** 列表披露的输入模态（小写），缺席 = 没说。 */
        val input: List<String>?,
        /** 列表披露的上下文长度。 */
        val contextLength: Long?,
    )

    fun detect(entry: JsonObject): Signal {
        var reasoning: Boolean? = null
        var source: String? = null
        var input: List<String>? = null
        var contextLength: Long? = null

        // 布尔约定：reasoning / supports_reasoning / reasoning_capable / is_reasoning
        for (key in listOf("reasoning", "supports_reasoning", "reasoning_capable", "is_reasoning")) {
            val v = (entry[key] as? JsonPrimitive)?.booleanOrNull
            if (v != null) { reasoning = v; source = key; break }
        }
        // capabilities 数组约定：成员含 reasoning/thinking → true；明确列出且无 → 保持未知
        // （数组里可能还有视觉等其它能力，不能反推）。嵌套对象约定：capabilities.reasoning=true。
        if (reasoning == null) {
            when (val caps = entry["capabilities"]) {
                is JsonArray -> {
                    val members = caps.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
                    if (members.any { it == "reasoning" || it == "thinking" || it.startsWith("reasoning") }) {
                        reasoning = true; source = "capabilities"
                    }
                }
                is JsonObject -> {
                    val v = (caps["reasoning"] as? JsonPrimitive)?.booleanOrNull
                        ?: (caps["thinking"] as? JsonPrimitive)?.booleanOrNull
                    if (v != null) { reasoning = v; source = "capabilities.reasoning" }
                }
                else -> {}
            }
        }
        // 模态约定：input_modalities / modalities / input_modes / supported_modalities
        for (key in listOf("input_modalities", "modalities", "input_modes", "supported_modalities")) {
            val arr = entry[key] as? JsonArray ?: continue
            val members = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
            if (members.isNotEmpty()) { input = members; break }
        }
        // 上下文长度约定：context_length / context_window / max_context_tokens / contextSize
        for (key in listOf("context_length", "context_window", "max_context_tokens", "contextSize")) {
            val v = (entry[key] as? JsonPrimitive)?.longOrNull
            if (v != null && v > 0) { contextLength = v; break }
        }
        return Signal(reasoning, source, input, contextLength)
    }

    /** 对整个 /models listing 建立模型 id → 信号映射。 */
    fun detectAll(data: JsonArray): Map<String, Signal> = data.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to detect(obj)
    }.toMap()

    /** ProviderModel.metadata 里存信号的键（listModels 写入，UI/适配消费）。 */
    const val METADATA_KEY = "tellev_endpoint_signal"
}
