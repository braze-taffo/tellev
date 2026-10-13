package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.Attachment
import app.tellev.feature.chat.isTextAttachment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal const val CREATION_IMAGE_BYTE_LIMIT = 12L * 1024 * 1024
private const val ATTACHMENT_CONTEXT_CHAR_LIMIT = 16_000
private val absoluteAttachmentPath = Regex("^[A-Za-z]:|^[/\\\\]")
private val creationImageMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

private fun Attachment.textContent(): String? =
    (metadata["textContent"] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

/** Same textContent wrapper as normal chat; truncation is explicit in the prompt. */
internal fun creationAttachmentContext(attachments: List<Attachment>): String {
    val context = attachments.mapNotNull { attachment ->
        attachment.textContent()?.let { "[附件 ${attachment.name}]\n$it" }
    }.joinToString("\n\n")
    return if (context.length <= ATTACHMENT_CONTEXT_CHAR_LIMIT) context
    else context.take(ATTACHMENT_CONTEXT_CHAR_LIMIT) + "\n...(truncated)"
}

/** Cheap preflight before send acceptance; image contents are read once on the IO dispatcher. */
internal fun validateCreationAttachments(attachments: List<Attachment>, root: Path?) {
    var imageBytes = 0L
    attachments.forEach { attachment ->
        if (attachment.mimeType.startsWith("image/")) {
            require(attachment.mimeType in creationImageMimeTypes) {
                UiStrings.get(S.crs_attachment_unsupported)
            }
            val path = creationImagePath(attachment, root)
            val size = Files.size(path)
            require(size > 0) { UiStrings.get(S.crs_error_read_image) }
            require(size <= CREATION_IMAGE_BYTE_LIMIT - imageBytes) {
                UiStrings.get(S.crs_error_image_too_large)
            }
            imageBytes += size
        } else {
            require(isTextAttachment(attachment.mimeType, attachment.name) && attachment.textContent() != null) {
                UiStrings.get(S.crs_attachment_unsupported)
            }
        }
    }
}

private fun creationImagePath(attachment: Attachment, root: Path?): Path {
    require(root != null && attachment.relativePath.isNotBlank() &&
        !absoluteAttachmentPath.containsMatchIn(attachment.relativePath) &&
        '\u0000' !in attachment.relativePath
    ) { UiStrings.get(S.crs_error_read_image) }
    try {
        val directory = root.toRealPath()
        val images = directory.resolve("user/images")
        val path = directory.resolve(attachment.relativePath.replace('\\', '/')).normalize()
        require(path.startsWith(images) && Files.isRegularFile(path)) {
            UiStrings.get(S.crs_error_read_image)
        }
        val actual = path.toRealPath()
        require(actual.startsWith(images) && actual.startsWith(directory)) {
            UiStrings.get(S.crs_error_read_image)
        }
        return actual
    } catch (error: java.io.IOException) {
        throw IllegalArgumentException(UiStrings.get(S.crs_error_read_image), error)
    }
}

internal fun readCreationImage(attachment: Attachment, root: Path?): ByteArray {
    val path = creationImagePath(attachment, root)
    require(Files.size(path) in 1..CREATION_IMAGE_BYTE_LIMIT) {
        UiStrings.get(S.crs_error_image_too_large)
    }
    val bytes = Files.newInputStream(path).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= CREATION_IMAGE_BYTE_LIMIT) {
                UiStrings.get(S.crs_error_image_too_large)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    require(matchesCreationImageMime(bytes, attachment.mimeType)) {
        UiStrings.get(S.crs_error_parse_image)
    }
    return bytes
}

private fun matchesCreationImageMime(bytes: ByteArray, mimeType: String): Boolean {
    fun startsWith(prefix: ByteArray) = bytes.size >= prefix.size &&
        prefix.indices.all { bytes[it] == prefix[it] }
    return when (mimeType) {
        "image/jpeg" -> startsWith(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
        "image/png" -> startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        "image/gif" -> startsWith("GIF87a".toByteArray(Charsets.US_ASCII)) ||
            startsWith("GIF89a".toByteArray(Charsets.US_ASCII))
        "image/webp" -> startsWith("RIFF".toByteArray(Charsets.US_ASCII)) &&
            bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
        else -> false
    }
}

/** Only request-local copies carry base64, never the persisted user turn. */
internal fun prepareCreationAttachments(attachments: List<Attachment>, root: Path?): List<Attachment> {
    validateCreationAttachments(attachments, root)
    var imageBytes = 0L
    return attachments.map { attachment ->
        if (!attachment.mimeType.startsWith("image/")) attachment else {
            val bytes = readCreationImage(attachment, root)
            require(bytes.size <= CREATION_IMAGE_BYTE_LIMIT - imageBytes) {
                UiStrings.get(S.crs_error_image_too_large)
            }
            imageBytes += bytes.size
            attachment.copy(metadata = JsonObject(attachment.metadata +
                ("base64" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))))
        }
    }
}
