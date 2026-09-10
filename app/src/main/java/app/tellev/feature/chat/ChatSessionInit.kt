package app.tellev.feature.chat

import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBook
import app.tellev.core.prompt.TavernInitVariables
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Helpers for creating, greeting-upgrading, and variable-initializing chat sessions.
 */
internal object ChatSessionInit {

    suspend fun createSessionForCharacter(
        character: CharacterCard,
        personaName: String,
        dataStore: StDataStore,
    ): ChatSession {
        val sessionId = generateSessionId()
        val greetings = character.initialGreetings()
        val firstMessage = if (greetings.isNotEmpty()) {
            listOf(
                ChatMessage(
                    id = generateMessageId(),
                    role = MessageRole.Character,
                    name = character.name,
                    content = greetings.first(),
                    createdAtMillis = System.currentTimeMillis(),
                    swipes = greetings,
                    swipeIndex = 0,
                ),
            )
        } else {
            emptyList()
        }

        val session = ChatSession(
            id = sessionId,
            title = "和 ${character.name} 的聊天",
            characterId = character.id,
            groupId = null,
            messages = firstMessage,
            rawHeader = buildJsonObject {
                put("user_name", personaName)
                put("character_name", character.name)
            },
        )

        val initialized = session.withTavernInitVariables(
            character = character,
            worldBooks = listOfNotNull(character.characterBook),
        )
        dataStore.saveChatSession(initialized)
        return dataStore.readChatSession(initialized.id)
    }

    fun ChatSession.withTavernInitVariables(
        character: CharacterCard,
        worldBooks: List<WorldBook>,
    ): ChatSession {
        if (CharacterTavernHelperScripts.extract(character).any { it.content.contains("MagVarUpdate/") }) return this
        if (messages.any { message ->
                (message.variables.getOrNull(message.swipeIndex) as? JsonObject)?.containsKey("stat_data") == true
            }
        ) {
            return this
        }
        val initial = TavernInitVariables.extractMessageVariables(
            if (worldBooks.isNotEmpty()) worldBooks else listOfNotNull(character.characterBook),
        ) ?: return this
        if (messages.isEmpty()) return this

        val targetIndex = messages.indexOfFirst {
            it.role == MessageRole.Character || it.role == MessageRole.Assistant
        }.takeIf { it >= 0 } ?: 0
        val updatedMessages = messages.toMutableList()
        val message = updatedMessages[targetIndex]
        val swipeCount = message.swipes.ifEmpty { listOf(message.content) }.size
            .coerceAtLeast(message.swipeIndex + 1)
        val variables = MutableList(swipeCount) { initial }
        val initialized = MutableList(swipeCount) { JsonPrimitive(true) }
        updatedMessages[targetIndex] = message.copy(
            variables = variables,
            variablesInitialized = initialized,
        )
        return copy(messages = updatedMessages)
    }

    fun CharacterCard.initialGreetings(): List<String> =
        (listOf(firstMessage) + alternateGreetings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun ChatSession.withCharacterGreetingSwipes(character: CharacterCard): ChatSession {
        val greetings = character.initialGreetings()
        if (greetings.size <= 1 || messages.isEmpty()) return this

        val first = messages.first()
        if (first.role != MessageRole.Character && first.role != MessageRole.Assistant) return this

        val mergedSwipes = (first.swipes + greetings)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (mergedSwipes == first.swipes) return this

        val currentIndex = mergedSwipes.indexOf(first.content).takeIf { it >= 0 }
            ?: first.swipeIndex.coerceIn(0, mergedSwipes.lastIndex)
        val upgradedFirst = first.copy(
            swipes = mergedSwipes,
            swipeIndex = currentIndex,
            content = mergedSwipes[currentIndex],
        )
        return copy(messages = listOf(upgradedFirst) + messages.drop(1))
    }

    fun generateMessageId(): String = "msg-${UUID.randomUUID()}"
    fun generateSessionId(): String = "sess-${UUID.randomUUID()}"
}
