package app.tellev.core.model

import kotlinx.serialization.json.*

/** Tellev-specific cast references and portable fallback settings, inside CCv2/v3 extensions. */
object CharacterCastBinding {
    const val EXTENSION_KEY = "tellev_cast"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun members(card: CharacterCard): List<CharacterCard> {
        val data = card.raw["data"] as? JsonObject ?: card.raw
        val cast = (data["extensions"] as? JsonObject)?.get(EXTENSION_KEY) as? JsonObject ?: return emptyList()
        return (cast["members"] as? JsonArray).orEmpty().mapNotNull { value ->
            runCatching { json.decodeFromJsonElement(CharacterCard.serializer(), value) }.getOrNull()
        }.filter { it.id != card.id }.distinctBy { it.id }
    }

    fun withMembers(card: CharacterCard, members: List<CharacterCard>): CharacterCard {
        val data = card.raw["data"] as? JsonObject ?: card.raw
        val extensions = data["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val selected = members.filter { it.id != card.id }.distinctBy { it.id }
        val updated = JsonObject(extensions +
            (EXTENSION_KEY to buildJsonObject {
                put("version", 1)
                put("members", JsonArray(selected.map { member ->
                    // Preserve standard settings without nesting other casts or executable extensions.
                    val clean = member.copy(raw = JsonObject(emptyMap()), avatarRelativePath = null)
                    val snapshot = CharacterWorldBinding.withWorldBookNames(clean, CharacterWorldBinding.linkedWorldBookName(member),
                        CharacterWorldBinding.linkedWorldBookNames(member).filterNot { it == CharacterWorldBinding.linkedWorldBookName(member) })
                    json.encodeToJsonElement(CharacterCard.serializer(), snapshot)
                }))
            }))
        val next = JsonObject(data + ("extensions" to updated))
        return card.copy(raw = if (card.raw["data"] is JsonObject) JsonObject(card.raw + ("data" to next)) else next)
    }
}
