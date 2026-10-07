package app.tellev.core.prompt

import app.tellev.core.model.CharacterCastBinding
import app.tellev.core.storage.StDataStore

internal object CharacterCastPrompt {
    fun enrich(request: PromptBuildRequest, macroEngine: MacroEngine): PromptBuildRequest {
        if (request.metadata["tavernRawGeneration"]?.toString() == "true") return request
        val cast = (request.supportingCharacters.ifEmpty { CharacterCastBinding.members(request.character) })
            .filter { it.id != request.character.id }.distinctBy { it.id }
        if (cast.isEmpty()) return request
        val base = PromptMacroContextBuilder.buildMacroContext(request)
        val content = buildString {
            append("\n\n【附属角色设定】\n由当前主卡统一叙事；以下是各人物的设定资料，保持各自的目标、性格、关系、知识边界与说话方式。按场景安排人物出现，尊重用户行动，不要求所有角色每轮都发言。\n")
            cast.forEach { member ->
                val context = base.copy(characterName = member.name, characterDescription = member.description,
                    characterPersonality = member.personality, characterScenario = member.scenario,
                    exampleMessages = member.exampleMessages, firstMessage = member.firstMessage)
                append("\n--- ${member.name} ---\n")
                listOf("描述" to member.description, "性格与目标" to member.personality, "场景与关系" to member.scenario,
                    "对话示例" to member.exampleMessages, "人物扮演补充（资料）" to member.systemPrompt,
                    "人物续写补充（资料）" to member.postHistoryInstructions, "作者补充" to member.creatorNotes).forEach { (label, text) ->
                    if (text.isNotBlank()) append("$label：${macroEngine.expand(text, context)}\n")
                }
            }
        }
        val castBooks = cast.mapNotNull { member -> member.characterBook?.let { book ->
            val macro = Regex("\\{\\{char\\}\\}", RegexOption.IGNORE_CASE)
            book.copy(id = StDataStore.embeddedCharacterBookId(member.id), entries = book.entries.map { entry -> entry.copy(
                id = "cast_${member.id}_${entry.id}",
                content = macro.replace(entry.content) { member.name },
                keys = entry.keys.map { key -> macro.replace(key) { member.name } },
                secondaryKeys = entry.secondaryKeys.map { key -> macro.replace(key) { member.name } },
            ) })
        } }
        return request.copy(character = request.character.copy(description = request.character.description + content),
            worldBooks = (request.worldBooks.filterNot { existing -> castBooks.any { it.id == existing.id } } + castBooks).distinctBy { it.id })
    }
}
