package app.tellev.feature.chat

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.MessageRole
import app.tellev.core.regex.CharacterRegexApplier

internal fun renderMessageParts(
    parts: MessageReasoning.Parts,
    role: MessageRole,
    character: CharacterCard?,
    preset: GenerationPreset?,
    userName: String,
    depth: Int,
    includeNormal: Boolean,
): List<TavernRenderSegment> = buildList {
    if (parts.reasoning.isNotBlank()) {
        val context = CharacterRegexApplier.RegexExecutionContext(
            character, preset, role, userName, depth,
            phase = CharacterRegexApplier.RegexPhase.Normal,
        )
        val normal = CharacterRegexApplier.apply(parts.reasoning, context, 6)
        val display = CharacterRegexApplier.apply(normal, context.copy(phase = CharacterRegexApplier.RegexPhase.Display), 6)
        add(TavernRenderSegment.Reasoning(display))
    }
    val displayBody = CharacterRegexApplier.applyForDisplay(
        parts.body, role, character, userName, depth, preset = preset, includeNormal = includeNormal,
    )
    // Display rules may create tags, but cannot move body text into the reasoning channel.
    addAll(TavernRenderParser.parseBody(displayBody))
}
