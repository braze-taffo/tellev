package app.tellev.core.storage.repository

import app.tellev.core.model.ChatSessionSummary
import app.tellev.core.storage.codec.ChatJsonlCodec
import app.tellev.core.storage.isGeneratedImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/** Reads the header and tail, without decoding or migrating inactive histories. */
internal class ChatSessionSummaryReader(private val json: Json) {
    fun read(path: Path): ChatSessionSummary {
        val id = path.nameWithoutExtension
        val header = path.toFile().bufferedReader(Charsets.UTF_8).use { reader ->
            generateSequence { reader.readLine() }.firstOrNull { it.isNotBlank() }
        }?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val title = if (header?.containsKey("user_name") == true) {
            ((header["chat_metadata"] as? JsonObject)?.get("title") as? JsonPrimitive)?.content ?: id
        } else id
        val lastTime = RandomAccessFile(path.toFile(), "r").use { file ->
            reverseLines(file).firstNotNullOfOrNull { line ->
                if (line.isBlank()) return@firstNotNullOfOrNull null
                val raw = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (raw?.containsKey("user_name") == true) return@firstNotNullOfOrNull null
                val message = ChatJsonlCodec.parseStChatMessage(line, id, 0, json)
                if (message.isGeneratedImage()) null else message.createdAtMillis
            } ?: 0L
        }
        return ChatSessionSummary(id, title, lastTime)
    }

    // Reverse bytes only to find LF boundaries; decode UTF-8 after restoring order.
    // Block reads avoid a syscall for every byte and stop at the last real message.
    private fun reverseLines(file: RandomAccessFile): Sequence<String> = sequence {
        var position = file.length()
        val block = ByteArray(8192)
        val line = ByteArrayOutputStream()
        while (position > 0) {
            val count = minOf(position, block.size.toLong()).toInt()
            position -= count
            file.seek(position)
            file.readFully(block, 0, count)
            for (index in count - 1 downTo 0) {
                if (block[index] == 10.toByte()) {
                    yield(line.toByteArray().reversedArray().toString(Charsets.UTF_8))
                    line.reset()
                } else line.write(block[index].toInt())
            }
        }
        if (line.size() > 0) yield(line.toByteArray().reversedArray().toString(Charsets.UTF_8))
    }
}
