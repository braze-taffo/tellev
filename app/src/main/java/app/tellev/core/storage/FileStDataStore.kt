package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.GroupChat
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.security.SensitiveFieldScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.zip.ZipEntry
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

    override suspend fun bootstrap(): Unit = withContext(Dispatchers.IO) {
        layout.allDirectories.forEach { it.createDirectories() }
        ensureDefaultPreset()
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
            return@withContext
        }

        // Default: save as SillyTavern V2 JSON, not tellev's internal model.
        val exporter = CharacterExporter(json)
        layout.characters.resolve("${card.id}.json").writeText(exporter.exportToJson(card))
        saveEmbeddedCharacterAssets(card)
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
                    if (entry.priority != 0 || entry.raw.containsKey("priority")) {
                        merged["priority"] = JsonPrimitive(entry.priority)
                    }
                    if (!merged.containsKey("comment")) merged["comment"] = JsonPrimitive("")
                    if (!merged.containsKey("position")) merged["position"] = JsonPrimitive(0)
                    if (!merged.containsKey("displayIndex")) merged["displayIndex"] = JsonPrimitive(index)
                    if (!merged.containsKey("extensions")) merged["extensions"] = buildJsonObject { }
                    put(index.toString(), JsonObject(merged))
                }
            }
        }

        layout.worlds.resolve("${book.id}.json").writeText(json.encodeToString(JsonObject.serializer(), output))
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
        val roots = listOf(layout.openAiSettings, layout.textGenSettings, layout.koboldAiSettings, layout.novelAiSettings)
        roots.flatMap { root ->
            readJsonFiles(root).map { (path, raw) ->
                val parsedMaxTokens = raw.intValue("max_tokens") ?: raw.intValue("maxTokens")
                GenerationPreset(
                    id = path.nameWithoutExtension,
                    name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                    providerType = root.name,
                    temperature = raw.doubleValue("temperature"),
                    topP = raw.doubleValue("top_p") ?: raw.doubleValue("topP"),
                    topK = raw.intValue("top_k") ?: raw.intValue("topK"),
                    maxTokens = parsedMaxTokens?.takeUnless { path.nameWithoutExtension == "default" && it == 512 },
                    stop = raw.stringList("stop"),
                    raw = raw,
                )
            }
        }
    }

    override suspend fun savePreset(preset: GenerationPreset): Unit = withContext(Dispatchers.IO) {
        val parent = resolvePresetDirectory(preset.providerType)
        parent.createDirectories()
        parent.resolve("${preset.id}.json").writeText(json.encodeToString(preset))
    }

    override suspend fun deletePreset(id: String, providerType: String?): Boolean = withContext(Dispatchers.IO) {
        val directories = if (providerType.isNullOrBlank()) {
            presetDirectories()
        } else {
            listOf(resolvePresetDirectory(providerType))
        }

        directories
            .map { it.resolve("$id.json") }
            .fold(false) { deletedAny, path -> path.deleteIfExists() || deletedAny }
    }

    override suspend fun listPersonas(): List<Persona> = withContext(Dispatchers.IO) {
        readJsonFiles(layout.user).map { (path, raw) ->
            Persona(
                id = path.nameWithoutExtension,
                name = raw["name"]?.jsonPrimitive?.content ?: path.nameWithoutExtension,
                description = raw["description"]?.jsonPrimitive?.content ?: "",
                metadata = raw,
            )
        }
    }

    override suspend fun savePersona(persona: Persona): Unit = withContext(Dispatchers.IO) {
        layout.user.createDirectories()
        layout.user.resolve("${persona.id}.json").writeText(json.encodeToString(persona))
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
        val hasPreset = presetDirectories().any { directory ->
            directory.exists() && directory.listDirectoryEntries("*.json").isNotEmpty()
        }
        if (hasPreset) return

        layout.openAiSettings.resolve("default.json").writeText(
            json.encodeToString(JsonObject.serializer(), defaultPresetRaw()),
        )
    }

    private fun defaultPresetRaw(): JsonObject = buildJsonObject {
        put("name", "默认聊天")
        put("temperature", 0.7)
        put("top_p", 1.0)
    }

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

        return ChatMessage(
            id = "$sessionId-$index",
            role = role,
            name = name,
            content = content,
            createdAtMillis = createdAtMillis,
            swipeIndex = swipeId,
            swipes = swipes,
            metadata = extra,
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
                WorldBookEntry(
                    id = entryObj["uid"]?.jsonPrimitive?.content ?: key,
                    keys = extractStringList(entryObj, "key"),
                    secondaryKeys = extractStringList(entryObj, "keysecondary"),
                    content = entryObj["content"]?.jsonPrimitive?.content ?: "",
                    enabled = !(entryObj["disable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false),
                    selective = entryObj["selective"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    constant = entryObj["constant"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    priority = entryObj["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    insertionOrder = entryObj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    depth = entryObj["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
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
