package app.tellev.core.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Normalizes image-generation results into validated image bytes.
 *
 * Providers disagree on the result shape: SD/ComfyUI return bare base64,
 * OpenAI Images may return an https URL or a `data:` URI. The chat image
 * pipeline used to base64-decode everything, so an OpenAI URL came back as
 * garbage. Classification is pure JVM (no Android graphics) so the wire
 * shapes stay unit-tested; pixel conversion stays with the Android caller.
 */
object ImageResultNormalizer {

    /** Refuse absurd payloads before decode/download (Android heap safety). */
    const val MAX_BYTES = 20 * 1024 * 1024

    sealed interface Classified {
        /** base64 or data-URI payload already decoded and sniffed. */
        data class Inline(val bytes: ByteArray, val mimeType: String) : Classified

        /** A remote URL the caller must download (with its own size policy). */
        data class Remote(val url: String) : Classified
    }

    data class Normalized(val bytes: ByteArray, val mimeType: String)

    /** Classifies one provider result; null means the text is unusable. */
    fun classify(resultText: String): Classified? {
        val text = resultText.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("data:", ignoreCase = true)) {
            val comma = text.indexOf(',')
            if (comma <= 0) return null
            val header = text.substring(5, comma)
            if (!header.startsWith("image/", ignoreCase = true)) return null
            val bytes = decodeBase64(text.substring(comma + 1)) ?: return null
            val mime = sniff(bytes) ?: return null
            return Classified.Inline(bytes, mime)
        }
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
            // Reject whitespace/control characters: a URL never contains them.
            if (text.any { it.isWhitespace() || it.code < 0x20 }) return null
            return Classified.Remote(text)
        }
        // Everything else is treated as a bare base64 payload (SD/ComfyUI/NovelAI).
        val bytes = decodeBase64(text) ?: return null
        val mime = sniff(bytes) ?: return null
        return Classified.Inline(bytes, mime)
    }

    /**
     * Turns one provider result into validated image bytes. [downloadUrl] is
     * injected so tests can fake transfers; it must enforce its own size cap
     * and network policy.
     */
    suspend fun toBytes(
        resultText: String,
        downloadUrl: (suspend (String) -> ByteArray?)? = null,
    ): Normalized? {
        return when (val classified = classify(resultText)) {
            null -> null
            is Classified.Inline -> Normalized(classified.bytes, classified.mimeType)
            is Classified.Remote -> {
                val downloader = downloadUrl ?: return null
                val bytes = runCatching { downloader(classified.url) }.getOrNull() ?: return null
                if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
                val mime = sniff(bytes) ?: return null
                Normalized(bytes, mime)
            }
        }
    }

    /**
     * Size-capped URL download on the shared provider client's policy
     * (CleartextGuard applies when the client carries it). Returns null on
     * any failure — callers surface their own error message.
     */
    suspend fun defaultUrlDownloader(
        client: okhttp3.OkHttpClient,
        url: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val length = body.contentLength()
                if (length > MAX_BYTES) return@use null
                val source = body.source()
                source.request(MAX_BYTES + 1L)
                if (source.buffer.size > MAX_BYTES) return@use null
                source.readByteArray()
            }
        }.getOrNull()
    }

    /** base64 with whitespace, URL-safe alphabets and missing padding tolerated. */
    internal fun decodeBase64(text: String): ByteArray? {
        if (text.isEmpty() || text.length > MAX_BYTES) return null
        val normalized = buildString(text.length) {
            for (ch in text) {
                if (ch.isWhitespace()) continue
                when (ch) {
                    '-' -> append('+')
                    '_' -> append('/')
                    else -> append(ch)
                }
            }
        }
        val bytes = runCatching {
            java.util.Base64.getMimeDecoder().decode(normalized)
        }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        return bytes
    }

    /** Image magic sniffing; anything else (HTML/JSON error bodies) is rejected. */
    internal fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        else -> null
    }
}
