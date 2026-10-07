package app.tellev.core.provider

import app.tellev.core.model.ChatSession
import app.tellev.core.model.ReasoningEffort
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Provider-side mapping of the unified [app.tellev.core.model.ReasoningEffort]
 * onto each wire protocol. Pure functions — no I/O, no secrets, unit-testable.
 */
enum class ReasoningFamily { None, OpenAiCompatible, DeepSeek, OpenRouter, Gemini, Anthropic }

/** What a provider family can express for reasoning controls. */
data class ReasoningCapabilities(
    val family: ReasoningFamily,
    val supportedLevels: Set<ReasoningEffort>,
    /** True when the family maps strength to an explicit token budget. */
    val independentBudget: Boolean,
) {
    val supportsReasoning: Boolean
        get() = family != ReasoningFamily.None && supportedLevels.any { it != ReasoningEffort.Auto }
}

/** The reasoning fields one adapter must apply to (or drop from) its request body. */
data class ReasoningInjection(
    val resolved: ReasoningEffort,
    /** Body fields to set, overriding preset values (generationConfig-level for Gemini). */
    val fields: Map<String, JsonElement> = emptyMap(),
    /** Body field names that must NOT reach the wire even if the preset carries them. */
    val suppress: Set<String> = emptySet(),
    /** Human-readable mapping notes; must never contain credentials or prompt content. */
    val warnings: List<String> = emptyList(),
)

object ReasoningSupport {

    /** Request metadata key carrying a per-request effort override (stored name string). */
    const val REQUEST_OVERRIDE_KEY = "tellev_reasoning_effort"

    /** Chat-session metadata key storing the per-session effort (stored name string). */
    const val SESSION_SETTING_KEY = "tellev_reasoning_effort"

    /** Request metadata key carrying the session-level override into [effortFor] (transport). */
    const val SESSION_OVERRIDE_KEY = "tellev_reasoning_effort_session"

    /** Session-level effort from chat-session metadata; null = no override. */
    fun sessionOverrideFrom(metadata: JsonObject?): ReasoningEffort? =
        metadata?.get(SESSION_SETTING_KEY)
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.let(ReasoningEffort::fromStored)

    /** Transport metadata injecting a session's override into a [GenerateRequest]. */
    fun sessionOverrideMetadata(session: ChatSession?): JsonObject =
        sessionOverrideFrom(session?.metadata)
            ?.let { buildJsonObject { put(SESSION_OVERRIDE_KEY, JsonPrimitive(it.name)) } }
            ?: JsonObject(emptyMap())

    /**
     * Session metadata with the per-session effort applied. Null or [ReasoningEffort.Auto]
     * clears the override so the preset's own field applies again — "Auto" in the UI
     * means "follow the preset", not an explicit level.
     */
    fun withSessionOverride(metadata: JsonObject, effort: ReasoningEffort?): JsonObject {
        val rest = JsonObject(metadata.filterKeys { it != SESSION_SETTING_KEY })
        if (effort == null || effort == ReasoningEffort.Auto) return rest
        return JsonObject(rest + (SESSION_SETTING_KEY to JsonPrimitive(effort.name)))
    }

    /** Maps a provider type (plus its options) onto a reasoning protocol family. */
    fun familyFor(providerType: String, options: JsonObject = JsonObject(emptyMap())): ReasoningFamily = when {
        providerType == ProviderCatalog.DEEPSEEK -> ReasoningFamily.DeepSeek
        providerType == ProviderCatalog.OPENROUTER -> ReasoningFamily.OpenRouter
        providerType == ProviderCatalog.GEMINI -> ReasoningFamily.Gemini
        providerType == ProviderCatalog.ANTHROPIC -> ReasoningFamily.Anthropic
        optionBoolean(options, "supportsReasoning") == true -> ReasoningFamily.OpenAiCompatible
        else -> ReasoningFamily.None
    }

