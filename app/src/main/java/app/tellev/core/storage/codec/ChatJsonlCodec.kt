package app.tellev.core.storage.codec

import app.tellev.core.model.Attachment
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import app.tellev.core.model.MessageRole
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter

internal object ChatJsonlCodec {

    fun serializeChatSessionJsonl(session: ChatSession, json: Json): List<String> {
        val compactJson = Json(json) { prettyPrint = false }
        val lines = mutableListOf<String>()

        // Write ST JSONL header line
        val header = buildJsonObject {
            for ((key, value) in session.rawHeader) put(key, value)
            put(
                "user_name",
                (session.rawHeader["user_name"] as? JsonPrimitive)?.content
                    ?: (session.metadata["userName"] as? JsonPrimitive)?.content
                    ?: "User",
            )
            put(
                "character_name",
                (session.rawHeader["character_name"] as? JsonPrimitive)?.content
                    ?: (session.metadata["characterName"] as? JsonPrimitive)?.content
                    ?: session.characterId
                    ?: "Character",
            )
            putJsonObject("chat_metadata") {
                // Preserve the full original chat_metadata (variables, model_class,
                // api, extension data, ...) read at parse time into session.metadata,
                // so ST<->tellev round-trips don't silently lose it. session_id and
                // title are overwritten with the current values afterward.
                for ((key, value) in session.metadata) {
                    put(key, value)
                }
                put("session_id", session.id)
                if (session.title.isNotBlank()) put("title", session.title)
            }
        }
        lines.add(compactJson.encodeToString(JsonObject.serializer(), header))

        // Write each message in ST JSONL format
        for (message in session.messages) {
            val merged = message.raw.toMutableMap()
            merged["_tellev_message_id"] = JsonPrimitive(message.id)
            merged["name"] = JsonPrimitive(message.name)
            merged["is_user"] = JsonPrimitive(message.role == MessageRole.User)
            merged["is_system"] = JsonPrimitive(message.isHidden)
            if (merged.containsKey("is_hidden")) {
                merged["is_hidden"] = JsonPrimitive(message.isHidden)
            }
            if (message.role == MessageRole.Tool || message.role == MessageRole.Assistant) {
                merged["role"] = JsonPrimitive(message.role.name.lowercase())
            }
            val originalDate = (message.raw["send_date"] as? JsonPrimitive)?.content
            if (originalDate == null || parseDateStringToMillis(originalDate) != message.createdAtMillis) {
                merged["send_date"] = JsonPrimitive(formatMillisToIso(message.createdAtMillis))
            }
            merged["mes"] = JsonPrimitive(message.content)
            merged["extra"] = if (message.role == MessageRole.System && message.metadata["type"] == null) {
                JsonObject(message.metadata + ("type" to JsonPrimitive("narrator")))
            } else message.metadata
            // JSR accepts empty swipe arrays; its selected text/extra are then undefined.
            val emptyUpstreamSwipes = message.raw["swipes"] == JsonArray(emptyList()) && message.swipes.isEmpty()
            if (emptyUpstreamSwipes && "mes" !in message.raw && message.content.isEmpty()) merged.remove("mes")
            if (emptyUpstreamSwipes && "extra" !in message.raw && message.metadata.isEmpty()) merged.remove("extra")

            if (message.swipes.isNotEmpty() || message.raw["swipes"] is JsonArray) {
                merged["swipes"] = JsonArray(message.swipes.map(::JsonPrimitive))
                merged["swipe_id"] = JsonPrimitive(message.swipeIndex)
            } else {
                merged.remove("swipes")
                merged.remove("swipe_id")
            }

            val rawVariables = message.raw["variables"]
            if (rawVariables is JsonObject && parseMessageVariables(message.raw) == message.variables) {
                merged["variables"] = rawVariables
            } else if (message.variables.isNotEmpty() || rawVariables is JsonArray) merged["variables"] = JsonArray(message.variables)
            else if (rawVariables == null) merged.remove("variables")
            if (message.swipeInfo.isNotEmpty() || message.raw["swipe_info"] is JsonArray) merged["swipe_info"] = JsonArray(message.swipeInfo)
            else merged.remove("swipe_info")
            if (message.isEjsProcessed.isNotEmpty() || message.raw["is_ejs_processed"] is JsonArray) {
                merged["is_ejs_processed"] = JsonArray(message.isEjsProcessed)
            } else merged.remove("is_ejs_processed")
            if (message.variablesInitialized.isNotEmpty() || message.raw["variables_initialized"] is JsonArray) {
                merged["variables_initialized"] = JsonArray(message.variablesInitialized)
            } else merged.remove("variables_initialized")

            if (message.attachments.isNotEmpty()) {
                merged["attachments"] = JsonArray(
                    message.attachments.map { json.encodeToJsonElement(Attachment.serializer(), it) },
                )
            } else if (!message.raw.containsKey("attachments")) {
                merged.remove("attachments")
            }

            val line = JsonObject(merged)
            lines.add(compactJson.encodeToString(JsonObject.serializer(), line))
        }

        return lines
    }

