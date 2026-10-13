package app.tellev.feature.chat

import kotlin.math.max

/**
 * 官方模型选择器的搜索排序——dsh 主仓库 `ui-primitives/src/rank-by-name.ts` 的逐行移植。
 *
 * 规则：查询串必须是候选名（或它的展示标签）的**大小写不敏感有序子序列**；
 * 前缀命中排最前，其次比对齐分数，最后按源顺序。空查询原样返回输入列表。
 *
 * 决策记录见上游 `.agents/notes/archived/feature/2026-08-04-web-slash-command-fuzzy-discovery.md`。
 */
internal object ModelSearchRanking {

    /** 名称起点与分隔符边界的额外权重。 */
    private fun boundaryBonus(name: String, index: Int): Int =
        if (index == 0 || name[index - 1] == '-' || name[index - 1] == '_') 8 else 0

    /**
     * 在 O(名字长度 × 查询长度) 内给最强有序子序列对齐打分。
     * 边界命中与相邻连续命中加权，跳过与前置字符扣分。
     * 查询不是子序列时返回 null。
     */
    private fun alignmentScore(name: String, query: String): Int? {
        if (query.length > name.length) return null
        val noMatch = Int.MIN_VALUE
        var previous = IntArray(name.length) { noMatch }
        for (index in name.indices) {
            if (name[index] == query[0]) {
                previous[index] = 1 + boundaryBonus(name, index) - index
            }
        }
        for (queryIndex in 1 until query.length) {
            val current = IntArray(name.length) { noMatch }
            // 只扫一遍上一行：left 是后退一格的分数（相邻延续），
            // leftLeft 是后退两格的（最早的跳格匹配）。
            var left = noMatch
            var leftLeft = noMatch
            var bestGapped = noMatch
            for (index in name.indices) {
                val prior = previous[index]
                if (leftLeft != noMatch) bestGapped = max(bestGapped, leftLeft + index - 2)
                if (name[index] == query[queryIndex]) {
                    val bonus = 1 + boundaryBonus(name, index)
                    var score = noMatch
                    if (left != noMatch) score = left + bonus + 4
                    if (bestGapped != noMatch) score = max(score, bestGapped + bonus + 1 - index)
                    current[index] = score
                }
                leftLeft = left
                left = prior
            }
            previous = current
        }
        var best = noMatch
        for (score in previous) best = max(best, score)
        return if (best == noMatch) null else best
    }

    /**
     * 按菜单查询给命名项排序。空查询时原样返回 [items]。
     *
     * @param items 源顺序的候选（宿主目录，再是客户端贡献）
     * @param rawQuery 用户输入的文本，大小写不敏感匹配
     */
    fun <T : NamedCandidate> rankByName(items: List<T>, rawQuery: String): List<T> {
        val query = rawQuery.lowercase()
        if (query.isEmpty()) return items
        data class Ranked(val item: T, val index: Int, val prefix: Boolean, val score: Int)
        val ranked = mutableListOf<Ranked>()
        items.forEachIndexed { index, item ->
            val label = item.label
            val keys = if (label.isNullOrEmpty()) listOf(item.name) else listOf(item.name, label)
            var prefix = false
            var score: Int? = null
            for (key in keys) {
                val lower = key.lowercase()
                val keyScore = alignmentScore(lower, query) ?: continue
                prefix = prefix || lower.startsWith(query)
                score = if (score == null) keyScore else max(score, keyScore)
            }
            if (score != null) ranked += Ranked(item, index, prefix, score)
        }
        // 前缀命中在前，然后分数降序，最后源顺序。
        return ranked
            .sortedWith(
                compareByDescending<Ranked> { it.prefix }
                    .thenByDescending { it.score }
                    .thenBy { it.index },
            )
            .map { it.item }
    }
}

/** 参与搜索排序的候选：名称必填，展示标签可选（第二个搜索键）。 */
internal interface NamedCandidate {
    val name: String
    val label: String?
}

/** 最常用的候选形态：只有名字。 */
internal data class SimpleCandidate(
    override val name: String,
    override val label: String? = null,
) : NamedCandidate

/**
 * 服务商显示顺序——上游 `provider-order.ts` 的移植。
 * 账号与官方服务商排最前，其余保持原有相对顺序；组内模型顺序不变。
 */
internal fun <T : ProviderGroup> orderModelProviders(groups: List<T>): List<T> {
    fun rank(id: String): Int = when (id) {
        "deepseek-account" -> 0
        "deepseek-official" -> 1
        else -> 2
    }
    // Kotlin 的 sortedWith 是稳定排序，同权重保持源顺序。
    return groups.sortedWith(compareBy { rank(it.id) })
}

/** 参与服务商排序的分组：至少要有 id。 */
internal interface ProviderGroup {
    val id: String
}
