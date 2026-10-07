package app.tellev.core.storage.codec

import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ST 用 data.entries[uid] 寻址条目：保存后的对象键必须是 uid 本身。
 * 旧实现按列表 index 写键却保留原 uid，稀疏 UID 的书保存后在 ST /
 * 扩展侧直接错位。
 */
class WorldBookUidKeyingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun raw(vararg entries: Pair<Int, String>) = kotlinx.serialization.json.buildJsonObject {
        put("name", JsonPrimitive("book"))
        put("entries", kotlinx.serialization.json.buildJsonObject {
            entries.forEach { (uid, text) ->
                put(uid.toString(), kotlinx.serialization.json.buildJsonObject {
                    put("uid", JsonPrimitive(uid))
                    put("key", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("k$uid")) })
                    put("content", JsonPrimitive(text))
                })
            }
        })
    }.let { Json.parseToJsonElement(it.toString()).jsonObject }

    @Test
    fun `save keys entries by uid not by list index`() {
        // 稀疏 UID（5、9）：删除中间条目后保存，剩余条目的对象键必须仍是 uid。
        val parsed = WorldBookCodec.parseWorldBookEntries(raw(5 to "five", 9 to "nine"))
        val book = WorldBook(id = "wb_t", name = "book", entries = parsed.filter { it.id == "9" }, raw = raw(5 to "five", 9 to "nine"))
        val output = WorldBookCodec.serializeWorldBook(book)["entries"]!!.jsonObject
        assertEquals(setOf("9"), output.keys)
        assertEquals(9, output.getValue("9").jsonObject.getValue("uid").jsonPrimitive.content.toInt())
    }

    @Test
    fun `entries without numeric uid get fresh uid keys above existing ones`() {
        // raw 里没有 uid、id 也非数字的新条目：分配现有最大 uid+1 作为键。
        val existing = WorldBookCodec.parseWorldBookEntries(raw(5 to "five"))
        val fresh = WorldBookEntry(id = "non-numeric", keys = listOf("k"), content = "new")
        val book = WorldBook(id = "wb_t", name = "book", entries = existing + fresh, raw = raw(5 to "five"))
        val output = WorldBookCodec.serializeWorldBook(book)["entries"]!!.jsonObject
        assertEquals(setOf("5", "6"), output.keys)
        assertEquals(6, output.getValue("6").jsonObject.getValue("uid").jsonPrimitive.content.toInt())
    }

    @Test
    fun `sparse uid book survives a full roundtrip`() {
        val original = raw(2 to "two", 7 to "seven", 11 to "eleven")
        val parsed = WorldBookCodec.parseWorldBookEntries(original)
        val book = WorldBook(id = "wb_t", name = "book", entries = parsed, raw = original)
        val reparsed = WorldBookCodec.parseWorldBookEntries(WorldBookCodec.serializeWorldBook(book))
        assertEquals(parsed.map { it.id }, reparsed.map { it.id })
        assertEquals(parsed.map { it.content }, reparsed.map { it.content })
    }
}