    fun parseStChatMessage(line: String, sessionId: String, index: Int, json: Json): ChatMessage {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()

        if (obj == null) {
            // Fallback for unparseable lines
            return ChatMessage(
                id = "$sessionId-$index",
                role = MessageRole.Character,
                name = "Unknown",
                content = line,
                createdAtMillis = 0L,
            )
        }

        val isUser = obj["is_user"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val isSystem = obj["is_system"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val explicitRole = obj["role"]?.jsonPrimitive?.content?.lowercase()
        val isHidden = obj["is_hidden"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: isSystem
        val name = obj["name"]?.jsonPrimitive?.content ?: if (isUser) "You" else "Character"
        val content = obj["mes"]?.jsonPrimitive?.content ?: obj["content"]?.jsonPrimitive?.content ?: ""
        val sendDate = obj["send_date"]?.jsonPrimitive?.content

        val role = when {
            explicitRole == "tool" -> MessageRole.Tool
            explicitRole == "assistant" -> MessageRole.Assistant
            explicitRole == "system" -> MessageRole.System
            explicitRole == "user" -> MessageRole.User
            (obj["extra"] as? JsonObject)?.get("type") == JsonPrimitive("narrator") -> MessageRole.System
            isUser -> MessageRole.User
            else -> MessageRole.Character
        }

        // Parse swipes
        val swipes = runCatching {
            obj["swipes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        }.getOrDefault(emptyList())

        val swipeId = obj["swipe_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        // Parse send_date to millis
        val createdAtMillis = parseDateStringToMillis(sendDate)

        val extra = obj["extra"]?.jsonObject ?: buildJsonObject { }

        // ST-Prompt-Template per-swipe arrays (absent on legacy chats → empty)
        val variables = parseMessageVariables(obj)
        val isEjsProcessed = (obj["is_ejs_processed"] as? JsonArray)?.toList() ?: emptyList()
        val variablesInitialized = (obj["variables_initialized"] as? JsonArray)?.toList() ?: emptyList()

        val attachments = runCatching {
            obj["attachments"]?.jsonArray?.mapNotNull {
                json.decodeFromJsonElement(Attachment.serializer(), it)
            } ?: emptyList()
        }.getOrDefault(emptyList())

        return ChatMessage(
            id = (obj["_tellev_message_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: "$sessionId-$index",
            role = role,
            name = name,
            content = content,
            createdAtMillis = createdAtMillis,
            isHidden = isHidden,
            swipeIndex = swipeId,
            swipes = swipes,
            attachments = attachments,
            metadata = extra,
            raw = obj,
            variables = variables,
            swipeInfo = (obj["swipe_info"] as? JsonArray)?.toList() ?: emptyList(),
            isEjsProcessed = isEjsProcessed,
            variablesInitialized = variablesInitialized,
        )
    }

    fun parseMessageVariables(obj: JsonObject): List<JsonElement> = when (val value = obj["variables"]) {
        is JsonArray -> value.toList()
        is JsonObject -> List((obj["swipes"] as? JsonArray)?.size ?: 1) { value[it.toString()] ?: buildJsonObject { } }
        else -> emptyList()
    }

    fun formatMillisToIso(millis: Long): String {
        if (millis == 0L) return ""
        return runCatching {
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
        }.getOrDefault("")
    }

    fun parseDateStringToMillis(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0L

        // Try ISO instant format first
        return runCatching {
            Instant.parse(dateString).toEpochMilli()
        }.getOrElse {
            // Try common date formats
            runCatching {
                DateTimeFormatter.ISO_DATE_TIME.parse(dateString) { temporal ->
                    Instant.from(temporal).toEpochMilli()
                }
            }.getOrElse {
                // Try epoch millis as string
                dateString.toLongOrNull() ?: 0L
            }
        }
    }
}
