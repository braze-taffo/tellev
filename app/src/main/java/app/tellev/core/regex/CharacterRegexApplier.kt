package app.tellev.core.regex

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

object CharacterRegexApplier {
    private const val NORMAL_PROCESSING_VERSION = 1
    private const val NORMAL_PROCESSING_KEY = "tellev_regex_normal_versions"
    private const val USER_INPUT = 1
    private const val AI_OUTPUT = 2
    private const val WORLD_INFO = 5

    enum class RegexPhase { Normal, Display, Prompt }

    data class RegexExecutionContext(
        val character: CharacterCard?,
        val preset: GenerationPreset? = null,
        val role: MessageRole,
        val userName: String = "User",
        /** Distance from the newest visible, non-system message. */
        val depth: Int = 0,
        val isEdit: Boolean = false,
        val phase: RegexPhase,
        val globalScripts: JsonArray = JsonArray(emptyList()),
        val onDiagnostic: ((RegexDiagnostic) -> Unit)? = null,
        /** Full, explicitly scoped macro resolver; never reads an active-chat singleton. */
        val macroExpander: ((String) -> String)? = null,
    )

    data class RegexDiagnostic(
        val scriptName: String,
        val message: String,
    )

    /** A regex script's stable identifier, display name, and card-owned switch state. */
    data class RegexScriptSummary(
        val id: String,
        val name: String,
        val enabled: Boolean,
    )

    fun isNormalProcessed(message: ChatMessage): Boolean =
        ((message.metadata[NORMAL_PROCESSING_KEY] as? JsonArray)
            ?.getOrNull(message.swipeIndex) as? JsonPrimitive)
            ?.intOrNull == NORMAL_PROCESSING_VERSION

    fun markNormalProcessed(message: ChatMessage): ChatMessage {
        val versions = ((message.metadata[NORMAL_PROCESSING_KEY] as? JsonArray)?.toMutableList()
            ?: mutableListOf()).apply {
            while (size <= message.swipeIndex) add(JsonNull)
            this[message.swipeIndex] = JsonPrimitive(NORMAL_PROCESSING_VERSION)
        }
        return message.copy(metadata = JsonObject(message.metadata + (NORMAL_PROCESSING_KEY to JsonArray(versions))))
    }

    /**
     * Summarize the scripts in a `regex_scripts` array into stable
     * [RegexScriptSummary]s. The card's `disabled` field is authoritative.
     */
    fun summarizeScripts(scripts: JsonArray): List<RegexScriptSummary> =
        scripts.mapIndexedNotNull { index, element ->
            val script = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = (script.stringValue("scriptName") ?: script.stringValue("name"))?.takeIf { it.isNotBlank() }
                ?: script.stringValue("findRegex")?.takeIf { it.isNotBlank() }
                ?: UiStrings.get(S.cregex_unnamed_script)
            RegexScriptSummary(scriptIdentifier(script, index), name, script.booleanValue("disabled") != true)
        }

    fun applyForDisplay(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int = 0,
        isEdit: Boolean = false,
        preset: GenerationPreset? = null,
        includeNormal: Boolean = true,
        macroExpander: ((String) -> String)? = null,
    ): String {
        val context = RegexExecutionContext(character, preset, role, userName, depth, isEdit, RegexPhase.Display, macroExpander = macroExpander)
        val normalized = if (includeNormal) apply(text, context.copy(phase = RegexPhase.Normal)) else text
        return apply(normalized, context)
    }

    fun applyNormal(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        preset: GenerationPreset? = null,
        userName: String = "User",
        depth: Int = 0,
        isEdit: Boolean = false,
        macroExpander: ((String) -> String)? = null,
    ): String = apply(text, RegexExecutionContext(character, preset, role, userName, depth, isEdit, RegexPhase.Normal, macroExpander = macroExpander))

    fun applyForPrompt(
        text: String,
        role: MessageRole,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int,
        isEdit: Boolean = false,
        preset: GenerationPreset? = null,
        includeNormal: Boolean = true,
        macroExpander: ((String) -> String)? = null,
    ): String {
        val context = RegexExecutionContext(character, preset, role, userName, depth, isEdit, RegexPhase.Prompt, macroExpander = macroExpander)
        val normalized = if (includeNormal) apply(text, context.copy(phase = RegexPhase.Normal)) else text
        return apply(normalized, context)
    }

