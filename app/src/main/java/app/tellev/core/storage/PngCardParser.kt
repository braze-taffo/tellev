package app.tellev.core.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/**
 * Pure Kotlin PNG tEXt chunk reader/writer for character card metadata.
 * Supports V2 ("chara" keyword) and V3 ("ccv3" keyword) specs.
 */
object PngCardParser {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val TEXT_CHUNK_TYPE = byteArrayOf(116, 69, 88, 116) // "tEXt"
    private val ITXT_CHUNK_TYPE = byteArrayOf(105, 84, 88, 116) // "iTXt"
    private val ZTXT_CHUNK_TYPE = byteArrayOf(122, 84, 88, 116) // "zTXt"
    private val IEND_CHUNK_TYPE = byteArrayOf(73, 69, 78, 68)   // "IEND"

    private const val KEYWORD_CHARA = "chara"
    private const val KEYWORD_CCV3 = "ccv3"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Extract character card JSON from a PNG file's tEXt chunks.
     * V3 (ccv3) takes precedence over V2 (chara) if both are present.
     */
    fun extractCardJson(pngBytes: ByteArray): JsonObject? {
        validateSignature(pngBytes)

        val chunks = parseChunks(pngBytes)
        var charaValue: String? = null
        var ccv3Value: String? = null

        for (chunk in chunks) {
            val decoded = decodeTextChunk(chunk) ?: continue
            val keyword = decoded.keyword.lowercase()
            val text = decoded.text

            when (keyword) {
                KEYWORD_CHARA -> charaValue = text
                KEYWORD_CCV3 -> ccv3Value = text
            }
        }

        // ccv3 takes precedence
        val encoded = ccv3Value ?: charaValue ?: return null
        return decodeCardJson(encoded)
    }

    /**
     * Embed character card JSON into a PNG file as tEXt chunks.
     * Writes both "chara" (V2) and "ccv3" (V3) keywords.
     * Preserves all existing PNG chunks except old chara/ccv3 tEXt chunks.
     */
    fun embedCardJson(pngBytes: ByteArray, cardJson: String): ByteArray {
        validateSignature(pngBytes)

        val encoded = Base64.getEncoder().encodeToString(cardJson.toByteArray(Charsets.UTF_8))
        val chunks = parseChunks(pngBytes)
        val output = ByteArrayOutputStream()

        // Write PNG signature
        output.write(PNG_SIGNATURE)

        // Write existing chunks, skipping old chara/ccv3 tEXt chunks
        for (chunk in chunks) {
            if (chunk.type.contentEquals(TEXT_CHUNK_TYPE)) {
                val nullIndex = chunk.data.indexOf(0)
                if (nullIndex >= 0) {
                    val keyword = String(chunk.data, 0, nullIndex, Charsets.ISO_8859_1)
                    if (keyword == KEYWORD_CHARA || keyword == KEYWORD_CCV3) continue
                }
            }
            if (!chunk.type.contentEquals(IEND_CHUNK_TYPE)) {
                writeChunk(output, chunk.type, chunk.data)
            }
        }

        // Write new chara and ccv3 tEXt chunks
        writeTextChunk(output, KEYWORD_CHARA, encoded)
        writeTextChunk(output, KEYWORD_CCV3, encoded)

        // Write IEND
        writeChunk(output, IEND_CHUNK_TYPE, ByteArray(0))

        return output.toByteArray()
    }

    private fun validateSignature(bytes: ByteArray) {
        require(bytes.size >= 8) { "Data too short to be a PNG file" }
        for (i in PNG_SIGNATURE.indices) {
            require(bytes[i] == PNG_SIGNATURE[i]) { "Invalid PNG signature at byte $i" }
        }
    }

    private data class PngChunk(val type: ByteArray, val data: ByteArray)

    private fun parseChunks(pngBytes: ByteArray): List<PngChunk> {
        val chunks = mutableListOf<PngChunk>()
        var offset = 8 // skip signature

        while (offset < pngBytes.size) {
            require(offset + 8 <= pngBytes.size) { "Unexpected end of PNG data at offset $offset" }

            val length = readInt32BigEndian(pngBytes, offset)
            offset += 4

            val type = pngBytes.copyOfRange(offset, offset + 4)
            offset += 4

            require(offset + length + 4 <= pngBytes.size) {
                "Chunk data exceeds PNG file bounds at offset $offset"
            }
            val data = pngBytes.copyOfRange(offset, offset + length)
            offset += length

            // Skip CRC (4 bytes)
            offset += 4

            chunks.add(PngChunk(type, data))

            if (type.contentEquals(IEND_CHUNK_TYPE)) break
        }

        return chunks
    }

    private fun writeTextChunk(output: ByteArrayOutputStream, keyword: String, text: String) {
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(keywordBytes.size + 1 + textBytes.size)
        System.arraycopy(keywordBytes, 0, data, 0, keywordBytes.size)
        data[keywordBytes.size] = 0 // null separator
        System.arraycopy(textBytes, 0, data, keywordBytes.size + 1, textBytes.size)
        writeChunk(output, TEXT_CHUNK_TYPE, data)
    }

