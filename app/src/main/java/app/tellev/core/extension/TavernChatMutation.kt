package app.tellev.core.extension

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.*

/** Fixed JSR chat_message.ts semantics; UI refresh/events belong to the caller. */
fun applyTavernChatMessages(messages: List<ChatMessage>, updates: JsonArray): List<ChatMessage> {
    val grouped = sortedMapOf<Int, MutableMap<String, JsonElement>>()
    for (element in updates) {
        val update = element.jsonObject
        val requested = update.getValue("message_id").jsonPrimitive.int
        val index = if (requested < 0) messages.size + requested else requested
        grouped.getOrPut(index) { linkedMapOf() }.putAll(update)
    }
    val result = messages.toMutableList()
    for ((index, update) in grouped) {
        // The fixed upstream explicitly skips nonexistent floors.
        val old = result.getOrNull(index) ?: continue
        var role = old.role
        var extra = old.metadata
        update["role"]?.jsonPrimitive?.content?.let {
            role = when (it) {
                "user" -> MessageRole.User
                "system" -> MessageRole.System
                else -> MessageRole.Character
            }
            extra = JsonObject(extra.toMutableMap().apply {
                if (it == "system") put("type", JsonPrimitive("narrator")) else remove("type")
            })
        }
        var text = old.content
        var texts = old.swipes
        var swipe = old.swipeIndex
        var variables = old.variables
        var info = old.swipeInfo
        val raw = old.raw.toMutableMap()
        fun array(key: String): List<JsonElement>? = (update[key] as? JsonArray)?.toList()
        fun nonNull(value: JsonElement?): JsonElement? = value?.takeUnless { it == JsonNull }
        if ("message" in update || "data" in update) {
            update["message"]?.let { value ->
                text = value.jsonPrimitive.content
                if (texts.isNotEmpty() || old.raw["swipes"] is JsonArray) {
                    texts = texts.toMutableList().apply {
                        while (size <= swipe) add("")
                        this[swipe] = text
                    }
                }
                raw["mes"] = value
            }
            update["data"]?.let { value ->
                variables = variables.ifEmpty { List(texts.size.takeIf { it > 0 } ?: 1) { JsonObject(emptyMap()) } }
                    .toMutableList().apply {
                        while (size <= swipe) add(JsonNull)
                        this[swipe] = value
                    }
            }
            update["extra"]?.let { value ->
                // Preserve the fixed upstream's swipes_info presence check (not swipe_info).
                if ("swipes_info" !in old.raw) info = List(texts.size.takeIf { it > 0 } ?: 1) { JsonObject(emptyMap()) }
                extra = value.jsonObject
                info = info.toMutableList().apply {
                    while (size <= swipe) add(JsonNull)
                    this[swipe] = value
                }
            }
        } else if (listOf("swipe_id", "swipes", "swipes_data", "swipes_info").any { it in update }) {
            val length = listOf("swipes", "swipes_data", "swipes_info").mapNotNull { array(it)?.size }.maxOrNull()
                ?: if (texts.isNotEmpty() || old.raw["swipes"] is JsonArray) texts.size else 1
            swipe = (update["swipe_id"]?.jsonPrimitive?.int ?: swipe).coerceAtMost(length - 1).coerceAtLeast(0)
            val sourceTexts = array("swipes") ?: texts.takeIf { it.isNotEmpty() || old.raw["swipes"] is JsonArray }?.map(::JsonPrimitive)
                ?: listOf(JsonPrimitive(text))
            val sourceVars = array("swipes_data") ?: variables
            val sourceInfo = array("swipes_info") ?: info
            texts = List(length) { nonNull(sourceTexts.getOrNull(it))?.jsonPrimitive?.content ?: "" }
            variables = List(length) { nonNull(sourceVars.getOrNull(it)) ?: JsonObject(emptyMap()) }
            info = List(length) { nonNull(sourceInfo.getOrNull(it)) ?: JsonObject(emptyMap()) }
            text = texts.getOrNull(swipe) ?: ""
            extra = info.getOrNull(swipe) as? JsonObject ?: JsonObject(emptyMap())
            raw["swipes"] = JsonArray(texts.map(::JsonPrimitive))
            raw["variables"] = JsonArray(variables)
            raw["swipe_info"] = JsonArray(info)
            if (length == 0) { raw.remove("mes"); raw.remove("extra") }
            else { raw["mes"] = JsonPrimitive(text); raw["extra"] = extra }
        }
        result[index] = old.copy(
            name = update["name"]?.jsonPrimitive?.content ?: old.name,
            role = role, isHidden = update["is_hidden"]?.jsonPrimitive?.boolean ?: old.isHidden,
            content = text, swipes = texts, swipeIndex = swipe, variables = variables, swipeInfo = info,
            metadata = extra, raw = JsonObject(raw),
        )
    }
    return result
}
