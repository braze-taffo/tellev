package app.tellev.core.extension.router

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal class ChatApiHandler(
    private val dataStore: StDataStore,
    private val json: Json,
) {
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
        dataStore.appendMessage(sessionId, message)
        return jsonResponse(200, buildJsonObject { put("ok", true) }, json)
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
