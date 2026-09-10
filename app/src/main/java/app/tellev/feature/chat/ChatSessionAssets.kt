package app.tellev.feature.chat

import app.tellev.core.model.CharacterSummary
import app.tellev.core.model.ChatSession
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.util.decodeImageAsPng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Handles file-based assets associated with chat sessions and characters:
 * background images and character card/avatar files.
 */
internal object ChatSessionAssets {

    suspend fun setChatBackground(
        imageBytes: ByteArray,
        session: ChatSession,
        layout: StDirectoryLayout,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
        onBackgroundFileResolved: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val pngBytes = withContext(Dispatchers.IO) { decodeImageAsPng(imageBytes) }
                ?: error("无法解析图片")
            val rel = "backgrounds/${session.id}.png"
            val bgFile = withContext(Dispatchers.IO) {
                val dir = layout.backgrounds.toFile()
                dir.mkdirs()
                val target = File(dir, "${session.id}.png")
                target.writeBytes(pngBytes)
                target
            }
            val updated = session.copy(
                metadata = buildJsonObject {
                    session.metadata.forEach { (key, value) -> put(key, value) }
                    put("background", rel)
                },
            )
            sessionRuntime.persistSessionMutation(session, updated, onSessionUpdated)
            onBackgroundFileResolved(bgFile)
        } catch (e: Exception) {
            onError("设置聊天背景失败：${e.message}")
        }
    }

    suspend fun clearChatBackground(
        session: ChatSession,
        layout: StDirectoryLayout,
        sessionRuntime: ChatSessionRuntime,
        onSessionUpdated: (ChatSession) -> Unit,
        onBackgroundCleared: () -> Unit,
        onError: (String) -> Unit,
    ) {
        try {
            val rel = session.metadata["background"]?.jsonPrimitive?.content
            val updated = session.copy(
                metadata = buildJsonObject {
                    session.metadata.forEach { (key, value) ->
                        if (key != "background") put(key, value)
                    }
                },
            )
            sessionRuntime.persistSessionMutation(session, updated, onSessionUpdated)
            onBackgroundCleared()
            if (rel != null) {
                withContext(Dispatchers.IO) {
                    layout.root.resolve(rel).toFile().takeIf { it.exists() }?.delete()
                }
            }
        } catch (e: Exception) {
            onError("清除聊天背景失败：${e.message}")
        }
    }

    fun chatBackgroundFileFor(session: ChatSession?, layout: StDirectoryLayout): File? {
        val rel = session?.metadata?.get("background")?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: return null
        return layout.root.resolve(rel).toFile().takeIf { it.exists() }
    }

    fun characterCardFile(characterId: String, layout: StDirectoryLayout): File? =
        listOf("png", "webp", "json").firstNotNullOfOrNull { extension ->
            layout.characters.resolve("$characterId.$extension").toFile()
                .takeIf { it.exists() }
        }

    fun avatarFilesFor(
        characters: List<CharacterSummary>,
        layout: StDirectoryLayout,
    ): Map<String, File?> =
        characters.associate { it.id to characterCardFile(it.id, layout) }
}