    fun applyWorldInfoForPrompt(
        text: String,
        character: CharacterCard?,
        userName: String = "User",
        depth: Int = 0,
        preset: GenerationPreset? = null,
        macroExpander: ((String) -> String)? = null,
    ): String = apply(
        text,
        RegexExecutionContext(character, preset, MessageRole.System, userName, depth, false, RegexPhase.Prompt, macroExpander = macroExpander),
        WORLD_INFO,
    )

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

    fun apply(
        text: String,
        context: RegexExecutionContext,
        forcedPlacement: Int? = null,
    ): String {
        if (text.isEmpty()) return text
        val placement = forcedPlacement ?: when (context.role) {
            MessageRole.User -> USER_INPUT
            MessageRole.Character, MessageRole.Assistant -> AI_OUTPUT
            else -> return text
        }
        val cardScripts = context.character?.raw?.cardDataObject()
            ?.objectValue("extensions")
            ?.arrayValue("regex_scripts")
            ?: JsonArray(emptyList())
        val presetScripts = context.preset?.extensions?.arrayValue("regex_scripts")
            ?: JsonArray(emptyList())
        // Matches ST's effective order and makes conflicts deterministic.
        val scripts = context.globalScripts + presetScripts + cardScripts
        val characterName = context.character?.name.orEmpty().ifBlank { "Character" }

        return scripts.fold(text) { current, scriptElement ->
            val script = scriptElement as? JsonObject ?: return@fold current
            if (script.booleanValue("disabled") == true) return@fold current
            if (!script.intArray("placement").contains(placement)) return@fold current
            if (context.isEdit && script.booleanValue("runOnEdit") != true) return@fold current
            val minDepth = script.intValue("minDepth")
            val maxDepth = script.intValue("maxDepth")
            if (minDepth != null && context.depth < minDepth) return@fold current
            if (maxDepth != null && maxDepth >= 0 && context.depth > maxDepth) return@fold current

            val markdownOnly = script.booleanValue("markdownOnly") == true
            val promptOnly = script.booleanValue("promptOnly") == true
            val appliesInMode = when (context.phase) {
                RegexPhase.Normal -> !markdownOnly && !promptOnly
                RegexPhase.Display -> markdownOnly
                RegexPhase.Prompt -> promptOnly
            }
            if (!appliesInMode) return@fold current
            runScript(script, current, characterName, context.userName, context.onDiagnostic, context.macroExpander)
        }
    }

    private fun runScript(
        script: JsonObject,
        input: String,
        characterName: String,
        userName: String,
        onDiagnostic: ((RegexDiagnostic) -> Unit)?,
        macroExpander: ((String) -> String)?,
    ): String {
        // RP Hub uses a bare pattern plus separate flags. Standard Tavern fields win.
        val standardSource = script.stringValue("findRegex")
        val rawSource = standardSource ?: script.stringValue("regex") ?: return input
        val separateFlags = if (standardSource == null) script.stringValue("flags").orEmpty() else null
        val source = when (script.intValue("substituteRegex") ?: 0) {
            1 -> substituteMacros(rawSource, characterName, userName, escaped = false, macroExpander)
            2 -> substituteMacros(rawSource, characterName, userName, escaped = true, macroExpander)
            else -> rawSource
        }
        val regexSource = javascriptRegexSource(source, separateFlags)
        val ignoreCase = 'i' in regexSource.flags
        // ICU scans every possible start for a leading greedy any-character capture.
        // When its literal suffix is absent, an equivalent literal precheck avoids
        // quadratic work on large HTML messages. Do not rewrite the regex itself.
        val literalSuffix = source.takeIf { it.startsWith("([\\s\\S]*)") }
            ?.removePrefix("([\\s\\S]*)")?.replace("\\/", "/")
            ?.takeIf { it.isNotEmpty() && it.none { c -> c in "\\.[](){}*+?^$|" } }
        if (literalSuffix != null && !input.contains(literalSuffix, ignoreCase)) return input
        // Same idea for the two shapes that dominate preset-shipped rules: a lazy
        // wildcard guarded by a literal (`(?=[\s\S]*?</think>)`) and a literal branch
        // head (`<refine>…`, `<行动选项>…`). Without this, a rule from the newly
        // selected preset backtracks through every tag of the previous preset's
        // message history on the compose thread (switching 思客→Kemini froze the
        // chat for ~20s per message). When every provable required literal is
        // absent the rule cannot match anything, so skipping it is result-neutral.
        val gate = requiredLiterals(regexSource.pattern)
        if (gate != null && gate.none { input.contains(it, ignoreCase) }) return input
        val regex = compileJavascriptRegex(regexSource) ?: run {
            onDiagnostic?.invoke(RegexDiagnostic(
                scriptName = script.stringValue("scriptName").orEmpty().ifBlank { rawSource },
                message = UiStrings.get(S.cregex_diag_invalid_regex),
            ))
            return input
        }
        val flags = regexSource.flags
        val replacement = (script.stringValue("replaceString") ?: script.stringValue("replacement")).orEmpty()
        val trimStrings = script.stringArray("trimStrings")
        val replacer: (MatchResult) -> CharSequence = { match ->
            substituteMacros(expandReplacement(
                replacement = replacement.replace("{{match}}", "$0", ignoreCase = true),
                match = match,
                trimStrings = trimStrings,
                trimExpand = { substituteMacros(it, characterName, userName, escaped = false, macroExpander) },
            ), characterName, userName, escaped = false, macroExpander)
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
        }.getOrElse { error ->
            onDiagnostic?.invoke(RegexDiagnostic(
                scriptName = script.stringValue("scriptName").orEmpty().ifBlank { rawSource },
                message = error.message ?: UiStrings.get(S.cregex_diag_replace_failed),
            ))
            input
        }
    }

