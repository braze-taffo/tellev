package app.tellev.core.provider

/**
 * 思考档位知识库（dsh-better-reasoning-effort knowledge.ts 的精编移植）。
 *
 * 数据来源与 bre 相同：各家官方 API 文档（DeepSeek / OpenAI / Anthropic /
 * Google / xAI / 智谱 / 月之暗面 / 阶跃）+ OpenRouter 公开目录交叉核对。
 * 匹配规则同 bre：小写子串命中，最长 pattern 优先（严格更长的别名条目
 * 永远排在其主干之前）。
 *
 * 拼写以 OpenAI 兼容 reasoning_effort 为主形态；DeepSeek 家族的 off 用
 * 'none'（Responses API 的官方枚举；completions 分支只查非空即发
 * thinking:disabled，与 bre 的占位符注释同一坑位记录）。
 */
object ReasoningKnowledgeBase {

    /** 一条知识库条目。 */
    data class Entry(
        val id: String,
        /** 小写子串模式（同时匹配模型 id 与显示名）。 */
        val patterns: List<String>,
        /** 档位 → 线上拼写；null 拼写 = 该档不发字段。false 语义用 efforts=空 + supported=false 表达。 */
        val efforts: Map<String, String?>?,
        val defaultEffort: String? = null,
        val contextWindow: Long? = null,
        val maxTokens: Long? = null,
        /** 人类可读备注（中文）。 */
        val note: String = "",
    )

    /** 推导结果（bre EffortSuggestion：带置信度与来源）。 */
    data class Suggestion(
        val efforts: Map<String, String?>?,
        val defaultEffort: String?,
        val contextWindow: Long?,
        val maxTokens: Long?,
        /** high=知识库命中；low=无命中（无档案可推导）。 */
        val confidence: String,
        val entryId: String?,
        val note: String,
    )

