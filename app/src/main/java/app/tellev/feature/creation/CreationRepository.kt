package app.tellev.feature.creation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Drafts and source manuscripts live in app-private storage, outside ST card directories. */
class CreationRepository(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun list(): List<CreationSession> = withContext(Dispatchers.IO) {
        root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { runCatching { json.decodeFromString<CreationSession>(it.readText()) }.getOrNull() }
            ?.sortedByDescending(CreationSession::updatedAt).orEmpty()
    }

    suspend fun load(id: String): CreationSession = withContext(Dispatchers.IO) {
        json.decodeFromString<CreationSession>(sessionFile(id).readText())
    }

    suspend fun save(session: CreationSession) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val destination = sessionFile(session.id)
        val temporary = File(root, "${safeId(session.id)}.json.tmp")
        temporary.writeText(json.encodeToString(session))
        try {
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    suspend fun saveSource(id: String, text: String): Pair<String, Int> =
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val bytes = text.toByteArray(Charsets.UTF_8)
            val hash = sha256(bytes)
            sourceFile(id, hash).writeBytes(bytes)
            hash to text.length
        }

    suspend fun readSource(id: String, expectedSha256: String): String = withContext(Dispatchers.IO) {
        val bytes = sourceFile(id, expectedSha256).readBytes()
        require(sha256(bytes) == expectedSha256) { "原文校验失败，请重新导入。" }
        bytes.decodeToString()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        sessionFile(id).delete()
        root.listFiles { file -> file.name.startsWith("${safeId(id)}.") && file.name.endsWith(".source.txt") }
            ?.forEach(File::delete)
    }

    private fun sessionFile(id: String): File = File(root, "${safeId(id)}.json")
    private fun sourceFile(id: String, sha256: String): File {
        require(Regex("[a-f0-9]{64}").matches(sha256)) { "Invalid source digest" }
        return File(root, "${safeId(id)}.$sha256.source.txt")
    }
    private fun safeId(id: String): String {
        require(Regex("[A-Za-z0-9_-]+").matches(id)) { "Invalid creation session id" }
        return id
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