    private fun expandReplacement(
        replacement: String,
        match: MatchResult,
        trimStrings: List<String>,
        trimExpand: (String) -> String,
    ): String {
        // ST uses its own replacement callback: $0/ {{match}} and captures;
        // $& remains literal. Missing captures become empty strings.
        val backrefRegex = Regex("""\$(\d+)|\$<([^>]+)>""")
        return backrefRegex.replace(replacement) { ref ->
            val value = when {
                ref.value == "$0" -> match.value
                ref.groups[1] != null -> (ref.groups[1]!!.value.toIntOrNull() ?: -1).let { index ->
                    if (index in 0 until match.groups.size) match.groups[index]?.value.orEmpty() else ""
                }
                ref.groups[2] != null -> runCatching { match.groups[ref.groups[2]!!.value]?.value }.getOrNull().orEmpty()
                else -> ""
            }
            trimStrings.fold(value) { acc, trim -> acc.replace(trimExpand(trim), "") }
        }
    }

    private fun substituteMacros(
        text: String,
        characterName: String,
        userName: String,
        escaped: Boolean,
        macroExpander: ((String) -> String)? = null,
    ): String {
        if (macroExpander != null) {
            if (!escaped) return macroExpander(text)
            // Escape macro results individually, leaving the authored regex intact.
            return Regex("""\{\{[^{}]+\}\}""").replace(text) { Regex.escape(macroExpander(it.value)) }
        }
        val characterValue = if (escaped) Regex.escape(characterName) else characterName
        val userValue = if (escaped) Regex.escape(userName) else userName
        // Escape both closing braces explicitly. Android's ICU regex engine
        // rejects bare `}}` here even though the desktop JVM engine accepts it.
        return Regex("""\{\{char(?:IfNotGroup)?\}\}""", RegexOption.IGNORE_CASE)
            .replace(text) { characterValue }
            .let { value -> Regex("""\{\{user\}\}""", RegexOption.IGNORE_CASE).replace(value) { userValue } }
    }


    /** Literal-pattern text plus its JavaScript flags, split like SillyTavern does. */
    private data class JavascriptRegexSource(val pattern: String, val flags: String)

    /**
     * Literals of which any match of this pattern must contain at least one
     * (OR semantics); null when none can be proven. The regex is only skipped
     * when *every* literal in the set is absent, which is result-neutral:
     * a rule whose required literals are all missing can never match, while
     * ICU would still backtrack through every plausible start position.
     * Evidence is collected across alternation branches and required groups:
     * `(?:</think>|<dream_plot>…)` proves one of those literals must appear —
     * 思客's `思考正则格式化` burned 1.35s per 11KB message in quadratic
     * backtracking exactly when both were absent.
     */
    private fun requiredLiterals(pattern: String): Set<String>? {
        val literals = mutableSetOf<String>()
        for (branch in splitTopLevelAlternation(pattern)) {
            literals.addAll(branchRequiredLiterals(branch))
        }
        return literals.takeIf { it.isNotEmpty() }
    }

    private val regexMetachars = ".^$*+?()[]{}|\\"

    private fun isQuantifier(c: Char?): Boolean = c == '*' || c == '+' || c == '?'

