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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            if (!root.exists()) emptyList() else root.listDirectoryEntries("*.jsonl").map { readJsonlChat(it) }
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
            val revision = durableFiles.revision(path)
            val merged = applyChatSessionMutation(base, desired, readJsonlChat(path))
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
            val revision = durableFiles.revision(path)
            val session = readJsonlChat(path)
            writeChatSession(session.copy(messages = session.messages + message), revision)
            Unit
        }
    }

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
        readChatWithoutGeneratedImages(path)
    }

    private fun readChatWithoutGeneratedImages(path: Path): ChatSession {
        val initialRevision = durableFiles.revision(path)
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
        if (images.isNotEmpty()) {
            fun ChatMessage.field(key: String) = (metadata[key] as? JsonPrimitive)?.content.orEmpty()
            GeneratedImageStore(layout).append(sessionId, images.map {
                GeneratedImage(it.id, it.createdAtMillis, it.attachments, it.field("image_prompt"),
                    it.field("image_negative_prompt"), it.field("image_engine"), it)
            })
            val kept = lines.take(messageStartIndex) + messages.mapIndexedNotNull { index, message ->
                if (message.isGeneratedImage()) null else {
                    val line = lines[messageStartIndex + index]
                    if (message.raw.isNotEmpty() && "_tellev_message_id" !in message.raw) {
                        JsonObject(message.raw + ("_tellev_message_id" to JsonPrimitive(message.id))).toString()
                    } else line
                }
            }
            durableFiles.write(path, kept.joinToString("\n").toByteArray(Charsets.UTF_8), expectedRevision = initialRevision)
            return readChatWithoutGeneratedImages(path)
        }

        return ChatSession(
            id = sessionId,
            title = chatMetadata["title"]?.jsonPrimitive?.content ?: sessionId,
            characterId = characterId,
            groupId = groupId,
            messages = messages,
            metadata = chatMetadata,
            rawHeader = if (headerParsed) firstObj ?: buildJsonObject { } else buildJsonObject { },
            storageRevision = durableFiles.revision(path),
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
}
