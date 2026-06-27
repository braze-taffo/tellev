package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.model.WorldBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

interface PromptEngine {
    fun build(request: PromptBuildRequest): PromptBuildResult
}

@Serializable
data class PromptBuildRequest(
    val character: CharacterCard,
    val persona: Persona?,
    val messages: List<ChatMessage>,
    val worldBooks: List<WorldBook>,
    val preset: GenerationPreset,
    val userInput: String,
    val providerType: String,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class PromptBuildResult(
    val messages: List<PromptMessage>,
    val stop: List<String>,
    val maxTokens: Int?,
    val providerType: String,
    val diagnostics: PromptDiagnostics,
)

@Serializable
data class PromptMessage(
    val role: MessageRole,
    val name: String? = null,
    val content: String,
)

@Serializable
data class PromptDiagnostics(
    val activatedWorldEntryIds: List<String>,
    val estimatedTokenCount: Int? = null,
    val warnings: List<String> = emptyList(),
)

class DefaultPromptEngine(
    private val macroEngine: MacroEngine = DefaultMacroEngine(),
    private val promptTemplateProcessor: PromptTemplateProcessor = DefaultPromptTemplateProcessor(),
) : PromptEngine {
    override fun build(request: PromptBuildRequest): PromptBuildResult {
        // 1. Build MacroContext from request data
        val macroContext = buildMacroContext(request)

        // 2. Expand macros in all text fields
        val expandedCharacter = expandCharacterFields(request.character, macroContext)
        val expandedUserInput = macroEngine.expand(request.userInput, macroContext)

        // 3. Build search text for world book key matching
        val searchText = buildString {
            append(expandedUserInput)
            append('\n')
            request.messages.takeLast(12).forEach { appendLine(it.content) }
        }

        // 4. Activate world book entries with depth/position support
        val activatedEntries = activateWorldEntries(request.worldBooks, searchText)

        // 5. Build system prompt
        val contextPresetObj = request.metadata["contextPreset"] as? JsonObject
        val contextPreset = contextPresetObj?.let { ContextTemplate.loadPreset(it) }

        val promptTemplateWorldEntries = activatedEntries.map {
            PromptTemplateWorldEntry(
                id = it.id,
                content = macroEngine.expand(it.content, macroContext),
                raw = it.raw,
            )
        }
        val worldInfoContent = promptTemplateWorldEntries
            .map { promptTemplateProcessor.systemPromptContentFor(it) }
            .filter { it.isNotBlank() }
        val systemPrompt = if (contextPreset != null) {
            buildSystemPromptWithContextTemplate(
                request = request,
                expandedCharacter = expandedCharacter,
                contextPreset = contextPreset,
                macroContext = macroContext,
                worldInfoContent = worldInfoContent,
            )
        } else {
            buildSystemPrompt(request, expandedCharacter, worldInfoContent, macroContext)
        }

        // 6. Build prompt messages
        val rawMessages = buildList {
            add(PromptMessage(role = MessageRole.System, content = systemPrompt))
            request.messages
                .filterNot { it.isHidden }
                .forEach { message ->
                    add(
                        PromptMessage(
                            role = when (message.role) {
                                MessageRole.Character -> MessageRole.Assistant
                                else -> message.role
                            },
                            name = message.name,
                            content = macroEngine.expand(
                                message.swipes.getOrNull(message.swipeIndex) ?: message.content,
                                macroContext,
                            ),
                        ),
                    )
                }
            add(
                PromptMessage(
                    role = MessageRole.User,
                    name = request.persona?.name,
                    content = expandedUserInput,
                ),
            )
        }

        // 7. Handle group chat ordering
        val orderedMessages = applyGroupChatOrdering(rawMessages, request.metadata)

        // 8. Apply ST-Prompt-Template compatible EJS processing before token trimming/provider formatting.
        val promptTemplateResult = promptTemplateProcessor.process(
            PromptTemplateRequest(
                messages = orderedMessages,
                context = macroContext,
                metadata = request.metadata,
                worldEntries = promptTemplateWorldEntries,
            ),
        )
        val templatedMessages = promptTemplateResult.messages
        val templatedSystemPrompt = templatedMessages.firstOrNull()?.content ?: systemPrompt

        // 9. Apply token budget
        val maxContextTokens = extractMaxContextTokens(request.metadata)
        val budgetedMessages = if (maxContextTokens != null) {
            TokenBudget.fitToBudget(
                systemPrompt = templatedSystemPrompt,
                worldInfo = worldInfoContent,
                characterDescription = expandedCharacter.description,
                messages = templatedMessages.drop(1), // Drop system message, fitToBudget adds its own
                budget = maxContextTokens - request.preset.maxTokens.orElse(0),
            )
        } else {
            templatedMessages
        }

        // 9.5. Splice in extension-injected prompts (authored by loaded
        // extensions via the ST-compatible `injectPrompts` JS API). Applied
        // after budget trimming so injected system/user/assistant messages
        // survive into the request and instruct mode sees them too.
        val withInjections = applyExtensionInjections(budgetedMessages, request.metadata)

        // 10. Check for instruct mode
        val instructPresetObj = request.metadata["instructPreset"] as? JsonObject
        val instructPreset = instructPresetObj?.let { InstructMode.loadPreset(it) }

        val finalMessages = if (instructPreset != null) {
            val instructText = InstructMode.applyInstruct(
                messages = withInjections,
                preset = instructPreset,
                macroEngine = macroEngine,
                macroContext = macroContext,
            )
            // When instruct mode is active, collapse everything into a single user message
            // with the formatted instruct text, since instruct formats are typically for
            // completion-style APIs
            listOf(PromptMessage(role = MessageRole.User, content = instructText))
        } else {
            withInjections
        }

        // 11. Build stop sequences
        val stopSequences = buildStopSequences(request.preset.stop, instructPreset, contextPreset)

        // 12. Estimate token count for diagnostics
        val estimatedTokens = TokenBudget.estimateTotalTokens(finalMessages)

        return PromptBuildResult(
            messages = finalMessages,
            stop = stopSequences,
            maxTokens = request.preset.maxTokens,
            providerType = request.providerType,
            diagnostics = PromptDiagnostics(
                activatedWorldEntryIds = activatedEntries.map { it.id },
                estimatedTokenCount = estimatedTokens,
                warnings = compatibilityWarnings(request) + promptTemplateResult.warnings,
            ),
        )
    }

    private fun buildMacroContext(request: PromptBuildRequest): MacroContext {
        val lastMessage = request.messages
            .filterNot { it.isHidden }
            .lastOrNull()
            ?.let { it.swipes.getOrNull(it.swipeIndex) ?: it.content }
            .orEmpty()

        val groupMemberNames = extractGroupMemberNames(request.metadata)

        return MacroContext(
            characterName = request.character.name,
            userName = request.persona?.name ?: "User",
            characterDescription = request.character.description,
            characterPersonality = request.character.personality,
            characterScenario = request.character.scenario,
            exampleMessages = request.character.exampleMessages,
            firstMessage = request.character.firstMessage,
            lastMessage = lastMessage,
            groupMemberNames = groupMemberNames,
            maxPromptTokens = request.preset.maxTokens ?: 0,
            maxContextTokens = extractMaxContextTokens(request.metadata) ?: 0,
        )
    }

    private fun expandCharacterFields(
        character: CharacterCard,
        context: MacroContext,
    ): CharacterCard {
        return character.copy(
            description = macroEngine.expand(character.description, context),
            personality = macroEngine.expand(character.personality, context),
            scenario = macroEngine.expand(character.scenario, context),
            firstMessage = macroEngine.expand(character.firstMessage, context),
            exampleMessages = macroEngine.expand(character.exampleMessages, context),
        )
    }

    private fun activateWorldEntries(
        worldBooks: List<WorldBook>,
        searchText: String,
    ): List<app.tellev.core.model.WorldBookEntry> {
        val allEntries = worldBooks.flatMap { it.entries }.filter { it.enabled || it.constant }

        return allEntries
            .filter { entry ->
                if (entry.constant) return@filter true
                entry.matches(searchText)
            }
            .sortedWith(
                compareByDescending<app.tellev.core.model.WorldBookEntry> { it.priority }
                    .thenBy { it.insertionOrder }
            )
    }

    private fun buildSystemPrompt(
        request: PromptBuildRequest,
        expandedCharacter: CharacterCard,
        worldEntries: List<String>,
        macroContext: MacroContext,
    ): String = buildString {
        appendLine("You are ${expandedCharacter.name}.")
        appendBlock("Character description", expandedCharacter.description)
        appendBlock("Personality", expandedCharacter.personality)
        appendBlock("Scenario", expandedCharacter.scenario)
        appendBlock("Persona", request.persona?.description?.let { macroEngine.expand(it, macroContext) }.orEmpty())
        if (worldEntries.isNotEmpty()) {
            appendLine("World info:")
            worldEntries.forEach { appendLine(it) }
        }
        appendBlock("Example messages", expandedCharacter.exampleMessages)
    }.trim()

    private fun buildSystemPromptWithContextTemplate(
        request: PromptBuildRequest,
        expandedCharacter: CharacterCard,
        contextPreset: ContextPreset,
        macroContext: MacroContext,
        worldInfoContent: List<String>,
    ): String {
        // Split world info into before/after based on entry depth
        // Entries with depth <= 2 go to wiAfter, others go to wiBefore
        val activatedEntries = activateWorldEntries(request.worldBooks, buildString {
            append(request.userInput)
            append('\n')
            request.messages.takeLast(12).forEach { appendLine(it.content) }
        })

        val entriesBefore = mutableListOf<String>()
        val entriesAfter = mutableListOf<String>()
        for (entry in activatedEntries) {
            val content = promptTemplateProcessor.systemPromptContentFor(
                PromptTemplateWorldEntry(
                    id = entry.id,
                    content = macroEngine.expand(entry.content, macroContext),
                    raw = entry.raw,
                ),
            )
            if (content.isBlank()) continue
            if (entry.depth <= 2) {
                entriesAfter.add(content)
            } else {
                entriesBefore.add(content)
            }
        }

        val enrichedContext = macroContext.copy(
            characterDescription = expandedCharacter.description,
            characterPersonality = expandedCharacter.personality,
            characterScenario = expandedCharacter.scenario,
            exampleMessages = expandedCharacter.exampleMessages,
            firstMessage = expandedCharacter.firstMessage,
        )

        return ContextTemplate.buildSystemPrompt(
            preset = contextPreset,
            context = enrichedContext,
            worldInfoBefore = entriesBefore.joinToString("\n"),
            worldInfoAfter = entriesAfter.joinToString("\n"),
        )
    }

    private fun buildStopSequences(
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

    private fun applyGroupChatOrdering(
        messages: List<PromptMessage>,
        metadata: JsonObject,
    ): List<PromptMessage> {
        val groupMembers = metadata["groupMembers"] as? JsonArray ?: return messages
        if (groupMembers.isEmpty()) return messages

        // Extract member names from group member character cards
        val memberNames = groupMembers.mapNotNull { element ->
            try {
                (element as? JsonObject)?.get("name")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }

        if (memberNames.size <= 1) return messages

        // For group chats, ensure assistant messages have proper name attribution
        // and maintain round-robin or metadata-specified ordering
        return messages.map { message ->
            if (message.role == MessageRole.Assistant && message.name == null) {
                // Try to attribute to the most recent group member who hasn't spoken
                message.copy(name = memberNames.firstOrNull())
            } else {
                message
            }
        }
    }

    private fun extractGroupMemberNames(metadata: JsonObject): String {
        val groupMembers = metadata["groupMembers"] as? JsonArray ?: return ""
        return groupMembers.mapNotNull { element ->
            try {
                (element as? JsonObject)?.get("name")?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }.joinToString(", ")
    }

    private fun extractMaxContextTokens(metadata: JsonObject): Int? {
        val element = metadata["maxContextTokens"] ?: return null
        return try {
            element.jsonPrimitive.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    private fun Int?.orElse(default: Int): Int = this ?: default

    private fun StringBuilder.appendBlock(title: String, value: String) {
        if (value.isBlank()) return
        appendLine("$title:")
        appendLine(value.trim())
    }

    /**
     * Splice extension-injected prompts into the message list.
     *
     * The convention mirrors SillyTavern's `extension_prompt_types`:
     * - `position == 2` (BEFORE_PROMPT): prepend before the system prompt.
     * - `position == 0` (IN_PROMPT): insert immediately after the leading
     *   run of system messages (i.e. after the system prompt).
     * - `position == 1` (IN_CHAT): depth-based insertion. `depth == 0`
     *   inserts at the very end of the message list, `depth == N` inserts
     *   N positions from the end. Within a depth, entries are grouped by
     *   role in the order system → user → assistant, matching ST's
     *   "most important go lower" rule.
     * - `position == -1` (NONE): skipped.
     */
    private fun applyExtensionInjections(
        messages: List<PromptMessage>,
        metadata: JsonObject,
    ): List<PromptMessage> {
        val injectedObj = metadata["injectedPrompts"] as? JsonObject ?: return messages
        if (injectedObj.isEmpty()) return messages

        val entries = mutableListOf<ExtensionInjection>()
        for ((_, entryElement) in injectedObj) {
            val entry = entryElement as? JsonObject ?: continue
            val value = runCatching { entry["value"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            if (value.isBlank()) continue
            val position = runCatching { entry["position"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
            val depth = runCatching { entry["depth"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 4
            val role = resolveExtensionInjectionRole(
                runCatching { entry["role"]?.jsonPrimitive?.content }.getOrNull(),
            )
            if (position == -1) continue // extension_prompt_types.NONE
            entries.add(ExtensionInjection(value, position, depth, role, entries.size))
        }
        if (entries.isEmpty()) return messages

        val beforePrompts = entries.filter { it.position == 2 }
        val afterSystemPrompts = entries.filter { it.position == 0 }
        val inChat = entries.filter { it.position == 1 }

        val result = messages.toMutableList()

        // BEFORE_PROMPT → prepend in arrival order.
        var frontIdx = 0
        for (entry in beforePrompts) {
            result.add(frontIdx, PromptMessage(role = entry.role, content = entry.value))
            frontIdx++
        }

        // IN_PROMPT → right after the leading run of system messages.
        val firstNonSystem = result.indexOfFirst { it.role != MessageRole.System }
        val inPromptBase = if (firstNonSystem < 0) result.size else firstNonSystem
        var inPromptIdx = inPromptBase
        for (entry in afterSystemPrompts) {
            result.add(inPromptIdx, PromptMessage(role = entry.role, content = entry.value))
            inPromptIdx++
        }

        // IN_CHAT at depth → deepest first to keep indices stable.
        val byDepth = inChat.groupBy { it.depth }.toSortedMap(reverseOrder())
        for (depth in byDepth.keys) {
            val atDepth = byDepth[depth] ?: emptyList()
            val insertIdx = (result.size - depth).coerceIn(0, result.size)
            val roleOrder = listOf(MessageRole.System, MessageRole.User, MessageRole.Assistant)
            var offset = 0
            for (role in roleOrder) {
                for (entry in atDepth.filter { it.role == role }) {
                    result.add(insertIdx + offset, PromptMessage(role = entry.role, content = entry.value))
                    offset++
                }
            }
        }

        return result
    }

    private fun resolveExtensionInjectionRole(raw: String?): MessageRole {
        if (raw == null) return MessageRole.System
        return when (raw.trim().lowercase()) {
            "0", "system" -> MessageRole.System
            "1", "user" -> MessageRole.User
            "2", "assistant", "char", "character" -> MessageRole.Assistant
            "tool" -> MessageRole.Tool
            else -> MessageRole.System
        }
    }

    private data class ExtensionInjection(
        val value: String,
        val position: Int,
        val depth: Int,
        val role: MessageRole,
        val order: Int,
    )

    private fun compatibilityWarnings(request: PromptBuildRequest): List<String> = buildList {
        if (request.character.raw.isNotEmpty()) {
            add("Raw SillyTavern character metadata is preserved but not fully interpreted yet.")
        }
        if (request.preset.raw.isNotEmpty()) {
            add("Provider-specific preset fields are preserved but adapter-specific mapping is incomplete.")
        }
    }
}
