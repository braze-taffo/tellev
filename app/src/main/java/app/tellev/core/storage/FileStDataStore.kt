package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.model.GroupChat
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.model.PromptSettings
import app.tellev.core.model.WorldInfoSettings
import app.tellev.core.security.SensitiveFieldScanner
import app.tellev.core.regex.CharacterRegexApplier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.zip.ZipEntry
import kotlin.io.path.copyTo
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

class FileStDataStore(
    override val layout: StDirectoryLayout,
    private val json: Json = defaultJson,
) : StDataStore {

    private val characterImporter = CharacterImporter(json)
    private val mutableCharacterChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val characterChanges = mutableCharacterChanges.asSharedFlow()
    private val mutablePresetChanges = MutableSharedFlow<PresetCategory>(extraBufferCapacity = 32)
    override val presetChanges = mutablePresetChanges.asSharedFlow()


    override suspend fun bootstrap(): Unit = withContext(Dispatchers.IO) {
        layout.allDirectories.forEach { it.createDirectories() }
        migrateLegacyRegexActivation()
        ensureDefaultPreset()
        ensureDefaultPersona()
        rebuildEmbeddedCharacterAssets()
    }

    override suspend fun listCharacters(): List<CharacterSummary> = withContext(Dispatchers.IO) {
        if (!layout.characters.exists()) return@withContext emptyList()
        layout.characters.listDirectoryEntries()
            .filter { it.extension.lowercase() in supportedCharacterExtensions }
            .sortedBy { it.name.lowercase() }
            .map { path ->
                val id = path.nameWithoutExtension
                val nameAndTags = readCharacterNameAndTags(path)
                CharacterSummary(
                    id = id,
                    name = nameAndTags?.first ?: id,
                    avatarRelativePath = "characters/${path.name}",
                    tags = nameAndTags?.second ?: emptyList(),
                )
            }
    }

    override suspend fun readCharacter(id: String): CharacterCard = withContext(Dispatchers.IO) {
        val path = resolveExisting(layout.characters, id, supportedCharacterExtensions)
            ?: error("Character not found: $id")
        when (path.extension.lowercase()) {
            "json" -> decodeCharacterJson(path, id)
            "png" -> decodeCharacterPng(path, id)
            "webp" -> decodeCharacterWebp(path, id)
            else -> error("Unsupported character format: ${path.extension}")
        }
    }

    override suspend fun saveCharacter(card: CharacterCard): Unit = withContext(Dispatchers.IO) {
        layout.characters.createDirectories()

        // Check if there's an existing PNG file for this character
        val existingPng = layout.characters.resolve("${card.id}.png")
        if (existingPng.exists()) {
            // Embed updated metadata into the existing PNG
            val exporter = CharacterExporter(json)
            val jsonStr = exporter.exportToJson(card)
            val pngBytes = PngCardParser.embedCardJson(existingPng.readBytes(), jsonStr)
            existingPng.outputStream().use { it.write(pngBytes) }
            saveEmbeddedCharacterAssets(card)
            mutableCharacterChanges.tryEmit(card.id)
            return@withContext
        }

        // Check if there's an existing WebP file
        val existingWebp = layout.characters.resolve("${card.id}.webp")
        if (existingWebp.exists()) {
            val exporter = CharacterExporter(json)
            val jsonStr = exporter.exportToJson(card)
            val webpBytes = WebpCardParser.embedCardJson(existingWebp.readBytes(), jsonStr)
            existingWebp.outputStream().use { it.write(webpBytes) }
            saveEmbeddedCharacterAssets(card)
            mutableCharacterChanges.tryEmit(card.id)
            return@withContext
        }

        // Default: save as SillyTavern V2 JSON, not tellev's internal model.
        val exporter = CharacterExporter(json)
        layout.characters.resolve("${card.id}.json").writeText(exporter.exportToJson(card))
        saveEmbeddedCharacterAssets(card)
            mutableCharacterChanges.tryEmit(card.id)
    }

    override suspend fun importCharacter(
        card: CharacterCard,
        sourceBytes: ByteArray,
        sourceFileName: String,
    ): Unit = withContext(Dispatchers.IO) {
        layout.characters.createDirectories()

        val exporter = CharacterExporter(json)
        val jsonString = exporter.exportToJson(card)
        val format = CharacterImporter.detectFormat(sourceBytes, sourceFileName)

        when (format) {
            "png" -> {
                removeCharacterVariants(card.id, keepExtension = "png")
                val pngBytes = PngCardParser.embedCardJson(sourceBytes, jsonString)
                layout.characters.resolve("${card.id}.png").outputStream().use { it.write(pngBytes) }
                saveEmbeddedCharacterAssets(card)
            }
            "webp" -> {
                removeCharacterVariants(card.id, keepExtension = "webp")
                val webpBytes = WebpCardParser.embedCardJson(sourceBytes, jsonString)
                layout.characters.resolve("${card.id}.webp").outputStream().use { it.write(webpBytes) }
                saveEmbeddedCharacterAssets(card)
            }
            else -> saveCharacter(card)
        }
        mutableCharacterChanges.tryEmit(card.id)
    }

    override suspend fun deleteCharacter(id: String): Unit = withContext(Dispatchers.IO) {
        // Remove all character card files (png/webp/json).
        removeCharacterVariants(id, keepExtension = "")
        // Remove embedded character assets (extensions/character-assets/<id>/).
        val assetDir = layout.extensions.resolve("character-assets").resolve(id)
        if (assetDir.exists()) assetDir.toFile().deleteRecursively()
        // Remove the materialized embedded character book and drop it from the
        // disabled-activation set. Replaces the prior "save an empty card"
        // soft-delete that left files on disk and a stub reappearing in listCharacters().
        runCatching { deleteWorldBook(StDataStore.embeddedCharacterBookId(id)) }
        mutableCharacterChanges.tryEmit(id)
    }

    override suspend fun listChatSessions(characterId: String?, groupId: String?): List<ChatSession> = withContext(Dispatchers.IO) {
        val roots = buildList {
            if (characterId != null) add(layout.chats.resolve(characterId))
            if (groupId != null) add(layout.groupChats.resolve(groupId))
            if (characterId == null && groupId == null) {
                add(layout.chats)
                add(layout.groupChats)
            }
        }
        roots.flatMap { root ->
            if (!root.exists()) emptyList() else root.listDirectoryEntries("*.jsonl").map { readJsonlChat(it) }
        }.sortedByDescending { session -> session.messages.lastOrNull()?.createdAtMillis ?: 0L }
    }

    override suspend fun readChatSession(id: String): ChatSession = withContext(Dispatchers.IO) {
        val path = findByFileName(listOf(layout.chats, layout.groupChats), "$id.jsonl")
            ?: error("Chat session not found: $id")
        readJsonlChat(path)
    }

    override suspend fun saveChatSession(session: ChatSession): Unit = withContext(Dispatchers.IO) {
        val parent = session.groupId?.let { layout.groupChats.resolve(it) }
            ?: session.characterId?.let { layout.chats.resolve(it) }
            ?: layout.chats.resolve("_orphan")
        parent.createDirectories()

        // Use compact JSON for JSONL (one JSON object per line, no pretty printing)
        val compactJson = Json(json) { prettyPrint = false }

        val lines = mutableListOf<String>()

        // Write ST JSONL header line
        val header = buildJsonObject {
            put("user_name", "unused")
            put("character_name", "unused")
            putJsonObject("chat_metadata") {
                // Preserve the full original chat_metadata (variables, model_class,
                // api, extension data, ...) read at parse time into session.metadata,
                // so ST<->tellev round-trips don't silently lose it. session_id and
                // title are overwritten with the current values afterward.
                for ((key, value) in session.metadata) {
                    put(key, value)
                }
                put("session_id", session.id)
                if (session.title.isNotBlank()) put("title", session.title)
            }
        }
        lines.add(compactJson.encodeToString(JsonObject.serializer(), header))

        // Write each message in ST JSONL format
        for (message in session.messages) {
            val line = buildJsonObject {
                put("name", message.name)
                put("is_user", message.role == MessageRole.User)
                put("is_system", message.role == MessageRole.System)
                put("send_date", formatMillisToIso(message.createdAtMillis))
                put("mes", message.content)

                if (message.swipes.isNotEmpty()) {
                    put("swipes", kotlinx.serialization.json.JsonArray(
                        message.swipes.map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ))
                    put("swipe_id", message.swipeIndex)
                }

                put("extra", message.metadata)

                // Preserve ST-Prompt-Template per-swipe arrays so template
                // evaluation state survives a save → reload round-trip.
                if (message.variables.isNotEmpty()) {
                    put("variables", JsonArray(message.variables))
                }
                if (message.isEjsProcessed.isNotEmpty()) {
                    putJsonArray("is_ejs_processed") {
                        message.isEjsProcessed.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (message.variablesInitialized.isNotEmpty()) {
                    putJsonArray("variables_initialized") {
                        message.variablesInitialized.forEach { add(JsonPrimitive(it)) }
                    }
                }

                // Vision attachments (base64 in metadata). Persisted so history round-trips.
                if (message.attachments.isNotEmpty()) {
                    put(
                        "attachments",
                        kotlinx.serialization.json.JsonArray(
                            message.attachments.map {
                                json.encodeToJsonElement(Attachment.serializer(), it)
                            },
                        ),
                    )
                }
            }
            lines.add(compactJson.encodeToString(JsonObject.serializer(), line))
        }

        parent.resolve("${session.id}.jsonl").writeText(lines.joinToString("\n"))
    }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage): Unit = withContext(Dispatchers.IO) {
        val session = readChatSession(sessionId)
        saveChatSession(session.copy(messages = session.messages + message))
    }

    override suspend fun listGroups(): List<GroupChat> = withContext(Dispatchers.IO) {
        readJsonObjects(layout.groups).map { raw ->
            val members = extractMembers(raw)
            GroupChat(
                id = raw["id"]?.jsonPrimitive?.content ?: raw["name"]?.jsonPrimitive?.content ?: "group",
                name = raw["name"]?.jsonPrimitive?.content ?: "Group",
                memberCharacterIds = members,
                metadata = raw,
            )
        }
    }

    override suspend fun saveGroup(group: GroupChat): Unit = withContext(Dispatchers.IO) {
        layout.groups.createDirectories()
        layout.groups.resolve("${group.id}.json").writeText(json.encodeToString(group))
    }

    override suspend fun listWorldBooks(): List<WorldBook> = withContext(Dispatchers.IO) {
        readJsonFiles(layout.worlds).map { (path, raw) ->
            val entries = parseWorldBookEntries(raw)
            WorldBook(
                id = path.nameWithoutExtension,
                name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                entries = entries,
                raw = raw,
            )
        }
    }

    override suspend fun readWorldBook(id: String): WorldBook = withContext(Dispatchers.IO) {
        listWorldBooks().firstOrNull { it.id == id } ?: error("World book not found: $id")
    }

    override suspend fun saveWorldBook(book: WorldBook): Unit = withContext(Dispatchers.IO) {
        layout.worlds.createDirectories()

        // Write in ST world book format while preserving unknown top-level and
        // per-entry fields from raw metadata.
        val output = buildJsonObject {
            for ((key, value) in book.raw) {
                if (key != "name" && key != "entries") put(key, value)
            }
            put("name", book.name)
            putJsonObject("entries") {
                book.entries.forEachIndexed { index, entry ->
                    val merged = mutableMapOf<String, JsonElement>()
                    merged.putAll(entry.raw)
                    merged["uid"] = entry.raw["uid"] ?: JsonPrimitive(entry.id.toIntOrNull() ?: index)
                    merged["key"] = stringArray(entry.keys)
                    merged["keysecondary"] = stringArray(entry.secondaryKeys)
                    merged["content"] = JsonPrimitive(entry.content)
                    merged["constant"] = JsonPrimitive(entry.constant)
                    merged["selective"] = JsonPrimitive(entry.selective)
                    merged["order"] = JsonPrimitive(entry.insertionOrder)
                    merged["disable"] = JsonPrimitive(!entry.enabled)
                    merged["depth"] = JsonPrimitive(entry.depth)
                    merged["position"] = JsonPrimitive(entry.position)
                    merged["probability"] = JsonPrimitive(entry.probability)
                    merged["useProbability"] = JsonPrimitive(entry.useProbability)
                    merged["selectiveLogic"] = JsonPrimitive(entry.selectiveLogic)
                    merged["role"] = JsonPrimitive(entry.role)
                    merged["matchWholeWords"] = JsonPrimitive(entry.matchWholeWords)
                    merged["useRegex"] = JsonPrimitive(entry.useRegex)
                    merged["caseSensitive"] = JsonPrimitive(entry.caseSensitive)
                    merged["comment"] = JsonPrimitive(entry.comment)
                    merged["excludeRecursion"] = JsonPrimitive(entry.excludeRecursion)
                    merged["preventRecursion"] = JsonPrimitive(entry.preventRecursion)
                    merged["delayUntilRecursion"] = JsonPrimitive(entry.delayUntilRecursion)
                    merged["ignoreBudget"] = JsonPrimitive(entry.ignoreBudget)
                    if (entry.priority != 0 || entry.raw.containsKey("priority")) {
                        merged["priority"] = JsonPrimitive(entry.priority)
                    }
                    if (!merged.containsKey("displayIndex")) merged["displayIndex"] = JsonPrimitive(index)
                    if (!merged.containsKey("extensions")) merged["extensions"] = buildJsonObject { }
                    put(index.toString(), JsonObject(merged))
                }
            }
        }

        layout.worlds.resolve("${book.id}.json").writeText(json.encodeToString(JsonObject.serializer(), output))
    }

    override suspend fun importWorldBook(
        jsonBytes: ByteArray,
        sourceFileName: String,
    ): WorldBook = withContext(Dispatchers.IO) {
        val raw = runCatching {
            json.parseToJsonElement(jsonBytes.decodeToString()) as? JsonObject
        }.getOrNull() ?: error("世界书 JSON 格式无效：$sourceFileName 不是有效的 JSON 对象")

        if (raw["entries"] !is JsonObject) {
            error("世界书 JSON 格式无效：$sourceFileName 缺少 entries 对象")
        }

        val fallbackName = sourceFileName.substringBeforeLast('.').ifBlank { "导入的世界书" }
        val name = (raw["name"] as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }
            ?: fallbackName
        val book = WorldBook(
            id = "wb_${UUID.randomUUID()}",
            name = name,
            entries = parseWorldBookEntries(raw),
            raw = raw,
        )
        saveWorldBook(book)
        book
    }

    override suspend fun deleteWorldBook(id: String): Unit = withContext(Dispatchers.IO) {
        layout.worlds.resolve("$id.json").deleteIfExists()
        // Drop the id from the disabled-activation set so it doesn't linger as a
        // stale entry after the book file is gone.
        val disabled = readDisabledWorldIds() - id
        if (disabled.isEmpty()) {
            layout.worldInfoActivation.deleteIfExists()
        } else {
            saveDisabledWorldIds(disabled)
        }
    }

    override suspend fun readDisabledWorldIds(): Set<String> = withContext(Dispatchers.IO) {
        val path = layout.worldInfoActivation
        if (!path.exists()) return@withContext emptySet()
        val raw = runCatching { json.parseToJsonElement(path.readText()) }.getOrNull() as? JsonObject
            ?: return@withContext emptySet()
        val disabled = raw["disabled"] as? JsonArray ?: return@withContext emptySet()
        disabled.mapNotNull { it.stringContentOrNull() }.toSet()
    }

    override suspend fun saveDisabledWorldIds(ids: Set<String>): Unit = withContext(Dispatchers.IO) {
        val output = buildJsonObject {
            putJsonArray("disabled") {
                ids.sorted().forEach { add(JsonPrimitive(it)) }
            }
        }
        layout.worldInfoActivation.writeText(json.encodeToString(JsonObject.serializer(), output))
    }

    /**
     * v1.2.2 kept regex switches in an app-only sidecar. SillyTavern defines
     * extensions.regex_scripts[].disabled as the source of truth, so migrate
     * once and remove the sidecar only after every writable card was saved.
     */
    private suspend fun migrateLegacyRegexActivation() {
        if (!layout.regexActivation.exists()) return
        val legacy = readDisabledRegexScriptIds()
        var allWritesSucceeded = true
        for ((characterId, disabledIds) in legacy) {
            val card = runCatching { readCharacter(characterId) }.getOrNull() ?: continue
            var patched = card
            disabledIds.forEach { scriptId ->
                patched = CharacterRegexApplier.withScriptEnabled(patched, scriptId, enabled = false)
            }
            if (patched != card) {
                runCatching { saveCharacter(patched) }
                    .onFailure { allWritesSucceeded = false }
            }
        }
        if (allWritesSucceeded) layout.regexActivation.deleteIfExists()
    }

    override suspend fun readDisabledRegexScriptIds(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val path = layout.regexActivation
        if (!path.exists()) return@withContext emptyMap()
        val raw = runCatching { json.parseToJsonElement(path.readText()) }.getOrNull() as? JsonObject
            ?: return@withContext emptyMap()
        val disabled = raw["disabled"] as? JsonObject ?: return@withContext emptyMap()
        disabled.entries.mapNotNull { (characterId, value) ->
            val ids = (value as? JsonArray)?.mapNotNull { it.stringContentOrNull() }?.toSet()
                ?: return@mapNotNull null
            if (ids.isEmpty()) return@mapNotNull null
            characterId to ids
        }.toMap()
    }

    override suspend fun saveDisabledRegexScriptIds(map: Map<String, Set<String>>): Unit = withContext(Dispatchers.IO) {
        val output = buildJsonObject {
            putJsonObject("disabled") {
                map.forEach { (characterId, ids) ->
                    if (ids.isNotEmpty()) {
                        putJsonArray(characterId) {
                            ids.sorted().forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
        }
        layout.regexActivation.writeText(json.encodeToString(JsonObject.serializer(), output))
    }

    private fun JsonElement.stringContentOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    override suspend fun listPresets(): List<GenerationPreset> = withContext(Dispatchers.IO) {
        presetDirectoriesWithCategories().flatMap { (category, root) ->
            readJsonFiles(root).filterNot { (path, _) -> path.nameWithoutExtension == "in_use" }
                .map { (path, raw) -> parsePreset(path, raw, category) }
        }
    }
    override suspend fun readPreset(category: PresetCategory, name: String): GenerationPreset? =
        withContext(Dispatchers.IO) {
            val path = resolvePresetDirectory(category).resolve("$name.json")
            if (!path.exists()) return@withContext null
            val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
                ?: return@withContext null
            parsePreset(path, raw, category)
        }


    override suspend fun readSelectedPresetName(category: PresetCategory): String? =
        withContext(Dispatchers.IO) {
            val statePath = layout.root.resolve("preset-selection.json")
            if (!statePath.exists()) return@withContext null
            val raw = runCatching { json.parseToJsonElement(statePath.readText()) as? JsonObject }.getOrNull()
                ?: return@withContext null
            raw[category.name.lowercase()]?.jsonPrimitive?.content
        }

    override suspend fun selectPreset(category: PresetCategory, name: String): Unit =
        withContext(Dispatchers.IO) {
            val directory = resolvePresetDirectory(category)
            val source = directory.resolve("$name.json")
            if (!source.exists()) error("Preset not found: ${category.name.lowercase()}/$name")
            source.copyTo(directory.resolve("in_use.json"), overwrite = true)

            val statePath = layout.root.resolve("preset-selection.json")
            val current = if (statePath.exists()) {
                runCatching { json.parseToJsonElement(statePath.readText()) as? JsonObject }.getOrNull()
            } else null
            val merged = current.orEmpty().toMutableMap()
            merged[category.name.lowercase()] = JsonPrimitive(name)
            statePath.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(merged)))
            mutablePresetChanges.tryEmit(category)
        }


    override suspend fun savePreset(preset: GenerationPreset): Unit = withContext(Dispatchers.IO) {
        val parent = resolvePresetDirectory(if (preset.category == PresetCategory.OpenAi) presetCategory(preset.providerType) else preset.category)
        parent.createDirectories()
        val merged = preset.raw.toMutableMap()
        preset.temperature?.let { merged["temperature"] = JsonPrimitive(it) }
        preset.topP?.let { merged["top_p"] = JsonPrimitive(it) }
        preset.topK?.let { merged["top_k"] = JsonPrimitive(it) }
        preset.presencePenalty?.let { merged["presence_penalty"] = JsonPrimitive(it) }
        preset.frequencyPenalty?.let { merged["frequency_penalty"] = JsonPrimitive(it) }
        preset.seed?.let { merged["seed"] = JsonPrimitive(it) }
        preset.maxContextTokens?.let { merged["openai_max_context"] = JsonPrimitive(it) }
        (preset.maxCompletionTokens ?: preset.maxTokens)?.let {
            merged["openai_max_tokens"] = JsonPrimitive(it)
            merged["max_tokens"] = JsonPrimitive(it)
        }
        if (preset.stop.isNotEmpty()) merged["stop"] = stringArray(preset.stop)
        if (preset.prompts.isNotEmpty() || preset.promptsUnused.isNotEmpty() || "prompts" in merged) {
            val definitions = (preset.prompts + preset.promptsUnused).distinctBy { it.identifier }
            merged["prompts"] = JsonArray(definitions.map(::serializePresetPrompt))
            merged["prompts_unused"] = JsonArray(preset.promptsUnused.map(::serializePresetPrompt))
            merged["prompt_order"] = serializePromptOrder(merged["prompt_order"], preset.prompts)
        }
        if (preset.extensions.isNotEmpty()) merged["extensions"] = preset.extensions
        parent.resolve("${preset.id}.json").writeText(
            json.encodeToString(JsonObject.serializer(), JsonObject(merged)),
        )
        mutablePresetChanges.tryEmit(preset.category)
    }
    override suspend fun saveWorkingPreset(
        category: PresetCategory,
        preset: GenerationPreset,
    ) {
        savePreset(
            preset.copy(
                id = "in_use",
                category = category,
                providerType = resolvePresetDirectory(category).name,
            ),
        )
    }



    override suspend fun deletePreset(id: String, providerType: String?): Boolean = withContext(Dispatchers.IO) {
        val targets = if (providerType.isNullOrBlank()) {
            presetDirectoriesWithCategories()
        } else {
            val category = presetCategory(providerType)
            listOf(category to resolvePresetDirectory(category))
        }
        var deletedAny = false
        targets.forEach { (category, directory) ->
            if (directory.resolve("$id.json").deleteIfExists()) {
                deletedAny = true
                ensureDefaultPreset(category)
                if (readSelectedPresetName(category) == id) {
                    selectPreset(category, "default")
                } else {
                    mutablePresetChanges.tryEmit(category)
                }
            }
        }
        deletedAny
    }

    override suspend fun importPreset(
        jsonBytes: ByteArray,
        providerCategory: String,
        sourceFileName: String,
    ): GenerationPreset = withContext(Dispatchers.IO) {
        val rawJsonString = jsonBytes.decodeToString()
        val parsed = runCatching { json.parseToJsonElement(rawJsonString) }.getOrNull()
        val rawObj = parsed as? JsonObject
            ?: error("预设 JSON 格式无效：$sourceFileName 不是有效的 JSON 对象")

        val category = presetCategory(providerCategory)
        val parent = resolvePresetDirectory(category)
        parent.createDirectories()

        val baseStem = sourceFileName.substringBeforeLast('.')
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "preset" }
        var id = baseStem
        var suffix = 2
        while (parent.resolve("$id.json").exists()) id = "$baseStem-${suffix++}"
        val destination = parent.resolve("$id.json")
        destination.outputStream().use { it.write(jsonBytes) }
        parsePreset(destination, rawObj, category).also { mutablePresetChanges.tryEmit(category) }
    }

    override suspend fun readWorldInfoSettings(): WorldInfoSettings = withContext(Dispatchers.IO) {
        val path = layout.root.resolve("world-info-settings.json")
        if (!path.exists()) return@withContext WorldInfoSettings()
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
            ?: return@withContext WorldInfoSettings()
        WorldInfoSettings(
            recursive = raw["recursive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            maxRecursionSteps = raw["maxRecursionSteps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            scanDepth = raw["scanDepth"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 12,
        )
    }

    override suspend fun saveWorldInfoSettings(settings: WorldInfoSettings): Unit =
        withContext(Dispatchers.IO) {
            layout.root.resolve("world-info-settings.json").writeText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("recursive", JsonPrimitive(settings.recursive))
                        put("maxRecursionSteps", JsonPrimitive(settings.maxRecursionSteps))
                        put("scanDepth", JsonPrimitive(settings.scanDepth))
                    },
                ),
            )
        }

    override suspend fun readPromptSettings(): PromptSettings = withContext(Dispatchers.IO) {
        val path = layout.root.resolve("prompt-settings.json")
        if (!path.exists()) return@withContext PromptSettings()
        val raw = runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
            ?: return@withContext PromptSettings()
        PromptSettings(
            preferCharacterPrompt = raw["preferCharacterPrompt"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            preferCharacterJailbreak = raw["preferCharacterJailbreak"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            instructEnabled = raw["instructEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            instructPresetName = raw["instructPresetName"]?.jsonPrimitive?.content ?: "",
        )
    }

    override suspend fun savePromptSettings(settings: PromptSettings): Unit =
        withContext(Dispatchers.IO) {
            layout.root.resolve("prompt-settings.json").writeText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("preferCharacterPrompt", JsonPrimitive(settings.preferCharacterPrompt))
                        put("preferCharacterJailbreak", JsonPrimitive(settings.preferCharacterJailbreak))
                        put("instructEnabled", JsonPrimitive(settings.instructEnabled))
                        put("instructPresetName", JsonPrimitive(settings.instructPresetName))
                    },
                ),
            )
        }

    override suspend fun listInstructPresets(): List<String> = withContext(Dispatchers.IO) {
        layout.instruct.toFile().listFiles()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()
    }

    override suspend fun readInstructPreset(name: String): JsonObject? = withContext(Dispatchers.IO) {
        val path = layout.instruct.resolve("$name.json")
        if (!path.exists()) return@withContext null
        runCatching { json.parseToJsonElement(path.readText()) as? JsonObject }.getOrNull()
    }

    override suspend fun listPersonas(): List<Persona> = withContext(Dispatchers.IO) {
        readJsonFiles(layout.user).map { (path, raw) ->
            Persona(
                id = path.nameWithoutExtension,
                name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                description = raw["description"]?.jsonPrimitive?.content ?: "",
                avatarRelativePath = raw["avatar"]
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                    ?.jsonPrimitive?.content,
                metadata = raw,
            )
        }
    }

    override suspend fun savePersona(persona: Persona): Unit = withContext(Dispatchers.IO) {
        layout.user.createDirectories()
        layout.user.resolve("${persona.id}.json").writeText(json.encodeToString(persona))
    }

    override suspend fun deletePersona(id: String): Unit = withContext(Dispatchers.IO) {
        layout.user.resolve("$id.json").deleteIfExists()
    }

    // A fresh install has no persona, which makes {{user}} fall back to "User".
    // Seed a default so the user has something to edit without an explicit create step.
    private fun ensureDefaultPersona() {
        if (layout.user.listDirectoryEntries("*.json").isNotEmpty()) return
        layout.user.createDirectories()
        val default = Persona(
            id = "default-user",
            name = "用户",
            description = "默认用户人设。",
        )
        layout.user.resolve("${default.id}.json").writeText(json.encodeToString(default))
    }

    override suspend fun exportBackup(targetZip: Path): Unit = withContext(Dispatchers.IO) {
        exportBackup(targetZip, includeSecrets = false)
    }

    /**
     * Export a backup to a ZIP file.
     * If includeSecrets is false (default), JSON files are sanitized to remove API keys and secrets.
     * If includeSecrets is true, all files are exported as-is (for full backup).
     */
    suspend fun exportBackup(targetZip: Path, includeSecrets: Boolean = false): Unit = withContext(Dispatchers.IO) {
        targetZip.parent?.createDirectories()

        ZipOutputStream(BufferedOutputStream(targetZip.outputStream())).use { zos ->
            val baseDir = layout.root

            // Walk all files under the root directory
            if (baseDir.exists()) {
                baseDir.walk().forEach { filePath ->
                    if (filePath.isDirectory()) return@forEach
                    if (filePath.normalize() == targetZip.normalize()) return@forEach

                    val relativePath = baseDir.relativize(filePath).toString().replace('\\', '/')
                    zos.putNextEntry(ZipEntry(relativePath))

                    if (!includeSecrets && filePath.extension.lowercase() == "json") {
                        // Parse and sanitize JSON files before adding to ZIP
                        val sanitizedContent = runCatching {
                            val jsonObj = json.parseToJsonElement(filePath.readText()).jsonObject
                            val sanitized = SensitiveFieldScanner.sanitize(jsonObj)
                            json.encodeToString(JsonObject.serializer(), sanitized)
                        }.getOrNull()

                        if (sanitizedContent != null) {
                            zos.write(sanitizedContent.toByteArray())
                        } else {
                            // If sanitization fails, include the file as-is
                            filePath.inputStream().use { input ->
                                input.copyTo(zos)
                            }
                        }
                    } else {
                        filePath.inputStream().use { input ->
                            input.copyTo(zos)
                        }
                    }

                    zos.closeEntry()
                }
            }
        }
    }

    override suspend fun importBackup(sourceZip: Path): Unit = withContext(Dispatchers.IO) {
        require(sourceZip.exists()) { "Backup file does not exist: $sourceZip" }

        ZipInputStream(sourceZip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name

                val normalizedPath = entryName.replace('\\', '/').trimStart('/')
                val root = layout.root.normalize()
                val targetPath = root.resolve(normalizedPath).normalize()

                if (!targetPath.startsWith(root)) {
                    throw IllegalArgumentException("Path traversal detected in backup entry: $entryName")
                }

                if (entry.isDirectory) {
                    targetPath.createDirectories()
                } else {
                    targetPath.parent?.createDirectories()
                    targetPath.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }

                entry = zis.nextEntry
            }
        }
    }

    // ---- Private helpers ----

    private suspend fun rebuildEmbeddedCharacterAssets() {
        if (!layout.characters.exists()) return
        layout.characters.listDirectoryEntries()
            .filter { it.extension.lowercase() in supportedCharacterExtensions }
            .forEach { path ->
                runCatching {
                    val id = path.nameWithoutExtension
                    val card = when (path.extension.lowercase()) {
                        "json" -> decodeCharacterJson(path, id)
                        "png" -> decodeCharacterPng(path, id)
                        "webp" -> decodeCharacterWebp(path, id)
                        else -> return@runCatching
                    }
                    saveEmbeddedCharacterAssets(card)
                }
            }
    }

    private suspend fun saveEmbeddedCharacterAssets(card: CharacterCard) {
        card.characterBook
            ?.takeIf { it.entries.isNotEmpty() }
            ?.let { embeddedBook ->
                saveWorldBook(
                    embeddedBook.copy(
                        id = StDataStore.embeddedCharacterBookId(card.id),
                        name = embeddedBook.name.ifBlank { "${card.name} 角色书" },
                    ),
                )
            }

        val extensions = card.raw.cardDataObject()["extensions"] as? JsonObject ?: return
        val assetDir = layout.extensions.resolve("character-assets").resolve(card.id)
        assetDir.createDirectories()

        val regexScripts = extensions["regex_scripts"]
        val tavernHelper = extensions["tavern_helper"]
        val tavernHelperScripts = extensions["TavernHelper_scripts"] ?: tavernHelper.arrayField("scripts")

        writeCharacterAsset(assetDir, "extensions.json", extensions)
        writeCharacterAsset(assetDir, "regex_scripts.json", regexScripts)
        writeCharacterAsset(assetDir, "TavernHelper_scripts.json", tavernHelperScripts)
        writeCharacterAsset(assetDir, "tavern_helper.json", tavernHelper)

        val manifest = buildJsonObject {
            put("character_id", card.id)
            put("character_name", card.name)
            put("world_book_id", StDataStore.embeddedCharacterBookId(card.id))
            put("regex_scripts", regexScripts.jsonItemCount())
            put("TavernHelper_scripts", tavernHelperScripts.jsonItemCount())
            put("tavern_helper", tavernHelper.tavernHelperVariableCount())
        }
        writeCharacterAsset(assetDir, "manifest.json", manifest)
    }

    private fun writeCharacterAsset(assetDir: Path, fileName: String, value: JsonElement?) {
        val path = assetDir.resolve(fileName)
        if (value == null || value.isEmptyJsonContainer()) {
            path.deleteIfExists()
            return
        }
        path.writeText(json.encodeToString(JsonElement.serializer(), value))
    }

    private fun JsonObject.cardDataObject(): JsonObject =
        (this["data"] as? JsonObject) ?: this

    private fun JsonElement?.jsonItemCount(): Int =
        when (this) {
            is JsonArray -> size
            is JsonObject -> size
            else -> 0
        }

    private fun JsonElement?.arrayField(name: String): JsonElement? =
        (this as? JsonObject)?.get(name)?.takeIf { it is JsonArray }

    private fun JsonElement?.tavernHelperVariableCount(): Int {
        val obj = this as? JsonObject ?: return jsonItemCount()
        return when (val variables = obj["variables"]) {
            is JsonArray -> variables.size
            is JsonObject -> variables.size
            else -> obj.size
        }
    }

    private fun JsonElement.isEmptyJsonContainer(): Boolean =
        when (this) {
            is JsonArray -> isEmpty()
            is JsonObject -> isEmpty()
            else -> false
        }

    private fun ensureDefaultPreset() {
        presetDirectoriesWithCategories().forEach { (category, directory) ->
            directory.createDirectories()
            val hasNamedPreset = directory.listDirectoryEntries("*.json")
                .any { it.nameWithoutExtension != "in_use" }
            if (!hasNamedPreset) ensureDefaultPreset(category)
        }
    }

    private fun ensureDefaultPreset(category: PresetCategory) {
        val directory = resolvePresetDirectory(category)
        directory.createDirectories()
        val path = directory.resolve("default.json")
        if (!path.exists()) {
            path.writeText(json.encodeToString(JsonObject.serializer(), defaultPresetRaw()))
        }
    }


    /**
     * Build a non-empty default preset that mirrors SillyTavern's built-in
     * prompt order. An empty default meant every chat fell back to a bare
     * "You are X." system prompt, breaking character-card prompt injections
     * that rely on named slots (e.g. TavernHelper scripts).
     */
    private fun defaultPresetRaw(): JsonObject = buildJsonObject {
        put("name", "默认聊天")
        put("temperature", 0.7)
        put("top_p", 1.0)
        put("openai_max_tokens", 300)
        put("openai_max_context", 4096)

        val identifiers = listOf(
            "main" to "Main Prompt",
            "worldInfoBefore" to "World Info (before)",
            "charDescription" to "Character Description",
            "charPersonality" to "Character Personality",
            "scenario" to "Scenario",
            "personaDescription" to "Persona Description",
            "dialogueExamples" to "Dialogue Examples",
            "worldInfoAfter" to "World Info (after)",
            "chatHistory" to "Chat History",
            // ST's Default.json ships a jailbreak slot, and that is where a
            // card's data.post_history_instructions lands. Without it, a
            // fresh install silently dropped the PHI of every modern card
            // (the prompt-manager path only emits slots the preset declares).
            "jailbreak" to "Post-History Instructions",
        )
        putJsonArray("prompts") {
            identifiers.forEachIndexed { index, (id, name) ->
                addJsonObject {
                    put("identifier", id)
                    put("name", name)
                    put("role", if (id == "chatHistory") "user" else "system")
                    put("content", "")
                    put("enabled", true)
                    put("relative", id == "chatHistory")
                    put("depth", 0)
                    put("order", index)
                }
            }
        }
        putJsonArray("prompt_order") {
            addJsonObject {
                put("character_id", 100001)
                putJsonArray("order") {
                    identifiers.forEach { (id, _) ->
                        addJsonObject {
                            put("identifier", id)
                            put("enabled", true)
                        }
                    }
                }
            }
        }
    }

    private fun presetCategory(value: String): PresetCategory = when (value.lowercase()) {
        "textgen", "textgen-webui", "textgen settings" -> PresetCategory.TextGen
        "kobold", "koboldai", "koboldcpp", "koboldai settings" -> PresetCategory.Kobold
        "novelai", "novelai settings" -> PresetCategory.NovelAi
        else -> PresetCategory.OpenAi
    }

    private fun resolvePresetDirectory(category: PresetCategory): Path = when (category) {
        PresetCategory.OpenAi -> layout.openAiSettings
        PresetCategory.TextGen -> layout.textGenSettings
        PresetCategory.Kobold -> layout.koboldAiSettings
        PresetCategory.NovelAi -> layout.novelAiSettings
    }

    private fun presetDirectoriesWithCategories(): List<Pair<PresetCategory, Path>> = listOf(
        PresetCategory.OpenAi to layout.openAiSettings,
        PresetCategory.TextGen to layout.textGenSettings,
        PresetCategory.Kobold to layout.koboldAiSettings,
        PresetCategory.NovelAi to layout.novelAiSettings,
    )

    private fun parsePreset(path: Path, raw: JsonObject, category: PresetCategory): GenerationPreset {
        val completionTokens = raw.intValue("openai_max_tokens")
            ?: raw.intValue("max_tokens")
            ?: raw.intValue("maxTokens")
            ?: raw.intValue("max_new_tokens")
        val definitions = parsePresetPrompts(raw["prompts"])
        val (orderedPrompts, inferredUnused) = applyPromptOrder(definitions, raw["prompt_order"])
        val explicitUnused = parsePresetPrompts(raw["prompts_unused"] ?: raw["promptsUnused"])
        return GenerationPreset(
            id = path.nameWithoutExtension,
            name = path.nameWithoutExtension,
            providerType = resolvePresetDirectory(category).name,
            category = category,
            temperature = raw.doubleValue("temperature") ?: raw.doubleValue("temp_openai"),
            topP = raw.doubleValue("top_p") ?: raw.doubleValue("topP"),
            topK = raw.intValue("top_k") ?: raw.intValue("topK"),
            maxTokens = completionTokens,
            maxContextTokens = raw.intValue("openai_max_context")
                ?: raw.intValue("max_context")
                ?: raw.intValue("context_length"),
            maxCompletionTokens = completionTokens,
            presencePenalty = raw.doubleValue("presence_penalty"),
            frequencyPenalty = raw.doubleValue("frequency_penalty"),
            seed = raw["seed"]?.jsonPrimitive?.content?.toLongOrNull(),
            stop = raw.stringList("stop"),
            prompts = orderedPrompts,
            promptsUnused = (inferredUnused + explicitUnused).distinctBy { it.identifier },
            extensions = raw["extensions"] as? JsonObject ?: buildJsonObject { },
            raw = raw,
        )
    }

    // Prompt list parsing (prompts / prompts_unused / prompt_order) lives in
    // PresetPrompts.kt so the virtual API router can reuse the same semantics.

    private fun serializePresetPrompt(prompt: PresetPrompt): JsonObject {
        val merged = prompt.raw.toMutableMap()
        merged["identifier"] = JsonPrimitive(prompt.identifier)
        merged["name"] = JsonPrimitive(prompt.name)
        merged["role"] = JsonPrimitive(prompt.role)
        merged["content"] = JsonPrimitive(prompt.content)
        merged["enabled"] = JsonPrimitive(prompt.enabled)
        merged["relative"] = JsonPrimitive(prompt.relative)
        merged["depth"] = JsonPrimitive(prompt.depth)
        merged["order"] = JsonPrimitive(prompt.order)
        return JsonObject(merged)
    }

    private fun serializePromptOrder(existing: JsonElement?, prompts: List<PresetPrompt>): JsonArray {
        val replacement = buildJsonObject {
            put("character_id", JsonPrimitive(100001))
            put("order", JsonArray(prompts.sortedBy { it.order }.map { prompt ->
                buildJsonObject {
                    put("identifier", JsonPrimitive(prompt.identifier))
                    put("enabled", JsonPrimitive(prompt.enabled))
                }
            }))
        }
        val groups = (existing as? JsonArray)?.toMutableList() ?: mutableListOf()
        val targetIndex = groups.indexOfFirst {
            (it as? JsonObject)?.intValue("character_id") == 100001
        }
        if (targetIndex >= 0) groups[targetIndex] = replacement else groups += replacement
        return JsonArray(groups)
    }


    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun resolvePresetDirectory(providerType: String): Path {
        return when (providerType.lowercase()) {
            "openai", "openai compatible", "openai-compatible", "deepseek", "volcengine-coding-plan" -> layout.openAiSettings
            "textgen", "textgen-webui" -> layout.textGenSettings
            "kobold", "koboldai" -> layout.koboldAiSettings
            "novelai" -> layout.novelAiSettings
            else -> layout.openAiSettings // default fallback
        }
    }

    private fun presetDirectories(): List<Path> =
        listOf(layout.openAiSettings, layout.textGenSettings, layout.koboldAiSettings, layout.novelAiSettings)

    private fun stringArray(values: List<String>): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })

    private fun JsonObject.doubleValue(key: String): Double? =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            ?: emptyList()

    private fun decodeCharacterJson(path: Path, id: String): CharacterCard {
        val raw = json.parseToJsonElement(path.readText()).jsonObject
        val card = characterImporter.parseCharacterJsonObject(raw)
        return card.copy(
            id = id,
            avatarRelativePath = "characters/${path.name}",
        )
    }

    private fun decodeCharacterPng(path: Path, id: String): CharacterCard {
        val pngBytes = path.readBytes()
        val cardJson = PngCardParser.extractCardJson(pngBytes)

        return if (cardJson != null) {
            val card = characterImporter.parseCharacterJsonObject(cardJson)
            card.copy(
                id = id,
                avatarRelativePath = "characters/${path.name}",
            )
        } else {
            // Fallback: return a basic card with the file name
            CharacterCard(
                id = id,
                name = id,
                avatarRelativePath = "characters/${path.name}",
                raw = buildJsonObject {
                    put("tellev_import_note", kotlinx.serialization.json.JsonPrimitive("Could not parse PNG metadata."))
                },
            )
        }
    }

    private fun decodeCharacterWebp(path: Path, id: String): CharacterCard {
        val webpBytes = path.readBytes()
        val cardJson = WebpCardParser.extractCardJson(webpBytes)

        return if (cardJson != null) {
            val card = characterImporter.parseCharacterJsonObject(cardJson)
            card.copy(
                id = id,
                avatarRelativePath = "characters/${path.name}",
            )
        } else {
            CharacterCard(
                id = id,
                name = id,
                avatarRelativePath = "characters/${path.name}",
                raw = buildJsonObject {
                    put("tellev_import_note", kotlinx.serialization.json.JsonPrimitive("Could not parse WebP metadata."))
                },
            )
        }
    }

    private fun readCharacterName(path: Path): String? {
        return readCharacterNameAndTags(path)?.first
    }

    private fun readCharacterNameAndTags(path: Path): Pair<String, List<String>>? = runCatching {
        when (path.extension.lowercase()) {
            "json" -> {
                val raw = json.parseToJsonElement(path.readText()).jsonObject
                val data = raw["data"]?.jsonObject ?: raw
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(raw)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            "png" -> {
                val cardJson = PngCardParser.extractCardJson(path.readBytes()) ?: return null
                val data = cardJson["data"]?.jsonObject ?: cardJson
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(cardJson)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            "webp" -> {
                val cardJson = WebpCardParser.extractCardJson(path.readBytes()) ?: return null
                val data = cardJson["data"]?.jsonObject ?: cardJson
                val name = data["name"]?.jsonPrimitive?.content
                val tags = extractTagsList(data) + extractTagsList(cardJson)
                if (name != null) Pair(name, tags.distinct()) else null
            }
            else -> null
        }
    }.getOrNull()

    private fun extractTagsList(obj: JsonObject): List<String> {
        val tagsElement = obj["tags"] ?: return emptyList()
        return runCatching {
            tagsElement.jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Parse a JSONL chat file in SillyTavern format.
     * Line 0: header with user_name, character_name, chat_metadata
     * Lines 1+: messages with is_user, mes, swipes, swipe_id, send_date, etc.
     */
    private fun readJsonlChat(path: Path): ChatSession {
        val lines = path.readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        if (lines.isEmpty()) {
            return ChatSession(
                id = path.nameWithoutExtension,
                title = path.nameWithoutExtension,
                characterId = inferCharacterId(path, isGroupChat = false),
                groupId = inferCharacterId(path, isGroupChat = true),
                messages = emptyList(),
            )
        }

        // Parse header line (line 0)
        var chatMetadata = buildJsonObject { }
        var headerParsed = false
        val messageStartIndex: Int

        val firstLine = lines[0]
        val firstObj = runCatching { json.parseToJsonElement(firstLine).jsonObject }.getOrNull()
        if (firstObj != null && firstObj.containsKey("user_name")) {
            // This is the ST header line
            chatMetadata = firstObj["chat_metadata"]?.jsonObject ?: buildJsonObject { }
            headerParsed = true
            messageStartIndex = 1
        } else {
            // No header, all lines are messages
            messageStartIndex = 0
        }

        val sessionId = path.nameWithoutExtension
        val characterId = inferCharacterId(path, isGroupChat = false)
        val groupId = inferCharacterId(path, isGroupChat = true)

        val messages = lines.drop(messageStartIndex).mapIndexed { index, line ->
            parseStChatMessage(line, sessionId, index)
        }

        return ChatSession(
            id = sessionId,
            title = chatMetadata["title"]?.jsonPrimitive?.content ?: sessionId,
            characterId = characterId,
            groupId = groupId,
            messages = messages,
            metadata = chatMetadata,
        )
    }

    private fun parseStChatMessage(line: String, sessionId: String, index: Int): ChatMessage {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()

        if (obj == null) {
            // Fallback for unparseable lines
            return ChatMessage(
                id = "$sessionId-$index",
                role = MessageRole.Character,
                name = "Unknown",
                content = line,
                createdAtMillis = 0L,
            )
        }

        val isUser = obj["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val isSystem = obj["is_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val name = obj["name"]?.jsonPrimitive?.content ?: if (isUser) "You" else "Character"
        val content = obj["mes"]?.jsonPrimitive?.content ?: obj["content"]?.jsonPrimitive?.content ?: ""
        val sendDate = obj["send_date"]?.jsonPrimitive?.content

        val role = when {
            isSystem -> MessageRole.System
            isUser -> MessageRole.User
            else -> MessageRole.Character
        }

        // Parse swipes
        val swipes = runCatching {
            obj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        }.getOrDefault(emptyList())

        val swipeId = obj["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        // Parse send_date to millis
        val createdAtMillis = parseDateStringToMillis(sendDate)

        val extra = obj["extra"]?.jsonObject ?: buildJsonObject { }

        // ST-Prompt-Template per-swipe arrays (absent on legacy chats → empty)
        val variables = runCatching {
            obj["variables"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        }.getOrDefault(emptyList())
        val isEjsProcessed = runCatching {
            obj["is_ejs_processed"]?.jsonArray?.map {
                it.jsonPrimitive.content.toBooleanStrictOrNull() ?: false
            } ?: emptyList()
        }.getOrDefault(emptyList())
        val variablesInitialized = runCatching {
            obj["variables_initialized"]?.jsonArray?.map {
                it.jsonPrimitive.content.toBooleanStrictOrNull() ?: false
            } ?: emptyList()
        }.getOrDefault(emptyList())

        val attachments = runCatching {
            obj["attachments"]?.jsonArray?.mapNotNull {
                json.decodeFromJsonElement(Attachment.serializer(), it)
            } ?: emptyList()
        }.getOrDefault(emptyList())

        return ChatMessage(
            id = "$sessionId-$index",
            role = role,
            name = name,
            content = content,
            createdAtMillis = createdAtMillis,
            swipeIndex = swipeId,
            swipes = swipes,
            attachments = attachments,
            metadata = extra,
            variables = variables,
            isEjsProcessed = isEjsProcessed,
            variablesInitialized = variablesInitialized,
        )
    }

    private fun inferCharacterId(path: Path, isGroupChat: Boolean): String? {
        val parent = path.parent ?: return null
        val parentName = parent.fileName?.toString() ?: return null
        val root = if (isGroupChat) layout.groupChats else layout.chats

        return when {
            parent == root -> null
            parent.parent == root -> parentName
            else -> null
        }
    }

    /**
     * Parse world book entries from ST format.
     * ST format: entries is an object with numeric string keys ("0", "1", ...),
     * each containing entry data with uid, key, keysecondary, content, etc.
     */
    private fun parseWorldBookEntries(raw: JsonObject): List<WorldBookEntry> {
        val entriesObj = raw["entries"]?.jsonObject ?: return emptyList()

        return entriesObj.mapNotNull { (key, value) ->
            runCatching {
                val entryObj = value.jsonObject
                val extensions = entryObj["extensions"] as? JsonObject
                WorldBookEntry(
                    id = entryObj["uid"]?.jsonPrimitive?.content ?: key,
                    keys = extractStringList(entryObj, "key"),
                    secondaryKeys = extractStringList(entryObj, "keysecondary"),
                    content = entryObj["content"]?.jsonPrimitive?.content ?: "",
                    enabled = !(entryObj["disable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false),
                    selective = entryObj["selective"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    constant = entryObj["constant"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    priority = entryObj["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    insertionOrder = entryObj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    depth = entryObj["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                    position = entryObj["position"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    probability = entryObj["probability"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    useProbability = entryObj["useProbability"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    selectiveLogic = entryObj["selectiveLogic"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    role = entryObj["role"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    matchWholeWords = entryObj["matchWholeWords"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    useRegex = entryObj["useRegex"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    caseSensitive = entryObj["caseSensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    comment = entryObj["comment"]?.jsonPrimitive?.content ?: "",
                    excludeRecursion = entryObj["excludeRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    preventRecursion = entryObj["preventRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    // ST delayUntilRecursion is a number (recursion level); older
                    // cards / tellev builds may carry a boolean (true -> 1).
                    delayUntilRecursion = entryObj["delayUntilRecursion"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: entryObj["delayUntilRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { if (it) 1 else 0 }
                        ?: 0,
                    ignoreBudget = extensions?.get("ignore_budget")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: entryObj["ignoreBudget"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: entryObj["ignore_budget"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: false,
                    group = entryObj["group"]?.jsonPrimitive?.content ?: "",
                    groupOverride = entryObj["groupOverride"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    groupWeight = entryObj["groupWeight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    useGroupScoring = entryObj["useGroupScoring"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    raw = entryObj,
                )
            }.getOrNull()
        }
    }

    private fun extractStringList(obj: JsonObject, key: String): List<String> {
        val element = obj[key] ?: return emptyList()
        return runCatching {
            element.jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Extract member character IDs from a group JSON.
     * ST stores members as a "members" array of character name strings.
     */
    private fun extractMembers(raw: JsonObject): List<String> {
        // Try "members" array first
        val membersElement = raw["members"] ?: raw["member_ids"]
        if (membersElement != null) {
            return runCatching {
                membersElement.jsonArray.mapNotNull {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }

        // Try "members" as a comma-separated string
        val membersString = raw["members"]?.jsonPrimitive?.content
        if (membersString != null) {
            return membersString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        return emptyList()
    }

    private fun formatMillisToIso(millis: Long): String {
        if (millis == 0L) return ""
        return runCatching {
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
        }.getOrDefault("")
    }

    private fun parseDateStringToMillis(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0L

        // Try ISO instant format first
        return runCatching {
            Instant.parse(dateString).toEpochMilli()
        }.getOrElse {
            // Try common date formats
            runCatching {
                DateTimeFormatter.ISO_DATE_TIME.parse(dateString) { temporal ->
                    Instant.from(temporal).toEpochMilli()
                }
            }.getOrElse {
                // Try epoch millis as string
                dateString.toLongOrNull() ?: 0L
            }
        }
    }

    private fun readJsonObjects(root: Path): List<JsonObject> = readJsonFiles(root).map { it.second }

    private fun readJsonFiles(root: Path): List<Pair<Path, JsonObject>> {
        if (!root.exists() || !root.isDirectory()) return emptyList()
        return root.listDirectoryEntries("*.json").mapNotNull { path ->
            runCatching { path to json.parseToJsonElement(path.readText()).jsonObject }.getOrNull()
        }
    }

    private fun resolveExisting(root: Path, id: String, extensions: Set<String>): Path? =
        extensions.asSequence()
            .map { root.resolve("$id.$it") }
            .firstOrNull { it.exists() }

    private fun removeCharacterVariants(id: String, keepExtension: String) {
        supportedCharacterExtensions
            .filter { it != keepExtension }
            .forEach { extension -> layout.characters.resolve("$id.$extension").deleteIfExists() }
    }

    private fun findByFileName(roots: List<Path>, fileName: String): Path? =
        roots.asSequence()
            .filter { it.exists() }
            .flatMap { root ->
                root.listDirectoryEntries().asSequence().flatMap { entry ->
                    if (entry.isDirectory()) entry.listDirectoryEntries(fileName).asSequence()
                    else sequenceOf(entry).filter { it.name == fileName }
                }
            }
            .firstOrNull()

    companion object {
        private val supportedCharacterExtensions = setOf("png", "webp", "json")

        val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
