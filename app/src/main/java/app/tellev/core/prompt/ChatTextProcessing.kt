package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.regex.CharacterRegexApplier
import kotlinx.serialization.json.JsonObject

data class ProcessedChatText(val text: String, val localVariables: JsonObject)

/** Normal regex precedes macro expansion at ST's greeting/user/edit persistence boundary. */
object ChatTextProcessing {
    fun context(character: CharacterCard, session: ChatSession, userName: String, persona: Persona? = null): MacroContext {
        val messages = session.messages.filterNot { it.isHidden }
        return MacroContext(
            characterName = character.name, userName = userName,
            characterDescription = character.description, characterPersonality = character.personality,
            characterScenario = character.scenario, exampleMessages = character.exampleMessages,
            firstMessage = character.firstMessage,
            alternateGreetings = character.alternateGreetings, characterId = character.id,
            personaDescription = persona?.description.orEmpty(),
            lastMessage = messages.lastOrNull()?.content.orEmpty(), lastMessageId = messages.lastIndex.toString(),
            lastUserMessage = messages.lastOrNull { it.role == MessageRole.User }?.content.orEmpty(),
            lastCharMessage = messages.lastOrNull { it.role == MessageRole.Assistant || it.role == MessageRole.Character }?.content.orEmpty(),
            lastUserMessageId = messages.indexOfLast { it.role == MessageRole.User },
            lastCharMessageId = messages.indexOfLast { it.role == MessageRole.Assistant || it.role == MessageRole.Character },
            messageVariables = messages.lastOrNull { it.variables.getOrNull(it.swipeIndex) is JsonObject }
                ?.let { it.variables[it.swipeIndex] as JsonObject },
            characterVariables = PromptMacroContextBuilder.extractCharacterVariables(character),
            groupMemberNames = PromptMacroContextBuilder.extractGroupMemberNames(session.metadata),
            localVariables = session.metadata["variables"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    fun process(
        text: String, role: MessageRole, character: CharacterCard?, preset: GenerationPreset?,
        context: MacroContext, macroEngine: MacroEngine = DefaultMacroEngine(),
        expandMacros: Boolean = true, depth: Int = 0, isEdit: Boolean = false,
        includeNormal: Boolean = true,
    ): ProcessedChatText {
        val variables = SnapshotMacroVariables(
            context.localVariables ?: JsonObject(emptyMap()), context.globalVariables ?: JsonObject(emptyMap()),
            globalAccess = (macroEngine as? DefaultMacroEngine)?.variableStore,
            preserveExistingLocal = context.preserveExistingLocalVariables,
        )
        val responseTokens = preset?.maxCompletionTokens ?: preset?.maxTokens ?: context.maxResponseTokens
        val scoped = context.copy(variableAccess = variables, inputText = text,
            maxContextTokens = preset?.maxContextTokens ?: context.maxContextTokens,
            maxPromptTokens = responseTokens, maxResponseTokens = responseTokens)
        val expand: (String) -> String = { macroEngine.expand(it, scoped) }
        val normal = if (includeNormal) CharacterRegexApplier.applyNormal(text, role, character, preset, context.userName, depth, isEdit, expand) else text
        return ProcessedChatText(if (expandMacros) expand(normal) else normal, variables.localObject())
    }
}
