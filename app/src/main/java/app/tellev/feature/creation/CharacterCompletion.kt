package app.tellev.feature.creation

/**
 * 角色卡完成度（B1）：保存前校验 + 预览卡评分的单一实现。
 *
 * 纯函数：输入 CharacterDraft，输出分数/缺失项/建议。权重按「能不能直接开聊」
 * 排序——开场白与描述是硬门槛，性格/场景是软门槛，其余是增强项。字段与
 * 酒馆 schema 对齐；tellev 专有字段（frontendHtml/coverPrompt）不计分。
 */
internal object CharacterCompletion {

    /** 评分权重（必须覆盖 100 分）。 */
    private val WEIGHTS: List<Pair<(CharacterDraft) -> Boolean, Int>> = listOf(
        { c: CharacterDraft -> c.name.isNotBlank() } to 14,
        { c: CharacterDraft -> c.description.isNotBlank() } to 18,
        { c: CharacterDraft -> c.firstMessage.isNotBlank() } to 16,
        { c: CharacterDraft -> c.personality.isNotBlank() } to 10,
        { c: CharacterDraft -> c.scenario.isNotBlank() } to 8,
        { c: CharacterDraft -> c.exampleMessages.isNotBlank() } to 8,
        { c: CharacterDraft -> c.systemPrompt.isNotBlank() || c.postHistoryInstructions.isNotBlank() } to 6,
        { c: CharacterDraft -> c.creatorNotes.isNotBlank() } to 4,
        { c: CharacterDraft -> c.tags.isNotEmpty() } to 4,
        { c: CharacterDraft -> c.alternateGreetings.any { g -> g.isNotBlank() } } to 4,
        { c: CharacterDraft -> c.frontendHtml.isNotBlank() } to 4,
        { c: CharacterDraft -> c.coverPrompt.isNotBlank() } to 4,
    )

    /** 人话字段名（缺失项与建议文案用）。 */
    private val LABELS: List<Pair<(CharacterDraft) -> Boolean, String>> = listOf(
        { c: CharacterDraft -> c.name.isNotBlank() } to "名称",
        { c: CharacterDraft -> c.description.isNotBlank() } to "简介",
        { c: CharacterDraft -> c.firstMessage.isNotBlank() } to "开场白",
        { c: CharacterDraft -> c.personality.isNotBlank() } to "性格",
        { c: CharacterDraft -> c.scenario.isNotBlank() } to "场景",
        { c: CharacterDraft -> c.exampleMessages.isNotBlank() } to "示例对话",
        { c: CharacterDraft -> c.systemPrompt.isNotBlank() || c.postHistoryInstructions.isNotBlank() } to "系统提示",
        { c: CharacterDraft -> c.creatorNotes.isNotBlank() } to "创建者备注",
        { c: CharacterDraft -> c.tags.isNotEmpty() } to "标签",
        { c: CharacterDraft -> c.alternateGreetings.any { g -> g.isNotBlank() } } to "备用开场白",
        { c: CharacterDraft -> c.frontendHtml.isNotBlank() } to "开场页面",
        { c: CharacterDraft -> c.coverPrompt.isNotBlank() } to "头像提示词",
    )

    /** 保存门槛：没有这些字段的角色卡进聊天也是残的。 */
    private val REQUIRED: List<Pair<(CharacterDraft) -> Boolean, String>> = listOf(
        { c: CharacterDraft -> c.name.isNotBlank() } to "名称",
        { c: CharacterDraft -> c.description.isNotBlank() } to "简介",
        { c: CharacterDraft -> c.firstMessage.isNotBlank() } to "开场白",
    )

    data class Report(
        val score: Int,
        val missing: List<String>,
        val blockers: List<String>,
        val suggestions: List<String>,
    ) {
        val canChat: Boolean get() = blockers.isEmpty()
    }

    fun evaluate(card: CharacterDraft): Report {
        var score = 0
        WEIGHTS.forEach { (check, weight) -> if (check(card)) score += weight }
        val missing = LABELS.filterNot { (check) -> check(card) }.map { (_, label) -> label }
        val blockers = REQUIRED.filterNot { (check) -> check(card) }.map { (_, label) -> label }
        val c = card
        val suggestions = buildList {
            if (c.personality.isBlank()) add("补充性格关键词，聊天语气会更稳定")
            if (c.tags.isEmpty()) add("加 2-4 个标签，角色库检索会更方便")
            if (c.alternateGreetings.none { g -> g.isNotBlank() }) add("加一条备用开场白，重开会话时有多一种开始")
            if (c.exampleMessages.isBlank()) add("写一组示例对话，語氣和格式更容易对齐")
            if (c.coverPrompt.isBlank()) add("补一个头像提示词，可以用 AI 生成封面")
        }
        return Report(
            score = score.coerceIn(0, 100),
            missing = missing,
            blockers = blockers,
            suggestions = suggestions,
        )
    }
}
