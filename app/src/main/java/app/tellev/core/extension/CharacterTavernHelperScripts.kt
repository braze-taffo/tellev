package app.tellev.core.extension

import app.tellev.core.model.CharacterCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts executable scripts embedded in a character card's
 * `data.extensions.tavern_helper.scripts` tree, compatible with the
 * JS-Slash-Runner (TavernHelper) v4.x script schema.
 *
 * Schema reference (JS-Slash-Runner `src/type/scripts.ts`):
 * - Script: `{ type: 'script', enabled, name, id, content, ... }`
 * - ScriptFolder: `{ type: 'folder', enabled, name, id, scripts: [...] }`
 *
 * Legacy backward format (`src/type/backward.ts`):
 * - ScriptItem: `{ type: 'script', value: ScriptData }` where ScriptData has `content`
 * - Legacy folder: `{ type: 'folder', value: ScriptData[] }`
 *
 * Key compatibility notes:
 * - `enabled` defaults to **false** (matches JSR). A script without `enabled: true`
 *   is NOT executed.
 * - `disabled` field is **not recognized** (JSR strips unknown keys).
 * - Content field is **only** `content` (not `value`/`source`/`code`).
 * - `type` must be `'script'` to be executable (`'javascript'`/`'js'` are rejected).
 * - Folder children live under `scripts` (not `children`/`items`).
 * - A disabled current-format folder gates its entire subtree (no per-child override).
 * - Legacy wrappers take `enabled` from their nested ScriptData; legacy folders
 *   are enabled by migration and do not have an outer `enabled` field.
 */
object CharacterTavernHelperScripts {
    data class Script(
        val id: String,
        val name: String,
        val content: String,
    )

    fun extract(character: CharacterCard): List<Script> {
        val extensions = character.extensionObject()
        val scripts = mutableListOf<Script>()

        // Current location: data.extensions.tavern_helper.scripts
        val tavernHelper = extensions["tavern_helper"]
        val nestedScripts = tavernHelper.asObjectOrNull()?.get("scripts")
        if (nestedScripts != null) {
            collect(nestedScripts, scripts, "tavern_helper.scripts")
        } else if (tavernHelper != null) {
            // tavern_helper might be stored as an array of [key, value] pairs (old format)
            collect(tavernHelper, scripts, "tavern_helper")
        }

        // Legacy location: data.extensions.TavernHelper_scripts (migrated by JSR, but read here for compat)
        val legacyScripts = extensions["TavernHelper_scripts"]
        if (legacyScripts != null) {
            collect(legacyScripts, scripts, "TavernHelper_scripts")
        }

        return scripts
    }

    fun buildScriptSource(character: CharacterCard): String {
        val scripts = extract(character)
        return buildString {
            scripts.forEachIndexed { index, script ->
                if (index > 0) append("\n\n")
                val safeName = script.name.replace(Regex("[\\r\\n]+"), " ").ifBlank { script.id }
                val safeSource = "tellev-character-${character.id}-${script.id}"
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                if (isModuleScript(script.content)) {
                    // ES module scripts (import/export) cannot be wrapped in IIFEs.
                    // Emit as-is; errors are caught by the global error handlers.
                    append("// $safeName\n")
                    append("//# sourceURL=$safeSource.js\n")
                    append(script.content)
                    append("\n")
                } else {
                    append("// $safeName\n")
                    append(";(function(){\n")
                    append("try{\n")
                    append(script.content)
                    append("\n}catch(e){if(window.Tellev){tellevNative.log('error','")
                    append(safeName.replace("'", "\\'"))
                    append(": '+e);}}\n")
                    append("})();\n")
                    append("//# sourceURL=$safeSource.js\n")
                }
            }
            // Character-scope variables are appended at the END so that in module
            // scripts, import declarations (which are hoisted) come first. The
            // vars are read lazily by event handlers, not during initial execution.
            append("\n")
            append(buildCharacterVarsInit(character))
        }
    }

    /**
     * Build JS that populates `_thScopedVariables.character` with the card's
     * `tavern_helper.variables`. Returns empty string when the card has none.
     */
    private fun buildCharacterVarsInit(character: CharacterCard): String {
        val vars = extractCharacterVariables(character) ?: return ""
        val jsonStr = vars.toString()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "try{if(typeof _thScopedVariables!=='undefined'){_thScopedVariables.character=JSON.parse('$jsonStr');}}catch(e){}\n\n"
    }

