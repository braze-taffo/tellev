package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object PromptSystemBuilder {

    fun buildSystemPrompt(
        request: PromptBuildRequest,
        expandedCharacter: CharacterCard,
        worldScan: WorldInfoScanner.ScanResult,
        macroContext: MacroContext,
        macroEngine: MacroEngine,
    ): String = buildString {
        // Use the character's system_prompt (data.system_prompt) when present
        // AND preferCharacterPrompt is enabled (ST power_user.prefer_character_prompt,
        // script.js:3356-3357); otherwise fall back to the default "You are X." opener.
        val preferCharPrompt = request.metadata["preferCharacterPrompt"]
            ?.jsonPrimitive?.booleanOrNull ?: true
        val charSystemPrompt = (request.character.raw["data"] as? JsonObject)
            ?.get("system_prompt")?.jsonPrimitive?.content?.trim()
        if (preferCharPrompt && !charSystemPrompt.isNullOrEmpty()) {
            appendLine(macroEngine.expand(charSystemPrompt, macroContext))
        } else {
            appendLine("You are ${expandedCharacter.name}.")
        }
        // Slot order follows ST's default chat-completion prompt order: main,
        // worldInfoBefore, personaDescription, charDescription,
        // charPersonality, scenario, worldInfoAfter, dialogueExamples.
        // Outlet (7) entries are NOT emitted: ST registers them as extension
        // prompts with position NONE unless a named outlet placeholder
        // consumes them (script.js:4615-4618).
        appendWorldInfo(worldScan.before)
        appendBlock("Persona", request.persona?.description?.let { macroEngine.expand(it, macroContext) }.orEmpty())
        appendBlock("Character description", expandedCharacter.description)
        appendBlock("Personality", expandedCharacter.personality)
        appendBlock("Scenario", expandedCharacter.scenario)
        // ↓Char (position 1).
        appendWorldInfo(worldScan.after)
        // ↑EM (position 5) before example messages.
        appendWorldInfo(worldScan.emTop)
        appendBlock("Example messages", expandedCharacter.exampleMessages)
        // ↓EM (position 6) after example messages.
        appendWorldInfo(worldScan.emBottom)
        // Note: AT_DEPTH (4), ↑AT/↓AT (2/3) entries are not part of the system
        // prompt; they are spliced into the chat history by
        // [applyExtensionInjections] — ↑AT/↓AT ride the author's-note slot,
        // which defaults to in-chat depth 4 (authors-note.js:272-274).
    }.trim()

    /** ST joins world info plainly with newlines (default wi_format `{0}`, openai.js:106). */
    fun StringBuilder.appendWorldInfo(entries: List<WorldInfoScanner.ActivatedEntry>) {
        val nonBlank = entries.map { it.content.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return
        nonBlank.forEach { appendLine(it) }
    }

    fun StringBuilder.appendBlock(title: String, value: String) {
        if (value.isBlank()) return
        appendLine("$title:")
        appendLine(value.trim())
    }

    fun buildSystemPromptWithContextTemplate(
        expandedCharacter: CharacterCard,
        contextPreset: ContextPreset,
        macroContext: MacroContext,
        worldScan: WorldInfoScanner.ScanResult,
        request: PromptBuildRequest,
        macroEngine: MacroEngine,
    ): String {
        // wiBefore/wiAfter carry only ↑Char/↓Char entries, matching ST's
        // worldInfoBefore/worldInfoAfter (world-info.js:5146-5147). ↑AT/↓AT and
        // AT_DEPTH are chat-history injections; ↑EM/↓EM wrap the example
        // messages; outlet entries are dropped without an outlet placeholder.
        val entriesBefore = worldScan.before.joinToString("\n") { it.content }
        val entriesAfter = worldScan.after.joinToString("\n") { it.content }

        // Resolve the system prompt content so {{system}} renders actual text (audit M10).
        val preferCharPrompt = request.metadata["preferCharacterPrompt"]?.jsonPrimitive?.booleanOrNull ?: true
        val charSysPrompt = (expandedCharacter.raw["data"] as? JsonObject)?.get("system_prompt")?.jsonPrimitive?.content?.trim()
        val systemContent = if (preferCharPrompt && !charSysPrompt.isNullOrEmpty()) {
            macroEngine.expand(charSysPrompt, macroContext)
        } else {
            "You are ${expandedCharacter.name}."
        }

        // ↑EM/↓EM entries prepend/append to the example-message block, mirroring
        // ST's mesExamplesArray unshift/push (script.js:4580-4596).
        val examplesWithWi = listOf(
            worldScan.emTop.joinToString("\n") { it.content },
            expandedCharacter.exampleMessages,
            worldScan.emBottom.joinToString("\n") { it.content },
        ).filter { it.isNotBlank() }.joinToString("\n")
        val enrichedContext = macroContext.copy(
            characterDescription = expandedCharacter.description,
            characterPersonality = expandedCharacter.personality,
            characterScenario = expandedCharacter.scenario,
            exampleMessages = examplesWithWi,
            firstMessage = expandedCharacter.firstMessage,
            customVariables = macroContext.customVariables + ("system" to systemContent),
        )

        return ContextTemplate.buildSystemPrompt(
            preset = contextPreset,
            context = enrichedContext,
            worldInfoBefore = entriesBefore,
            worldInfoAfter = entriesAfter,
        )
    }
}
