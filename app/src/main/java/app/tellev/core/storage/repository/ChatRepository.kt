package app.tellev.core.storage.repository

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.GroupChat
import app.tellev.core.storage.GeneratedImage
import app.tellev.core.storage.GeneratedImageStore
import app.tellev.core.storage.JournaledFileWriter
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.applyChatSessionMutation
import app.tellev.core.storage.codec.ChatJsonlCodec
import app.tellev.core.storage.codec.WorldBookCodec
import app.tellev.core.storage.isGeneratedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

internal class ChatRepository(
    private val layout: StDirectoryLayout,
    private val json: Json,
    private val durableFiles: JournaledFileWriter,
    private val chatWrites: Mutex,
    private val chatChanges: MutableSharedFlow<String>,
) {
    suspend fun listChatSessionSummaries(characterId: String?, groupId: String?) = withContext(Dispatchers.IO) {
        val roots = buildList {
            if (characterId != null) add(layout.chats.resolve(characterId))
            if (groupId != null) add(layout.groupChats.resolve(groupId))
            if (characterId == null && groupId == null) {
                add(layout.chats)
                add(layout.groupChats)
            }
        }
        val reader = ChatSessionSummaryReader(json)
        roots.flatMap { root ->
            if (!root.exists()) emptyList() else root.listDirectoryEntries("*.jsonl").mapNotNull { path ->
                runCatching { reader.read(path) }.getOrElse { if (!path.exists()) null else throw it }
            }
        }.sortedByDescending { it.lastMessageAtMillis }
    }

    suspend fun listChatSessions(characterId: String?, groupId: String?): List<ChatSession> = withContext(Dispatchers.IO) {
        val roots = buildList {
            if (characterId != null) add(layout.chats.resolve(characterId))
            if (groupId != null) add(layout.groupChats.resolve(groupId))
            if (characterId == null && groupId == null) {
                add(layout.chats)
                add(layout.groupChats)
            }
        }
        roots.flatMap { root ->
            if (!root.exists()) emptyList() else root.listDirectoryEntries("*.jsonl").mapNotNull { path ->
                // 并发删除的会话直接跳过；仍然存在但损坏的会话照旧如实失败。
                if (!path.exists()) null
                else runCatching { readJsonlChat(path) }.getOrElse { if (!path.exists()) null else throw it }
            }
        }.sortedByDescending { session -> session.messages.lastOrNull()?.createdAtMillis ?: 0L }
    }

    suspend fun readChatSession(id: String): ChatSession = withContext(Dispatchers.IO) {
        val path = StorageFileOps.findByFileName(listOf(layout.chats, layout.groupChats), "$id.jsonl")
            ?: error("Chat session not found: $id")
        readJsonlChat(path)
    }

    suspend fun saveChatSession(session: ChatSession): Unit = withContext(Dispatchers.IO) {
        chatWrites.withLock { writeChatSession(session); Unit }
    }

    suspend fun commitChatMutation(
        base: ChatSession,
        desired: ChatSession,
        expectedRevision: Long?,
        operationId: String?,
    ): ChatSession = withContext(Dispatchers.IO) {
        chatWrites.withLock {
            val path = StorageFileOps.findByFileName(listOf(layout.chats, layout.groupChats), "${base.id}.jsonl")
                ?: error("Chat session not found: ${base.id}")
            val merged = applyChatSessionMutation(base, desired, readJsonlChat(path))
            // Read the revision after parsing: a read-time migration may have just bumped it.
            val revision = durableFiles.revision(path)
            val receipt = writeChatSession(merged, expectedRevision ?: revision, operationId ?: UUID.randomUUID().toString())
            merged.copy(storageRevision = receipt.revision)
        }
    }

    fun writeChatSession(
        session: ChatSession,
        expectedRevision: Long? = null,
        operationId: String = UUID.randomUUID().toString(),
    ): JournaledFileWriter.Receipt {
        val parent = session.groupId?.let { layout.groupChats.resolve(it) }
            ?: session.characterId?.let { layout.chats.resolve(it) }
            ?: layout.chats.resolve("_orphan")
        parent.createDirectories()

        val lines = ChatJsonlCodec.serializeChatSessionJsonl(session, json)
        val receipt = durableFiles.write(parent.resolve("${session.id}.jsonl"), lines.joinToString("\n").toByteArray(Charsets.UTF_8), operationId, expectedRevision)
        chatChanges.tryEmit(session.id)
        return receipt
    }

    suspend fun appendMessage(sessionId: String, message: ChatMessage): Unit = withContext(Dispatchers.IO) {
        chatWrites.withLock {
            val path = StorageFileOps.findByFileName(listOf(layout.chats, layout.groupChats), "$sessionId.jsonl")
                ?: error("Chat session not found: $sessionId")
            val session = readJsonlChat(path)
            // Read the revision after parsing: a read-time migration may have just bumped it.
            val revision = durableFiles.revision(path)
            writeChatSession(session.copy(messages = session.messages + message), revision)
            Unit
        }
    }

    /**
     * Permanently remove a session: JSONL, its gallery index, referenced chat image
     * files, and the per-session background. Only files under user/images are touched;
     * attachments pointing at shared assets stay untouched.
     *
     * The JSONL is removed first and its failure is the only one reported: past that
     * point the session is gone and the cascade is best-effort (a mid-way failure can
     * only leave invisible orphan files, never a half-deleted chat nor destroyed images).
     */
    suspend fun deleteChatSession(id: String): Unit = withContext(Dispatchers.IO) {
        chatWrites.withLock {
            val path = StorageFileOps.findByFileName(listOf(layout.chats, layout.groupChats), "$id.jsonl")
                ?: return@withLock
            val galleryStore = GeneratedImageStore(layout)
            val chatText = runCatching { path.readText() }.getOrDefault("")
            val galleryText = runCatching { galleryStore.galleryFile(id).readText() }.getOrDefault("")
            durableFiles.delete(path)
            runCatching {
                (imagePathsReferencedBy(chatText) + imagePathsReferencedBy(galleryText)).forEach { relative ->
                    val file = layout.root.resolve(relative).normalize()
                    if (file.startsWith(layout.userImages)) Files.deleteIfExists(file)
                }
                galleryStore.delete(id)
                Files.deleteIfExists(layout.backgrounds.resolve("$id.png"))
                val dir = path.parent
                if (dir != null && dir != layout.chats && dir != layout.groupChats &&
                    Files.isDirectory(dir) && Files.list(dir).use { !it.findAny().isPresent }
                ) {
                    Files.deleteIfExists(dir)
                }
            }
            chatChanges.tryEmit(id)
        }
        Unit
    }

    /** Cheap existence probe used by UI failure paths to decide whether to restore state. */
    suspend fun chatSessionExists(id: String): Boolean = withContext(Dispatchers.IO) {
        StorageFileOps.findByFileName(listOf(layout.chats, layout.groupChats), "$id.jsonl") != null
    }

    private fun imagePathsReferencedBy(text: String): Set<String> =
        CHAT_IMAGE_PATH_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    suspend fun listGroups(): List<GroupChat> = withContext(Dispatchers.IO) {
        StorageFileOps.readJsonObjects(layout.groups, json).map { raw ->
            val members = WorldBookCodec.extractMembers(raw)
            GroupChat(
                id = raw["id"]?.jsonPrimitive?.content ?: raw["name"]?.jsonPrimitive?.content ?: "group",
                name = raw["name"]?.jsonPrimitive?.content ?: "Group",
                memberCharacterIds = members,
                metadata = raw,
            )
        }
    }

    suspend fun saveGroup(group: GroupChat): Unit = withContext(Dispatchers.IO) {
        layout.groups.createDirectories()
        val path = layout.groups.resolve("${group.id}.json")
        StorageFileOps.durableWriteText(durableFiles, path, json.encodeToString(group))
    }

    private fun readJsonlChat(path: Path): ChatSession = GeneratedImageStore(layout).withSessionLock(path.nameWithoutExtension) {
        readJsonlChatCached(path)
    }

    private data class SessionCacheEntry(
        val revision: Long,
        val mtimeMillis: Long,
        val size: Long,
        val session: ChatSession,
    )

    // Opening a character lists (and the UI often re-lists) every session; re-parsing
    // all JSONL each time is O(history). Entries self-invalidate via revision/mtime/size.
    private val sessionCache = object : LinkedHashMap<Path, SessionCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, SessionCacheEntry>): Boolean = size > 8
    }

    private fun readJsonlChatCached(path: Path): ChatSession = synchronized(sessionCache) {
        val revision = durableFiles.revision(path)
        val stats = runCatching {
            Files.getLastModifiedTime(path).toMillis() to Files.size(path)
        }.getOrNull()
        val cached = if (stats != null) sessionCache[path] else null
        if (cached != null && stats != null &&
            cached.revision == revision && cached.mtimeMillis == stats.first && cached.size == stats.second
        ) {
            return@synchronized cached.session
        }
        val outcome = readChatWithoutGeneratedImages(path)
        // 未落定的读取结果（迁移写失败）不缓存：下一次读取必须重试迁移，
        // 否则缓存会把「内存已剥离、磁盘未迁移」的视图固化。
        if (outcome.settled) {
            val postStats = runCatching {
                Files.getLastModifiedTime(path).toMillis() to Files.size(path)
            }.getOrNull()
            if (postStats != null) {
                sessionCache[path] = SessionCacheEntry(
                    durableFiles.revision(path),
                    postStats.first,
                    postStats.second,
                    outcome.session,
                )
            }
        }
        outcome.session
    }

    /** [settled] 为 false 表示磁盘上还有待迁移内容，内存视图与磁盘不完全一致。 */
    private class ChatReadOutcome(val session: ChatSession, val settled: Boolean)

    private fun readChatWithoutGeneratedImages(path: Path): ChatReadOutcome {
        val initialRevision = durableFiles.revision(path)
        val lines = path.readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        if (lines.isEmpty()) {
            return ChatReadOutcome(
                ChatSession(
                    id = path.nameWithoutExtension,
                    title = path.nameWithoutExtension,
                    characterId = inferCharacterId(path, isGroupChat = false),
                    groupId = inferCharacterId(path, isGroupChat = true),
                    messages = emptyList(),
                ),
                settled = true,
            )
        }

        var chatMetadata = buildJsonObject { }
        var headerParsed = false
        val messageStartIndex: Int

        val firstLine = lines[0]
        val firstObj = runCatching { json.parseToJsonElement(firstLine).jsonObject }.getOrNull()
        if (firstObj != null && firstObj.containsKey("user_name")) {
            chatMetadata = firstObj["chat_metadata"]?.jsonObject ?: buildJsonObject { }
            headerParsed = true
            messageStartIndex = 1
        } else {
            messageStartIndex = 0
        }

        val sessionId = path.nameWithoutExtension
        val characterId = inferCharacterId(path, isGroupChat = false)
        val groupId = inferCharacterId(path, isGroupChat = true)

        val messages = lines.drop(messageStartIndex).mapIndexed { index, line ->
            ChatJsonlCodec.parseStChatMessage(line, sessionId, index, json)
        }

        val images = messages.filter { it.isGeneratedImage() }
        // 画廊与 JSONL 的剥离必须同生共死：append 失败就不动磁盘，也不从消息中剥离。
        val gallerySettled = if (images.isEmpty()) {
            true
        } else {
            runCatching {
                fun ChatMessage.field(key: String) = (metadata[key] as? JsonPrimitive)?.content.orEmpty()
                GeneratedImageStore(layout).append(sessionId, images.map {
                    GeneratedImage(it.id, it.createdAtMillis, it.attachments, it.field("image_prompt"),
                        it.field("image_negative_prompt"), it.field("image_engine"), stripLegacyMessage(it))
                })
            }.isSuccess
        }
        var extractionChanged = false
        val kept = lines.take(messageStartIndex) + messages.mapIndexedNotNull { index, message ->
            val line = lines[messageStartIndex + index]
            when {
                message.isGeneratedImage() && gallerySettled -> null
                else -> {
                    val withId = if (message.raw.isNotEmpty() && "_tellev_message_id" !in message.raw) {
                        JsonObject(message.raw + ("_tellev_message_id" to JsonPrimitive(message.id))).toString()
                    } else line
                    // Substring prefilter: parsing every line twice would double read cost.
                    val migratedLine = if ("base64" !in withId) null else extractInlineImageAttachments(withId)
                    migratedLine?.also { extractionChanged = true } ?: withId
                }
            }
        }
        val needsRewrite = (images.isNotEmpty() && gallerySettled) || extractionChanged
        if (needsRewrite) {
            // 读取路径不允许因为迁移写而失败：写失败时返回与目标状态一致的内存视图，
            // 磁盘保持旧格式等待下次读取重试（base64 附件保留，请求构建仍兼容）。
            var rewritten = runCatching {
                durableFiles.write(path, kept.joinToString("\n").toByteArray(Charsets.UTF_8), expectedRevision = initialRevision)
            }.isSuccess
            if (!rewritten) {
                // 半提交残留的 .pending 会挡住该目标的所有后续写入（直到重启 recover）：
                // 就地重放一次；只有确实重放了挂起写入（非空回执）才算完成了迁移，
                // 空恢复说明失败发生在 pending 之前，递归会变成无限重试。
                val recovered = runCatching { durableFiles.recover() }.getOrNull()
                rewritten = recovered != null && recovered.isNotEmpty()
            }
            if (rewritten) return readChatWithoutGeneratedImages(path)
        }

        // 内存视图与画廊对齐：已入画廊的生成图从消息中剥离，避免气泡与画廊
        // 双份显示、以及它们作为对话回合进入提示词；画廊未收录时保留原消息。
        val effectiveMessages = if (gallerySettled) messages.filterNot { it.isGeneratedImage() } else messages
        return ChatReadOutcome(
            ChatSession(
                id = sessionId,
                title = chatMetadata["title"]?.jsonPrimitive?.content ?: sessionId,
                characterId = characterId,
                groupId = groupId,
                messages = effectiveMessages,
                metadata = chatMetadata,
                rawHeader = if (headerParsed) firstObj ?: buildJsonObject { } else buildJsonObject { },
                storageRevision = durableFiles.revision(path),
            ),
            // 画廊未收录（需要重试 append）或重写失败（磁盘未迁移）都视为未落定。
            settled = !needsRewrite && (images.isEmpty() || gallerySettled),
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
     * Move a legacy inline-base64 image attachment out of the JSONL into a file under
     * user/images and rewrite the attachment object; null when nothing is migratable.
     * The downsampled bytes are always JPEG by construction, so the stored extension is fixed.
     */
    private fun migrateAttachment(attachment: JsonObject): JsonObject? {
        val metadata = attachment["metadata"] as? JsonObject ?: return null
        val base64 = (metadata["base64"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        val attId = (attachment["id"] as? JsonPrimitive)?.contentOrNull
            ?.replace(Regex("[^a-zA-Z0-9._-]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: "att-${UUID.randomUUID().toString().substring(0, 8)}"
        val relative = writeMigratedImage(attId, bytes) ?: return null
        return JsonObject(
            attachment +
                ("relativePath" to JsonPrimitive(relative)) +
                ("mimeType" to JsonPrimitive("image/jpeg")) +
                ("metadata" to JsonObject(metadata.filterKeys { it != "base64" })),
        )
    }

    /**
     * Persist migrated image bytes under user/images with the same durability discipline
     * as the journal (temp + fsync + atomic rename + directory fsync): the JSONL commit
     * that drops the inline base64 is fsynced by the journal, so the image must be fully
     * on disk first. A same-named file with identical content is a replay and gets reused;
     * different content means an id conflict and gets a unique name instead of an overwrite.
     * Returns the data-root-relative path, or null when the write failed.
     */
    private fun writeMigratedImage(attId: String, bytes: ByteArray): String? {
        var fileName = "att-mig-$attId.jpg"
        var target = layout.userImages.resolve(fileName)
        if (Files.exists(target)) {
            val identical = runCatching { Files.readAllBytes(target).contentEquals(bytes) }.getOrDefault(false)
            if (!identical) {
                fileName = "att-mig-$attId-${UUID.randomUUID().toString().substring(0, 8)}.jpg"
                target = layout.userImages.resolve(fileName)
            }
        }
        return runCatching {
            layout.userImages.createDirectories()
            app.tellev.core.storage.DurableFileOps.write(target, bytes)
            "user/images/$fileName"
        }.getOrNull()
    }

    /** Rewrite a message line whose attachments still carry inline base64; null when unchanged. */
    private fun extractInlineImageAttachments(line: String): String? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val attachments = obj["attachments"] as? JsonArray ?: return null
        if (attachments.none { it is JsonObject && (it["metadata"] as? JsonObject)?.get("base64") is JsonPrimitive }) return null
        var changed = false
        val rewritten = attachments.map { element ->
            (element as? JsonObject)?.let { migrateAttachment(it) }?.also { changed = true } ?: element
        }
        if (!changed) return null
        return JsonObject(obj + ("attachments" to JsonArray(rewritten))).toString()
    }

    /** Gallery legacy records must not keep base64 payloads; the bytes live in files. */
    private fun stripLegacyMessage(message: ChatMessage): ChatMessage {
        if (message.attachments.none { it.metadata.containsKey("base64") }) return message
        val rawAttachments = message.raw["attachments"] as? JsonArray
        var rawChanged = false
        val cleanRaw = if (rawAttachments != null) {
            val mapped = rawAttachments.map { element ->
                (element as? JsonObject)?.let { migrateAttachment(it) }?.also { rawChanged = true } ?: element
            }
            if (rawChanged) JsonObject(message.raw + ("attachments" to JsonArray(mapped))) else message.raw
        } else {
            message.raw
        }
        val cleanAttachments = message.attachments.map { attachment ->
            val base64 = (attachment.metadata["base64"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: return@map attachment
            val bytes = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull() ?: return@map attachment
            val attId = attachment.id.replace(Regex("[^a-zA-Z0-9._-]"), "")
                .ifBlank { "att-${UUID.randomUUID().toString().substring(0, 8)}" }
            // 与 JSONL 迁移同一条冲突安全的写路径，两条路径不得一高一低；
            // 迁移成功即统一覆盖 relativePath，与 migrateAttachment 语义一致。
            val relative = writeMigratedImage(attId, bytes) ?: return@map attachment
            attachment.copy(
                relativePath = relative,
                mimeType = "image/jpeg",
                metadata = JsonObject(attachment.metadata.filterKeys { it != "base64" }),
            )
        }
        return message.copy(attachments = cleanAttachments, raw = cleanRaw)
    }

    private companion object {
        /** Chat-created images live only under user/images; other attachment paths are shared assets. */
        private val CHAT_IMAGE_PATH_REGEX = Regex("\"relativePath\"\\s*:\\s*\"(user/images/[^\"]+)\"")
    }
}