    /**
     * Extract `data.extensions.tavern_helper.variables` as a JsonObject.
     * Returns null when the card has no tavern_helper variables.
     */
    private fun extractCharacterVariables(character: CharacterCard): JsonObject? {
        val extensions = character.extensionObject()
        val tavernHelper = extensions["tavern_helper"]?.asObjectOrNull() ?: return null
        val variables = tavernHelper["variables"]
        return when {
            variables is JsonObject -> variables
            variables is JsonArray -> {
                val map = variables.mapNotNull { element ->
                    val pair = element as? JsonArray ?: return@mapNotNull null
                    val key = pair.getOrNull(0)?.stringOrNull() ?: return@mapNotNull null
                    val value = pair.getOrNull(1) ?: return@mapNotNull null
                    key to value
                }.toMap()
                if (map.isEmpty()) null else JsonObject(map)
            }
            else -> null
        }
    }

    /**
     * Detect ES module syntax: `import` or `export` at the start of a line
     * (optionally preceded by whitespace). Matches both static imports
     * (`import ... from '...'`) and side-effect imports (`import '...'`).
     */
    private fun isModuleScript(content: String): Boolean {
        val pattern = Regex(
            """^\s*(import\s|export\s|import\{|export\{)""",
            RegexOption.MULTILINE,
        )
        return pattern.containsMatchIn(content)
    }

    /** True when any enabled script in the card uses ES module syntax. */
    fun hasModuleScripts(character: CharacterCard): Boolean =
        extract(character).any { isModuleScript(it.content) }

    fun hasScripts(character: CharacterCard): Boolean = extract(character).isNotEmpty()

    private fun collect(
        element: JsonElement?,
        out: MutableList<Script>,
        path: String,
        inheritedEnabled: Boolean = true,
    ) {
        if (element == null || !inheritedEnabled) return

        val array = element.asArrayOrNull()
        if (array != null) {
            array.forEachIndexed { index, child -> collect(child, out, "$path.$index", inheritedEnabled) }
            return
        }

        val obj = element.asObjectOrNull() ?: return
        val type = obj.stringField("type")

        // Backward ScriptItem: { type: "script", value: ScriptData }. The
        // executable fields (including enabled/id/name) belong to ScriptData,
        // not to the wrapper. Checking obj.isEnabled() here used to disable
        // every real legacy item because the wrapper has no enabled field.
        if (type == "script") {
            val legacyScriptData = obj["value"]?.asObjectOrNull()
            if (legacyScriptData != null) {
                collect(legacyScriptData, out, "$path.value", inheritedEnabled)
                return
            }
        }

        // A legacy folder is { type: "folder", value: ScriptData[] }. JSR's
        // backward migration explicitly converts it to enabled=true. A
        // current folder uses `scripts` and retains the normal enabled=false
        // default.
        if (type == "folder") {
            val isLegacyFolder = obj["scripts"] == null && obj["value"]?.asArrayOrNull() != null
            val folderEnabled = inheritedEnabled && (isLegacyFolder || obj.isEnabled())
            if (!folderEnabled) return
            val children = obj["scripts"] ?: obj["value"]
            if (children != null) {
                collect(children, out, "$path.scripts", folderEnabled)
            }
            return
        }

        // Script (type === "script" or type is null/absent for backward ScriptData)
        if (type != null && type != "script") return

        val enabled = inheritedEnabled && obj.isEnabled()
        if (!enabled) return

        val content = obj.scriptContent()
        if (!content.isNullOrBlank()) {
            val id = obj.stringField("id") ?: path.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val name = obj.stringField("name") ?: id
            out += Script(id = id, name = name, content = content)
        }
    }

    private fun CharacterCard.extensionObject(): JsonObject {
        val data = raw["data"].asObjectOrNull() ?: raw
        return data["extensions"].asObjectOrNull() ?: JsonObject(emptyMap())
    }

    /**
     * JSR `Script.enabled` defaults to false.
     * The `disabled` field is NOT recognized by JSR (stripped by Zod).
     */
    private fun JsonObject.isEnabled(): Boolean {
        val enabled = this["enabled"]?.booleanLike()
        return enabled ?: false
    }

    /**
     * Only `content` is recognized as the script source by JSR.
     * The legacy `value` field is an object (ScriptData), not a string;
     * if present, we unwrap it and read its `content`.
     */
    private fun JsonObject.scriptContent(): String? {
        // Direct content field (current format)
        stringField("content")?.takeIf { it.isNotBlank() }?.let { return it }

        // Backward format: { type: 'script', value: { content: "..." } }
        val valueObj = this["value"]?.asObjectOrNull()
        if (valueObj != null) {
            return valueObj.stringField("content")?.takeIf { it.isNotBlank() }
        }

        return null
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.stringOrNull()

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        this?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonElement.asArrayOrNull(): List<JsonElement>? =
        runCatching { jsonArray.toList() }.getOrNull()

    private fun JsonElement?.stringOrNull(): String? =
        this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonElement?.booleanLike(): Boolean? =
        this?.let { element ->
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
                ?: runCatching { element.jsonPrimitive.content.toBooleanStrictOrNull() }.getOrNull()
        }
}
