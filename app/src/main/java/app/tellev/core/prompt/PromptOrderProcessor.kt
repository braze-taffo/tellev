package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object PromptOrderProcessor {

    fun applyPresetPromptOrder(
        messages: List<PromptMessage>,
        preset: GenerationPreset,
        context: MacroContext,
        character: CharacterCard,
        personaDescription: String,
        worldScan: WorldInfoScanner.ScanResult,
        preferCharPrompt: Boolean = true,
        preferCharJailbreak: Boolean = true,
        macroEngine: MacroEngine,
        rawGeneration: Boolean = false,
        overrides: JsonObject = JsonObject(emptyMap()),
    ): PresetOrderResult {
        if (!rawGeneration && (preset.prompts.isEmpty() || messages.isEmpty())) return PresetOrderResult(messages)
        val unused = preset.promptsUnused.map { it.identifier }.toSet()
        val enabled = preset.prompts
            .filter { it.enabled && it.identifier !in unused }
            .sortedWith(compareBy({ it.order }, { it.identifier }))
        val system = messages.firstOrNull { it.role == MessageRole.System }
        val userInput = if (rawGeneration) messages.lastOrNull { it.channel == "user_input" } else null
        val history = messages.filterNot { it === system || it === userInput }
        // worldInfoBefore/worldInfoAfter carry only ↑Char/↓Char entries
        // (world-info.js:5093-5098). ↑AT/↓AT and @D are chat injections, ↑EM/↓EM
        // wrap dialogue examples, and outlets need an explicit placeholder.
        val worldBefore = worldScan.before.joinToString("\n") { it.content.trim() }
        val worldAfter = worldScan.after.joinToString("\n") { it.content.trim() }
        val charData = character.raw["data"] as? JsonObject
        val charSysPrompt = charData?.get("system_prompt")?.jsonPrimitive?.content?.trim()
        val charJailbreak = charData?.get("post_history_instructions")?.jsonPrimitive?.content?.trim()
        val absolutes = mutableListOf<ExtensionInjection>()
        val ordered = mutableListOf<PromptMessage>()

        fun roleFor(value: String): MessageRole = when (value.lowercase()) {
            "user" -> MessageRole.User
            "assistant", "character" -> MessageRole.Assistant
            else -> MessageRole.System
        }

        /** ST character-card overrides replace the preset slot's content and may
         * reference the replaced text via {{original}} (openai.js:1486-1504,
         * PromptManager preparePrompt). */
        fun applyOverride(presetContent: String, override: String?, allowed: Boolean, forbid: Boolean): String {
            if (!allowed || forbid || override.isNullOrBlank()) return presetContent
            return override.replace(ORIGINAL_MACRO, Regex.escapeReplacement(presetContent))
        }

        enabled.forEach { prompt ->
            val identifier = prompt.identifier.lowercase().replace("_", "").replace("-", "")
            if (identifier == "userinput") {
                userInput?.takeIf { it.content.isNotBlank() }?.let { ordered += it }
                return@forEach
            }
            if (identifier in setOf("chathistory", "history")) {
                ordered += history
                return@forEach
            }
            if (identifier in setOf("dialogueexamples", "examplemessages", "examples")) {
                // ST populateDialogueExamples splits examples into chats on
                // <START> and precedes each with '[Example Chat]'
                // (openai.js:1092-1123, new_example_chat_prompt); ↑EM/↓EM WI
                // entries become their own example blocks (script.js:4580-4596).
                val chunks = buildList {
                    worldScan.emTop.forEach { add(it.content.trim()) }
                    character.exampleMessages.split(EXAMPLE_CHAT_SPLIT)
                        .map { it.trim() }
                        .forEach { add(it) }
                    worldScan.emBottom.forEach { add(it.content.trim()) }
                }.filter { it.isNotEmpty() }
                chunks.forEach { chunk ->
                    ordered += PromptMessage(role = MessageRole.System, content = "[Example Chat]", channel = CHANNEL_MARKER)
                    ordered += PromptMessage(role = MessageRole.System, content = macroEngine.expand(chunk, context))
                }
                return@forEach
            }
            val channel = when (identifier) {
                "main", "system", "systemprompt" -> CHANNEL_MAIN
                else -> if (rawGeneration) prompt.identifier else null
            }
            val component = when (identifier) {
                "main", "system", "systemprompt" -> {
                    val base = prompt.content.takeIf { it.isNotBlank() } ?: "You are ${character.name}."
                    applyOverride(base, charSysPrompt, preferCharPrompt, prompt.forbidOverrides)
                }
                "jailbreak", "posthistoryinstructions", "phi" ->
                    applyOverride(prompt.content, charJailbreak, preferCharJailbreak, prompt.forbidOverrides)
                // A JSON null override must fall through to the computed value:
                // JsonNull.jsonPrimitive.content is the literal string "null",
                // which used to be injected into the prompt verbatim.
                "worldinfobefore" -> (overrides["world_info_before"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content ?: worldBefore
                "worldinfoafter" -> (overrides["world_info_after"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content ?: worldAfter
                "chardescription", "characterdescription" -> character.description
                "charpersonality", "characterpersonality" -> character.personality
                "scenario" -> character.scenario
                "personadescription", "persona" -> macroEngine.expand(personaDescription, context)
                else -> prompt.content
            }
            if (component.isBlank()) return@forEach
            val expanded = macroEngine.expand(component, context)
            if (prompt.relative) {
                // injection_position=1 (absolute): depth-inject into chat
                // history with the prompt's role, depth and order, exactly like
                // ST's absolutePrompts (openai.js:1239-1244, 801-866).
                absolutes += ExtensionInjection(
                    value = expanded,
                    position = 1,
                    depth = prompt.depth.coerceAtLeast(0),
                    role = roleFor(prompt.role),
                    order = absolutes.size,
                    key = null,
                    orderGroup = prompt.injectionOrder,
                )
            } else {
                ordered += PromptMessage(
                    role = roleFor(prompt.role),
                    content = expanded,
                    channel = channel,
                )
            }
        }
        return PresetOrderResult(ordered, absolutes)
    }

    fun applyGroupChatOrdering(
        messages: List<PromptMessage>,
        metadata: JsonObject,
    ): List<PromptMessage> {
        val memberNames = PromptMacroContextBuilder.groupMemberNamesList(metadata)
        if (memberNames.size <= 1) return messages

        // For group chats, ensure assistant messages have proper name
        // attribution: unnamed drafts rotate through the member list
        // (round-robin), resuming after the last *named* speaker, instead of
        // collapsing every message onto the first member.
        var next = messages.indexOfLast { it.role == MessageRole.Assistant && it.name != null }
            .takeIf { it >= 0 }
            ?.let { lastNamed -> memberNames.indexOf(messages[lastNamed].name) }
            ?: -1
        return messages.map { message ->
            if (message.role == MessageRole.Assistant && message.name == null) {
                next = (next + 1).mod(memberNames.size)
                message.copy(name = memberNames[next])
            } else {
                message
            }
        }
    }
}
