package app.tellev.feature.chat

import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.model.preserveReasoningSwipe
import app.tellev.core.model.selectReasoningSwipe
import app.tellev.core.regex.CharacterRegexApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Handles operations on chat messages: swiping, editing, deleting, and message transformations.
 */
internal class ChatMessageActions(
    private val sessionRuntime: ChatSessionRuntime,
) {
    fun swipeMessage(
        messageIndex: Int,
        direction: Int,
        state: ChatUiState,
        scope: CoroutineScope,
        onSessionUpdated: (ChatSession) -> Unit,
        onError: (String) -> Unit,
        onSwipeCommitted: suspend (updatedMessage: ChatMessage) -> Unit,
    ) {
        if (state.isLoading) return
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        val message = messages[messageIndex]

        if (message.swipes.isEmpty()) return

        val newSwipeIndex = when (direction) {
            -1 -> if (message.swipeIndex > 0) message.swipeIndex - 1 else message.swipes.size - 1
            1 -> if (message.swipeIndex < message.swipes.size - 1) message.swipeIndex + 1 else 0
            else -> return
        }

        val updatedMessage = message.selectReasoningSwipe(newSwipeIndex)
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        val commit = sessionRuntime.scheduleUiMutation(session, updatedSession, onSessionUpdated, onError) ?: return
        sessionRuntime.launchAfterCommit(scope, commit, onError) {
            onSwipeCommitted(updatedMessage)
        }
    }

    fun editMessage(
        messageIndex: Int,
        newContent: String,
        state: ChatUiState,
        scope: CoroutineScope,
        onSessionUpdated: (ChatSession) -> Unit,
        onError: (String) -> Unit,
        onEditCommitted: suspend (updatedMessage: ChatMessage) -> Unit,
        onUserMessageEdit: (
            baseSession: ChatSession,
            trimmedSession: ChatSession,
            trimmedMessages: List<ChatMessage>,
            rawContent: String,
            attachments: List<Attachment>,
            updatedMessage: ChatMessage,
        ) -> Unit,
    ) {
        if (state.isLoading) return
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        val message = messages[messageIndex]

        val processedContent = CharacterRegexApplier.applyNormal(
            text = newContent,
            role = message.role,
            character = state.selectedCharacter,
            preset = state.selectedPreset,
            userName = state.selectedPersona?.name ?: "User",
            depth = visibleRegexDepth(state.messages, messageIndex),
            isEdit = true,
        )
        val updatedSwipes = if (message.swipes.isNotEmpty()) {
            message.swipes.toMutableList().also {
                if (message.swipeIndex in it.indices) {
                    it[message.swipeIndex] = processedContent
                } else {
                    it.add(processedContent)
                }
            }
        } else {
            listOf(processedContent)
        }

        val updatedMessage = CharacterRegexApplier.markNormalProcessed(message.copy(
            content = processedContent,
            swipes = updatedSwipes,
            swipeIndex = if (message.swipes.isEmpty()) 0 else message.swipeIndex,
        ))
        messages[messageIndex] = updatedMessage

        val session = state.currentSession ?: return

        if (message.role == MessageRole.User) {
            val trimmedMessages = messages.take(messageIndex)
            val trimmedSession = session.copy(messages = trimmedMessages)
            onUserMessageEdit(session, trimmedSession, trimmedMessages, newContent, message.attachments, updatedMessage)
            return
        }

        val updatedSession = session.copy(messages = messages)
        val commit = sessionRuntime.scheduleUiMutation(session, updatedSession, onSessionUpdated, onError) ?: return
        sessionRuntime.launchAfterCommit(scope, commit, onError) {
            onEditCommitted(updatedMessage)
        }
    }

    fun deleteMessage(
        messageIndex: Int,
        state: ChatUiState,
        scope: CoroutineScope,
        onSessionUpdated: (ChatSession) -> Unit,
        onError: (String) -> Unit,
        onDeleteCommitted: suspend () -> Unit,
    ) {
        if (state.isLoading) return
        val messages = state.messages.toMutableList()

        if (messageIndex !in messages.indices) return
        messages.removeAt(messageIndex)

        val session = state.currentSession ?: return
        val updatedSession = session.copy(messages = messages)

        val commit = sessionRuntime.scheduleUiMutation(session, updatedSession, onSessionUpdated, onError) ?: return
        sessionRuntime.launchAfterCommit(scope, commit, onError) {
            onDeleteCommitted()
        }
    }

    fun removeTavernMessageWithoutSaving(
        messageIndex: Int,
        state: ChatUiState,
        onSessionUpdated: (ChatSession) -> Unit,
    ): Boolean {
        val session = state.currentSession ?: return false
        if (messageIndex !in state.messages.indices) return false
        val messages = state.messages.toMutableList().also { it.removeAt(messageIndex) }
        val updatedSession = session.copy(messages = messages)
        onSessionUpdated(updatedSession)
        sessionRuntime.scheduleMetadataSave(session, updatedSession, onSessionUpdated)
        return true
    }

    suspend fun setChatMessageFromExtension(
        index: Int,
        field: String,
        value: String,
        state: ChatUiState,
        onSessionUpdated: (ChatSession) -> Unit,
    ): Boolean {
        val session = state.currentSession ?: return false
        val messages = state.messages.toMutableList()
        if (index !in messages.indices) return false

        val original = messages[index]
        val updated = when (field.lowercase()) {
            "message", "mes" -> original.withContent(value)
            "name" -> original.copy(name = value)
            "role" -> {
                val newRole = when (value.lowercase()) {
                    "user" -> MessageRole.User
                    "assistant" -> MessageRole.Assistant
                    "system" -> MessageRole.System
                    "character" -> MessageRole.Character
                    else -> original.role
                }
                original.copy(role = newRole)
            }
            "is_hidden" -> original.copy(isHidden = value.toBooleanStrictOrNull() ?: original.isHidden)
            "data" -> {
                val parsed = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                    ?: return false
                val vars = original.variables.toMutableList()
                while (vars.size <= original.swipeIndex) vars.add(buildJsonObject { })
                vars[original.swipeIndex] = parsed
                original.copy(variables = vars)
            }
            "swipe_id" -> {
                val swipes = original.swipes.ifEmpty { listOf(original.content) }
                val swipeIndex = value.toIntOrNull()?.coerceIn(0, swipes.lastIndex) ?: original.swipeIndex
                original.copy(
                    swipeIndex = swipeIndex,
                    swipes = swipes,
                    content = swipes[swipeIndex],
                )
            }
            "extra" -> {
                val parsed = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                original.copy(metadata = parsed ?: original.metadata)
            }
            else -> original.copy(
                metadata = buildJsonObject {
                    original.metadata.forEach { (key, element) -> put(key, element) }
                    put(field, value)
                },
            )
        }

        messages[index] = updated
        val updatedSession = session.copy(messages = messages)
        sessionRuntime.persistSessionMutation(session, updatedSession, onSessionUpdated)
        return true
    }
}

