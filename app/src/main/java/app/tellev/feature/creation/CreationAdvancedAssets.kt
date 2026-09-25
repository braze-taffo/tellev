package app.tellev.feature.creation

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
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.argString(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive
        require(primitive?.isString == true) { "$key 必须是字符串" }
        return primitive.content
    }

    private fun JsonObject.argBoolean(key: String): Boolean? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive
        val parsed = primitive?.takeIf { !it.isString }?.booleanOrNull
        require(parsed != null) { "$key 必须是布尔值" }
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
        require(session.kind == CreationKind.Character) { "高级资源只能写入角色卡草稿" }
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
                    ?: throw IllegalArgumentException("id 不能为空")
                val script = session.scripts().mapNotNull { it as? JsonObject }
                    .firstOrNull { it.string("id") == id && it.string("type") == "script" }
                    ?: throw IllegalArgumentException("找不到脚本 $id")
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
                    ?: throw IllegalArgumentException("脚本结构无效") else null
                require(previous == null || previous.string("type") == "script") { "该 id 属于脚本文件夹" }
                val unknown = call.arguments.keys - setOf("id", "name", "enabled", "content", "mode", "info")
                require(unknown.isEmpty()) { "未知脚本字段：${unknown.joinToString()}" }
                val mode = call.arguments.argString("mode") ?: "replace"
                require(mode in setOf("replace", "append")) { "mode 只能是 replace 或 append" }
                val name = call.arguments.argString("name") ?: previous?.string("name").orEmpty()
                require(name.isNotBlank()) { "脚本需要 name" }
                val enabled = call.arguments.argBoolean("enabled")
                    ?: ((previous?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false)
                val chunk = call.arguments.argString("content") ?: ""
                require(chunk.length <= 24_000) { "单次脚本内容过长，请分块追加" }
                val content = if (mode == "append") previous?.string("content").orEmpty() + chunk
                    else if (call.arguments.containsKey("content")) chunk else previous?.string("content").orEmpty()
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
                require((call.arguments.keys - allowed).isEmpty()) { "未知正则字段" }
                listOf("scriptName", "findRegex", "replaceString").forEach { call.arguments.argString(it) }
                listOf("disabled", "markdownOnly", "promptOnly", "runOnEdit").forEach { call.arguments.argBoolean(it) }
                for (field in listOf("substituteRegex", "minDepth", "maxDepth")) {
                    val value = call.arguments[field] ?: continue
                    val primitive = value as? JsonPrimitive
                    require(primitive != null && !primitive.isString && primitive.intOrNull != null) { "$field 必须是整数" }
                }
                for (field in listOf("trimStrings", "placement")) {
                    val values = call.arguments[field] ?: continue
                    val array = values as? JsonArray ?: throw IllegalArgumentException("$field 必须是数组")
                    require(array.all { item ->
                        val value = item as? JsonPrimitive
                        if (field == "placement") value != null && !value.isString && value.intOrNull != null
                        else value?.isString == true
                    }) { "$field 的元素类型无效" }
                }
                val updated = JsonObject((previous ?: buildJsonObject {
                    put("id", id)
                    put("trimStrings", JsonArray(emptyList()))
                    put("disabled", false)
                    put("markdownOnly", false)
                    put("promptOnly", false)
                    put("runOnEdit", false)
                    put("substituteRegex", 0)
                    put("minDepth", 0)
                    put("maxDepth", 0)
                }) + call.arguments + ("id" to JsonPrimitive(id)))
                require(updated.string("scriptName").orEmpty().isNotBlank()) { "正则需要 scriptName" }
                require(updated.string("findRegex").orEmpty().isNotBlank()) { "正则需要 findRegex" }
                require((updated["placement"] as? JsonArray)?.isNotEmpty() == true) { "正则需要 placement 数组" }
                if (index >= 0) items[index] = updated else items += updated
                val next = session.withRegexes(JsonArray(items))
                next to result(call.name, buildJsonObject {
                    put("id", id)
                    put("status", if (index >= 0) "updated" else "created")
                })
            }

            "read_regex" -> {
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("id 不能为空")
                val regex = session.regexes().mapNotNull { it as? JsonObject }
                    .firstOrNull { it.string("id") == id }
                    ?: throw IllegalArgumentException("找不到正则 $id")
                session to result(call.name, regex)
            }

            "remove_asset" -> {
                val type = call.arguments.argString("type")
                val id = call.arguments.argString("id")?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("id 不能为空")
                val next = when (type) {
                    "script" -> {
                        val items = session.scripts()
                        require(items.any { (it as? JsonObject)?.string("id") == id }) { "找不到脚本 $id" }
                        session.withTavernHelper(buildJsonObject {
                            put("scripts", JsonArray(items.filterNot { (it as? JsonObject)?.string("id") == id }))
                        })
                    }
                    "regex" -> {
                        val items = session.regexes()
                        require(items.any { (it as? JsonObject)?.string("id") == id }) { "找不到正则 $id" }
                        session.withRegexes(JsonArray(items.filterNot { (it as? JsonObject)?.string("id") == id }))
                    }
                    "variable" -> {
                        require(id in session.variables()) { "找不到变量 $id" }
                        session.withTavernHelper(buildJsonObject {
                            put("variables", JsonObject(session.variables().filterKeys { it != id }))
                        })
                    }
                    else -> throw IllegalArgumentException("type 只能是 script、regex 或 variable")
                }
                next to result(call.name, buildJsonObject { put("removed", id); put("type", type) })
            }

            "set_variables" -> {
                val patch = call.arguments["values"] as? JsonObject
                    ?: throw IllegalArgumentException("values 必须是 JSON 对象")
                val values = JsonObject(session.variables() + patch)
                val next = session.withTavernHelper(buildJsonObject { put("variables", values) })
                next to result(call.name, buildJsonObject {
                    put("keys", JsonArray(patch.keys.map(::JsonPrimitive)))
                    put("total", values.size)
                })
            }

            "read_variables" -> {
                val names = (call.arguments["names"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("names 必须是字符串数组")
                } ?: session.variables().keys.take(20)
                require(names.size <= 30) { "单次最多读取 30 个变量" }
                session to result(call.name, buildJsonObject {
                    put("values", JsonObject(session.variables().filterKeys { it in names }))
                })
            }

            else -> throw IllegalArgumentException("未知高级资源工具")
        }
    }
}
