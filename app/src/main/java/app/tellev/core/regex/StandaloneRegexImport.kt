package app.tellev.core.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Parses a SillyTavern standalone regex export into script objects.
 *
 * Accepts one script object or a raw JSON array (bulk export). Each script
 * gets a new id, matching SillyTavern import. Character-card documents are
 * rejected so their embedded `regex_scripts` are not copied into the global list.
 */
object StandaloneRegexImport {
    fun parse(text: String): List<JsonObject> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("独立正则 JSON 无法解析", error)
        }
        val scripts = when (root) {
            is JsonArray -> root.map { element ->
                element as? JsonObject
                    ?: throw IllegalArgumentException("每条独立正则必须是 JSON 对象")
            }
            is JsonObject -> listOf(root)
            else -> throw IllegalArgumentException("独立正则必须是 JSON 对象或数组")
        }
        return scripts.map(::normalize)
    }

    private fun normalize(script: JsonObject): JsonObject {
        if (script.isCharacterCardDocument()) {
            throw IllegalArgumentException("不能把角色卡当作独立正则导入")
        }
        val name = script.string("scriptName") ?: script.string("name")
            ?: throw IllegalArgumentException("正则缺少名称")
        val pattern = script.string("findRegex") ?: script.string("regex")?.let { regex ->
            // RP Hub keeps the raw pattern and flags apart. ST stores one
            // `/pattern/flags` literal; the slash we append is the delimiter,
            // so slashes inside the pattern stay in the pattern.
            "/$regex/${script.string("flags").orEmpty()}"
        } ?: throw IllegalArgumentException("正则缺少查找表达式")
        val replace = script.stringRaw("replaceString")
            ?: script.stringRaw("replacement")
            ?: ""
        return JsonObject(
            script +
                ("id" to JsonPrimitive(UUID.randomUUID().toString())) +
                ("scriptName" to JsonPrimitive(name)) +
                ("findRegex" to JsonPrimitive(pattern)) +
                ("replaceString" to JsonPrimitive(replace)),
        )
    }

    /** A card has `spec` and/or `data.extensions.regex_scripts`. A script has neither. */
    private fun JsonObject.isCharacterCardDocument(): Boolean {
        val spec = (this["spec"] as? JsonPrimitive)?.contentOrNull
        if (!spec.isNullOrBlank()) return true
        val extensions = (this["data"] as? JsonObject)?.get("extensions") as? JsonObject ?: return false
        return extensions["regex_scripts"] is JsonArray
    }

    private fun JsonObject.string(key: String): String? =
        stringRaw(key)?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringRaw(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        return value.content
    }
}
