package app.tellev.feature.creation

/** A starting brief stays in the conversation, so reopening a draft keeps the user's choices. */
internal data class CreationBrief(
    val kind: CreationKind,
    val guided: Boolean,
    val title: String = "",
    val premise: String = "",
    val relationship: String = "",
    val userPersona: String = "",
    val characters: String = "",
    val detail: CreationDetail = CreationDetail.Normal,
    val loreOneByOne: Boolean = false,
) {
    fun toPrompt(): String = buildString {
        appendLine("【创作起点】")
        appendLine(if (kind == CreationKind.Character) "请帮我创作角色卡。" else "请帮我创作世界书。")
        appendLine("创作方式：${if (guided) "引导对话" else "直接生成初稿"}。")
        if (kind == CreationKind.Character) {
            appendLine("篇幅：${detail.label}（${detail.characterLength}，请以内容质量为准，不为凑字数重复）。")
            if (characters.isNotBlank()) appendLine("多角色设定（每行一个角色，保留各自身份与关系）：\n${characters.trim()}")
            if (relationship.isNotBlank()) appendLine("角色与用户的关系：${relationship.trim()}")
            if (userPersona.isNotBlank()) appendLine("用户自身设定：${userPersona.trim()}")
        } else {
            appendLine(if (loreOneByOne) "世界书方式：先逐条讨论；每条确认后再加入草稿。" else "世界书方式：根据现有信息生成一版可编辑的完整条目草稿。")
        }
        if (title.isNotBlank()) appendLine("${if (kind == CreationKind.Character) "角色或故事名" else "世界书名"}：${title.trim()}")
        if (premise.isNotBlank()) appendLine("核心设定：\n${premise.trim()}")
        if (guided) {
            append("请先根据已提供的信息写出能确定的部分，再只问当前最关键的 1 至 2 个问题；不要重复询问已经填写的内容。")
        } else {
            append(if (kind == CreationKind.Character) {
                "请现在实际填写角色卡草稿字段；有必要时再写可用的世界书条目。"
            } else if (loreOneByOne) {
                "请先提议第一条世界书条目，等我确认后再写入草稿。"
            } else {
                "请现在实际填写可编辑的世界书条目草稿。"
            })
            append("最后简短说明哪些设定需要我核对。")
        }
    }.trim()
}

internal enum class CreationDetail(val label: String, val characterLength: String) {
    Concise("精简", "约 500 至 1000 字"),
    Normal("标准", "约 1000 至 2000 字"),
    Rich("详细", "约 2000 至 5000 字"),
}
