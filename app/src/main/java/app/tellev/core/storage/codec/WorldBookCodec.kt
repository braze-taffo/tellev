package app.tellev.core.storage.codec

import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object WorldBookCodec {

    /**
     * Parse world book entries from ST format.
     * ST format: entries is an object with numeric string keys ("0", "1", ...),
     * each containing entry data with uid, key, keysecondary, content, etc.
     */
    fun parseWorldBookEntries(raw: JsonObject): List<WorldBookEntry> {
        val entriesObj = raw["entries"]?.jsonObject ?: return emptyList()

        return entriesObj.mapNotNull { (key, value) ->
            runCatching {
                val entryObj = value.jsonObject
                val extensions = entryObj["extensions"] as? JsonObject
                WorldBookEntry(
                    id = entryObj["uid"]?.jsonPrimitive?.content ?: key,
                    keys = extractStringList(entryObj, "key"),
                    secondaryKeys = extractStringList(entryObj, "keysecondary"),
                    content = entryObj["content"]?.jsonPrimitive?.content ?: "",
                    enabled = !(entryObj["disable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false),
                    selective = entryObj["selective"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    constant = entryObj["constant"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    priority = entryObj["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    insertionOrder = entryObj["order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    depth = entryObj["depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                    position = entryObj["position"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    probability = entryObj["probability"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    useProbability = entryObj["useProbability"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    selectiveLogic = entryObj["selectiveLogic"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    role = entryObj["role"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    matchWholeWords = entryObj["matchWholeWords"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    useRegex = entryObj["useRegex"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    caseSensitive = entryObj["caseSensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    comment = entryObj["comment"]?.jsonPrimitive?.content ?: "",
                    excludeRecursion = entryObj["excludeRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    preventRecursion = entryObj["preventRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    // ST delayUntilRecursion is a number (recursion level); older
                    // cards / tellev builds may carry a boolean (true -> 1).
                    delayUntilRecursion = entryObj["delayUntilRecursion"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: entryObj["delayUntilRecursion"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?.let { if (it) 1 else 0 }
                        ?: 0,
                    ignoreBudget = extensions?.get("ignore_budget")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: entryObj["ignoreBudget"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: entryObj["ignore_budget"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                        ?: false,
                    group = entryObj["group"]?.jsonPrimitive?.content ?: "",
                    groupOverride = entryObj["groupOverride"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    groupWeight = entryObj["groupWeight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                    useGroupScoring = entryObj["useGroupScoring"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    raw = entryObj,
                )
            }.getOrNull()
        }
    }

    fun serializeWorldBook(book: WorldBook): JsonObject {
        return buildJsonObject {
            for ((key, value) in book.raw) {
                if (key != "name" && key != "entries") put(key, value)
            }
            put("name", book.name)
            putJsonObject("entries") {
                book.entries.forEachIndexed { index, entry ->
                    val merged = mutableMapOf<String, JsonElement>()
                    merged.putAll(entry.raw)
                    merged["uid"] = entry.raw["uid"] ?: JsonPrimitive(entry.id.toIntOrNull() ?: index)
                    merged["key"] = JsonArray(entry.keys.map { JsonPrimitive(it) })
                    merged["keysecondary"] = JsonArray(entry.secondaryKeys.map { JsonPrimitive(it) })
                    merged["content"] = JsonPrimitive(entry.content)
                    merged["constant"] = JsonPrimitive(entry.constant)
                    merged["selective"] = JsonPrimitive(entry.selective)
                    merged["order"] = JsonPrimitive(entry.insertionOrder)
                    merged["disable"] = JsonPrimitive(!entry.enabled)
                    merged["depth"] = JsonPrimitive(entry.depth)
                    merged["position"] = JsonPrimitive(entry.position)
                    merged["probability"] = JsonPrimitive(entry.probability)
                    merged["useProbability"] = JsonPrimitive(entry.useProbability)
                    merged["selectiveLogic"] = JsonPrimitive(entry.selectiveLogic)
                    merged["role"] = JsonPrimitive(entry.role)
                    merged["matchWholeWords"] = JsonPrimitive(entry.matchWholeWords)
                    merged["useRegex"] = JsonPrimitive(entry.useRegex)
                    merged["caseSensitive"] = JsonPrimitive(entry.caseSensitive)
                    merged["comment"] = JsonPrimitive(entry.comment)
                    merged["excludeRecursion"] = JsonPrimitive(entry.excludeRecursion)
                    merged["preventRecursion"] = JsonPrimitive(entry.preventRecursion)
                    merged["delayUntilRecursion"] = JsonPrimitive(entry.delayUntilRecursion)
                    merged["ignoreBudget"] = JsonPrimitive(entry.ignoreBudget)
                    if (entry.priority != 0 || entry.raw.containsKey("priority")) {
                        merged["priority"] = JsonPrimitive(entry.priority)
                    }
                    if (!merged.containsKey("displayIndex")) merged["displayIndex"] = JsonPrimitive(index)
                    if (!merged.containsKey("extensions")) merged["extensions"] = buildJsonObject { }
                    put(index.toString(), JsonObject(merged))
                }
            }
        }
    }

    fun extractStringList(obj: JsonObject, key: String): List<String> {
        val element = obj[key] ?: return emptyList()
        return runCatching {
            element.jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Extract member character IDs from a group JSON.
     * ST stores members as a "members" array of character name strings.
     */
    fun extractMembers(raw: JsonObject): List<String> {
        val membersElement = raw["members"] ?: raw["member_ids"]
        if (membersElement != null) {
            return runCatching {
                membersElement.jsonArray.mapNotNull {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                }
            }.getOrDefault(emptyList())
        }

        val membersString = raw["members"]?.jsonPrimitive?.content
        if (membersString != null) {
            return membersString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        return emptyList()
    }
}
