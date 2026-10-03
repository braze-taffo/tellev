package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** Edits the card's native extension tree without flattening unknown fields. */
internal object CreationAdvancedAssets {
    /** Four full 24k chunks: generous for real card scripts, bounded for runaway loops (O8). */
    private const val MAX_SCRIPT_TOTAL_CHARS = 96_000

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.argString(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive
        require(primitive?.isString == true) { UiStrings.get(S.creng_arg_not_string, key) }
        return primitive.content
    }

    private fun JsonObject.argBoolean(key: String): Boolean? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive
        val parsed = primitive?.takeIf { !it.isString }?.booleanOrNull
        require(parsed != null) { UiStrings.get(S.creng_arg_not_boolean, key) }
        return parsed
    }

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun CreationSession.extensions(): JsonObject = advancedExtensions.takeIf { it.isNotEmpty() }
        ?: ((originalCard?.raw?.get("data") as? JsonObject)?.get("extensions") as? JsonObject)
        ?: JsonObject(emptyMap())

    private fun CreationSession.scripts(): JsonArray =
        (extensions()["tavern_helper"] as? JsonObject)?.array("scripts") ?: JsonArray(emptyList())

    private fun CreationSession.regexes(): JsonArray = extensions().array("regex_scripts")

    private fun CreationSession.variables(): JsonObject {
        val raw = (extensions()["tavern_helper"] as? JsonObject)?.get("variables")
        if (raw is JsonObject) return raw
        if (raw is JsonArray) return JsonObject(raw.mapNotNull { item ->
            val pair = item as? JsonArray ?: return@mapNotNull null
            val key = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val value = pair.getOrNull(1) ?: return@mapNotNull null
            key to value
        }.toMap())
        return JsonObject(emptyMap())
    }

    private fun CreationSession.withTavernHelper(patch: JsonObject): CreationSession {
        val ext = extensions()
        val helper = ext["tavern_helper"] as? JsonObject ?: JsonObject(emptyMap())
        return copy(advancedExtensions = JsonObject(ext + ("tavern_helper" to JsonObject(helper + patch))),
            updatedAt = System.currentTimeMillis())
    }

    private fun CreationSession.withRegexes(regexes: JsonArray): CreationSession =
        copy(advancedExtensions = JsonObject(extensions() + ("regex_scripts" to regexes)),
            updatedAt = System.currentTimeMillis())

    private fun result(name: String, payload: JsonObject): ToolResult = ToolResult(true, name, payload)

    fun execute(session: CreationSession, call: ToolCallRequest): Pair<CreationSession, ToolResult> {
        require(session.kind == CreationKind.Character) { UiStrings.get(S.creng_asset_character_only) }
        return when (call.name) {
            "list_assets" -> session to result(call.name, buildJsonObject {
                put("scripts", JsonArray(session.scripts().mapNotNull { item ->
                    val script = item as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        put("id", script.string("id").orEmpty())
                        put("name", script.string("name").orEmpty())
                        put("type", script.string("type").orEmpty())
                        put("enabled", (script["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false)
                        put("content_chars", script.string("content")?.length ?: 0)
                    }
                }))
                put("regex_scripts", JsonArray(session.regexes().mapNotNull { item ->
                    val regex = item as? JsonObject ?: return@mapNotNull null
                    buildJsonObject {
                        put("id", regex.string("id").orEmpty())
                        put("name", regex.string("scriptName").orEmpty())
                    }
                }))
                put("variable_keys", JsonArray(session.variables().keys.map(::JsonPrimitive)))
            })

            "read_script" -> {
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_id_empty))
                val script = session.scripts().mapNotNull { it as? JsonObject }
                    .firstOrNull { it.string("id") == id && it.string("type") == "script" }
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_script_not_found, id))
                val content = script.string("content").orEmpty()
                val offset = ((call.arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, content.length)
                val limit = ((call.arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 4000).coerceIn(1, 6000)
                session to result(call.name, buildJsonObject {
                    put("id", id)
                    put("name", script.string("name").orEmpty())
                    put("enabled", (script["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false)
                    put("total_chars", content.length)
                    put("offset", offset)
                    put("content", content.substring(offset, (offset + limit).coerceAtMost(content.length)))
                })
            }

            "upsert_script" -> {
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
                val items = session.scripts().toMutableList()
                val index = items.indexOfFirst { (it as? JsonObject)?.string("id") == id }
                val previous = if (index >= 0) items[index] as? JsonObject
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_script_structure)) else null
                require(previous == null || previous.string("type") == "script") { UiStrings.get(S.creng_asset_id_is_folder) }
                val unknown = call.arguments.keys - setOf("id", "name", "enabled", "content", "mode", "info")
                require(unknown.isEmpty()) { UiStrings.get(S.creng_asset_unknown_script_fields, unknown.joinToString()) }
                val mode = call.arguments.argString("mode") ?: "replace"
                require(mode in setOf("replace", "append")) { UiStrings.get(S.creng_asset_mode_invalid) }
                val name = call.arguments.argString("name") ?: previous?.string("name").orEmpty()
                require(name.isNotBlank()) { UiStrings.get(S.creng_asset_name_required) }
                val enabled = call.arguments.argBoolean("enabled")
                    ?: ((previous?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false)
                val chunk = call.arguments.argString("content") ?: ""
                require(chunk.length <= 24_000) { UiStrings.get(S.creng_asset_content_too_long) }
                val content = if (mode == "append") previous?.string("content").orEmpty() + chunk
                    else if (call.arguments.containsKey("content")) chunk else previous?.string("content").orEmpty()
                // O8: appends used to grow without bound — a looping model
                // could inflate the card script until the session JSON and
                // every later prompt drown in it.
                require(content.length <= MAX_SCRIPT_TOTAL_CHARS) {
                    UiStrings.get(S.creng_asset_content_total_too_long, content.length)
                }
                val updated = JsonObject((previous ?: JsonObject(emptyMap())) + buildMap<String, JsonElement> {
                    put("type", JsonPrimitive("script"))
                    put("id", JsonPrimitive(id))
                    put("name", JsonPrimitive(name))
                    put("enabled", JsonPrimitive(enabled))
                    put("content", JsonPrimitive(content))
                    call.arguments.argString("info")?.let { put("info", JsonPrimitive(it)) }
                })
                if (index >= 0) items[index] = updated else items += updated
                val next = session.withTavernHelper(buildJsonObject { put("scripts", JsonArray(items)) })
                next to result(call.name, buildJsonObject {
                    put("id", id)
                    put("status", if (index >= 0) "updated" else "created")
                    put("content_chars", content.length)
                    put("enabled", enabled)
                })
            }

            "upsert_regex" -> {
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
                val items = session.regexes().toMutableList()
                val index = items.indexOfFirst { (it as? JsonObject)?.string("id") == id }
                val previous = if (index >= 0) items[index] as? JsonObject else null
                val allowed = setOf("id", "scriptName", "findRegex", "replaceString", "trimStrings",
                    "placement", "disabled", "markdownOnly", "promptOnly", "runOnEdit", "substituteRegex",
                    "minDepth", "maxDepth")
                require((call.arguments.keys - allowed).isEmpty()) { UiStrings.get(S.creng_asset_unknown_regex_fields) }
                listOf("scriptName", "findRegex", "replaceString").forEach { call.arguments.argString(it) }
                listOf("disabled", "markdownOnly", "promptOnly", "runOnEdit").forEach { call.arguments.argBoolean(it) }
                for (field in listOf("substituteRegex", "minDepth", "maxDepth")) {
                    val value = call.arguments[field] ?: continue
                    val primitive = value as? JsonPrimitive
                    require(primitive != null && !primitive.isString && primitive.intOrNull != null) { UiStrings.get(S.creng_arg_not_int, field) }
                }
                for (field in listOf("trimStrings", "placement")) {
                    val values = call.arguments[field] ?: continue
                    val array = values as? JsonArray ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_field_not_array, field))
                    require(array.all { item ->
                        val value = item as? JsonPrimitive
                        if (field == "placement") value != null && !value.isString && value.intOrNull != null
                        else value?.isString == true
                    }) { UiStrings.get(S.creng_asset_field_item_invalid, field) }
                }
                val updated = JsonObject((previous ?: buildJsonObject {
                    put("id", id)
                    put("trimStrings", JsonArray(emptyList()))
                    // 新建正则默认停用（对照 upsert_script 的 enabled=false 默认）：
                    // 模型生成的灾难性回溯正则不允许直接入库冻结聊天，试编译通过后
                    // 由用户手动启用。
                    put("disabled", call.arguments.argBoolean("disabled") ?: true)
                    put("markdownOnly", false)
                    put("promptOnly", false)
                    put("runOnEdit", false)
                    put("substituteRegex", 0)
                    put("minDepth", 0)
                    put("maxDepth", 0)
                }) + call.arguments + ("id" to JsonPrimitive(id)))
                require(updated.string("scriptName").orEmpty().isNotBlank()) { UiStrings.get(S.creng_asset_regex_name_required) }
                require(updated.string("findRegex").orEmpty().isNotBlank()) { UiStrings.get(S.creng_asset_regex_find_required) }
                require((updated["placement"] as? JsonArray)?.isNotEmpty() == true) { UiStrings.get(S.creng_asset_regex_placement_required) }
                // 试编译（O3）：与 CharacterRegexApplier.runScript 同一条编译管线，
                // 编译不过的正则入库即静默冻结聊天，必须在工具期就打回重试。
                require(app.tellev.core.regex.CharacterRegexApplier.isCompilable(updated.string("findRegex").orEmpty())) {
                    UiStrings.get(S.creng_asset_regex_not_compilable)
                }
                if (index >= 0) items[index] = updated else items += updated
                val next = session.withRegexes(JsonArray(items))
                next to result(call.name, buildJsonObject {
                    put("id", id)
                    put("status", if (index >= 0) "updated" else "created")
                    updated["disabled"]?.let { put("disabled", it) }
                })
            }

            "read_regex" -> {
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_id_empty))
                val regex = session.regexes().mapNotNull { it as? JsonObject }
                    .firstOrNull { it.string("id") == id }
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_regex_not_found, id))
                session to result(call.name, regex)
            }

            "remove_asset" -> {
                val type = call.arguments.argString("type")
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_id_empty))
                val next = when (type) {
                    "script" -> {
                        val items = session.scripts()
                        require(items.any { (it as? JsonObject)?.string("id") == id }) { UiStrings.get(S.creng_asset_script_not_found, id) }
                        session.withTavernHelper(buildJsonObject {
                            put("scripts", JsonArray(items.filterNot { (it as? JsonObject)?.string("id") == id }))
                        })
                    }
                    "regex" -> {
                        val items = session.regexes()
                        require(items.any { (it as? JsonObject)?.string("id") == id }) { UiStrings.get(S.creng_asset_regex_not_found, id) }
                        session.withRegexes(JsonArray(items.filterNot { (it as? JsonObject)?.string("id") == id }))
                    }
                    "variable" -> {
                        require(id in session.variables()) { UiStrings.get(S.creng_asset_variable_not_found, id) }
                        session.withTavernHelper(buildJsonObject {
                            put("variables", JsonObject(session.variables().filterKeys { it != id }))
                        })
                    }
                    else -> throw IllegalArgumentException(UiStrings.get(S.creng_asset_type_invalid))
                }
                next to result(call.name, buildJsonObject { put("removed", id); put("type", type) })
            }

            "set_variables" -> {
                val patch = call.arguments["values"] as? JsonObject
                    ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_values_not_object))
                val values = JsonObject(session.variables() + patch)
                val next = session.withTavernHelper(buildJsonObject { put("variables", values) })
                next to result(call.name, buildJsonObject {
                    put("keys", JsonArray(patch.keys.map(::JsonPrimitive)))
                    put("total", values.size)
                })
            }

            "read_variables" -> {
                val names = (call.arguments["names"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException(UiStrings.get(S.creng_asset_names_not_array))
                } ?: session.variables().keys.take(20)
                require(names.size <= 30) { UiStrings.get(S.creng_asset_too_many_variables) }
                session to result(call.name, buildJsonObject {
                    put("values", JsonObject(session.variables().filterKeys { it in names }))
                })
            }

            else -> throw IllegalArgumentException(UiStrings.get(S.creng_asset_unknown_tool))
        }
    }
}
