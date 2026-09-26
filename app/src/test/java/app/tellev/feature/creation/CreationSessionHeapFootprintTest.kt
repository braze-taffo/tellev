package app.tellev.feature.creation

import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.nio.file.Files
import kotlin.math.roundToLong

/**
 * 设备侧 dropbox 显示 Java 堆常驻 200-215MB 后 OOM（上限 256MB），而用户磁盘上有
 * 4 份 ~6.8MB 的创作草稿，且创作页列表此前对每份草稿做全量解码常驻。本测试走生产
 * 路径量化：list() 摘要的常驻必须比 load() 全量对象图低两个数量级，字段仍需一致。
 */
class CreationSessionHeapFootprintTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun summaryListingFootprintAndConsistency() = runBlocking {
        val root = Files.createTempDirectory("creation-heap-")
        val repository = CreationRepository(root.toFile())
        val baseTime = 1_700_000_000_000L
        val sessions = (0 until 4).map { buildSyntheticSession("creation_synth_$it", baseTime + it) }
        val utf8Bytes = json.encodeToString(sessions[0]).toByteArray(Charsets.UTF_8).size
        println("[heap-test] synthetic draft JSON UTF-8 bytes = $utf8Bytes")
        sessions.forEach { repository.save(it) }

        val fullBefore = gcAndUsed()
        var full: List<CreationSession>? = List(4) { repository.load("creation_synth_$it") }
        val fullAfter = gcAndUsed()
        val fullRetained = fullAfter - fullBefore
        println("[heap-test] retained 4 full CreationSessions (old list()) = $fullRetained bytes")
        full?.forEach { check(it.lore.size == 251) }
        check(fullRetained > 4L * 1024 * 1024) { "full-session baseline too small to be meaningful: $fullRetained" }

        // Release the full graphs, then measure the summary listing on a quiet heap.
        full = null
        val summaryBaseline = gcAndUsed()
        val summaries = repository.list()
        val summaryAfter = gcAndUsed()
        val summaryRetained = (summaryAfter - summaryBaseline).coerceAtLeast(0)
        println("[heap-test] retained list() summaries (new list()) = $summaryRetained bytes")

        val expected = sessions.sortedByDescending { it.updatedAt }
        check(summaries.size == 4)
        summaries.forEachIndexed { i, summary ->
            val session = expected[i]
            check(summary.id == session.id)
            check(summary.kind == session.kind)
            check(summary.cardName == session.card.name)
            check(summary.worldName == session.worldName)
            check(summary.turnsCount == session.turns.size)
            check(summary.loreCount == session.lore.size)
            check(summary.sourceCursor == session.sourceCursor)
            check(summary.sourceLength == session.sourceLength)
            check(summary.updatedAt == session.updatedAt)
        }

        println(
            "[heap-test] ratio = ${(summaryRetained.toDouble() / fullRetained * 100).roundToLong()}%" +
                " (must stay far below 10%)",
        )
        check(summaryRetained < fullRetained / 10) {
            "summary retained $summaryRetained not far below full-session $fullRetained"
        }
    }

    private fun gcAndUsed(): Long {
        repeat(5) { System.gc(); Thread.sleep(50) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    /** 量级对齐用户实测草稿：251 条 lore、编码后 ~7-8MB、含 sourceQuote 与 originalBook。 */
    private fun buildSyntheticSession(id: String, updatedAt: Long): CreationSession {
        val entryCount = 251
        val contents = (0 until entryCount).map { i ->
            buildString {
                repeat(46) { append("条目${i}第${it}段：这是用于内存测量的合成世界书正文，长度对齐真实条目均值。") }
            }
        }
        val sourceQuotes = contents.mapIndexed { i, _ ->
            buildString {
                repeat(20) { append("原文证据${i}-${it}：来自源文本的可定位片段，制卡校验要求逐字命中。") }
            }
        }
        val entries = contents.mapIndexed { i, content ->
            val raw = buildJsonObject {
                put("uid", i)
                put("key", JsonArray(listOf(JsonPrimitive("关键词$i"), JsonPrimitive("词条$i"))))
                put("keysecondary", JsonArray(emptyList()))
                put("content", content)
                put("comment", "合成条目$i")
                put("constant", false)
                put("selective", true)
                put("order", 100)
                put("depth", 4)
                put("probability", 100)
                put("position", 0)
                put("disable", false)
                put("addMemo", true)
                put("excludeRecursion", false)
                put("preventRecursion", false)
                put("delayUntilRecursion", false)
                put("displayIndex", i)
                put("extensions", JsonObject(emptyMap()))
            }
            WorldBookEntry(
                id = i.toString(),
                keys = listOf("关键词$i", "词条$i"),
                content = content,
                comment = "合成条目$i",
                insertionOrder = 100,
                raw = raw,
            )
        }
        val book = WorldBook(
            id = "wb_synthetic",
            name = "合成世界书",
            entries = entries,
            raw = buildJsonObject {
                put("entries", JsonObject(buildMap { entries.forEachIndexed { i, e -> put(i.toString(), e.raw) } }))
            },
        )
        val turns = (0 until 60).map { i ->
            CreationTurn(
                if (i % 2 == 0) "user" else "agent",
                buildString { repeat(30) { append("对话轮$i-${it}：包含工具调用与推理过程的完整记录。") } },
            )
        }
        return CreationSession.fromWorldBook(book).copy(
            id = id,
            turns = turns,
            updatedAt = updatedAt,
            sourceName = "合成源文本",
            sourceSha256 = "a".repeat(64),
            sourceCursor = 0,
            sourceLength = 0,
            lore = entries.mapIndexed { i, e ->
                e.toLoreDraft().copy(
                    sourceQuote = sourceQuotes[i],
                    sourceName = "合成源文本",
                    sourceSha256 = "a".repeat(64),
                    note = "备注$i",
                )
            },
        )
    }
}
