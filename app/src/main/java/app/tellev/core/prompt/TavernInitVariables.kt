package app.tellev.core.prompt

import app.tellev.core.model.WorldBook
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * SillyTavern MVU cards commonly keep their initial message-variable state in
 * a disabled world-info entry whose comment starts with `[initvar]`. The entry
 * is deliberately disabled because it is metadata, not a prompt entry; the
 * MVU extension parses it and stores it under the message-scope `stat_data`.
 */
object TavernInitVariables {
    private val initVarPrefix = Regex("""^\s*\[initvar]""", RegexOption.IGNORE_CASE)

    fun extractMessageVariables(worldBooks: List<WorldBook>): JsonObject? {
        val entry = worldBooks.asSequence()
            .flatMap { it.entries.asSequence() }
            .firstOrNull { initVarPrefix.containsMatchIn(it.comment) && it.content.isNotBlank() }
            ?: return null
        val statData = parseYaml(entry.content) as? JsonObject ?: return null
        return buildJsonObject { put("stat_data", statData) }
    }

    internal fun parseYaml(content: String): JsonElement? = runCatching {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            codePointLimit = 2_000_000
        }
        val value = Yaml(SafeConstructor(options)).load<Any?>(content)
        value?.let(::toJsonElement)
    }.getOrNull()

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(
            value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) },
        )
        is Iterable<*> -> JsonArray(value.map(::toJsonElement))
        is Array<*> -> JsonArray(value.map(::toJsonElement))
        is Boolean -> JsonPrimitive(value)
        is Byte -> JsonPrimitive(value.toInt())
        is Short -> JsonPrimitive(value.toInt())
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
