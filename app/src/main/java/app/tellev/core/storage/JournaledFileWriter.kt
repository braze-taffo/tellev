package app.tellev.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * A write-ahead record contains only bytes and their destination, never executable work.
 * A failed accepted write blocks that destination until explicit recovery succeeds.
 * All files, including temporary replacements, stay on the same filesystem.
 */
class JournaledFileWriter(
    root: Path,
    private val fault: (Stage) -> Unit = {},
) {
    enum class Stage { PAYLOAD_SYNCED, PREPARED, REPLACED, COMMITTED }

    @Serializable
    data class Receipt(val operationId: String, val revision: Long, val sha256: String?, val persisted: Boolean = true)

    @Serializable
    private data class Prepared(
        val target: String,
        val beforeSha256: String?,
        val payload: String?,
        val receipt: Receipt,
    )

    private val root = root.toAbsolutePath().normalize()
    private val journal = this.root.resolve(".tellev-writes")
    private val json = Json { encodeDefaults = true }
    // Multiple FileStDataStore instances within one process must share the lock.
    private val lock = locks.computeIfAbsent(this.root.toString()) { Any() }

    fun revision(target: Path): Long = synchronized(lock) {
        stateFile(checked(target)).takeIf { it.exists() }?.let {
            json.decodeFromString<Receipt>(it.readText()).revision
        } ?: 0L
    }

    fun write(
        target: Path,
        bytes: ByteArray,
        operationId: String = UUID.randomUUID().toString(),
        expectedRevision: Long? = null,
    ): Receipt = commit(target, bytes, operationId, expectedRevision)

    fun delete(target: Path, operationId: String = UUID.randomUUID().toString(), expectedRevision: Long? = null): Receipt =
        commit(target, null, operationId, expectedRevision)

    private fun commit(target: Path, bytes: ByteArray?, operationId: String, expectedRevision: Long?): Receipt = synchronized(lock) {
        require(operationId.isNotBlank()) { "A storage operation must have an ID" }
        val path = checked(target)
        val digest = bytes?.let(::sha)
        val completed = completedFile(path, operationId)
        if (completed.exists()) {
            val receipt = json.decodeFromString<Receipt>(completed.readText())
            check(receipt.sha256 == digest) { "Operation ID reused with different data: $operationId" }
            return@synchronized receipt
        }
        val pending = pendingFile(path)
        check(!pending.exists()) { "Unrecovered write for ${root.relativize(path)}" }
        val revision = revision(path)
        check(expectedRevision == null || revision == expectedRevision) {
            "Stale write to ${root.relativize(path)}: expected $expectedRevision, current $revision"
        }
        Files.createDirectories(path.parent)
        Files.createDirectories(journal)
        val payload = bytes?.let { key(path) + ".payload" }
        val receipt = Receipt(operationId, revision + 1, digest)
        val before = path.takeIf { it.exists() }?.readBytes()
        // Retain the exact pre-migration file. Subsequent commits do not overwrite it.
        val backup = journal.resolve(key(path) + ".original")
        if (before != null && !backup.exists()) atomicWrite(backup, before)
        if (before != null) atomicWrite(journal.resolve(key(path) + ".previous"), before)
        if (payload != null) atomicWrite(journal.resolve(payload), bytes)
        fault(Stage.PAYLOAD_SYNCED)
        val prepared = Prepared(root.relativize(path).toString(), before?.let(::sha), payload, receipt)
        atomicWrite(pending, json.encodeToString(prepared).toByteArray(Charsets.UTF_8))
        fault(Stage.PREPARED)
        finish(prepared, path, pending)
        receipt
    }

    /** Call at bootstrap, before exposing any data. A conflict is reported, never guessed away. */
    fun recover(): List<Receipt> = synchronized(lock) {
        if (!journal.exists()) return@synchronized emptyList()
        Files.list(journal).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".pending") }.sorted().map { pending ->
                val prepared = json.decodeFromString<Prepared>(pending.readText())
                val path = checked(root.resolve(prepared.target))
                check(pending == pendingFile(path)) { "Journal destination mismatch: $pending" }
                check(prepared.payload == null || prepared.payload == key(path) + ".payload") { "Invalid journal payload: $pending" }
                finish(prepared, path, pending)
                prepared.receipt
            }.collect(java.util.stream.Collectors.toList())
        }
    }

    private fun finish(prepared: Prepared, path: Path, pending: Path) {
        val payloadPath = prepared.payload?.let(journal::resolve)
        val bytes = payloadPath?.readBytes()
        check(bytes?.let(::sha) == prepared.receipt.sha256) { "Corrupt write payload for ${prepared.target}" }
        val currentSha = path.takeIf { it.exists() }?.readBytes()?.let(::sha)
        val currentRevision = revision(path)
        check(currentRevision <= prepared.receipt.revision) { "Refusing to recover an obsolete write: ${prepared.target}" }
        check(currentSha == prepared.beforeSha256 || currentSha == prepared.receipt.sha256) {
            "Recovery conflict: ${prepared.target} was changed outside the accepted operation"
        }
        if (currentSha != prepared.receipt.sha256) {
            if (bytes != null) atomicWrite(path, bytes)
            else { Files.deleteIfExists(path); syncDirectory(path.parent) }
        }
        fault(Stage.REPLACED)
        val record = json.encodeToString(prepared.receipt).toByteArray(Charsets.UTF_8)
        atomicWrite(completedFile(path, prepared.receipt.operationId), record)
        atomicWrite(stateFile(path), record)
        fault(Stage.COMMITTED)
        // Once pending is removed, recovery has nothing to replay. An orphan payload is harmless.
        Files.delete(pending)
        syncDirectory(journal)
        payloadPath?.let { Files.deleteIfExists(it) }
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling(path.fileName.toString() + ".new")
        FileOutputStream(temp.toFile()).use { stream -> stream.write(bytes); stream.fd.sync() }
        // Do not fall back to a truncate/copy if atomic replacement is unsupported.
        Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(path.parent)
    }

    private fun syncDirectory(path: Path) {
        // Android/Linux require the rename's directory entry to be forced as well.
        // Windows' Java provider cannot open directories; the JVM tests there
        // establish process-crash recovery, not a power-loss durability guarantee.
        if (System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) != true) {
            FileChannel.open(path, READ).use { it.force(true) }
        }
    }

    private fun checked(target: Path): Path {
        val path = target.toAbsolutePath().normalize()
        require(path.startsWith(root) && path != root && !path.startsWith(journal)) { "Storage destination escapes data root" }
        var ancestor: Path? = path
        while (ancestor != null && ancestor.startsWith(root)) {
            require(!Files.isSymbolicLink(ancestor)) { "Storage destination contains a symbolic link" }
            ancestor = ancestor.parent
        }
        return path
    }

    private fun key(path: Path) = sha(root.relativize(path).toString().toByteArray(Charsets.UTF_8))
    private fun stateFile(path: Path) = journal.resolve(key(path) + ".state")
    private fun pendingFile(path: Path) = journal.resolve(key(path) + ".pending")
    private fun completedFile(path: Path, operationId: String): Path =
        journal.resolve(key(path) + ".commits").resolve(sha(operationId.toByteArray(Charsets.UTF_8)) + ".json")

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
