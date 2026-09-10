package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.Persona
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetImportResult
import app.tellev.core.model.PromptSettings
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldInfoSettings
import app.tellev.core.storage.coordinator.BackupCoordinator
import app.tellev.core.storage.coordinator.EmbeddedAssetsCoordinator
import app.tellev.core.storage.repository.CharacterRepository
import app.tellev.core.storage.repository.ChatRepository
import app.tellev.core.storage.repository.PersonaRepository
import app.tellev.core.storage.repository.PresetRepository
import app.tellev.core.storage.repository.SettingsRepository
import app.tellev.core.storage.repository.WorldBookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories

class FileStDataStore(
    override val layout: StDirectoryLayout,
    private val json: Json = defaultJson,
    private val durableFiles: JournaledFileWriter = JournaledFileWriter(layout.root),
) : StDataStore {

    private val characterImporter = CharacterImporter(json)
    private val chatWrites = Mutex()

    private val mutableCharacterChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val characterChanges = mutableCharacterChanges.asSharedFlow()

    private val mutablePresetChanges = MutableSharedFlow<PresetCategory>(extraBufferCapacity = 32)
    override val presetChanges = mutablePresetChanges.asSharedFlow()

    private val mutableWorldBookChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val worldBookChanges = mutableWorldBookChanges.asSharedFlow()

    private val mutablePersonaChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val personaChanges = mutablePersonaChanges.asSharedFlow()

    private val mutableChatChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val chatChanges = mutableChatChanges.asSharedFlow()

    // Domain Coordinators
    private val embeddedAssetsCoordinator = EmbeddedAssetsCoordinator(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        characterImporter = characterImporter,
        saveWorldBook = { worldBookRepository.saveWorldBook(it) },
    )

    private val backupCoordinator = BackupCoordinator(
        layout = layout,
        json = json,
    )

    // Domain Repositories
    private val worldBookRepository = WorldBookRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        worldBookChanges = mutableWorldBookChanges,
    )

    private val characterRepository = CharacterRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        characterImporter = characterImporter,
        embeddedCoordinator = embeddedAssetsCoordinator,
        characterChanges = mutableCharacterChanges,
        deleteWorldBook = { worldBookRepository.deleteWorldBook(it) },
    )

    private val chatRepository = ChatRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        chatWrites = chatWrites,
        chatChanges = mutableChatChanges,
    )

    private val presetRepository = PresetRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        presetChanges = mutablePresetChanges,
    )

    private val personaRepository = PersonaRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
        personaChanges = mutablePersonaChanges,
    )

    private val settingsRepository = SettingsRepository(
        layout = layout,
        json = json,
        durableFiles = durableFiles,
    )

    override suspend fun bootstrap(): Unit = withContext(Dispatchers.IO) {
        layout.allDirectories.forEach { it.createDirectories() }
        durableFiles.recover()
        settingsRepository.migrateLegacyRegexActivation(
            readCharacter = { characterRepository.readCharacter(it) },
            saveCharacter = { characterRepository.saveCharacter(it) },
        )
        presetRepository.ensureDefaultPresets()
        presetRepository.migrateHandwrittenOpenAiDefaultPreset()
        presetRepository.migrateDefaultPresetLimits()
        personaRepository.ensureDefaultPersona()
        embeddedAssetsCoordinator.rebuildEmbeddedCharacterAssets()
    }

    // ── Character Operations ──

    override suspend fun listCharacters(): List<CharacterSummary> =
        characterRepository.listCharacters()

    override suspend fun readCharacter(id: String): CharacterCard =
        characterRepository.readCharacter(id)

    override suspend fun saveCharacter(card: CharacterCard) =
        characterRepository.saveCharacter(card)

    override suspend fun importCharacter(card: CharacterCard, sourceBytes: ByteArray, sourceFileName: String) =
        characterRepository.importCharacter(card, sourceBytes, sourceFileName)

    override suspend fun deleteCharacter(id: String) =
        characterRepository.deleteCharacter(id)

    override suspend fun replaceCharacterAvatar(id: String, pngBytes: ByteArray) =
        characterRepository.replaceCharacterAvatar(id, pngBytes)

    // ── Chat & Group Operations ──

    override suspend fun listChatSessions(characterId: String?, groupId: String?): List<ChatSession> =
        chatRepository.listChatSessions(characterId, groupId)

    override suspend fun readChatSession(id: String): ChatSession =
        chatRepository.readChatSession(id)

    override suspend fun saveChatSession(session: ChatSession) =
        chatRepository.saveChatSession(session)

    override suspend fun commitChatMutation(
        base: ChatSession,
        desired: ChatSession,
        expectedRevision: Long?,
        operationId: String?,
    ): ChatSession =
        chatRepository.commitChatMutation(base, desired, expectedRevision, operationId)

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) =
        chatRepository.appendMessage(sessionId, message)

    override suspend fun listGroups(): List<GroupChat> =
        chatRepository.listGroups()

    override suspend fun saveGroup(group: GroupChat) =
        chatRepository.saveGroup(group)

    // ── WorldBook Operations ──

    override suspend fun listWorldBooks(): List<WorldBook> =
        worldBookRepository.listWorldBooks()

    override suspend fun readWorldBook(id: String): WorldBook =
        worldBookRepository.readWorldBook(id)

    override suspend fun saveWorldBook(book: WorldBook) =
        worldBookRepository.saveWorldBook(book)

    override suspend fun importWorldBook(jsonBytes: ByteArray, sourceFileName: String): WorldBook =
        worldBookRepository.importWorldBook(jsonBytes, sourceFileName)

    override suspend fun deleteWorldBook(id: String) =
        worldBookRepository.deleteWorldBook(id)

    override suspend fun readDisabledWorldIds(): Set<String> =
        worldBookRepository.readDisabledWorldIds()

    override suspend fun saveDisabledWorldIds(ids: Set<String>) =
        worldBookRepository.saveDisabledWorldIds(ids)

    // ── Preset Operations ──

    override suspend fun listPresets(): List<GenerationPreset> =
        presetRepository.listPresets()

    override suspend fun readPreset(category: PresetCategory, name: String): GenerationPreset? =
        presetRepository.readPreset(category, name)

    override suspend fun readSelectedPresetName(category: PresetCategory): String? =
        presetRepository.readSelectedPresetName(category)

    override suspend fun selectPreset(category: PresetCategory, name: String) =
        presetRepository.selectPreset(category, name)

    override suspend fun savePreset(preset: GenerationPreset) =
        presetRepository.savePreset(preset)

    override suspend fun saveWorkingPreset(category: PresetCategory, preset: GenerationPreset) =
        presetRepository.saveWorkingPreset(category, preset)

    override suspend fun deletePreset(id: String, providerType: String?): Boolean =
        presetRepository.deletePreset(id, providerType)

    override suspend fun importPreset(
        jsonBytes: ByteArray,
        providerCategory: String,
        sourceFileName: String,
    ): PresetImportResult =
        presetRepository.importPreset(jsonBytes, providerCategory, sourceFileName)

    // ── Settings Operations ──

    override suspend fun readWorldInfoSettings(): WorldInfoSettings =
        settingsRepository.readWorldInfoSettings()

    override suspend fun saveWorldInfoSettings(settings: WorldInfoSettings) =
        settingsRepository.saveWorldInfoSettings(settings)

    override suspend fun readPromptSettings(): PromptSettings =
        settingsRepository.readPromptSettings()

    override suspend fun savePromptSettings(settings: PromptSettings) =
        settingsRepository.savePromptSettings(settings)

    override suspend fun listInstructPresets(): List<String> =
        settingsRepository.listInstructPresets()

    override suspend fun readInstructPreset(name: String): JsonObject? =
        settingsRepository.readInstructPreset(name)

    override suspend fun readDisabledRegexScriptIds(): Map<String, Set<String>> =
        settingsRepository.readDisabledRegexScriptIds()

    override suspend fun saveDisabledRegexScriptIds(map: Map<String, Set<String>>) =
        settingsRepository.saveDisabledRegexScriptIds(map)

    // ── Persona Operations ──

    override suspend fun listPersonas(): List<Persona> =
        personaRepository.listPersonas()

    override suspend fun savePersona(persona: Persona) =
        personaRepository.savePersona(persona)

    override suspend fun deletePersona(id: String) =
        personaRepository.deletePersona(id)

    // ── Backup Operations ──

    override suspend fun exportBackup(targetZip: Path): Unit =
        backupCoordinator.exportBackup(targetZip, includeSecrets = false)

    suspend fun exportBackup(targetZip: Path, includeSecrets: Boolean = false): Unit =
        backupCoordinator.exportBackup(targetZip, includeSecrets)

    override suspend fun importBackup(sourceZip: Path): Unit =
        backupCoordinator.importBackup(sourceZip)

    companion object {
        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