    /**
     * All provable required literals in one alternation branch (OR evidence):
     * consecutive non-quantified characters and escaped punctuation runs
     * (≥2 chars each), plus the evidence of required and positive-lookaround
     * groups. Optional groups and negative lookarounds contribute nothing.
     */
    private fun branchRequiredLiterals(branch: String): Set<String> {
        val literals = mutableSetOf<String>()
        var i = 0
        val run = StringBuilder()
        fun flushRun() {
            if (run.length >= 2) literals.add(run.toString())
            run.setLength(0)
        }
        while (i < branch.length) {
            val c = branch[i]
            when {
                c == '\\' -> {
                    val next = branch.getOrNull(i + 1)
                    if (next == null || next.isLetterOrDigit()) {
                        flushRun() // \d, \s, \1… — class or control, not input text
                        i += 2
                    } else if (isQuantifier(branch.getOrNull(i + 2))) {
                        flushRun() // (X)* — X never required as a run
                        i += 3
                    } else {
                        run.append(next)
                        i += 2
                    }
                }
                c in regexMetachars -> {
                    flushRun()
                    when (c) {
                        '^', '$' -> i++
                        '.' -> {
                            // Swallow the lazy/greedy quantifier that almost always follows.
                            var j = i + 1
                            while (branch.getOrNull(j) == '*' || branch.getOrNull(j) == '+') j++
                            if (branch.getOrNull(j) == '?') j++
                            i = j
                        }
                        '[' -> i = skipCharacterClass(branch, i)
                        '{' -> {
                            // {n,m} repeats the previous element; its digits are not input text.
                            val close = branch.indexOf('}', i)
                            i = if (close >= 0) close + 1 else branch.length
                        }
                        '(' -> {
                            val group = groupRequirement(branch, i)
                            if (group is GroupRequirement.Contents) {
                                literals.addAll(group.literals)
                            }
                            i = group.after
                        }
                        else -> i++
                    }
                }
                else -> {
                    if (isQuantifier(branch.getOrNull(i + 1))) {
                        // The quantifier binds to this (not yet appended) character,
                        // so the run collected so far is untouched: `xyb*c` proves `xy`.
                        flushRun()
                        i += 2
                    } else {
                        run.append(c)
                        i++
                    }
                }
            }
        }
        flushRun()
        return literals
    }

    private sealed interface GroupRequirement {
        /** Index just past the group, including any quantifier. */
        val after: Int

        /**
         * [literals] is the group's OR evidence: at least one of them must be
         * present in any input the group (or its positive lookaround) matches.
         */
        data class Contents(override val after: Int, val literals: Set<String>) : GroupRequirement

        /** Optional or negative-lookaround group: nothing inside is required. */
        data class Skipped(override val after: Int) : GroupRequirement
    }

    private fun groupRequirement(branch: String, openIndex: Int): GroupRequirement {
        val body = balancedGroupBody(branch, openIndex)
            ?: return GroupRequirement.Skipped(branch.length)
        var after = openIndex + body.length + 2
        val quantifier = branch.getOrNull(after)
        val optional = quantifier == '*' || quantifier == '?'
        if (quantifier != null && quantifier in "*+?") after++

        // Negative lookarounds require the opposite: their literals must NOT appear.
        if (body.startsWith("?!") || body.startsWith("?<!")) return GroupRequirement.Skipped(after)
        val inner = when {
            body.startsWith("?=") -> body.substring(2)
            body.startsWith("?<=") -> body.substring(3)
            body.startsWith("?:") -> body.substring(2)
            body.startsWith("?<") -> body.substringAfter('>', "")
            body.startsWith("?'") -> body.substringAfter('\'', "")
            body.startsWith("?") -> return GroupRequirement.Skipped(after) // unknown extension
            else -> body
        }
        // Optional groups never force their contents; required groups (and the
        // positive lookarounds unwrapped above) prove at least one inner literal.
        val literals = if (optional || inner.isEmpty()) {
            emptySet()
        } else {
            requiredLiterals(inner) ?: emptySet()
        }
        return GroupRequirement.Contents(after, literals)
    }

