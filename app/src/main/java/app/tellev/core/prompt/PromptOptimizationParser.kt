package app.tellev.core.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses the optimizer's strict JSON reply. Kept separate from the engine so
 * unit tests can pin the acceptance surface: fenced JSON tolerated, prose
 * around the object tolerated, anything without a usable object rejected.
 */
internal object PromptOptimizationParser {
    private val json = Json { ignoreUnknownKeys = true }

    data class Parsed(val optimized: String, val notes: String)

    fun parse(text: String): Parsed? {
        val clean = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(clean.substring(start, end + 1)) }
            .getOrNull() as? JsonObject ?: return null
        // Cast (not .jsonPrimitive) so a non-primitive field reads as absent
        // instead of throwing out of the parser.
        val optimized = (root["optimized"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (optimized.isBlank()) return null
        val notes = (root["notes"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return Parsed(optimized, notes)
    }
}
