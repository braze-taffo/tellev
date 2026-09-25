package app.tellev.feature.creation

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
enum class CreationKind { Character, WorldBook }

@Serializable
data class CreationTurn(val role: String, val text: String)

@Serializable
data class CharacterDraft(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleMessages: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    /** Portable HTML/CSS fragment placed in the opening message after review. */
    val frontendHtml: String = "",
)

@Serializable
data class LoreDraft(
    val title: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
    val secondaryKeys: List<String> = emptyList(),
    val selective: Boolean = false,
    val constant: Boolean = false,
    val insertionOrder: Int = 100,
    val depth: Int = 4,
    val position: Int = 0,
    val probability: Int = 100,
    val matchWholeWords: Boolean = false,
    val sourceQuote: String = "",
    val sourceOffset: Int = -1,
    val sourceName: String = "",
    val sourceSha256: String = "",
    val note: String = "",
)

@Serializable
data class CreationSession(
    val id: String = "creation_${UUID.randomUUID()}",
    val kind: CreationKind,
    val turns: List<CreationTurn> = emptyList(),
    val card: CharacterDraft = CharacterDraft(),
    /** SHA-256 of the separately stored PNG cover; empty for no cover. */
    val coverSha256: String = "",
    val worldName: String = "",
    val lore: List<LoreDraft> = emptyList(),
    val sourceName: String = "",
    val sourceSha256: String = "",
    /** Character offset after the last successfully extracted source chunk. */
    val sourceCursor: Int = 0,
    val sourceLength: Int = 0,
    val savedArtifactId: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

/** All generated fields are kept in the standard V2 data object. */
fun CreationSession.toCharacterCard(): CharacterCard {
    require(kind == CreationKind.Character && card.name.isNotBlank())
    fun withFrontend(opening: String): String = listOf(opening.trim(), card.frontendHtml.trim())
        .filter { it.isNotBlank() }.joinToString("\n\n")
    val opening = withFrontend(card.firstMessage)
    val raw = buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", buildJsonObject {
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            put("creator", "Tellev AI 协作创作")
            put("character_version", "1.0")
        })
    }
    return CharacterCard(
        id = savedArtifactId.ifBlank { "char_${UUID.randomUUID()}" },
        name = card.name.trim(),
        description = card.description,
        personality = card.personality,
        scenario = card.scenario,
        firstMessage = opening,
        alternateGreetings = card.alternateGreetings.map(::withFrontend),
        exampleMessages = card.exampleMessages,
        creatorNotes = card.creatorNotes,
        tags = card.tags,
        characterBook = if (lore.isEmpty()) null else toWorldBook(),
        raw = raw,
    )
}

fun CreationSession.toWorldBook(): WorldBook = WorldBook(
    id = savedArtifactId.ifBlank { "wb_${UUID.randomUUID()}" },
    name = worldName.ifBlank { card.name.ifBlank { "新世界书" } },
    entries = lore.mapIndexed { index, item ->
        WorldBookEntry(
            id = index.toString(),
            keys = item.keys.map(String::trim).filter(String::isNotBlank).distinct(),
            secondaryKeys = item.secondaryKeys.map(String::trim).filter(String::isNotBlank).distinct(),
            content = item.content.trim(),
            constant = item.constant,
            selective = item.selective && item.secondaryKeys.isNotEmpty(),
            comment = item.title,
            insertionOrder = item.insertionOrder,
            depth = item.depth,
            position = item.position,
            probability = item.probability.coerceIn(0, 100),
            matchWholeWords = item.matchWholeWords,
            raw = buildJsonObject {
                put("comment", item.title)
                put("extensions", JsonObject(emptyMap()))
            },
        )
    },
)

/** No JavaScript, external assets, or Tavern-only helper APIs in a portable opening panel. */
fun portableFrontendIssues(html: String): List<String> = buildList {
    if (html.isBlank()) return@buildList
    val forbidden = listOf(
        Regex("<\\s*script\\b", RegexOption.IGNORE_CASE) to "包含脚本",
        Regex("\\bon[a-z]+\\s*=", RegexOption.IGNORE_CASE) to "包含内联事件",
        Regex("<\\s*(iframe|object|embed|link|form|img|video|audio|canvas|svg|math|meta|base|input|button|textarea|select|template)\\b", RegexOption.IGNORE_CASE) to "包含当前双端范围外的标签",
        Regex("\\b(src|href|srcset|action)\\s*=", RegexOption.IGNORE_CASE) to "依赖链接或资源",
        Regex("@import\\b|url\\s*\\(|expression\\s*\\(|behavior\\s*:", RegexOption.IGNORE_CASE) to "包含外部或动态样式",
        Regex("\\{\\{", RegexOption.IGNORE_CASE) to "包含可能依赖运行扩展的模板变量",
    )
    forbidden.forEach { (pattern, reason) -> if (pattern.containsMatchIn(html)) add(reason) }
}
