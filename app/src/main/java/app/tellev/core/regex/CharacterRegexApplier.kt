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
    private const val WORLD_INFO = 5

    private enum class Mode { Display, Prompt }

    /** A regex script's stable identifier, display name, and card-owned switch state. */
    data class RegexScriptSummary(
        val id: String,
        val name: String,
        val enabled: Boolean,
    )

    /**
     * Summarize the scripts in a `regex_scripts` array into stable
     * [RegexScriptSummary]s. The card's `disabled` field is authoritative.
     */
    fun summarizeScripts(scripts: JsonArray): List<RegexScriptSummary> =
        scripts.mapIndexedNotNull { index, element ->
            val script = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = script.stringValue("scriptName")?.takeIf { it.isNotBlank() }
                ?: script.stringValue("findRegex")?.takeIf { it.isNotBlank() }
                ?: "未命名脚本"
            RegexScriptSummary(scriptIdentifier(script, index), name, script.booleanValue("disabled") != true)
        }

    fun applyForDisplay(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int = 0,
        isEdit: Boolean = false,
    ): String = apply(text, role, character, userName, Mode.Display, depth, isEdit)

    fun applyForPrompt(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int,
        isEdit: Boolean = false,
    ): String = apply(text, role, character, userName, Mode.Prompt, depth, isEdit)

    fun applyWorldInfoForPrompt(
        text: String,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int = 0,
    ): String = apply(text, MessageRole.System, character, userName, Mode.Prompt, depth, false, WORLD_INFO)

    /** Persist the authoritative switch in the character card itself. */
    fun withScriptEnabled(card: CharacterCard, scriptId: String, enabled: Boolean): CharacterCard {
        val raw = card.raw
        val data = raw.cardDataObject()
        val extensions = data.objectValue("extensions") ?: return card
        val scripts = extensions.arrayValue("regex_scripts") ?: return card
        var changed = false
        val patchedScripts = JsonArray(scripts.mapIndexed { index, element ->
            val script = element as? JsonObject ?: return@mapIndexed element
            if (scriptIdentifier(script, index) != scriptId) return@mapIndexed element
            changed = true
            JsonObject(script + ("disabled" to JsonPrimitive(!enabled)))
        })
        if (!changed) return card
        val patchedExtensions = JsonObject(extensions + ("regex_scripts" to patchedScripts))
        val patchedData = JsonObject(data + ("extensions" to patchedExtensions))
        val patchedRaw = if (raw["data"] is JsonObject) {
            JsonObject(raw + ("data" to patchedData))
        } else {
            patchedData
        }
        return card.copy(raw = patchedRaw)
    }

    private fun apply(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        userName: String,
        mode: Mode,
        depth: Int,
        isEdit: Boolean,
        forcedPlacement: Int? = null,
    ): String {
        if (text.isBlank() || character == null) return text
        val placement = forcedPlacement ?: when (role) {
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
            if (!script.intArray("placement").contains(placement)) return@fold current
            if (isEdit && script.booleanValue("runOnEdit") != true) return@fold current
            val minDepth = script.intValue("minDepth")
            val maxDepth = script.intValue("maxDepth")
            if (minDepth != null && depth < minDepth) return@fold current
            if (maxDepth != null && maxDepth >= 0 && depth > maxDepth) return@fold current

            val markdownOnly = script.booleanValue("markdownOnly") == true
            val promptOnly = script.booleanValue("promptOnly") == true
            val appliesInMode = when (mode) {
                Mode.Display -> markdownOnly || (!markdownOnly && !promptOnly)
                Mode.Prompt -> promptOnly || (!markdownOnly && !promptOnly)
            }
            if (!appliesInMode) return@fold current
            runScript(script, current, character.name, userName)
        }
    }

    private fun runScript(script: JsonObject, input: String, characterName: String, userName: String): String {
        val rawSource = script.stringValue("findRegex") ?: return input
        val source = when (script.intValue("substituteRegex") ?: 0) {
            1 -> substituteMacros(rawSource, characterName, userName, escaped = false)
            2 -> substituteMacros(rawSource, characterName, userName, escaped = true)
            else -> rawSource
        }
        val regex = parseJavascriptRegex(source) ?: return input
        val flags = javascriptFlags(source)
        val replacement = substituteMacros(
            script.stringValue("replaceString").orEmpty(),
            characterName,
            userName,
            escaped = false,
        )
        val trimStrings = script.stringArray("trimStrings")
            .map { substituteMacros(it, characterName, userName, escaped = false) }
        val replacer: (MatchResult) -> CharSequence = { match ->
            expandReplacement(
                replacement = replacement.replace("{{match}}", "$0", ignoreCase = true),
                match = match,
                trimStrings = trimStrings,
            )
        }
        return runCatching {
            // JavaScript String.replace(): g replaces all matches; y without g
            // matches only at position 0. When both g and y are present, g
            // takes precedence (replace still replaces all matches).
            when {
                'g' in flags -> regex.replace(input, replacer)
                'y' in flags -> {
                    val first = regex.find(input)?.takeIf { it.range.first == 0 } ?: return@runCatching input
                    input.replaceRange(first.range, replacer(first))
                }
                else -> {
                    val first = regex.find(input) ?: return@runCatching input
                    input.replaceRange(first.range, replacer(first))
                }
            }
        }.getOrDefault(input)
    }

    private fun javascriptFlags(source: String): String {
        val trimmed = source.trim()
        if (!trimmed.startsWith("/")) return ""
        val closing = findClosingSlash(trimmed)
        return if (closing < 0) "" else trimmed.substring(closing + 1)
    }
    private fun expandReplacement(
        replacement: String,
        match: MatchResult,
        trimStrings: List<String>,
    ): String {
        // JavaScript replacement syntax: $& = full match, $0 = full match
        // (tellev extension), $1-$9 = capture groups, $<name> = named group.
        val backrefRegex = Regex("""\$(\d{1,2})|\$<([^>]+)>|\$0|\$&""")
        return backrefRegex.replace(replacement) { ref ->
            val value = when {
                ref.value == "$0" -> match.value
                ref.value == "$&" -> match.value
                ref.groups[1] != null -> match.groups[ref.groups[1]!!.value.toIntOrNull() ?: -1]?.value.orEmpty()
                ref.groups[2] != null -> match.groups[ref.groups[2]!!.value]?.value.orEmpty()
                else -> ""
            }
            trimStrings.fold(value) { acc, trim -> acc.replace(trim, "") }
        }
    }

    private fun substituteMacros(
        text: String,
        characterName: String,
        userName: String,
        escaped: Boolean,
    ): String {
        val characterValue = if (escaped) Regex.escape(characterName) else characterName
        val userValue = if (escaped) Regex.escape(userName) else userName
        // Escape both closing braces explicitly. Android's ICU regex engine
        // rejects bare `}}` here even though the desktop JVM engine accepts it.
        return Regex("""\{\{char(?:IfNotGroup)?\}\}""", RegexOption.IGNORE_CASE)
            .replace(text, characterValue)
            .let { Regex("""\{\{user\}\}""", RegexOption.IGNORE_CASE).replace(it, userValue) }
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
        var inCharacterClass = false
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
            if (char == '[') inCharacterClass = true
            if (char == ']') inCharacterClass = false
            if (char == '/' && !inCharacterClass) return index
        }
        return -1
    }

    private fun JsonObject.cardDataObject(): JsonObject =
        (this["data"] as? JsonObject) ?: this

    /**
     * Stable per-script key. Prefers the card's own `id`; falls back to an
     * index-based key so scripts without an id can still be toggled (though
     * such toggles won't survive card reordering).
     */
    private fun scriptIdentifier(script: JsonObject, index: Int): String =
        script.stringValue("id")?.takeIf { it.isNotBlank() } ?: "idx:$index"

    private fun JsonObject.objectValue(key: String): JsonObject? =
        this[key] as? JsonObject

    private fun JsonObject.arrayValue(key: String): JsonArray? =
        this[key] as? JsonArray

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.booleanValue(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.intValue(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

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