    val ENTRIES: List<Entry> = listOf(
        // ── DeepSeek：严格更长的视觉/世代条目必须排在主干之前（最长命中规则） ──
        Entry(
            id = "deepseek-v4.1-flash",
            patterns = listOf("deepseek-v4.1-flash", "deepseek-flash", "deepseek-v4-vision", "deepseek-v4-flash-vision"),
            efforts = mapOf("off" to "none", "low" to "low", "high" to "high", "max" to "max"),
            defaultEffort = "high",
            contextWindow = 1_048_576,
            maxTokens = 384_000,
            note = "DeepSeek-V4.1-Flash（官方现行 id deepseek-flash，原生图片输入）。枚举 Off(=thinking:disabled)/Low/High/Max，默认 High。",
        ),
        Entry(
            id = "deepseek-v4",
            patterns = listOf("deepseek-v4"),
            efforts = mapOf("off" to "none", "low" to "low", "high" to "high", "max" to "max"),
            defaultEffort = "high",
            contextWindow = 1_048_576,
            maxTokens = 384_000,
            note = "DeepSeek V4 家族纯文本成员（pro/退役别名）。Off 即 thinking:disabled；Responses API 下 off 以 reasoning.effort:none 表示。",
        ),
        Entry(
            id = "deepseek-v3",
            patterns = listOf("deepseek-v3", "deepseek-chat"),
            efforts = mapOf("off" to "none", "high" to "high", "max" to "max"),
            defaultEffort = null,
            contextWindow = 163_840,
            maxTokens = 65_536,
            note = "DeepSeek V3 档位 Off/High/Max（官方已停售，条目服务第三方网关）。",
        ),
        Entry(
            id = "deepseek-r1",
            patterns = listOf("deepseek-r1", "deepseek-reasoner"),
            efforts = mapOf("high" to "high"),
            defaultEffort = "high",
            contextWindow = 163_840,
            maxTokens = 32_768,
            note = "DeepSeek-R1 为纯推理模型，API 层无法关闭思考，仅 High。",
        ),
        // ── OpenAI ──
        Entry(
            id = "openai-gpt-5.2",
            patterns = listOf("gpt-5.2"),
            efforts = mapOf("off" to "none", "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "off",
            contextWindow = 400_000,
            maxTokens = 128_000,
            note = "GPT-5.2 档位 None/Low/Medium/High（默认 None）。gpt-5.2-pro/codex 按同档处理。",
        ),
        Entry(
            id = "openai-gpt-5.1",
            patterns = listOf("gpt-5.1"),
            efforts = mapOf("off" to "none", "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 400_000,
            maxTokens = 128_000,
            note = "GPT-5.1 档位 None/Low/Medium/High（默认 Medium）。",
        ),
        Entry(
            id = "openai-gpt-5",
            patterns = listOf("gpt-5"),
            efforts = mapOf("off" to "none", "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 400_000,
            maxTokens = 128_000,
            note = "GPT-5 档位 None/minimal 兼容为 None/Low/Medium/High（默认 Medium）。",
        ),
        Entry(
            id = "openai-o-series",
            patterns = listOf("o1", "o3", "o4-mini"),
            efforts = mapOf("low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 200_000,
            maxTokens = 100_000,
            note = "o 系列推理模型：Low/Medium/High（无 off——API 层不可关）。",
        ),
        Entry(
            id = "openai-gpt-4o",
            patterns = listOf("gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-"),
            efforts = null,
            defaultEffort = null,
            contextWindow = 128_000,
            maxTokens = 16_384,
            note = "GPT-4o/4.1 世代非推理模型：无 reasoning_effort 档位（发它反而 400）。",
        ),
        // ── Anthropic（OpenAI 兼容端点上经 reasoning_effort；原生走 budget 映射） ──
        Entry(
            id = "anthropic-claude",
            patterns = listOf("claude"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high", "max" to "max"),
            defaultEffort = null,
            contextWindow = 200_000,
            maxTokens = 64_000,
            note = "Claude 4 世代经 OpenAI 兼容端点收 reasoning_effort=low/medium/high/max；原生协议走 thinking budget_tokens（tellev 适配器自动映射）。",
        ),
        // ── Google ──
        Entry(
            id = "gemini-2.5-pro",
            patterns = listOf("gemini-2.5-pro", "gemini-3"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 1_048_576,
            maxTokens = 64_000,
            note = "Gemini 2.5 Pro/3 系：thinkingBudget 映射（tellev 适配器按档位给 0/1024/4096/12288）。Pro 关闭思考会被模型忽略。",
        ),
        Entry(
            id = "gemini-flash",
            patterns = listOf("gemini"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "high",
            contextWindow = 1_048_576,
            maxTokens = 65_536,
            note = "Gemini Flash 系：默认开思考（High），off=thinkingBudget:0。",
        ),
        // ── 国内家族 ──
        Entry(
            id = "qwen-thinking",
            patterns = listOf("qwen3", "qwen-", "qwq"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high", "max" to "max"),
            defaultEffort = "high",
            contextWindow = 262_144,
            maxTokens = 65_536,
            note = "Qwen3/QwQ：enable_thinking + thinking_budget 家族（tellev 适配器映射）。",
        ),
        Entry(
            id = "glm",
            patterns = listOf("glm"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 200_000,
            maxTokens = 128_000,
            note = "GLM-4.5+ thinking 档位（智谱 API）。",
        ),
        Entry(
            id = "kimi-k2",
            patterns = listOf("kimi", "moonshot"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 262_144,
            maxTokens = 131_072,
            note = "Kimi K2 thinking 档位（月之暗面 API）。",
        ),
        Entry(
            id = "step",
            patterns = listOf("step-"),
            efforts = mapOf("off" to null, "low" to "low", "medium" to "medium", "high" to "high"),
            defaultEffort = "medium",
            contextWindow = 262_144,
            maxTokens = 131_072,
            note = "阶跃 Step-3 reasoning effort 档位。",
        ),
    )

    /** bre 最长边界命中：模式按长度降序，第一个命中的条目胜出。 */
    fun suggest(modelId: String, displayName: String? = null): Suggestion {
        val targets = listOf(modelId, displayName.orEmpty()).filter { it.isNotBlank() }
        val hit = ENTRIES.asSequence()
            .flatMap { entry -> entry.patterns.map { pattern -> Triple(pattern.length, entry, pattern) } }
            .filter { (_, _, pattern) -> targets.any { it.contains(pattern, ignoreCase = true) } }
            .maxByOrNull { it.first }
            ?.second
        return if (hit == null) {
            Suggestion(null, null, null, null, "low", null, "无知识库命中：该模型未收录，档位需手动声明")
        } else {
            Suggestion(
                efforts = hit.efforts,
                defaultEffort = hit.defaultEffort,
                contextWindow = hit.contextWindow,
                maxTokens = hit.maxTokens,
                confidence = "high",
                entryId = hit.id,
                note = hit.note,
            )
        }
    }
}
