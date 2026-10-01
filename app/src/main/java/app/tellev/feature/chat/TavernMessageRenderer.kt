package app.tellev.feature.chat

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.prompt.DefaultMacroEngine
import app.tellev.core.prompt.MacroContext
import app.tellev.core.prompt.SnapshotMacroVariables
import kotlinx.serialization.json.JsonObject

internal fun renderMessageParts(
    parts: MessageReasoning.Parts,
    role: MessageRole,
    character: CharacterCard?,
    preset: GenerationPreset?,
    userName: String,
    depth: Int,
    includeNormal: Boolean,
    macroContext: MacroContext? = null,
): List<TavernRenderSegment> = buildList {
    // Workers retain the owning session's snapshot, including after a chat switch.
    val base = macroContext ?: MacroContext(characterName = character?.name ?: "Character", userName = userName)
    val scoped = base.copy(variableAccess = SnapshotMacroVariables(
        base.localVariables ?: JsonObject(emptyMap()), base.globalVariables ?: JsonObject(emptyMap()),
    ))
    val engine = DefaultMacroEngine()
    val expand: (String) -> String = { engine.expand(it, scoped) }
    if (parts.reasoning.isNotBlank()) {
        val context = CharacterRegexApplier.RegexExecutionContext(
            character, preset, role, userName, depth,
            phase = CharacterRegexApplier.RegexPhase.Normal,
            macroExpander = expand,
        )
        val normal = CharacterRegexApplier.apply(parts.reasoning, context, 6)
        val display = CharacterRegexApplier.apply(normal, context.copy(phase = CharacterRegexApplier.RegexPhase.Display), 6)
        add(TavernRenderSegment.Reasoning(display))
    }
    val displayBody = CharacterRegexApplier.applyForDisplay(
        parts.body, role, character, userName, depth, preset = preset, includeNormal = includeNormal,
        macroExpander = expand,
    )
    // Display rules may create tags, but cannot move body text into the reasoning channel.
    val assistant = role == MessageRole.Character || role == MessageRole.Assistant
    TavernRenderParser.parseBody(displayBody).forEach { segment ->
        // Card/preset rules get the anchor first. Only hide leftovers in visible text;
        // the stored message and authored frontend HTML must retain their anchors.
        if (assistant && segment is TavernRenderSegment.Text) {
            val visible = hideStandaloneMvuPlaceholder(segment.text)
            if (visible.isNotBlank()) add(TavernRenderSegment.Text(visible))
        } else add(segment)
    }
}

/**
 * Regex-free degradation of [renderMessageParts]: parsing and MVU-anchor
 * hiding only, linear in message size and safe on any thread. Used as the
 * placeholder while the real pipeline runs on a worker and as the fallback
 * when a preset/card regex exceeds the render budget instead of freezing
 * the app.
 */
internal fun renderMessagePartsUnregulated(
    parts: MessageReasoning.Parts,
    role: MessageRole,
): List<TavernRenderSegment> = buildList {
    if (parts.reasoning.isNotBlank()) {
        add(TavernRenderSegment.Reasoning(parts.reasoning))
    }
    val assistant = role == MessageRole.Character || role == MessageRole.Assistant
    TavernRenderParser.parseBody(parts.body).forEach { segment ->
        if (assistant && segment is TavernRenderSegment.Text) {
            val visible = hideStandaloneMvuPlaceholder(segment.text)
            if (visible.isNotBlank()) add(TavernRenderSegment.Text(visible))
        } else add(segment)
    }
}

private val mvuPlaceholderLine = Regex("""^ {0,3}<StatusPlaceHolderImpl[ \t]*/>[ \t]*$""")
private val markdownFenceLine = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

private fun hideStandaloneMvuPlaceholder(text: String): String {
    if (!text.contains("<StatusPlaceHolderImpl")) return text
    var fenceCharacter: Char? = null
    var fenceLength = 0
    return text.lineSequence().filter { line ->
        val fence = markdownFenceLine.matchEntire(line)
        if (fenceCharacter != null) {
            if (fence != null && fence.groupValues[1].first() == fenceCharacter &&
                fence.groupValues[1].length >= fenceLength && fence.groupValues[2].isBlank()) {
                fenceCharacter = null
            }
            true
        } else if (fence != null &&
            (fence.groupValues[1].first() != '`' || !fence.groupValues[2].contains('`'))) {
            fenceCharacter = fence.groupValues[1].first()
            fenceLength = fence.groupValues[1].length
            true
        } else !mvuPlaceholderLine.matches(line)
    }.joinToString("\n").trim()
}
