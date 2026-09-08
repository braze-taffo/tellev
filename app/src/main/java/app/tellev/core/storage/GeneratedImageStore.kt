package app.tellev.core.storage

import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Gallery records are app data, never chat turns or extension context. */
@Serializable
data class GeneratedImage(
    val id: String,
    val createdAtMillis: Long,
    val attachments: List<Attachment>,
    val prompt: String,
    val negativePrompt: String = "",
    val engine: String = "",
    /** Preserve old records losslessly without activating their variables or scripts. */
    val legacyMessage: ChatMessage? = null,
)

fun ChatMessage.isGeneratedImage(): Boolean = metadata["image_prompt"] is JsonPrimitive &&
    attachments.any { it.mimeType.startsWith("image/") } &&
    metadata["image_engine"] is JsonPrimitive

class GeneratedImageStore(private val layout: StDirectoryLayout) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writer = JournaledFileWriter(layout.root)

    // Shared across the chat reader and UI store instances; image writes never use chat locks/events.
    fun <T> withSessionLock(sessionId: String, block: () -> T): T =
        synchronized(locks.computeIfAbsent(path(sessionId).toString()) { Any() }, block)

    fun read(sessionId: String): List<GeneratedImage> = withSessionLock(sessionId) {
        val file = path(sessionId)
        if (file.exists()) json.decodeFromString<List<GeneratedImage>>(file.readText()) else emptyList()
    }

    fun append(sessionId: String, images: List<GeneratedImage>) = withSessionLock(sessionId) {
        val previous = read(sessionId)
        val next = (previous + images.filter { image -> previous.none { it.id == image.id } })
        if (next != previous) writer.write(path(sessionId), json.encodeToString(next).toByteArray(Charsets.UTF_8))
        Unit
    }

    private fun path(sessionId: String): Path {
        val key = MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return layout.userImages.resolve("gallery").resolve("$key.json").toAbsolutePath().normalize()
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