    /**
     * Adapter-side family gate: an explicit effort (Off..Max) must reach the
     * wire even when the endpoint never declared reasoning support — OpenAI-
     * shaped bodies take the OpenAiCompatible mapping. [ReasoningEffort.Auto]
     * keeps the legacy passthrough exactly (no fields when not opted in), so
     * presets that already carry reasoning fields keep working.
     */
    fun effectiveFamily(providerType: String, options: JsonObject, effort: ReasoningEffort): ReasoningFamily {
        val declared = familyFor(providerType, options)
        return if (declared == ReasoningFamily.None && effort != ReasoningEffort.Auto) {
            ReasoningFamily.OpenAiCompatible
        } else {
            declared
        }
    }

    fun capabilities(family: ReasoningFamily): ReasoningCapabilities = when (family) {
        ReasoningFamily.None ->
            ReasoningCapabilities(family, setOf(ReasoningEffort.Auto), independentBudget = false)
        ReasoningFamily.OpenAiCompatible, ReasoningFamily.OpenRouter ->
            // OpenAI-shaped bodies accept minimal/low/medium/high/xhigh; "Max"
            // is not a wire value and is clamped at injection time.
            ReasoningCapabilities(
                family,
                setOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh),
                independentBudget = false,
            )
        ReasoningFamily.DeepSeek ->
            // Thinking is a boolean on the relays this family targets; no
            // per-level budget, so Low..Max all map to "enabled".
            ReasoningCapabilities(
                family,
                setOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh, ReasoningEffort.Max),
                independentBudget = false,
            )
        ReasoningFamily.Gemini, ReasoningFamily.Anthropic ->
            ReasoningCapabilities(
                family,
                setOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.Max),
                independentBudget = true,
            )
    }

    /**
     * Ordered effort levels the chat slider may offer for [family]. Honest per
     * protocol: the DeepSeek relay family only expresses on/off, so exactly one
     * enabled level is offered instead of five fake strengths; OpenAI-shaped
     * bodies cap at high (Max is not a wire value); budget families expose the
     * full ladder. A stored level outside the list still resolves on the wire —
     * this list is a UI affordance, not a validation gate.
     */
    fun uiLevels(family: ReasoningFamily): List<ReasoningEffort> = when (family) {
        ReasoningFamily.None -> listOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High)
        ReasoningFamily.OpenAiCompatible, ReasoningFamily.OpenRouter ->
            listOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh)
        ReasoningFamily.DeepSeek -> listOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Low)
        ReasoningFamily.Gemini, ReasoningFamily.Anthropic -> ReasoningEffort.entries.toList()
    }

    /**
     * Slider index for [effort] within [levels]. A stored level the UI no longer
     * offers (e.g. DeepSeek Medium persisted by an older build) snaps to the
     * nearest truthful position: Off stays Off, any enabled level snaps to the
     * last (enabled) entry, Auto to the first.
     */
    fun uiIndexOf(levels: List<ReasoningEffort>, effort: ReasoningEffort): Int =
        levels.indexOf(effort).takeIf { it >= 0 } ?: when (effort) {
            ReasoningEffort.Auto -> 0
            ReasoningEffort.Off -> levels.indexOf(ReasoningEffort.Off).takeIf { it >= 0 } ?: 0
            else -> levels.lastIndex
        }

    /**
     * Applies the override chain. [presetEffort] is the preset's own serialized
     * field; preset raw fields are handled at injection time so [ReasoningEffort.Auto]
     * preserves the legacy passthrough exactly.
     */
    fun resolve(
        requestOverride: ReasoningEffort?,
        sessionOverride: ReasoningEffort?,
        presetEffort: ReasoningEffort?,
    ): ReasoningEffort = requestOverride ?: sessionOverride ?: presetEffort ?: ReasoningEffort.Auto

    /** Per-request effort from [GenerateRequest.metadata] + the preset's typed field. */
    fun effortFor(request: GenerateRequest): ReasoningEffort = resolve(
        requestOverride = request.metadata[REQUEST_OVERRIDE_KEY]
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.let(ReasoningEffort::fromStored) },
        sessionOverride = request.metadata[SESSION_OVERRIDE_KEY]
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.let(ReasoningEffort::fromStored) },
        presetEffort = request.preset.reasoningEffort,
    )

    /**
     * Builds the request-body deltas for one generation. [raw] is the preset's
     * raw JSON (source of legacy fields), [maxTokens] the engine-resolved
     * output budget used to clamp budgets that must stay below it.
     */
    fun inject(
        family: ReasoningFamily,
        effort: ReasoningEffort,
        raw: JsonObject = JsonObject(emptyMap()),
        maxTokens: Int? = null,
    ): ReasoningInjection = when (family) {
        ReasoningFamily.None -> ReasoningInjection(effort)
        ReasoningFamily.OpenAiCompatible -> openAiCompatible(effort, raw)
        ReasoningFamily.DeepSeek -> deepSeek(effort, raw)
        ReasoningFamily.OpenRouter -> openRouter(effort, raw)
        ReasoningFamily.Gemini -> gemini(effort, raw)
        ReasoningFamily.Anthropic -> anthropic(effort, maxTokens)
    }

    /**
     * 档案拼写优先的注入（bre 每模型声明语义）：用户为该模型声明过
     * reasoning profile 时，档位按档案的线上拼写落到 openai 兼容 body；
     * 拼写为 null = 该档不发字段（端点默认）。无档案 → 回落 [inject] 的
     * family 硬编码，行为与旧版完全一致。
     */
    fun injectWithProfile(
        family: ReasoningFamily,
        effort: ReasoningEffort,
        modelId: String?,
        profile: ModelReasoningProfile?,
        raw: JsonObject = JsonObject(emptyMap()),
        maxTokens: Int? = null,
    ): ReasoningInjection {
        if (effort == ReasoningEffort.Auto) return inject(family, effort, raw, maxTokens)
        val efforts = profile?.efforts?.takeIf { it.isNotEmpty() } ?: return inject(family, effort, raw, maxTokens)
        val wire = efforts[effort.name.lowercase()] ?: run {
            // 档案没声明这个档位：off 缺席时抑制字段（不发明拼写），其余档回落 family。
            if (effort == ReasoningEffort.Off) {
                return ReasoningInjection(effort, suppress = setOf("reasoning_effort", "thinking", "reasoning"))
            }
            return inject(family, effort, raw, maxTokens)
        }
        if (wire == null) {
            return ReasoningInjection(effort, suppress = setOf("reasoning_effort", "thinking", "reasoning"))
        }
        return ReasoningInjection(
            effort,
            fields = mapOf("reasoning_effort" to JsonPrimitive(wire)),
            suppress = setOf("thinking", "reasoning"),
        )
    }

    private fun openAiCompatible(effort: ReasoningEffort, raw: JsonObject): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto ->
            ReasoningInjection(effort, fields = rawFields(raw, "thinking", "reasoning_effort"))
        ReasoningEffort.Off ->
            ReasoningInjection(effort, suppress = setOf("thinking", "reasoning_effort"))
        ReasoningEffort.Max -> ReasoningInjection(
            effort,
            fields = mapOf("reasoning_effort" to JsonPrimitive("xhigh")),
            warnings = listOf("reasoning_effort 已按接口上限映射为 xhigh（端点不支持时请用 high 档）"),
        )
        else -> ReasoningInjection(
            effort,
            fields = mapOf("reasoning_effort" to JsonPrimitive(effort.name.lowercase())),
        )
    }

    private fun deepSeek(effort: ReasoningEffort, raw: JsonObject): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto ->
            ReasoningInjection(effort, fields = rawFields(raw, "thinking", "reasoning_effort"))
        ReasoningEffort.Off -> ReasoningInjection(
            effort,
            fields = mapOf("thinking" to buildJsonObject { put("type", JsonPrimitive("disabled")) }),
            suppress = setOf("reasoning_effort"),
        )
        else -> ReasoningInjection(
            effort,
            fields = mapOf("thinking" to buildJsonObject { put("type", JsonPrimitive("enabled")) }),
            suppress = setOf("reasoning_effort"),
            warnings = if (effort == ReasoningEffort.Max) {
                listOf("该接口仅支持开/关思考，强度由模型自身决定")
            } else emptyList(),
        )
    }

    private fun openRouter(effort: ReasoningEffort, raw: JsonObject): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto ->
            ReasoningInjection(effort, fields = rawFields(raw, "reasoning"))
        ReasoningEffort.Off ->
            ReasoningInjection(effort, suppress = setOf("reasoning"))
        ReasoningEffort.Max -> ReasoningInjection(
            effort,
            fields = mapOf("reasoning" to buildJsonObject { put("effort", JsonPrimitive("high")) }),
            warnings = listOf("OpenRouter reasoning.effort 最高为 high"),
        )
        else -> ReasoningInjection(
            effort,
            fields = mapOf("reasoning" to buildJsonObject { put("effort", JsonPrimitive(effort.name.lowercase())) }),
        )
    }

    private fun gemini(effort: ReasoningEffort, raw: JsonObject): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto ->
            ReasoningInjection(effort, fields = rawFields(raw, "thinkingConfig"))
        ReasoningEffort.Off -> ReasoningInjection(
            effort,
            fields = mapOf("thinkingConfig" to buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) }),
            warnings = listOf("thinkingBudget=0 仅 Gemini 2.5 Flash 支持关闭；Pro 会忽略该值"),
        )
        else -> {
            val budget = when (effort) {
                ReasoningEffort.Minimal -> 512
                ReasoningEffort.Low -> 1_024
                ReasoningEffort.Medium -> 4_096
                ReasoningEffort.High -> 12_288
                ReasoningEffort.XHigh -> 24_576
                ReasoningEffort.Max -> 32_768
                else -> null
            } ?: return ReasoningInjection(effort)
            ReasoningInjection(
                effort,
                fields = mapOf("thinkingConfig" to buildJsonObject { put("thinkingBudget", JsonPrimitive(budget)) }),
            )
        }
    }

    private fun anthropic(effort: ReasoningEffort, maxTokens: Int?): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto -> ReasoningInjection(effort)
        ReasoningEffort.Off ->
            ReasoningInjection(effort, suppress = setOf("thinking"))
        else -> {
            // Anthropic requires budget_tokens >= 1024 and < max_tokens, and
            // temperature == 1 without top_p while extended thinking is on.
            val wanted = when (effort) {
                ReasoningEffort.Minimal -> 1_024
                ReasoningEffort.Low -> 2_048
                ReasoningEffort.Medium -> 8_192
                ReasoningEffort.High -> 16_384
                ReasoningEffort.XHigh -> 24_576
                ReasoningEffort.Max -> 32_768
                else -> return ReasoningInjection(effort)
            }
            val budget = maxTokens?.let { wanted.coerceAtMost(it - 1) } ?: wanted
            if (budget < 1_024) {
                ReasoningInjection(effort, warnings = listOf("输出预算不足以开启 extended thinking（需 ≥1024 tokens）"))
            } else {
                val warnings = buildList {
                    if (budget < wanted) add("thinking 预算已按输出上限收缩为 $budget")
                    add("extended thinking 要求 temperature=1 且不带 top_p，本轮请求已覆盖预设中的对应字段")
                }
                ReasoningInjection(
                    effort,
                    fields = mapOf(
                        "thinking" to buildJsonObject {
                            put("type", JsonPrimitive("enabled"))
                            put("budget_tokens", JsonPrimitive(budget))
                        },
                        "temperature" to JsonPrimitive(1),
                    ),
                    suppress = setOf("top_p"),
                    warnings = warnings,
                )
            }
        }
    }

    /** Legacy raw fields copied verbatim for [ReasoningEffort.Auto]. */
    private fun rawFields(raw: JsonObject, vararg keys: String): Map<String, JsonElement> =
        keys.mapNotNull { key -> raw[key]?.let { key to it } }.toMap()

    private fun optionBoolean(options: JsonObject, key: String): Boolean? =
        options[key]?.let { element ->
            (element as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        }
}