internal fun ChatMessage.withContent(value: String): ChatMessage {
    val updatedSwipes = swipes.ifEmpty { listOf(content) }.toMutableList()
    val targetIndex = swipeIndex.coerceIn(0, updatedSwipes.lastIndex)
    updatedSwipes[targetIndex] = value
    return copy(
        content = value,
        swipes = updatedSwipes,
        swipeIndex = targetIndex,
    )
}

internal fun canRegenerateResponse(messages: List<ChatMessage>, messageIndex: Int): Boolean {
    if (messageIndex !in messages.indices || messageIndex != messages.lastIndex) return false
    val message = messages[messageIndex]
    if (message.role != MessageRole.Character && message.role != MessageRole.Assistant) return false
    return messages.take(messageIndex).any { it.role == MessageRole.User }
}

internal fun visibleRegexDepth(messages: List<ChatMessage>, messageIndex: Int): Int =
    messages.drop(messageIndex + 1).count {
        !it.isHidden && it.role != MessageRole.System && it.role != MessageRole.Tool
    }

internal fun ChatMessage.withRegeneratedSwipe(newContent: String): ChatMessage {
    val previousSwipes = swipes.ifEmpty { listOf(content) }
    return preserveReasoningSwipe().copy(
        content = newContent,
        swipes = previousSwipes + newContent,
        swipeIndex = previousSwipes.size,
    )
}
