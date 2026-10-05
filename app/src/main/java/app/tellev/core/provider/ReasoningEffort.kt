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

    fun capabilities(family: ReasoningFamily): ReasoningCapabilities = when (family) {
        ReasoningFamily.None ->
            ReasoningCapabilities(family, setOf(ReasoningEffort.Auto), independentBudget = false)
        ReasoningFamily.OpenAiCompatible, ReasoningFamily.OpenRouter ->
            // OpenAI-shaped bodies accept low/medium/high; "Max" is not a wire
            // value and is clamped at injection time.
            ReasoningCapabilities(
                family,
                setOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High),
                independentBudget = false,
            )
        ReasoningFamily.DeepSeek ->
            // Thinking is a boolean on the relays this family targets; no
            // per-level budget, so Low..Max all map to "enabled".
            ReasoningCapabilities(
                family,
                setOf(ReasoningEffort.Auto, ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.Max),
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

    private fun openAiCompatible(effort: ReasoningEffort, raw: JsonObject): ReasoningInjection = when (effort) {
        ReasoningEffort.Auto ->
            ReasoningInjection(effort, fields = rawFields(raw, "thinking", "reasoning_effort"))
        ReasoningEffort.Off ->
            ReasoningInjection(effort, suppress = setOf("thinking", "reasoning_effort"))
        ReasoningEffort.Max -> ReasoningInjection(
            effort,
            fields = mapOf("reasoning_effort" to JsonPrimitive("high")),
            warnings = listOf("reasoning_effort 已按接口上限映射为 high"),
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
                ReasoningEffort.Low -> 1_024
                ReasoningEffort.Medium -> 4_096
                ReasoningEffort.High -> 12_288
                ReasoningEffort.Max -> 24_576
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
                ReasoningEffort.Low -> 2_048
                ReasoningEffort.Medium -> 8_192
                ReasoningEffort.High -> 16_384
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
