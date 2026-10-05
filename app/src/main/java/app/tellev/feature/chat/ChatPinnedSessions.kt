package app.tellev.feature.chat

import app.tellev.core.storage.DurableFileOps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/**
 * 会话置顶（参考图三的 📌）：按会话 id 存储在 st-data 根的 pinned-sessions.json。
 * 独立小文件而不是会话 metadata：抽屉列表只读 summary（无 metadata），这样不用
 * 为了一个置顶标记去加载全部会话正文。
 */
internal object ChatPinnedSessions {
    private const val FILE_NAME = "pinned-sessions.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: Path): Set<String> {
        val file = root.resolve(FILE_NAME).toFile()
        if (!file.isFile) return emptySet()
        return runCatching {
            val array = json.parseToJsonElement(file.readText()).jsonArray
            array.map { it.jsonPrimitive.content }.toSet()
        }.getOrDefault(emptySet())
    }

    /** 切换置顶并落盘，返回新的置顶集合。写失败时返回旧集合（内存态不漂移）。 */
    fun toggle(root: Path, sessionId: String): Set<String> {
        val current = read(root)
        val updated = if (sessionId in current) current - sessionId else current + sessionId
        val payload = JsonArray(updated.map { JsonPrimitive(it) }).toString()
        val written = runCatching {
            DurableFileOps.write(root.resolve(FILE_NAME), payload.toByteArray(Charsets.UTF_8))
        }.isSuccess
        return if (written) updated else current
    }
}
