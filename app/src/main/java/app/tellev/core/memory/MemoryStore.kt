package app.tellev.core.memory

import app.tellev.core.model.ChatSession
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Path
import java.security.MessageDigest

/** Sidecar storage under the backed-up st-data root, never inside the ST JSONL. */
class MemoryStore(private val layout: StDirectoryLayout) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directory: Path get() = layout.root.resolve("memory")

    private fun path(id: String): Path {
        require(id.isNotBlank()) { "无效的对话 ID" }
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$digest.json")
    }

    suspend fun read(id: String): MemoryDocument? = withContext(Dispatchers.IO) {
        val file = path(id)
        if (!Files.isRegularFile(file)) null
        else json.decodeFromString(MemoryDocument.serializer(), Files.readAllBytes(file).toString(Charsets.UTF_8))
    }

    suspend fun write(id: String, document: MemoryDocument) = writes.withLock {
        withContext(Dispatchers.IO) {
            val hasChat = listOf(layout.chats, layout.groupChats).any { root ->
                Files.isDirectory(root) && Files.walk(root).use { stream ->
                    stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString() == "$id.jsonl" }
                }
            }
            if (!hasChat) return@withContext
            Files.createDirectories(directory)
            val file = path(id)
            val temp = Files.createTempFile(directory, "memory-", ".tmp")
            try {
                Files.write(temp, json.encodeToString(MemoryDocument.serializer(), document).toByteArray(Charsets.UTF_8))
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    suspend fun initialize(session: ChatSession, mode: MemoryMode) {
        if (read(session.id)?.mode != mode.name) {
            write(session.id, MemoryDocument.empty(mode).copy(baselineIds = session.messages.map { it.id }.toSet()))
        }
    }

    suspend fun delete(id: String) = writes.withLock {
        withContext(Dispatchers.IO) { Files.deleteIfExists(path(id)) }
    }

    companion object { private val writes = Mutex() }
}
