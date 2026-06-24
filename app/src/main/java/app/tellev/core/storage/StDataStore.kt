package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import java.nio.file.Path

interface StDataStore {
    val layout: StDirectoryLayout

    suspend fun bootstrap()

    suspend fun listCharacters(): List<CharacterSummary>
    suspend fun readCharacter(id: String): CharacterCard
    suspend fun saveCharacter(card: CharacterCard)
    suspend fun importCharacter(card: CharacterCard, sourceBytes: ByteArray, sourceFileName: String) {
        saveCharacter(card)
    }

    suspend fun listChatSessions(characterId: String? = null, groupId: String? = null): List<ChatSession>
    suspend fun readChatSession(id: String): ChatSession
    suspend fun saveChatSession(session: ChatSession)
    suspend fun appendMessage(sessionId: String, message: ChatMessage)

    suspend fun listGroups(): List<GroupChat>
    suspend fun saveGroup(group: GroupChat)

    suspend fun listWorldBooks(): List<WorldBook>
    suspend fun readWorldBook(id: String): WorldBook
    suspend fun saveWorldBook(book: WorldBook)

    // World book activation. Worlds absent from the disabled set are active
    // (default on), so freshly imported character books activate by default
    // and no migration is needed.
    suspend fun readDisabledWorldIds(): Set<String>
    suspend fun saveDisabledWorldIds(ids: Set<String>)

    // Per-character regex script activation, keyed by character id. Scripts
    // whose id is in a character's set are skipped at display time. Absent
    // (or a missing character entry) = all scripts active (default on).
    suspend fun readDisabledRegexScriptIds(): Map<String, Set<String>>
    suspend fun saveDisabledRegexScriptIds(map: Map<String, Set<String>>)

    suspend fun listPresets(): List<GenerationPreset>
    suspend fun savePreset(preset: GenerationPreset)
    suspend fun deletePreset(id: String, providerType: String? = null): Boolean

    suspend fun listPersonas(): List<Persona>
    suspend fun savePersona(persona: Persona)

    suspend fun exportBackup(targetZip: Path)
    suspend fun importBackup(sourceZip: Path)

    companion object {
        /**
         * The id under which a character card's embedded `character_book` is
         * materialized into `worlds/` at import time. Shared so other layers
         * (e.g. chat activation logic) can derive the same id without
         * duplicating the string rule.
         */
        fun embeddedCharacterBookId(characterId: String): String =
            "${characterId}_character_book"
    }
}
