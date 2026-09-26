package app.tellev.core.extension.router

import app.tellev.core.extension.ExternalChatWritePort
import app.tellev.core.extension.VirtualApiRequest
import app.tellev.core.extension.VirtualApiResponse
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import app.tellev.core.storage.StDataStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.NoSuchFileException

internal class ChatApiHandler(
    private val dataStore: StDataStore,
    private val json: Json,
    private val externalChatWrites: ExternalChatWritePort = object : ExternalChatWritePort {},
) {
    private suspend fun readExistingSession(id: String): ChatSession? = try {
        dataStore.readChatSession(id)
    } catch (_: NoSuchFileException) {
        null
    } catch (error: IllegalStateException) {
        if (error.message == "Chat session not found: $id") null else throw error
    }

    suspend fun handleListChats(request: VirtualApiRequest): VirtualApiResponse {
        val queryParams = parseSimpleQuery(request.path)
        val characterId = queryParams["characterId"]
        val groupId = queryParams["groupId"]
        val sessions = dataStore.listChatSessions(characterId, groupId)
        val body = buildJsonObject {
            putJsonArray("chats") {
                for (s in sessions) {
                    add(json.encodeToJsonElement(ChatSession.serializer(), s))
                }
            }
        }
        return jsonResponse(200, body, json)
    }

    suspend fun handleReadChat(id: String): VirtualApiResponse {
        val session = dataStore.readChatSession(id)
        val body = json.encodeToJsonElement(ChatSession.serializer(), session)
        return jsonResponse(200, body as? JsonObject ?: buildJsonObject { put("data", body) }, json)
    }

    suspend fun handleAppendMessage(
        sessionId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val message = parseBody<ChatMessage>(request, json)
        // This write bypasses RuntimeWriteCoordinator; quiesce keeps it out of the
        // coordinator's in-flight tail and notifyWritten re-adopts the bumped revision.
        externalChatWrites.quiesce(sessionId)
        dataStore.appendMessage(sessionId, message)
        externalChatWrites.notifyWritten(sessionId)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    /**
     * POST /api/chats/{id}/messages/delete { message_ids: [...] } — removes
     * the listed messages (id strings as they appear in the session), backing
     * TavernHelper.deleteChatMessages. Same coordinator courtesy as append.
     */
    suspend fun handleDeleteMessages(
        sessionId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val ids = (body["message_ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
            ?: return errorResponse(400, "Missing message_ids array", json)
        externalChatWrites.quiesce(sessionId)
        try {
            val session = readExistingSession(sessionId)
                ?: return errorResponse(404, "Chat not found: $sessionId", json)
            val kept = session.messages.filterNot { it.id in ids }
            dataStore.saveChatSession(session.copy(messages = kept))
            return jsonResponse(200, buildJsonObject { put("ok", true); put("deleted", session.messages.size - kept.size) }, json)
        } finally {
            externalChatWrites.notifyWritten(sessionId)
        }
    }

    /**
     * POST /api/chats/{id}/messages/insert { messages: [...], before: n } —
     * inserts a batch in one durable write at index n (clamped), backing
     * TavernHelper.createChatMessages' insert_before option.
     */
    suspend fun handleInsertMessage(
        sessionId: String,
        request: VirtualApiRequest,
    ): VirtualApiResponse {
        val body = parseBodyAsJsonObject(request, json)
        val elements = body["messages"] as? JsonArray
            ?: body["message"]?.let { JsonArray(listOf(it)) }
            ?: return errorResponse(400, "Missing messages", json)
        val messages = runCatching { elements.map { json.decodeFromJsonElement(ChatMessage.serializer(), it) } }
            .getOrElse { return errorResponse(400, "Invalid messages: ${it.message}", json) }
        if (messages.map { it.id }.toSet().size != messages.size) {
            return errorResponse(400, "Duplicate message ids", json)
        }
        val before = (body["before"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return errorResponse(400, "Missing before index", json)
        externalChatWrites.quiesce(sessionId)
        try {
            val session = readExistingSession(sessionId)
                ?: return errorResponse(404, "Chat not found: $sessionId", json)
            if (session.messages.any { existing -> messages.any { it.id == existing.id } }) {
                return errorResponse(409, "Message id already exists", json)
            }
            val at = before.coerceIn(0, session.messages.size)
            dataStore.saveChatSession(session.copy(messages = session.messages.toMutableList().apply { addAll(at, messages) }))
            return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
        } finally {
            externalChatWrites.notifyWritten(sessionId)
        }
    }

    suspend fun handleStGetChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val chatId = bodyObj?.get("file_name")?.jsonPrimitive?.content
            ?: bodyObj?.get("ch_name")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing file_name/ch_name", json)
        val session = runCatching { dataStore.readChatSession(chatId) }.getOrNull()
            ?: return errorResponse(404, "Chat not found: $chatId", json)
        return jsonResponse(200, sessionToStChatArray(session), json)
    }

    suspend fun handleStGetGroupChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
        val chatId = bodyObj?.get("chat_id")?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing chat_id", json)
        return handleReadChat(chatId)
    }

    suspend fun handleStImportChat(request: VirtualApiRequest): VirtualApiResponse {
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    suspend fun handleStSaveChat(request: VirtualApiRequest): VirtualApiResponse {
        val bodyObj = parseBodyAsJsonObjectOrNull(request, json)
            ?: return errorResponse(400, "Missing body", json)
        val chatId = bodyObj["file_name"]?.jsonPrimitive?.content
            ?: bodyObj["ch_name"]?.jsonPrimitive?.content
            ?: return errorResponse(400, "Missing file_name", json)
        val chatArray = bodyObj["chat"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return errorResponse(400, "Missing chat array", json)
        // This whole-file save bypasses RuntimeWriteCoordinator and overwrites without a
        // revision check; quiesce BEFORE reading so the rewrite is not built on a snapshot
        // that a coordinated write is about to replace underneath it.
        externalChatWrites.quiesce(chatId)
        val session = runCatching { dataStore.readChatSession(chatId) }.getOrNull()
            ?: return errorResponse(404, "Chat not found: $chatId", json)

        val messages = stChatArrayToMessages(chatArray, chatId)
        val header = chatArray.firstOrNull() as? JsonObject
        val metadata = (header?.get("chat_metadata") as? JsonObject) ?: session.metadata
        dataStore.saveChatSession(
            session.copy(
                messages = messages,
                metadata = metadata,
                rawHeader = header ?: session.rawHeader,
            ),
        )
        externalChatWrites.notifyWritten(chatId)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
    }

    private fun sessionToStChatArray(session: ChatSession): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                for ((key, value) in session.rawHeader) put(key, value)
                put(
                    "user_name",
                    session.rawHeader["user_name"]?.jsonPrimitive?.content
                        ?: session.metadata["userName"]?.jsonPrimitive?.content
                        ?: "User",
                )
                put(
                    "character_name",
                    session.rawHeader["character_name"]?.jsonPrimitive?.content
                        ?: session.metadata["characterName"]?.jsonPrimitive?.content
                        ?: "",
                )
                put("create_date", session.metadata["create_date"]?.jsonPrimitive?.content ?: "")
                put("chat_metadata", session.metadata)
            },
        )
        session.messages.forEach { msg ->
            add(
                buildJsonObject {
                    for ((key, value) in msg.raw) put(key, value)
                    put("name", msg.name)
                    put("mes", msg.swipes.getOrNull(msg.swipeIndex) ?: msg.content)
                    put("is_user", msg.role == MessageRole.User)
                    put("is_system", msg.role == MessageRole.System || msg.isHidden)
                    put("is_hidden", msg.isHidden)
                    put("send_date", msg.createdAtMillis)
                    put("swipes", JsonArray(msg.swipes.map { JsonPrimitive(it) }))
                    put("swipe_id", msg.swipeIndex)
                    put("variables", JsonArray(msg.variables))
                    put("extra", msg.metadata)
                },
            )
        }
    }

    private fun stChatArrayToMessages(chatArray: JsonArray, chatId: String): List<ChatMessage> =
        chatArray.drop(1).mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val isUser = obj["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val isSystem = obj["is_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val isHidden = obj["is_hidden"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: isSystem
            val swipes = runCatching {
                obj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            }.getOrDefault(emptyList())
            val swipeId = obj["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val content = obj["mes"]?.jsonPrimitive?.content
                ?: swipes.getOrNull(swipeId)
                ?: ""
            ChatMessage(
                id = "$chatId-$index",
                role = when {
                    isUser -> MessageRole.User
                    isSystem && !isHidden -> MessageRole.System
                    else -> MessageRole.Character
                },
                name = obj["name"]?.jsonPrimitive?.content ?: if (isUser) "You" else "Character",
                content = content,
                createdAtMillis = obj["send_date"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                swipeIndex = swipeId,
                swipes = swipes,
                metadata = obj["extra"] as? JsonObject ?: buildJsonObject { },
                raw = obj,
                variables = runCatching {
                    obj["variables"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
                }.getOrDefault(emptyList()),
                isHidden = isHidden,
            )
        }
}
