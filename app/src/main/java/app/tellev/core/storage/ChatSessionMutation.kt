package app.tellev.core.storage

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.ChatSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Applies only fields changed by this operation. A stale unrelated field is never copied back.
 * Concurrent edits of the same field, or competing structural changes, fail explicitly.
 * Message identity is stable across deletion/reordering; an array index is never an identity.
 */
fun applyChatSessionMutation(base: ChatSession, desired: ChatSession, current: ChatSession): ChatSession {
    require(base.id == desired.id && base.id == current.id) { "A chat mutation cannot change ownership" }
    require(base.characterId == desired.characterId && base.groupId == desired.groupId) { "A chat mutation cannot move storage owners" }
    val json = Json { encodeDefaults = true }
    val beforeIds = base.messages.map { it.id }
    val afterIds = desired.messages.map { it.id }
    val currentIds = current.messages.map { it.id }
    require(beforeIds.distinct().size == beforeIds.size && afterIds.distinct().size == afterIds.size && currentIds.distinct().size == currentIds.size) {
        "Duplicate stable message IDs"
    }
    val before = base.messages.associateBy { it.id }
    val after = desired.messages.associateBy { it.id }
    val present = current.messages.associateBy { it.id }
    (before.keys intersect after.keys).forEach { id ->
        check(before[id] == after[id] || id in present) { "Message was removed before modification: $id" }
    }
    val order = when {
        afterIds == beforeIds -> currentIds
        currentIds == beforeIds || currentIds == afterIds -> afterIds
        else -> error("Concurrent message insertion, deletion or reordering in chat ${base.id}")
    }
    // A deletion conflicts with a concurrent edit of the deleted object.
    (before.keys - after.keys).forEach { id ->
        check(present[id] == null || present[id] == before[id]) { "Message changed before deletion: $id" }
    }
    val messages = order.map { id ->
        if (before[id] == after[id]) return@map present.getValue(id)
        if (present[id] == before[id] || present[id] == after[id]) return@map after.getValue(id)
        check(before[id] != null && after[id] != null && present[id] != null) { "Message was removed or replaced: $id" }
        json.decodeFromJsonElement(ChatMessage.serializer(), mergeChangedFields(
            json.encodeToJsonElement(ChatMessage.serializer(), before.getValue(id)),
            json.encodeToJsonElement(ChatMessage.serializer(), after.getValue(id)),
            json.encodeToJsonElement(ChatMessage.serializer(), present.getValue(id)),
            "message[$id]",
            swipeFields = setOf("variables", "swipeInfo", "swipes", "isEjsProcessed", "variablesInitialized")
                .map { "message[$id].$it" }.toSet(),
        )!!)
    }
    fun withoutMessages(session: ChatSession) = json.encodeToJsonElement(ChatSession.serializer(), session).jsonObject
        .let { JsonObject(it - "messages") }
    val merged = mergeChangedFields(withoutMessages(base), withoutMessages(desired), withoutMessages(current), "chat[${base.id}]")!!.jsonObject
    return json.decodeFromJsonElement(ChatSession.serializer(), JsonObject(merged +
        ("messages" to json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages)))).copy(storageRevision = current.storageRevision)
}

private fun mergeChangedFields(before: JsonElement?, after: JsonElement?, current: JsonElement?, path: String,
    swipeFields: Set<String> = emptySet()): JsonElement? {
    if (before == after) return current
    if (current == before || current == after) return after
    if (before is JsonObject && after is JsonObject && current is JsonObject) {
        val fields = current.toMutableMap()
        (before.keys + after.keys).forEach { key ->
            val value = mergeChangedFields(before[key], after[key], current[key], "$path.$key", swipeFields)
            if (value == null) fields.remove(key) else fields[key] = value
        }
        return JsonObject(fields)
    }
    if (path in swipeFields && before is JsonArray && after is JsonArray && current is JsonArray) {
        val size = when {
            after.size == before.size -> current.size
            current.size == before.size || current.size == after.size -> after.size
            else -> error("Concurrent swipe structure changes at $path")
        }
        for (i in size until maxOf(before.size, after.size, current.size)) {
            check(after.getOrNull(i) == before.getOrNull(i) || current.getOrNull(i) == before.getOrNull(i) || after.getOrNull(i) == current.getOrNull(i)) {
                "Swipe was removed during modification at $path[$i]"
            }
        }
        return JsonArray(List(size) { i ->
            mergeChangedFields(before.getOrNull(i), after.getOrNull(i), current.getOrNull(i), "$path[$i]")
                ?: error("Missing swipe at $path[$i]")
        })
    }
    error("Conflicting stale update at $path")
}
