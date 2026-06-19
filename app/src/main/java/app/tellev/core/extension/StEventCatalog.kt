package app.tellev.core.extension

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object StEventCatalog {

    // ── Chat / message events ──────────────────────────────────────────
    const val MESSAGE_RECEIVED = "MESSAGE_RECEIVED"
    const val MESSAGE_SENT = "MESSAGE_SENT"
    const val MESSAGE_EDITED = "MESSAGE_EDITED"
    const val MESSAGE_DELETED = "MESSAGE_DELETED"
    const val MESSAGE_SWIPED = "MESSAGE_SWIPED"

    // ── Character events ───────────────────────────────────────────────
    const val CHARACTER_SELECTED = "CHARACTER_SELECTED"
    const val CHARACTER_CREATED = "CHARACTER_CREATED"
    const val CHARACTER_EDITED = "CHARACTER_EDITED"
    const val CHARACTER_DELETED = "CHARACTER_DELETED"
    const val CHARACTER_IMPORTED = "CHARACTER_IMPORTED"
    const val CHARACTER_EXPORTED = "CHARACTER_EXPORTED"

    // ── Chat-session events ────────────────────────────────────────────
    const val CHAT_CHANGED = "CHAT_CHANGED"
    const val CHAT_CREATED = "CHAT_CREATED"
    const val CHAT_DELETED = "CHAT_DELETED"
    const val CHAT_IMPORTED = "CHAT_IMPORTED"
    const val CHAT_EXPORTED = "CHAT_EXPORTED"

    // ── Generation events ──────────────────────────────────────────────
    const val GENERATION_STARTED = "GENERATION_STARTED"
    const val GENERATION_ENDED = "GENERATION_ENDED"
    const val GENERATION_STOPPED = "GENERATION_STOPPED"

    // ── World-info events ──────────────────────────────────────────────
    const val WORLD_INFO_ACTIVATED = "WORLD_INFO_ACTIVATED"
    const val WORLD_INFO_CHANGED = "WORLD_INFO_CHANGED"

    // ── UI events ──────────────────────────────────────────────────────
    const val EXTENSION_SETTINGS_OPENED = "EXTENSION_SETTINGS_OPENED"
    const val EXTENSION_SETTINGS_CLOSED = "EXTENSION_SETTINGS_CLOSED"

    // ── Group events ───────────────────────────────────────────────────
    const val GROUP_SELECTED = "GROUP_SELECTED"
    const val GROUP_CHAT_STARTED = "GROUP_CHAT_STARTED"

    // ── System events ──────────────────────────────────────────────────
    const val APP_READY = "APP_READY"
    const val SETTINGS_CHANGED = "SETTINGS_CHANGED"

    /** Every known event name, useful for introspection / debug UIs. */
    val ALL_EVENTS: List<String> = listOf(
        MESSAGE_RECEIVED, MESSAGE_SENT, MESSAGE_EDITED, MESSAGE_DELETED, MESSAGE_SWIPED,
        CHARACTER_SELECTED, CHARACTER_CREATED, CHARACTER_EDITED, CHARACTER_DELETED,
        CHARACTER_IMPORTED, CHARACTER_EXPORTED,
        CHAT_CHANGED, CHAT_CREATED, CHAT_DELETED, CHAT_IMPORTED, CHAT_EXPORTED,
        GENERATION_STARTED, GENERATION_ENDED, GENERATION_STOPPED,
        WORLD_INFO_ACTIVATED, WORLD_INFO_CHANGED,
        EXTENSION_SETTINGS_OPENED, EXTENSION_SETTINGS_CLOSED,
        GROUP_SELECTED, GROUP_CHAT_STARTED,
        APP_READY, SETTINGS_CHANGED,
    )

    /**
     * Build a properly-shaped [JsonObject] payload for the given [eventName].
     *
     * [data] is a flat map of key-value pairs supplied by the caller. Values
     * are coerced into the correct JSON types:
     * - `String`  -> JsonPrimitive(string)
     * - `Number`  -> JsonPrimitive(number)
     * - `Boolean` -> JsonPrimitive(boolean)
     * - `List<*>` -> JsonArray of strings
     * - `Map<*,*>` -> nested JsonObject (one level deep)
     * - anything else -> JsonPrimitive(toString())
     */
    fun buildPayload(eventName: String, data: Map<String, Any?>): JsonObject =
        buildJsonObject {
            when (eventName) {
                // ── message events ─────────────────────────────────────
                MESSAGE_RECEIVED,
                MESSAGE_SENT,
                MESSAGE_EDITED,
                MESSAGE_DELETED,
                MESSAGE_SWIPED -> {
                    putStringOrSkip(data, "messageId")
                    putStringOrSkip(data, "chatId")
                    putStringOrSkip(data, "characterId")
                    putStringOrSkip(data, "role")
                    putStringOrSkip(data, "content")
                    putLongOrSkip(data, "timestamp")
                    if (eventName == MESSAGE_SWIPED) {
                        putIntOrSkip(data, "swipeIndex")
                    }
                }

                // ── character events ───────────────────────────────────
                CHARACTER_SELECTED,
                CHARACTER_CREATED,
                CHARACTER_EDITED,
                CHARACTER_DELETED,
                CHARACTER_IMPORTED,
                CHARACTER_EXPORTED -> {
                    putStringOrSkip(data, "characterId")
                    putStringOrSkip(data, "name")
                    if (eventName == CHARACTER_EXPORTED) {
                        putStringOrSkip(data, "exportPath")
                    }
                }

                // ── chat session events ────────────────────────────────
                CHAT_CHANGED,
                CHAT_CREATED,
                CHAT_DELETED,
                CHAT_IMPORTED,
                CHAT_EXPORTED -> {
                    putStringOrSkip(data, "chatId")
                    putStringOrSkip(data, "characterId")
                    putStringOrSkip(data, "title")
                    if (eventName == CHAT_EXPORTED) {
                        putStringOrSkip(data, "exportPath")
                    }
                }

                // ── generation events ──────────────────────────────────
                GENERATION_STARTED,
                GENERATION_ENDED,
                GENERATION_STOPPED -> {
                    putStringOrSkip(data, "chatId")
                    putStringOrSkip(data, "characterId")
                    putStringOrSkip(data, "providerType")
                    putStringOrSkip(data, "model")
                    if (eventName == GENERATION_ENDED) {
                        putStringOrSkip(data, "finishReason")
                        putLongOrSkip(data, "tokenCount")
                    }
                }

                // ── world-info events ──────────────────────────────────
                WORLD_INFO_ACTIVATED,
                WORLD_INFO_CHANGED -> {
                    putStringOrSkip(data, "worldBookId")
                    putStringOrSkip(data, "entryId")
                    if (eventName == WORLD_INFO_ACTIVATED) {
                        val keys = data["keys"]
                        if (keys is List<*>) {
                            putJsonArray("keys") {
                                keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
                            }
                        }
                    }
                }

                // ── UI events ──────────────────────────────────────────
                EXTENSION_SETTINGS_OPENED,
                EXTENSION_SETTINGS_CLOSED -> {
                    putStringOrSkip(data, "extensionId")
                    putStringOrSkip(data, "panelId")
                }

                // ── group events ───────────────────────────────────────
                GROUP_SELECTED,
                GROUP_CHAT_STARTED -> {
                    putStringOrSkip(data, "groupId")
                    putStringOrSkip(data, "name")
                    val members = data["memberCharacterIds"]
                    if (members is List<*>) {
                        putJsonArray("memberCharacterIds") {
                            members.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
                        }
                    }
                }

                // ── system events ──────────────────────────────────────
                APP_READY -> {
                    putStringOrSkip(data, "appVersion")
                }

                SETTINGS_CHANGED -> {
                    putStringOrSkip(data, "category")
                    val changedKeys = data["changedKeys"]
                    if (changedKeys is List<*>) {
                        putJsonArray("changedKeys") {
                            changedKeys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
                        }
                    }
                }

                // ── fallback: pass everything through ──────────────────
                else -> {
                    for ((key, value) in data) {
                        if (value == null) continue
                        putGenericValue(key, value)
                    }
                }
            }
        }

    // ── private helpers ────────────────────────────────────────────────

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStringOrSkip(
        data: Map<String, Any?>,
        key: String,
    ) {
        val v = data[key] ?: return
        put(key, v.toString())
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putLongOrSkip(
        data: Map<String, Any?>,
        key: String,
    ) {
        val v = data[key] ?: return
        when (v) {
            is Long -> put(key, v)
            is Int -> put(key, v.toLong())
            is Number -> put(key, v.toLong())
            is String -> runCatching { put(key, v.toLong()) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIntOrSkip(
        data: Map<String, Any?>,
        key: String,
    ) {
        val v = data[key] ?: return
        when (v) {
            is Int -> put(key, v)
            is Number -> put(key, v.toInt())
            is String -> runCatching { put(key, v.toInt()) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putGenericValue(
        key: String,
        value: Any,
    ) {
        when (value) {
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            is Float -> put(key, value.toDouble())
            is Number -> put(key, value.toLong())
            is List<*> -> putJsonArray(key) {
                value.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.toString())) }
            }
            is Map<*, *> -> putJsonObject(key) {
                value.forEach { (k, v) ->
                    if (k != null && v != null) putGenericValue(k.toString(), v)
                }
            }
            else -> put(key, value.toString())
        }
    }
}
