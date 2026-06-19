package app.tellev.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * WebP RIFF container parser for character card metadata.
 * Reads/writes XMP metadata chunks containing base64-encoded character JSON.
 *
 * WebP structure:
 * - "RIFF" (4 bytes) + file size (4 bytes LE) + "WEBP" (4 bytes)
 * - Chunks: FourCC (4 bytes) + size (4 bytes LE) + data (padded to even length)
 */
object WebpCardParser {

    private val RIFF_HEADER = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_HEADER = "WEBP".toByteArray(Charsets.US_ASCII)
    private val XMP_FOURCC = "XMP ".toByteArray(Charsets.US_ASCII)
    private val EXIF_FOURCC = "EXIF".toByteArray(Charsets.US_ASCII)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class WebpChunk(val fourCC: ByteArray, val data: ByteArray) {
        fun fourCCString(): String = String(fourCC, Charsets.US_ASCII)
    }

    /**
     * Extract character card JSON from a WebP file's XMP metadata.
     * Looks for base64-encoded JSON in XMP or EXIF chunks.
     */
    fun extractCardJson(webpBytes: ByteArray): JsonObject? {
        validateRiffHeader(webpBytes)
        val chunks = parseChunks(webpBytes)

        // Try XMP chunk first, then EXIF
        for (chunk in chunks) {
            val fourCC = chunk.fourCCString()
            if (fourCC != "XMP " && fourCC != "EXIF") continue

            val result = tryExtractFromChunkData(chunk.data)
            if (result != null) return result
        }

        return null
    }

    /**
     * Embed character card JSON into a WebP file as an XMP chunk.
     * Preserves all existing chunks, replacing any existing XMP chunk.
     */
    fun embedCardJson(webpBytes: ByteArray, cardJson: String): ByteArray {
        validateRiffHeader(webpBytes)
        val chunks = parseChunks(webpBytes)
        val encoded = Base64.getEncoder().encodeToString(cardJson.toByteArray(Charsets.UTF_8))

        val output = ByteArrayOutputStream()

        // Reserve space for RIFF header (12 bytes: "RIFF" + size + "WEBP")
        output.write(RIFF_HEADER)
        output.write(ByteArray(4)) // placeholder for size
        output.write(WEBP_HEADER)

        var xmpWritten = false
        for (chunk in chunks) {
            if (chunk.fourCCString() == "XMP ") {
                if (!xmpWritten) {
                    writeChunk(output, XMP_FOURCC, encoded.toByteArray(Charsets.US_ASCII))
                    xmpWritten = true
                }
                // Skip old XMP chunk
                continue
            }
            writeChunk(output, chunk.fourCC, chunk.data)
        }

        // If no XMP chunk existed, add one before the end
        if (!xmpWritten) {
            writeChunk(output, XMP_FOURCC, encoded.toByteArray(Charsets.US_ASCII))
        }

        // Fix up the RIFF size field
        val result = output.toByteArray()
        val riffPayloadSize = result.size - 8 // everything after "RIFF" + size
        writeInt32LittleEndian(result, 4, riffPayloadSize)

        return result
    }

    private fun tryExtractFromChunkData(data: ByteArray): JsonObject? {
        val text = String(data, Charsets.UTF_8).trim()

        // Try direct base64 decode
        val base64Decoded = tryBase64Decode(text)
        if (base64Decoded != null) {
            val parsed = tryParseJson(base64Decoded)
            if (parsed != null) return parsed
        }

        // Try extracting base64 from XMP XML description
        val extracted = extractBase64FromXmpXml(text)
        if (extracted != null) {
            val decoded = tryBase64Decode(extracted)
            if (decoded != null) {
                val parsed = tryParseJson(decoded)
                if (parsed != null) return parsed
            }
        }

        // Try direct JSON parse (in case it's stored as raw JSON)
        val directParse = tryParseJson(text)
        if (directParse != null) return directParse

        return null
    }