    private data class DecodedTextChunk(val keyword: String, val text: String)

    private fun decodeTextChunk(chunk: PngChunk): DecodedTextChunk? {
        return when {
            chunk.type.contentEquals(TEXT_CHUNK_TYPE) -> decodePlainTextChunk(chunk.data)
            chunk.type.contentEquals(ITXT_CHUNK_TYPE) -> decodeInternationalTextChunk(chunk.data)
            chunk.type.contentEquals(ZTXT_CHUNK_TYPE) -> decodeCompressedTextChunk(chunk.data)
            else -> null
        }
    }

    private fun decodePlainTextChunk(data: ByteArray): DecodedTextChunk? {
        val nullIndex = data.indexOfByte(0)
        if (nullIndex < 0) return null
        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
        val text = String(data, nullIndex + 1, data.size - nullIndex - 1, Charsets.ISO_8859_1)
        return DecodedTextChunk(keyword, text)
    }

    private fun decodeCompressedTextChunk(data: ByteArray): DecodedTextChunk? {
        val nullIndex = data.indexOfByte(0)
        if (nullIndex < 0 || nullIndex + 2 > data.size) return null
        val keyword = String(data, 0, nullIndex, Charsets.ISO_8859_1)
        val compressionMethod = data[nullIndex + 1].toInt()
        if (compressionMethod != 0) return null
        val compressed = data.copyOfRange(nullIndex + 2, data.size)
        val text = runCatching {
            InflaterInputStream(compressed.inputStream()).bufferedReader(Charsets.ISO_8859_1).readText()
        }.getOrNull() ?: return null
        return DecodedTextChunk(keyword, text)
    }

    private fun decodeInternationalTextChunk(data: ByteArray): DecodedTextChunk? {
        val keywordEnd = data.indexOfByte(0)
        if (keywordEnd < 0 || keywordEnd + 3 > data.size) return null

        val keyword = String(data, 0, keywordEnd, Charsets.ISO_8859_1)
        val compressionFlag = data[keywordEnd + 1].toInt()
        val compressionMethod = data[keywordEnd + 2].toInt()
        var offset = keywordEnd + 3

        val languageEnd = data.indexOfByte(0, offset)
        if (languageEnd < 0) return null
        offset = languageEnd + 1

        val translatedEnd = data.indexOfByte(0, offset)
        if (translatedEnd < 0) return null
        offset = translatedEnd + 1

        val textBytes = data.copyOfRange(offset, data.size)
        val text = if (compressionFlag == 1) {
            if (compressionMethod != 0) return null
            runCatching {
                InflaterInputStream(textBytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
            }.getOrNull() ?: return null
        } else {
            String(textBytes, Charsets.UTF_8)
        }

        return DecodedTextChunk(keyword, text)
    }

    private fun decodeCardJson(text: String): JsonObject? {
        val trimmed = text.trim()
        val jsonString = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            val compact = trimmed.replace("\\s".toRegex(), "")
            val decoded = runCatching { Base64.getMimeDecoder().decode(compact) }.getOrNull()
                ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull()
                ?: return null
            String(decoded, Charsets.UTF_8)
        }
        return runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull()
    }

    private fun ByteArray.indexOfByte(value: Int, startIndex: Int = 0): Int {
        for (i in startIndex.coerceAtLeast(0) until size) {
            if (this[i] == value.toByte()) return i
        }
        return -1
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: ByteArray, data: ByteArray) {
        // Length
        writeInt32BigEndian(output, data.size)
        // Type
        output.write(type)
        // Data
        output.write(data)
        // CRC (computed over type + data)
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        writeInt32BigEndian(output, crc.value.toInt())
    }

    private fun readInt32BigEndian(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt32BigEndian(output: ByteArrayOutputStream, value: Int) {
        output.write((value shr 24) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    /**
     * Build a minimal valid PNG for testing purposes.
     * Creates a 1x1 white pixel PNG.
     */
    fun createMinimalPng(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)

        // IHDR chunk: 1x1, 8-bit RGB
        val ihdr = ByteArrayOutputStream()
        writeInt32BigEndian(ihdr, 1)  // width
        writeInt32BigEndian(ihdr, 1)  // height
        ihdr.write(8)                 // bit depth
        ihdr.write(2)                 // color type (RGB)
        ihdr.write(0)                 // compression method
        ihdr.write(0)                 // filter method
        ihdr.write(0)                 // interlace method
        writeChunk(output, byteArrayOf(73, 72, 68, 82), ihdr.toByteArray())

        // IDAT chunk: deflate-compressed single scanline
        // Scanline: filter byte (0) + 3 bytes RGB = 4 bytes
        val rawData = byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val deflater = java.util.zip.Deflater()
        deflater.setInput(rawData)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(64)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            compressed.write(buf, 0, n)
        }
        deflater.end()
        writeChunk(output, byteArrayOf(73, 68, 65, 84), compressed.toByteArray())

        // IEND chunk
        writeChunk(output, IEND_CHUNK_TYPE, ByteArray(0))

        return output.toByteArray()
    }
}
