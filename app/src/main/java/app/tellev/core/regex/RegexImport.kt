package app.tellev.core.regex

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.CharacterCard
import kotlinx.serialization.json.*
import java.util.UUID

/** Lossless import of SillyTavern's single-rule and bulk JSON exports. */
object RegexImport {
    data class Result(val scripts: JsonArray, val added: Int, val replaced: Int)

    suspend fun importInto(store: app.tellev.core.storage.StDataStore, incoming: JsonArray, characterId: String?): Result {
        val card = characterId?.let { store.readCharacter(it) }
        val result = merge(card?.let(::scripts) ?: store.readGlobalRegexScripts(), incoming)
        if (card != null) store.saveCharacter(withScripts(card, result.scripts))
        else store.saveGlobalRegexScripts(result.scripts)
        return result
    }

    fun parse(text: String): JsonArray {
        val root = try { Json.parseToJsonElement(text.removePrefix("\uFEFF")) }
        catch (_: IllegalArgumentException) { error(UiStrings.get(S.ri_invalid_json)) }
        val scripts = when (root) {
            is JsonObject -> JsonArray(listOf(root))
            is JsonArray -> root
            else -> error(UiStrings.get(S.ri_invalid_json))
        }
        require(scripts.isNotEmpty()) { UiStrings.get(S.ri_empty) }
        scripts.forEachIndexed { index, element ->
            fun invalid(field: String): Nothing = error(UiStrings.get(S.ri_invalid_rule, index + 1, field))
            val rule = element as? JsonObject ?: invalid("object")
            for (key in listOf("scriptName", "findRegex")) {
                val value = rule[key] as? JsonPrimitive ?: invalid(key)
                if (!value.isString || value.content.isBlank()) invalid(key)
            }
            for (key in listOf("id", "replaceString")) {
                rule[key]?.let { if (it !is JsonPrimitive || !it.isString) invalid(key) }
            }
            for (key in listOf("disabled", "markdownOnly", "promptOnly", "runOnEdit")) {
                rule[key]?.let { if (it !is JsonPrimitive || it.isString || it.booleanOrNull == null) invalid(key) }
            }
            for (key in listOf("minDepth", "maxDepth", "substituteRegex")) {
                rule[key]?.takeUnless { it == JsonNull }?.let {
                    if (it !is JsonPrimitive || it.isString || it.intOrNull == null) invalid(key)
                }
            }
            rule["placement"]?.let { value ->
                if (value !is JsonArray || value.any { it !is JsonPrimitive || it.isString || it.intOrNull == null }) invalid("placement")
            }
            rule["trimStrings"]?.let { value ->
                if (value !is JsonArray || value.any { it !is JsonPrimitive || !it.isString }) invalid("trimStrings")
            }
        }
        val duplicates = scripts.map { it.jsonObject.getValue("scriptName").jsonPrimitive.content }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { UiStrings.get(S.ri_conflict, duplicates.joinToString(", ")) }
        return scripts
    }

    fun merge(existing: JsonArray, incoming: JsonArray): Result {
        // Validate the entire batch before producing any replacement.
        val validated = parse(incoming.toString())
        val names = validated.map { it.jsonObject.getValue("scriptName").jsonPrimitive.content }.toSet()
        val conflicts = existing.mapNotNull { (it as? JsonObject)?.get("scriptName")?.let { v -> (v as? JsonPrimitive)?.content } }
            .filter { it in names }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(conflicts.isEmpty()) { UiStrings.get(S.ri_conflict, conflicts.joinToString(", ")) }
        val output = existing.toMutableList()
        var added = 0
        var replaced = 0
        validated.forEach { element ->
            val rule = element.jsonObject
            val name = rule.getValue("scriptName")
            val index = output.indexOfFirst { (it as? JsonObject)?.get("scriptName") == name }
            val old = output.getOrNull(index) as? JsonObject
            val id = (old?.get("id") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val updated = JsonObject(rule + ("id" to JsonPrimitive(id)))
            if (index >= 0) { output[index] = updated; replaced++ }
            else { output.add(updated); added++ }
        }
        return Result(JsonArray(output), added, replaced)
    }

    fun scripts(card: CharacterCard): JsonArray {
        val data = card.raw["data"] as? JsonObject ?: card.raw
        return ((data["extensions"] as? JsonObject)?.get("regex_scripts") as? JsonArray) ?: JsonArray(emptyList())
    }

    fun withScripts(card: CharacterCard, scripts: JsonArray): CharacterCard {
        val data = card.raw["data"] as? JsonObject ?: card.raw
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(data + ("extensions" to JsonObject(extensions + ("regex_scripts" to scripts))))
        return card.copy(raw = if (card.raw["data"] is JsonObject) JsonObject(card.raw + ("data" to updated)) else updated)
    }

    fun toggle(scripts: JsonArray, id: String): JsonArray {
        require(scripts.withIndex().any { (i, v) -> v is JsonObject && CharacterRegexApplier.scriptIdentifier(v, i) == id }) {
            UiStrings.get(S.extvm_regex_script_not_found, id)
        }
        return JsonArray(scripts.mapIndexed { index, element ->
            val rule = element as? JsonObject ?: return@mapIndexed element
            if (CharacterRegexApplier.scriptIdentifier(rule, index) != id) return@mapIndexed element
            JsonObject(rule + ("disabled" to JsonPrimitive((rule["disabled"] as? JsonPrimitive)?.booleanOrNull != true)))
        })
    }

    fun delete(scripts: JsonArray, id: String): JsonArray {
        val index = scripts.indices.firstOrNull { i ->
            val element = scripts[i]
            element is JsonObject && CharacterRegexApplier.scriptIdentifier(element, i) == id
        } ?: -1
        require(index >= 0) { UiStrings.get(S.extvm_regex_script_not_found, id) }
        return JsonArray(scripts.filterIndexed { i, _ -> i != index })
    }
}
