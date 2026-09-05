package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetImportResult
import app.tellev.core.model.Persona
import app.tellev.core.model.PromptSettings
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldInfoSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

interface StDataStore {
    val layout: StDirectoryLayout
    val characterChanges: Flow<String>
        get() = emptyFlow()
    val presetChanges: Flow<PresetCategory>
        get() = emptyFlow()
    val worldBookChanges: Flow<String>
        get() = emptyFlow()
    val personaChanges: Flow<String>
        get() = emptyFlow()
    val chatChanges: Flow<String>
        get() = emptyFlow()


    suspend fun bootstrap()

    suspend fun listCharacters(): List<CharacterSummary>
    suspend fun readCharacter(id: String): CharacterCard
    suspend fun saveCharacter(card: CharacterCard)
    suspend fun deleteCharacter(id: String)
    suspend fun importCharacter(card: CharacterCard, sourceBytes: ByteArray, sourceFileName: String) {
        saveCharacter(card)
    }

    /**
     * Replaces a character card's avatar image while keeping all card data
     * (description, greetings, embedded book, ...). [pngBytes] must be an
     * encoded PNG (see app.tellev.util.decodeImageAsPng); the card is saved
     * as <id>.png and any webp/json variants of the same id are removed
     * only after the new file is written.
     */
    suspend fun replaceCharacterAvatar(id: String, pngBytes: ByteArray) {
        error("当前存储实现不支持更换角色头像：$id")
    }

    suspend fun listChatSessions(characterId: String? = null, groupId: String? = null): List<ChatSession>
    suspend fun readChatSession(id: String): ChatSession
    suspend fun saveChatSession(session: ChatSession)
    suspend fun commitChatMutation(base: ChatSession, desired: ChatSession, expectedRevision: Long? = null, operationId: String? = null): ChatSession {
        val merged = applyChatSessionMutation(base, desired, readChatSession(base.id))
        val committed = merged.copy(storageRevision = (expectedRevision ?: base.storageRevision) + 1)
        saveChatSession(committed)
        return committed
    }
    suspend fun appendMessage(sessionId: String, message: ChatMessage)

    suspend fun listGroups(): List<GroupChat>
    suspend fun saveGroup(group: GroupChat)

    suspend fun listWorldBooks(): List<WorldBook>
    suspend fun readWorldBook(id: String): WorldBook
    suspend fun saveWorldBook(book: WorldBook)
    suspend fun importWorldBook(jsonBytes: ByteArray, sourceFileName: String): WorldBook {
        error("当前存储实现不支持导入世界书：$sourceFileName")
    }

    // Permanently remove a world book: deletes worlds/<id>.json and drops the id
    // from the disabled-activation set. Replaces the old "write an empty book"
    // approach that left a stub file reappearing in listWorldBooks().
    suspend fun deleteWorldBook(id: String)

    // World book activation. Worlds absent from the disabled set are active
    // (default on), so freshly imported character books activate by default
    // and no migration is needed.
    suspend fun readDisabledWorldIds(): Set<String>
    suspend fun saveDisabledWorldIds(ids: Set<String>)

    // Legacy v1.2.2 sidecar access retained only for one-time migration into
    // character-card extensions.regex_scripts[].disabled.
    suspend fun readDisabledRegexScriptIds(): Map<String, Set<String>>
    suspend fun saveDisabledRegexScriptIds(map: Map<String, Set<String>>)

    suspend fun listPresets(): List<GenerationPreset>
    suspend fun readPreset(category: PresetCategory, name: String): GenerationPreset? =
        listPresets().firstOrNull { it.category == category && it.id == name }
    suspend fun readSelectedPresetName(category: PresetCategory): String?
    suspend fun selectPreset(category: PresetCategory, name: String)
    suspend fun savePreset(preset: GenerationPreset)
    suspend fun saveWorkingPreset(category: PresetCategory, preset: GenerationPreset) = savePreset(preset)
    suspend fun deletePreset(id: String, providerType: String? = null): Boolean

    // Import a SillyTavern-style preset JSON verbatim into the provider-specific
    // settings directory. Raw bytes are preserved so provider-specific fields are
    // not lost through round-tripping the internal GenerationPreset serializer.
    // [providerCategory] is one of the four ST preset directory keys: "openai",
    // "textgen", "kobold", "novelai". Returns the parsed preset.
    suspend fun importPreset(
        jsonBytes: ByteArray,
        providerCategory: String,
        sourceFileName: String,
    ): PresetImportResult

    // World-info scan settings (ST world_info_recursive / world_info_max_recursion_steps /
    // world_info_depth equivalents), persisted next to the ST data layout.
    suspend fun readWorldInfoSettings(): WorldInfoSettings = WorldInfoSettings()
    suspend fun saveWorldInfoSettings(settings: WorldInfoSettings) = Unit

    // Prompt preferences (ST power_user.prefer_character_prompt / prefer_character_jailbreak).
    suspend fun readPromptSettings(): PromptSettings = PromptSettings()
    suspend fun savePromptSettings(settings: PromptSettings) = Unit

    // Instruct preset loading (from the instruct/ directory).
    suspend fun listInstructPresets(): List<String> = emptyList()
    suspend fun readInstructPreset(name: String): JsonObject? = null

    suspend fun listPersonas(): List<Persona>
    suspend fun savePersona(persona: Persona)
    suspend fun deletePersona(id: String)

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
            "$characterId$EMBEDDED_CHARACTER_BOOK_SUFFIX"

        /** Suffix of [embeddedCharacterBookId]; lets callers recognise such ids. */
        const val EMBEDDED_CHARACTER_BOOK_SUFFIX = "_character_book"
    }
}
