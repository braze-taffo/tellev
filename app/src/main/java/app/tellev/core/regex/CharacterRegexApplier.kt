package app.tellev.core.regex

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object CharacterRegexApplier {
    private const val USER_INPUT = 1
    private const val AI_OUTPUT = 2

    fun applyForDisplay(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
    ): String {
        if (text.isBlank() || character == null) return text
        val placement = when (role) {
            MessageRole.User -> USER_INPUT
            MessageRole.Character, MessageRole.Assistant -> AI_OUTPUT
            else -> return text
        }

        val scripts = character.raw.cardDataObject()
            .objectValue("extensions")
            ?.arrayValue("regex_scripts")
            ?: return text

        return scripts.fold(text) { current, scriptElement ->
            val script = scriptElement as? JsonObject ?: return@fold current
            if (script.booleanValue("disabled") == true) return@fold current
            if (script.booleanValue("promptOnly") == true) return@fold current
            if (!script.intArray("placement").contains(placement)) return@fold current
            runScript(script, current)
        }
    }

    private fun runScript(script: JsonObject, input: String): String {
        val parsedRegex = parseJavascriptRegex(script.stringValue("findRegex") ?: return input)
            ?: return input
        val replacement = script.stringValue("replaceString").orEmpty()
        val trimStrings = script.stringArray("trimStrings")

        return runCatching {
            parsedRegex.replace(input) { match ->
                expandReplacement(
                    replacement = replacement.replace("{{match}}", "$0", ignoreCase = true),
                    match = match,
                    trimStrings = trimStrings,
                )
            }
        }.getOrDefault(input)
    }

    private fun expandReplacement(
        replacement: String,
        match: MatchResult,
        trimStrings: List<String>,
    ): String {
        val backrefRegex = Regex("""\$(\d{1,2})|\$<([^>]+)>|\$0""")
        return backrefRegex.replace(replacement) { ref ->
            val value = when {
                ref.value == "$0" -> match.value
                ref.groups[1] != null -> match.groups[ref.groups[1]!!.value.toIntOrNull() ?: -1]?.value.orEmpty()
                ref.groups[2] != null -> match.groups[ref.groups[2]!!.value]?.value.orEmpty()
                else -> ""
            }
            trimStrings.fold(value) { acc, trim -> acc.replace(trim, "") }
        }
    }

    private fun parseJavascriptRegex(source: String): Regex? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null

        val (pattern, flags) = if (trimmed.startsWith("/")) {
            val slashIndex = findClosingSlash(trimmed)
            if (slashIndex <= 0) {
                trimmed to ""
            } else {
                trimmed.substring(1, slashIndex) to trimmed.substring(slashIndex + 1)
            }
        } else {
            trimmed to ""
        }

        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }

        return runCatching { Regex(pattern, options) }.getOrNull()
    }

    private fun findClosingSlash(value: String): Int {
        var escaped = false
        for (index in 1 until value.length) {
            val char = value[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '/') return index
        }
        return -1
    }

    private fun JsonObject.cardDataObject(): JsonObject =
        (this["data"] as? JsonObject) ?: this

    private fun JsonObject.objectValue(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.arrayValue(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.booleanValue(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.intArray(key: String): List<Int> =
        when (val value = this[key]) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.intOrNull }
            is JsonPrimitive -> value.jsonPrimitive.intOrNull?.let(::listOf).orEmpty()
            else -> emptyList()
        }

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()
}
