package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.model.reasoningParts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object PromptMacroContextBuilder {

    fun buildMacroContext(request: PromptBuildRequest): MacroContext {
        val macroMessages = request.macroMessages ?: request.messages
        val groupMemberNames = extractGroupMemberNames(request.metadata)
        return withChatSnapshot(MacroContext(
            characterName = request.character.name,
            userName = request.persona?.name ?: "User",
            characterDescription = request.character.description,
            characterPersonality = request.character.personality,
            characterScenario = request.character.scenario,
            exampleMessages = request.character.exampleMessages,
            firstMessage = request.character.firstMessage,
            groupMemberNames = groupMemberNames,
            maxPromptTokens = request.preset.maxCompletionTokens
                ?: request.preset.maxTokens
                ?: DEFAULT_MAX_COMPLETION_TOKENS,
            maxContextTokens = request.preset.maxContextTokens
                ?: extractMaxContextTokens(request.metadata)
                ?: DEFAULT_MAX_CONTEXT_TOKENS,
            // ── Step 5 gap-fill: SillyTavern macro parity ──
            personaDescription = request.persona?.description.orEmpty(),
            modelName = extractModelName(request.metadata, request.providerType),
            maxResponseTokens = extractMaxResponseTokens(request.metadata)
                ?: request.preset.maxCompletionTokens
                ?: request.preset.maxTokens
                ?: DEFAULT_MAX_COMPLETION_TOKENS,
            inputText = request.userInput,
            characterId = request.character.id,
            alternateGreetings = request.character.alternateGreetings,
            characterVariables = extractCharacterVariables(request.character),
        ), macroMessages)
    }

    /** ST indexes the saved chat, including hidden floors (`is_system`). */
    fun withChatSnapshot(context: MacroContext, messages: List<ChatMessage>): MacroContext {
        val userIndex = messages.indexOfLast { it.role == MessageRole.User && !it.isHidden }
        val charIndex = messages.indexOfLast { it.role != MessageRole.User && !it.isHidden }
        // TavernHelper macro_like.ts selects the latest active-swipe variables
        // object, even on a hidden floor. MVU owns initvar initialization; a
        // missing saved object must not be replaced with uncommitted defaults.
        val variables = messages.lastOrNull { it.variables.getOrNull(it.swipeIndex) is JsonObject }
            ?.let { it.variables[it.swipeIndex] as JsonObject }
        return context.copy(
            lastMessage = messages.lastOrNull()?.let(::messageContent).orEmpty(),
            lastMessageId = if (messages.isEmpty()) "" else messages.lastIndex.toString(),
            lastUserMessage = messages.getOrNull(userIndex)?.let(::messageContent).orEmpty(),
            lastCharMessage = messages.getOrNull(charIndex)?.let(::messageContent).orEmpty(),
            lastUserMessageId = userIndex,
            lastCharMessageId = charIndex,
            messageVariables = variables,
        )
    }

    /** Resolves the active content of a message, honoring the current swipe. */
    fun messageContent(message: ChatMessage): String =
        message.reasoningParts().body

    fun expandCharacterFields(
        character: CharacterCard,
        context: MacroContext,
        macroEngine: MacroEngine,
    ): CharacterCard {
        return character.copy(
            description = macroEngine.expand(character.description, context),
            personality = macroEngine.expand(character.personality, context),
            scenario = macroEngine.expand(character.scenario, context),
            // Keep first_mes lazy. Explicit {{greeting}} still resolves the card's
            // main greeting; merely building character fields must not replay it.
            firstMessage = character.firstMessage,
            exampleMessages = macroEngine.expand(character.exampleMessages, context),
        )
    }

    /**
     * Extract group member names from metadata. ChatViewModel writes
     * `groupMembers` as an array of name strings; the older object form
     * (`[{"name": ...}]`) is also accepted.
     */
    fun groupMemberNamesList(metadata: JsonObject): List<String> {
        val groupMembers = metadata["groupMembers"] as? JsonArray ?: return emptyList()
        return groupMembers.mapNotNull { element ->
            when (element) {
                is JsonObject -> element["name"]?.jsonPrimitive?.content
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    fun extractGroupMemberNames(metadata: JsonObject): String =
        groupMemberNamesList(metadata).joinToString(", ")

    fun extractMaxContextTokens(metadata: JsonObject): Int? {
        val element = metadata["maxContextTokens"] ?: return null
        return try {
            element.jsonPrimitive.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    /** {{model}} — selected model id, falling back to the provider type. */
    fun extractModelName(metadata: JsonObject, providerType: String): String {
        val element = metadata["modelName"] ?: return providerType
        return try {
            element.jsonPrimitive.content
        } catch (_: Exception) {
            providerType
        }
    }

    /** {{maxResponse}} — max response tokens, falling back to the preset value. */
    fun extractMaxResponseTokens(metadata: JsonObject): Int? {
        val element = metadata["maxResponseTokens"] ?: return null
        return try {
            element.jsonPrimitive.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract `data.extensions.tavern_helper.variables` from the character card
     * as a JsonObject for the character variable scope. Returns null when the
     * card has no tavern_helper variables.
     */
    fun extractCharacterVariables(character: CharacterCard): JsonObject? {
        val data = character.raw["data"] as? JsonObject ?: character.raw
        val extensions = data["extensions"] as? JsonObject ?: return null
        val tavernHelper = extensions["tavern_helper"] as? JsonObject ?: return null
        // tavern_helper might be stored as an array of [key, value] pairs (old format)
        val variables = tavernHelper["variables"]
        return when {
            variables is JsonObject -> variables
            variables is JsonArray -> {
                // Old format: [[key, value], ...] -> object
                val map = variables.mapNotNull { element ->
                    val pair = element as? JsonArray ?: return@mapNotNull null
                    val key = pair.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
                    val value = pair.getOrNull(1) ?: return@mapNotNull null
                    key to value
                }.toMap()
                if (map.isEmpty()) null else JsonObject(map)
            }
            else -> null
        }
    }
}
