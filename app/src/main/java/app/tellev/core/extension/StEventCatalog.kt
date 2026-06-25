package app.tellev.core.extension

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object StEventCatalog {

    // ── Chat / message events ──────────────────────────────────────────
    const val MESSAGE_RECEIVED = "message_received"
    const val MESSAGE_SENT = "message_sent"
    const val MESSAGE_EDITED = "message_edited"
    const val MESSAGE_DELETED = "message_deleted"
    const val MESSAGE_SWIPED = "message_swiped"
    const val MESSAGE_UPDATED = "message_updated"
    const val USER_MESSAGE_RENDERED = "user_message_rendered"
    const val CHARACTER_MESSAGE_RENDERED = "character_message_rendered"

    // ── Character events ───────────────────────────────────────────────
    const val CHARACTER_SELECTED = "character_selected"
    const val CHARACTER_CREATED = "character_created"
    const val CHARACTER_EDITED = "character_edited"
    const val CHARACTER_DELETED = "characterDeleted"
    const val CHARACTER_FIRST_MESSAGE_SELECTED = "character_first_message_selected"
    const val CHARACTER_DUPLICATED = "character_duplicated"
    const val CHARACTER_RENAMED = "character_renamed"
    const val CHARACTER_IMPORTED = "character_imported"
    const val CHARACTER_EXPORTED = "character_exported"

    // ── Chat-session events ────────────────────────────────────────────
    const val CHAT_CHANGED = "chat_id_changed"
    const val CHAT_LOADED = "chatLoaded"
    const val CHAT_CREATED = "chat_created"
    const val CHAT_DELETED = "chat_deleted"
    const val CHAT_RENAMED = "chat_renamed"
    const val CHAT_IMPORTED = "chat_imported"
    const val CHAT_EXPORTED = "chat_exported"

    // ── Generation events ──────────────────────────────────────────────
    const val GENERATION_AFTER_COMMANDS = "GENERATION_AFTER_COMMANDS"
    const val GENERATION_STARTED = "generation_started"
    const val GENERATION_ENDED = "generation_ended"
    const val GENERATION_STOPPED = "generation_stopped"
    const val CHAT_COMPLETION_SETTINGS_READY = "chat_completion_settings_ready"
    const val CHAT_COMPLETION_PROMPT_READY = "chat_completion_prompt_ready"
    const val GENERATE_BEFORE_COMBINE_PROMPTS = "generate_before_combine_prompts"
    const val GENERATE_AFTER_COMBINE_PROMPTS = "generate_after_combine_prompts"
    const val GENERATE_AFTER_DATA = "generate_after_data"

    // ── World-info events ──────────────────────────────────────────────
    const val WORLD_INFO_ACTIVATED = "world_info_activated"
    const val WORLD_INFO_CHANGED = "worldinfo_updated"

    // ── UI events ──────────────────────────────────────────────────────
    const val EXTENSION_SETTINGS_OPENED = "extension_settings_opened"
    const val EXTENSION_SETTINGS_CLOSED = "extension_settings_closed"

    // ── Group events ───────────────────────────────────────────────────
    const val GROUP_SELECTED = "group_selected"
    const val GROUP_CHAT_STARTED = "group_chat_started"
    const val GROUP_UPDATED = "group_updated"

    // ── System events ──────────────────────────────────────────────────
    const val APP_INITIALIZED = "app_initialized"
    const val APP_READY = "app_ready"
    const val SETTINGS_LOADED = "settings_loaded"
    const val SETTINGS_CHANGED = "settings_updated"
    const val SETTINGS_UPDATED = "settings_updated"
    const val EXTENSIONS_FIRST_LOAD = "extensions_first_load"
    const val EXTENSION_SETTINGS_LOADED = "extension_settings_loaded"
    const val MAIN_API_CHANGED = "main_api_changed"
    const val PERSONA_CHANGED = "persona_changed"

    /** Every known event name, useful for introspection / debug UIs. */
    val ALL_EVENTS: List<String> = listOf(
        MESSAGE_RECEIVED, MESSAGE_SENT, MESSAGE_EDITED, MESSAGE_DELETED, MESSAGE_SWIPED,
        MESSAGE_UPDATED, USER_MESSAGE_RENDERED, CHARACTER_MESSAGE_RENDERED,
        CHARACTER_SELECTED, CHARACTER_CREATED, CHARACTER_EDITED, CHARACTER_DELETED,
        CHARACTER_IMPORTED, CHARACTER_EXPORTED, CHARACTER_DUPLICATED, CHARACTER_RENAMED,
        CHARACTER_FIRST_MESSAGE_SELECTED,
        CHAT_CHANGED, CHAT_LOADED, CHAT_CREATED, CHAT_DELETED, CHAT_RENAMED,
        CHAT_IMPORTED, CHAT_EXPORTED,
        GENERATION_AFTER_COMMANDS, GENERATION_STARTED, GENERATION_ENDED, GENERATION_STOPPED,
        CHAT_COMPLETION_SETTINGS_READY, CHAT_COMPLETION_PROMPT_READY, GENERATE_BEFORE_COMBINE_PROMPTS,
        GENERATE_AFTER_COMBINE_PROMPTS, GENERATE_AFTER_DATA,
        WORLD_INFO_ACTIVATED, WORLD_INFO_CHANGED,
        EXTENSION_SETTINGS_OPENED, EXTENSION_SETTINGS_CLOSED,
        GROUP_SELECTED, GROUP_CHAT_STARTED, GROUP_UPDATED,
        APP_INITIALIZED, APP_READY, SETTINGS_LOADED, SETTINGS_CHANGED, SETTINGS_UPDATED,
        EXTENSIONS_FIRST_LOAD, EXTENSION_SETTINGS_LOADED, MAIN_API_CHANGED, PERSONA_CHANGED,
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
                MESSAGE_SWIPED,
                MESSAGE_UPDATED,
                USER_MESSAGE_RENDERED,
                CHARACTER_MESSAGE_RENDERED -> {
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
                CHAT_LOADED,
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
                GROUP_CHAT_STARTED,
                GROUP_UPDATED -> {
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
                APP_INITIALIZED,
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