    private fun extractBase64FromXmpXml(xmpText: String): String? {
        // Look for base64 content inside XMP description tags or as raw text between XML tags
        val descRegex = Regex("""<rdf:Description[^>]*>(.*?)</rdf:Description>""", RegexOption.DOT_MATCHES_ALL)
        val match = descRegex.find(xmpText) ?: return null
        val inner = match.groupValues[1].trim()

        // The base64 might be in a custom property or directly in the description
        // Try to find a long base64 string
        val b64Regex = Regex("""[A-Za-z0-9+/=]{20,}""")
        return b64Regex.find(inner)?.value
    }

    private fun tryBase64Decode(text: String): String? {
        return runCatching {
            // Remove any whitespace that might be in the base64 string
            val cleaned = text.replace("\\s".toRegex(), "")
            if (cleaned.isEmpty()) return null
            val decoded = Base64.getDecoder().decode(cleaned)
            String(decoded, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun tryParseJson(text: String): JsonObject? {
        if (!text.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
    }

    private fun validateRiffHeader(bytes: ByteArray) {
        require(bytes.size >= 12) { "Data too short to be a WebP file" }
        for (i in RIFF_HEADER.indices) {
            require(bytes[i] == RIFF_HEADER[i]) { "Invalid RIFF header" }
        }
        for (i in WEBP_HEADER.indices) {
            require(bytes[8 + i] == WEBP_HEADER[i]) { "Invalid WEBP header" }
        }
    }

    private fun parseChunks(webpBytes: ByteArray): List<WebpChunk> {
        val chunks = mutableListOf<WebpChunk>()
        var offset = 12 // skip RIFF header (4) + size (4) + WEBP (4)

        while (offset + 8 <= webpBytes.size) {
            val fourCC = webpBytes.copyOfRange(offset, offset + 4)
            offset += 4

            val chunkSize = readInt32LittleEndian(webpBytes, offset)
            offset += 4

            if (chunkSize < 0 || offset + chunkSize > webpBytes.size) break

            val data = webpBytes.copyOfRange(offset, offset + chunkSize)
            offset += chunkSize

            // Chunks are padded to even byte boundaries
            if (chunkSize % 2 != 0 && offset < webpBytes.size) {
                offset += 1
            }

            chunks.add(WebpChunk(fourCC, data))
        }

        return chunks
    }

    private fun writeChunk(output: ByteArrayOutputStream, fourCC: ByteArray, data: ByteArray) {
        output.write(fourCC)
        writeInt32LittleEndianToStream(output, data.size)
        output.write(data)
        // Pad to even length
        if (data.size % 2 != 0) {
            output.write(0)
        }
    }

    private fun readInt32LittleEndian(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt32LittleEndianToStream(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeInt32LittleEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Build a minimal valid WebP file for testing purposes.
     * Creates a 1x1 pixel lossy WebP.
     */
    fun createMinimalWebp(): ByteArray {
        val output = ByteArrayOutputStream()

        // Minimal VP8 bitstream for a 1x1 pixel image
        // VP8 chunk data: a minimal valid VP8 bitstream
        val vp8Data = byteArrayOf(
            0x9D.toByte(), 0x01, 0x2A, // VP8 frame tag
            0x01, 0x00, // width = 1
            0x01, 0x00, // height = 1
            // Minimal quantizer and partition data
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        val vp8FourCC = "VP8 ".toByteArray(Charsets.US_ASCII)

        // Calculate RIFF payload size
        val chunkHeaderSize = 8 // FourCC + size
        val paddedDataSize = if (vp8Data.size % 2 != 0) vp8Data.size + 1 else vp8Data.size
        val riffPayloadSize = 4 + chunkHeaderSize + paddedDataSize // "WEBP" + VP8 chunk

        // RIFF header
        output.write(RIFF_HEADER)
        writeInt32LittleEndianToStream(output, riffPayloadSize)
        output.write(WEBP_HEADER)

        // VP8 chunk
        output.write(vp8FourCC)
        writeInt32LittleEndianToStream(output, vp8Data.size)
        output.write(vp8Data)
        if (vp8Data.size % 2 != 0) output.write(0)

        return output.toByteArray()
    }
}
