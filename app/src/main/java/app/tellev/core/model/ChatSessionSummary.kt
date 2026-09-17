package app.tellev.core.model

/** Navigation data only; never retain other sessions' messages in UI state. */
data class ChatSessionSummary(val id: String, val title: String, val lastMessageAtMillis: Long)

fun ChatSession.toSummary() = ChatSessionSummary(id, title, messages.lastOrNull()?.createdAtMillis ?: 0L)
