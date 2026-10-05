package app.tellev.core.tts

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Filesystem cache for generated speech, with deterministic keys and LRU eviction. */
class TtsCache(
    root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val directory = (if (root.name == "st-data") root.resolve("tts") else root.resolve("st-data/tts"))
        .apply { mkdirs() }

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        evictIfNeeded()
    }

    fun key(text: String, settings: TtsSettingsValues): String = sha256(
        listOf(text, settings.model, settings.voice, settings.speed.toString(), settings.format)
            .joinToString("\u0000"),
    )

    @Synchronized
    fun get(text: String, settings: TtsSettingsValues): File? {
        val file = fileFor(key(text, settings), settings.format)
        return file.takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(clock()) }
    }

    @Synchronized
    fun put(text: String, settings: TtsSettingsValues, bytes: ByteArray): File {
        require(bytes.isNotEmpty()) { "audio bytes must not be empty" }
        val target = fileFor(key(text, settings), settings.format)
        val temporary = File(directory, ".${target.name}.tmp")
        try {
            temporary.outputStream().use { it.write(bytes) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.setLastModified(clock())
            evictIfNeeded()
            return target
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    internal fun files(): List<File> = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()

    private fun fileFor(key: String, format: String): File = File(directory, "$key.$format")

    private fun evictIfNeeded() {
        var total = files().sumOf { it.length() }
        files().sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024

        fun sha256Key(value: String): String = sha256(value)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
