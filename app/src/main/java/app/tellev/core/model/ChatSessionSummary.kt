package app.tellev.core.model

import kotlinx.serialization.json.JsonPrimitive

/** Navigation data only; never retain other sessions' messages in UI state. */
data class ChatSessionSummary(
    val id: String,
    val title: String,
    val lastMessageAtMillis: Long,
    /** 分支来源会话（DSH forkAt 语义：分支=子会话），存于 chat_metadata.tellev_parent_session。 */
    val parentId: String? = null,
    /** 所属角色卡目录（chats/<characterId>/<id>.jsonl）；群聊为 null。 */
    val characterId: String? = null,
)

fun ChatSession.toSummary() = ChatSessionSummary(
    id = id,
    title = title,
    lastMessageAtMillis = messages.lastOrNull()?.createdAtMillis ?: 0L,
    parentId = (metadata["tellev_parent_session"] as? JsonPrimitive)?.content
        ?.takeIf { it.isNotBlank() },
    characterId = characterId,
)
