package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings

/**
 * The brief's leading marker is a protocol token shared with
 * [creationConversationContext], which re-detects the brief turn when a draft
 * is reopened. The token is language-neutral so detection never depends on the
 * app language; the legacy Chinese marker keeps pre-i18n drafts working.
 */
internal const val CREATION_BRIEF_MARKER = "[CREATION BRIEF]"
internal val CREATION_BRIEF_MARKERS = listOf(CREATION_BRIEF_MARKER, "【创作起点】")

/** A starting brief stays in the conversation, so reopening a draft keeps the user's choices. */
internal data class CreationBrief(
    val kind: CreationKind,
    val guided: Boolean,
    val title: String = "",
    val premise: String = "",
    val relationship: String = "",
    val userPersona: String = "",
    val characters: String = "",
    val detail: CreationDetail = CreationDetail.Normal,
    val loreOneByOne: Boolean = false,
) {
    fun toPrompt(): String = buildString {
        appendLine(CREATION_BRIEF_MARKER)
        appendLine(UiStrings.get(if (kind == CreationKind.Character) S.creng_brief_opening_character else S.creng_brief_opening_worldbook))
        appendLine(UiStrings.get(S.creng_brief_mode_line,
            UiStrings.get(if (guided) S.creng_brief_mode_guided else S.creng_brief_mode_direct)))
        if (kind == CreationKind.Character) {
            appendLine(UiStrings.get(S.creng_brief_length_line, detail.label(), detail.characterLength()))
            if (characters.isNotBlank()) appendLine(UiStrings.get(S.creng_brief_characters_line, characters.trim()))
            if (relationship.isNotBlank()) appendLine(UiStrings.get(S.creng_brief_relationship_line, relationship.trim()))
            if (userPersona.isNotBlank()) appendLine(UiStrings.get(S.creng_brief_persona_line, userPersona.trim()))
        } else {
            appendLine(UiStrings.get(if (loreOneByOne) S.creng_brief_lore_one_by_one else S.creng_brief_lore_full_draft))
        }
        if (title.isNotBlank()) appendLine(UiStrings.get(S.creng_brief_title_line,
            UiStrings.get(if (kind == CreationKind.Character) S.creng_brief_title_label_character else S.creng_brief_title_label_worldbook),
            title.trim()))
        if (premise.isNotBlank()) appendLine(UiStrings.get(S.creng_brief_premise_line, premise.trim()))
        if (guided) {
            append(UiStrings.get(S.creng_brief_guided_tail))
        } else {
            append(when {
                kind == CreationKind.Character -> UiStrings.get(S.creng_brief_direct_character)
                loreOneByOne -> UiStrings.get(S.creng_brief_direct_lore_one_by_one)
                else -> UiStrings.get(S.creng_brief_direct_lore)
            })
            append(UiStrings.get(S.creng_brief_review_tail))
        }
    }.trim()
}

/** Prompt-side labels; the UI shows its own crs_length_* strings. */
internal enum class CreationDetail(val labelKey: String, val lengthKey: String) {
    Concise(S.creng_detail_concise, S.creng_detail_length_concise),
    Normal(S.creng_detail_normal, S.creng_detail_length_normal),
    Rich(S.creng_detail_rich, S.creng_detail_length_rich);

    internal fun label(): String = UiStrings.get(labelKey)
    internal fun characterLength(): String = UiStrings.get(lengthKey)
}
