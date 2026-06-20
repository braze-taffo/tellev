package app.tellev.core.extension

import app.tellev.core.model.CharacterCard
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
 * - A disabled folder gates its entire subtree (no per-child override).
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

    fun buildScriptSource(character: CharacterCard): String =
        extract(character).joinToString("\n\n") { script ->
            val safeName = script.name.replace(Regex("[\\r\\n]+"), " ").ifBlank { script.id }
            val safeSource = "tellev-character-${character.id}-${script.id}"
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            """
            // $safeName
            ;(function(){
            try{
            ${script.content}
            }catch(e){if(window.Tellev){tellevNative.log('error','${safeName.replace("'", "\\'")}: '+e);}}
            })();
            //# sourceURL=$safeSource.js
            """.trimIndent()
        }

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

        // Folder: gate the entire subtree by its own enabled flag
        if (type == "folder") {
            val folderEnabled = inheritedEnabled && obj.isEnabled()
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
