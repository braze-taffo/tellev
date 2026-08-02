package app.tellev.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Reads and writes the per-character world-info binding that SillyTavern stores
 * as `data.extensions.world` (a lorebook name). This is the field ST writes when
 * you pick a lorebook for a character; selecting that character at chat time
 * activates the named external book in addition to the card's embedded
 * `character_book`.
 *
 * The binding lives in the card's raw JSON (there is no typed model field), so
 * both reads and writes operate on [CharacterCard.raw]. The exporter preserves
 * `data.extensions` verbatim, so a binding written here round-trips through
 * save/export/import without any exporter changes.
 */
object CharacterWorldBinding {

    /**
     * The lorebook names this card binds to, in priority order: the canonical
     * `data.extensions.world` first, then the legacy bare `data.world` and
     * top-level `world` that show up on older exports. Duplicates removed.
     */
    fun linkedWorldBookNames(card: CharacterCard): List<String> {
        val data = (card.raw["data"] as? JsonObject) ?: card.raw
        val names = mutableListOf<String>()
        fun add(element: JsonElement?) {
            (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(names::add)
        }
        add((data["extensions"] as? JsonObject)?.get("world"))
        add(data["world"])
        add(card.raw["world"])
        return names.distinct()
    }

    /** The primary bound lorebook name, or null if none. */
    fun linkedWorldBookName(card: CharacterCard): String? = linkedWorldBookNames(card).firstOrNull()

    /**
     * Returns a copy of [card] whose raw JSON has `data.extensions.world` set to
     * [name] (ST's per-character lorebook binding). A blank/null [name] clears
     * the binding. Legacy bare `data.world` and top-level `world` fields are
     * removed either way so the binding has a single source of truth and cannot
     * double-activate a book. All other raw fields are preserved.
     */
    fun withLinkedWorldBookName(card: CharacterCard, name: String?): CharacterCard {
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        val worldValue: JsonElement? = cleanName?.let { JsonPrimitive(it) }

        val data = (card.raw["data"] as? JsonObject) ?: buildJsonObject {}
        val extensions = (data["extensions"] as? JsonObject) ?: buildJsonObject {}

        val newExtensions = setOrRemove(extensions, "world", worldValue)
        // Drop legacy bare `world` inside data so it can't shadow the canonical binding.
        val newData = setOrRemove(data, "extensions", newExtensions).let { setOrRemove(it, "world", null) }
        // And the top-level legacy `world` too.
        val newRaw = setOrRemove(card.raw, "data", newData).let { setOrRemove(it, "world", null) }

        return card.copy(raw = newRaw)
    }

    /**
     * Returns a copy of [obj] with [key] set to [value], or with [key] removed
     * when [value] is null. All other entries are preserved in order.
     */
    private fun setOrRemove(obj: JsonObject, key: String, value: JsonElement?): JsonObject =
        buildJsonObject {
            var written = false
            for ((k, v) in obj) {
                if (k == key) {
                    if (value != null) {
                        put(key, value)
                        written = true
                    }
                    // value == null -> drop the key
                } else {
                    put(k, v)
                }
            }
            if (!written && value != null) put(key, value)
        }
}