    /** Index just past the character class starting at [open]; skips `\]` escapes. */
    private fun skipCharacterClass(s: String, open: Int): Int {
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                ']' -> return i + 1
            }
            i++
        }
        return i
    }

    /** Body of the balanced group starting at `openIndex`'s `(`, or null when unbalanced. */
    private fun balancedGroupBody(pattern: String, openIndex: Int): String? {
        var depth = 0
        var inClass = false
        var i = openIndex
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return pattern.substring(openIndex + 1, i)
                }
            }
            i++
        }
        return null
    }

    /** Splits on `|` outside classes and groups; a pattern without one is a single branch. */
    private fun splitTopLevelAlternation(pattern: String): List<String> {
        val branches = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> {
                    current.append(c)
                    if (i + 1 < pattern.length) current.append(pattern[i + 1])
                    i += 2
                    continue
                }
                inClass -> {
                    current.append(c)
                    if (c == ']') inClass = false
                }
                c == '[' -> {
                    current.append(c)
                    inClass = true
                }
                c == '(' -> {
                    current.append(c)
                    depth++
                }
                c == ')' -> {
                    current.append(c)
                    depth--
                }
                c == '|' && depth == 0 -> {
                    branches.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        branches.add(current.toString())
        return branches
    }

    private fun javascriptRegexSource(source: String, separateFlags: String? = null): JavascriptRegexSource =
        // RP Hub style entries keep the pattern and its flags in separate fields.
        if (separateFlags != null) JavascriptRegexSource(source, separateFlags) else splitJavascriptRegex(source)

    /**
     * Mirrors `regexFromString` from SillyTavern (`public/scripts/utils.js`):
     * `input.match(/(\/?)(.+)\1([a-z]*)/i)`.
     *
     * The backreference splits the literal at the **last** slash, so a pattern
     * is allowed to contain unescaped slashes — presets ship rules such as
     * `/<dream_big_discuss>\s*([\s\S]*?)\s*</dream_big_discuss>/gm` and
     * `/(</dream_plot>|</paragraph>)/g`. Splitting at the first slash instead
     * turns the rest of the pattern into a bogus flag string, which drops the
     * whole rule: the raw tags then leak into the chat (思客大调查 rendered as
     * plain text, `</dream_after_format> </dream_plot>` left at the tail).
     *
     * When the trailing letters are not a legal SillyTavern flag set, SillyTavern
     * compiles the entire input as a pattern (`RegExp(input)`, slashes included)
     * and drops the flags; we keep the same shape so such rules stay no-ops
     * rather than silently becoming a different match.
     */
    private fun splitJavascriptRegex(source: String): JavascriptRegexSource {
        val trimmed = source.trim()
        if (!trimmed.startsWith("/")) return JavascriptRegexSource(trimmed, "")

        val slashIndex = trimmed.lastIndexOf('/')
        // `(.+)` needs at least one character between the opening slash and the
        // delimiter; without one SillyTavern compiles the whole input as pattern.
        if (slashIndex <= 1) return JavascriptRegexSource(trimmed, "")

        // The outer parser regex is case-insensitive, so `[a-z]*` also accepts
        // uppercase letters before the validity check rejects them.
        val flags = trimmed.substring(slashIndex + 1).takeWhile { it in 'a'..'z' || it in 'A'..'Z' }
        if (flags.isNotEmpty() && !isSillyTavernFlagSet(flags)) {
            return JavascriptRegexSource(trimmed, "")
        }
        return JavascriptRegexSource(trimmed.substring(1, slashIndex), flags)
    }

    private fun isSillyTavernFlagSet(flags: String): Boolean =
        flags.all { it in "gmixXsuUAJ" } && flags.toSet().size == flags.length

    private fun compileJavascriptRegex(source: JavascriptRegexSource): Regex? {
        var pattern = source.pattern
        if (pattern.isEmpty()) return null
        val flags = source.flags

        // A malformed/unknown JavaScript flag invalidates only this script.
        // Kotlin cannot emulate every new JS regex feature, but accepting the
        // standard flags here keeps diagnostics deterministic and isolated.
        if (flags.any { it !in "dgimsuvy" } || flags.toSet().size != flags.length) return null

        if ('u' in flags) {
            pattern = translateCodePointEscapes(pattern) ?: return null
        }

        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }

        return runCatching { Regex(pattern, options) }.getOrNull()
    }

    /** Translate only unescaped JS Unicode code points to JVM/ICU's shared syntax. */
    private fun translateCodePointEscapes(pattern: String): String? {
        val result = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '\\' && i + 1 < pattern.length) {
                if (pattern.startsWith("\\u{", i)) {
                    val end = pattern.indexOf('}', i + 3)
                    if (end < 0) return null
                    val code = pattern.substring(i + 3, end).toIntOrNull(16) ?: return null
                    if (code !in 0..0x10FFFF) return null
                    result.append("\\x{").append(code.toString(16)).append('}')
                    i = end + 1
                } else {
                    result.append(pattern[i]).append(pattern[i + 1])
                    i += 2
                }
            } else result.append(pattern[i++])
        }
        return result.toString()
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
