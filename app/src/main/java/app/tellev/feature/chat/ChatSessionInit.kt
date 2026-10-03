package app.tellev.feature.chat

import app.tellev.core.extension.CharacterTavernHelperScripts
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBook
import app.tellev.core.prompt.TavernInitVariables
import app.tellev.core.prompt.ChatTextProcessing
import app.tellev.core.prompt.DefaultPromptEngine
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.model.GenerationPreset
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Helpers for creating, greeting-upgrading, and variable-initializing chat sessions.
 */
internal object ChatSessionInit {
    private const val GREETING_VERSIONS = "tellev_greeting_macro_versions"
    private const val GREETING_COUNT = "tellev_greeting_source_count"
    private const val GREETING_HASHES = "tellev_greeting_macro_hashes"

    suspend fun createSessionForCharacter(
        character: CharacterCard,
        personaName: String,
        dataStore: StDataStore,
        promptEngine: PromptEngine = DefaultPromptEngine(),
        preset: GenerationPreset? = null,
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
            title = UiStrings.get(S.chat_session_default_title, character.name),
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
        ).withProcessedGreeting(character, personaName, promptEngine, preset)
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

        val count = (first.metadata[GREETING_COUNT] as? JsonPrimitive)?.intOrNull ?: 0
        val mergedSwipes = if (count > 0) first.swipes + greetings.drop(count).filterNot { it in first.swipes }
        else (first.swipes + greetings)
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

    /** ST script.js messageFormatting/getFirstMessage: persist only the chosen greeting. */
    fun ChatSession.withProcessedGreeting(
        character: CharacterCard, userName: String, promptEngine: PromptEngine,
        preset: GenerationPreset? = null,
    ): ChatSession {
        val first = messages.firstOrNull() ?: return this
        if (first.role != MessageRole.Character && first.role != MessageRole.Assistant) return this
        val versions = (first.metadata[GREETING_VERSIONS] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val hashes = (first.metadata[GREETING_HASHES] as? JsonArray)?.toMutableList() ?: mutableListOf()
        if ((versions.getOrNull(first.swipeIndex) as? JsonPrimitive)?.intOrNull == 1 &&
            (hashes.getOrNull(first.swipeIndex) as? JsonPrimitive)?.content == greetingHash(first.content)) return this
        val rawText = first.content
        val originalGreeting = rawText in character.initialGreetings()
        // ST also expands imported first bot floors, but does not rerun their
        // creation-time normal regex. Plain custom first floors stay untouched.
        if (!originalGreeting && !rawText.contains("{{")) return this
        val context = ChatTextProcessing.context(character, this, userName).copy(
            preserveExistingLocalVariables = versions.isEmpty() &&
                (messages.size > 1 || (metadata["variables"] as? JsonObject)?.isNotEmpty() == true),
        )
        val processed = promptEngine.processChatText(rawText, first.role, character, preset, context,
            includeNormal = originalGreeting && !CharacterRegexApplier.isNormalProcessed(first))
        val swipes = first.swipes.toMutableList().apply { if (isNotEmpty()) this[first.swipeIndex] = processed.text }
        while (versions.size <= first.swipeIndex) versions.add(JsonNull)
        versions[first.swipeIndex] = JsonPrimitive(1)
        while (hashes.size <= first.swipeIndex) hashes.add(JsonNull)
        hashes[first.swipeIndex] = JsonPrimitive(greetingHash(processed.text))
        val updatedFirst = CharacterRegexApplier.markNormalProcessed(first.copy(
            content = processed.text, swipes = swipes,
            metadata = JsonObject(first.metadata + mapOf(
                GREETING_VERSIONS to JsonArray(versions), GREETING_COUNT to JsonPrimitive(character.initialGreetings().size),
                GREETING_HASHES to JsonArray(hashes),
            )),
        ))
        return copy(messages = listOf(updatedFirst) + messages.drop(1), metadata = JsonObject(metadata + ("variables" to processed.localVariables)))
    }

    private fun greetingHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun generateMessageId(): String = "msg-${UUID.randomUUID()}"
    fun generateSessionId(): String = "sess-${UUID.randomUUID()}"
}
