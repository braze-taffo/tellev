package app.tellev.core.prompt

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object PromptPostProcessor {

    /**
     * SillyTavern's `names_behavior` (openai.js:204-209, 586-603, 948-951;
     * PromptManager.js:1343-1351; mirrored by js-slash-runner
     * generateRaw.ts:243) has four values:
     *
     *  - NONE(-1): no `name` field, no content prefix.
     *  - DEFAULT(0): prefix `Name: ` only for group-chat messages whose name
     *    differs from the user's (openai.js:590; the force_avatar branch has
     *    no tellev counterpart).
     *  - COMPLETION(1): set the `name` field on every named message,
     *    sanitizing invalid characters to `_` (PromptManager.sanitizeName:
     *    `[^a-zA-Z0-9_]` -> `_`, capped at 64 chars) instead of dropping the
     *    name — a CJK name like 小明 becomes `__`, never a raw value that
     *    fails OpenAI's name rule.
     *  - CONTENT(2): prefix `Name: ` to the content of every named message,
     *    including user messages, and never set the `name` field
     *    (openai.js:594-596).
     *
     * The name is still carried internally up to this point because group
     * ordering and instruct formatting need it.
     */
    fun sanitizeCompletionName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_").take(64)

    fun applyNamesBehavior(
        messages: List<PromptMessage>,
        metadata: JsonObject,
        preset: GenerationPreset,
    ): List<PromptMessage> {
        val isGroup = PromptMacroContextBuilder.groupMemberNamesList(metadata).size > 1
        val namesBehavior = preset.raw["names_behavior"]?.jsonPrimitive?.intOrNull ?: 0
        return messages.map { message ->
            val name = message.name
            when {
                name.isNullOrBlank() -> message.copy(name = null)
                namesBehavior == 1 -> message.copy(name = sanitizeCompletionName(name))
                namesBehavior == 2 ->
                    if (message.content.startsWith("$name: ")) message.copy(name = null)
                    else message.copy(name = null, content = "$name: ${message.content}")
                namesBehavior == 0 && isGroup && message.role == MessageRole.Assistant ->
                    if (message.content.startsWith("$name: ")) message.copy(name = null)
                    else message.copy(name = null, content = "$name: ${message.content}")
                else -> message.copy(name = null)
            }
        }
    }

    fun squashAdjacentSystemMessages(messages: List<PromptMessage>): List<PromptMessage> =
        messages.fold(emptyList()) { out, message ->
            val previous = out.lastOrNull()
            if (message.role == MessageRole.System && previous?.role == MessageRole.System) {
                out.dropLast(1) + previous.copy(content = previous.content + "\n\n" + message.content)
            } else out + message
        }

    fun buildStopSequences(
        presetStop: List<String>,
        instructPreset: InstructPreset?,
        contextPreset: ContextPreset?,
    ): List<String> {
        val stops = presetStop.toMutableList()
        instructPreset?.let {
            if (it.stopSequence.isNotEmpty() && !stops.contains(it.stopSequence)) {
                stops.add(it.stopSequence)
            }
            // Input sequence often serves as a stop for generation
            if (it.inputSequence.isNotEmpty() && !stops.contains(it.inputSequence)) {
                stops.add(it.inputSequence)
            }
        }
        contextPreset?.let {
            if (it.stopSequence.isNotEmpty() && !stops.contains(it.stopSequence)) {
                stops.add(it.stopSequence)
            }
        }
        return stops
    }

    fun compatibilityWarnings(request: PromptBuildRequest): List<String> = buildList {
        if (request.character.raw.isNotEmpty()) {
            add("Raw SillyTavern character metadata is preserved but not fully interpreted yet.")
        }
        if (request.preset.raw.isNotEmpty()) {
            add("Provider-specific preset fields are preserved but adapter-specific mapping is incomplete.")
        }
    }
}
