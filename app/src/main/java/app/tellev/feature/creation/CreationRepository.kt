package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Drafts and source manuscripts live in app-private storage, outside ST card directories. */
class CreationRepository(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Streaming decode target: turns/lore elements count only, bodies are skipped. */
    @Serializable
    private data class SessionSummaryDto(
        val id: String = "",
        val kind: CreationKind = CreationKind.Character,
        val card: CardNameDto = CardNameDto(),
        val worldName: String = "",
        val lore: List<CountOnlyDto> = emptyList(),
        val turns: List<CountOnlyDto> = emptyList(),
        val sourceCursor: Int = 0,
        val sourceLength: Int = 0,
        val updatedAt: Long = 0,
    )

    @Serializable
    private data class CardNameDto(val name: String = "")

    @Serializable
    private class CountOnlyDto

    suspend fun list(): List<CreationSessionSummary> = withContext(Dispatchers.IO) {
        root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { file ->
                // ignoreUnknownKeys makes unknown fields (including whole lore
                // bodies and merge bases) stream past without being retained.
                runCatching {
                    val dto = json.decodeFromString<SessionSummaryDto>(file.readText())
                    CreationSessionSummary(
                        id = dto.id.ifBlank { file.nameWithoutExtension },
                        kind = dto.kind,
                        cardName = dto.card.name,
                        worldName = dto.worldName,
                        turnsCount = dto.turns.size,
                        loreCount = dto.lore.size,
                        sourceCursor = dto.sourceCursor,
                        sourceLength = dto.sourceLength,
                        updatedAt = dto.updatedAt,
                    )
                }.getOrNull()
            }
            ?.sortedByDescending(CreationSessionSummary::updatedAt).orEmpty()
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

    suspend fun saveCover(id: String, pngBytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(pngBytes.size in 1..10_000_000 && pngBytes.take(8).toByteArray().contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )) { UiStrings.get(S.crrepo_error_invalid_cover) }
        root.mkdirs()
        val hash = sha256(pngBytes)
        val destination = coverFile(id, hash)
        if (!destination.isFile) {
            val temporary = File(root, "${safeId(id)}.$hash.cover.png.tmp")
            temporary.writeBytes(pngBytes)
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath())
            }
        }
        hash
    }

    suspend fun readCover(id: String, expectedSha256: String): ByteArray = withContext(Dispatchers.IO) {
        val bytes = coverFile(id, expectedSha256).readBytes()
        require(sha256(bytes) == expectedSha256) { UiStrings.get(S.crrepo_error_cover_checksum) }
        bytes
    }

    suspend fun pruneCovers(id: String, keepSha256: String) = withContext(Dispatchers.IO) {
        val prefix = "${safeId(id)}."
        root.listFiles { file -> file.name.startsWith(prefix) && file.name.endsWith(".cover.png") &&
            file.name != "$id.$keepSha256.cover.png" }?.forEach(File::delete)
    }

    suspend fun readSource(id: String, expectedSha256: String): String = withContext(Dispatchers.IO) {
        val bytes = sourceFile(id, expectedSha256).readBytes()
        require(sha256(bytes) == expectedSha256) { UiStrings.get(S.crrepo_error_source_checksum) }
        bytes.decodeToString()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        sessionFile(id).delete()
        root.listFiles { file -> file.name.startsWith("${safeId(id)}.") && file.name.endsWith(".source.txt") }
            ?.forEach(File::delete)
        pruneCovers(id, "")
    }

    private fun sessionFile(id: String): File = File(root, "${safeId(id)}.json")
    private fun coverFile(id: String, sha256: String): File {
        require(Regex("[a-f0-9]{64}").matches(sha256)) { "Invalid cover digest" }
        return File(root, "${safeId(id)}.$sha256.cover.png")
    }
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
