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
        append(when {
            kind == CreationKind.WorldBook && loreOneByOne -> "请先提议第一条世界书条目，等我确认后再写入草稿。"
            guided -> CreationPrompts.guidedInstruction
            kind == CreationKind.Character -> CreationPrompts.characterDraftInstruction
            else -> CreationPrompts.worldBookDraftInstruction
        })
        append("最后简短说明实际改动及真正需要我核对之处，不输出固定审核清单。")
    }.trim()
}

internal enum class CreationDetail(val label: String, val characterLength: String) {
    Concise("精简", "约 500 至 1000 字"),
    Normal("标准", "约 1000 至 2000 字"),
    Rich("详细", "约 2000 至 5000 字"),
}
