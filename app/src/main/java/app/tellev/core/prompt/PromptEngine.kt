package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.model.Persona
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.StDataStore
import app.tellev.core.model.WorldBook
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_MAX_CONTEXT_TOKENS = 8192

interface PromptEngine {
    fun build(request: PromptBuildRequest): PromptBuildResult

    fun buildWithLocalVariableBackend(
        request: PromptBuildRequest,
        backend: LocalVariableBackend,
    ): PromptBuildResult = build(request)

    fun snapshotPromptTemplateVariables(): PromptTemplateVariableSnapshot = PromptTemplateVariableSnapshot()

    fun persistGlobalPromptTemplateVariables(variables: JsonObject) {}
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
    val promptTemplateVariableUpdates: PromptTemplateVariableUpdates = PromptTemplateVariableUpdates(),
)

@Serializable
data class PromptTemplateVariableSnapshot(
    val local: JsonObject = JsonObject(emptyMap()),
    val global: JsonObject = JsonObject(emptyMap()),
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

    /**
     * Update the EJS template settings used by the internal
     * [DefaultPromptTemplateProcessor].  Callers (typically the UI layer
     * after the user changes a setting) should persist the new settings
     * through [ExtensionSettingsStore] first, then call this method so
     * subsequent [build] calls respect the updated configuration.
     */
    fun updateEjsSettings(settings: EjsTemplateSettings) {
        (promptTemplateProcessor as? DefaultPromptTemplateProcessor)?.ejsSettings = settings
    }

    override fun snapshotPromptTemplateVariables(): PromptTemplateVariableSnapshot {
        val variableStore = (macroEngine as? DefaultMacroEngine)?.variableStore
            ?: return PromptTemplateVariableSnapshot()
        return PromptTemplateVariableSnapshot(
            local = variableStore.localObject(),
            global = variableStore.globalObject(),
        )
    }

    override fun persistGlobalPromptTemplateVariables(variables: JsonObject) {
        (macroEngine as? DefaultMacroEngine)?.variableStore?.replaceGlobal(variables)
    }

    override fun buildWithLocalVariableBackend(
        request: PromptBuildRequest,
        backend: LocalVariableBackend,
    ): PromptBuildResult {
        val variableStore = (macroEngine as? DefaultMacroEngine)?.variableStore
            ?: return build(request)
        return variableStore.withLocalBackend(backend) {
            build(request)
        }
    }

    override fun build(request: PromptBuildRequest): PromptBuildResult {
        // 1. Build MacroContext from request data
        val macroContext = buildMacroContext(request)

        // 2. Expand macros in all text fields
        val expandedCharacter = expandCharacterFields(request.character, macroContext)
        val expandedUserInput = macroEngine.expand(request.userInput, macroContext)

        // 3. Build search text for world book key matching
        val worldInfoScanDepth = request.metadata["worldInfoScanDepth"]?.jsonPrimitive?.intOrNull
            ?.coerceAtLeast(1) ?: 12
        val searchText = buildString {
            append(expandedUserInput)
            append('\n')
            request.messages.takeLast(worldInfoScanDepth).forEach { appendLine(it.content) }
            extensionInjectionScanText(request.metadata).forEach(::appendLine)
        }

        val maxContextTokens = request.preset.maxContextTokens
            ?: extractMaxContextTokens(request.metadata)
            ?: DEFAULT_MAX_CONTEXT_TOKENS
        val worldInfoTokenBudget = maxContextTokens
            ?.let { ((it.toLong() * 25L) / 100L).toInt() }
            ?.coerceAtLeast(1)

        // 4. Activate world book entries with depth/position support.
        // The expand callback runs each entry's content through macro
        // expansion AND the prompt-template processor so that [GENERATE]/@INJECT
        // instruction blocks are stripped here (their actual injection happens
        // later via promptTemplateProcessor.process) and EJS is resolved. This
        // mirrors the previous single-scope path that filtered world content
        // through systemPromptContentFor before splicing into the system prompt.
        // Recursion follows ST's world_info_recursive / world_info_max_recursion_steps
        // settings (0 steps = unlimited); keys are macro-expanded before matching.
        val worldInfoRecursive = request.metadata["worldInfoRecursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val worldInfoMaxRecursionSteps = request.metadata["worldInfoMaxRecursionSteps"]?.jsonPrimitive?.intOrNull ?: 0
        val worldScanner = WorldInfoScanner(
            maxRecursionSteps = if (worldInfoRecursive) {
                if (worldInfoMaxRecursionSteps > 0) worldInfoMaxRecursionSteps else Int.MAX_VALUE
            } else {
                0
            },
            maxContentTokens = worldInfoTokenBudget,
        )
        val worldScan = worldScanner.scan(
            entries = request.worldBooks.flatMap { it.entries },
            searchText = searchText,
            expand = {
                promptTemplateProcessor.systemPromptContentFor(
                    PromptTemplateWorldEntry(
                        id = it.id,
                        content = CharacterRegexApplier.applyWorldInfoForPrompt(
                            text = macroEngine.expand(it.content, macroContext),
                            character = request.character,
                            userName = request.persona?.name ?: "User",
                        ),
                        raw = it.raw,
                    ),
                )
            },
            keyExpand = { macroEngine.expand(it, macroContext) },
        )
        val activatedEntries = worldScan.allActivated.map { it.entry }

        // 5. Build system prompt
        val contextPresetObj = request.metadata["contextPreset"] as? JsonObject
        val contextPreset = contextPresetObj?.let { ContextTemplate.loadPreset(it) }

        fun templateWorldEntry(book: WorldBook, entry: app.tellev.core.model.WorldBookEntry) =
            PromptTemplateWorldEntry(
                id = entry.id,
                content = CharacterRegexApplier.applyWorldInfoForPrompt(
                    text = macroEngine.expand(entry.content, macroContext),
                    character = request.character,
                    userName = request.persona?.name ?: "User",
                ),
                raw = entry.raw,
                bookId = book.id,
                bookName = book.name,
                comment = entry.comment,
            )
        val promptTemplateWorldCatalog = request.worldBooks.flatMap { book ->
            book.entries.map { entry -> templateWorldEntry(book, entry) }
        }
        val promptTemplateWorldEntries = request.worldBooks.flatMap { book ->
            book.entries
                .filter { it in activatedEntries }
                .map { entry -> templateWorldEntry(book, entry) }
        }
        val systemPrompt = if (contextPreset != null) {
            buildSystemPromptWithContextTemplate(
                expandedCharacter = expandedCharacter,
                contextPreset = contextPreset,
                macroContext = macroContext,
                worldScan = worldScan,
                request = request,
            )
        } else {
            buildSystemPrompt(request, expandedCharacter, worldScan, macroContext)
        }

        // 6. Build prompt messages
        val visibleHistory = request.messages.filterNot { it.isHidden }
        val rawMessages = buildList {
            add(PromptMessage(role = MessageRole.System, content = systemPrompt))
            visibleHistory.forEachIndexed { index, message ->
                val expandedContent = macroEngine.expand(
                    message.swipes.getOrNull(message.swipeIndex) ?: message.content,
                    macroContext,
                )
                add(
                    PromptMessage(
                        role = when (message.role) {
                            MessageRole.Character -> MessageRole.Assistant
                            else -> message.role
                        },
                        name = message.name,
                        content = CharacterRegexApplier.applyForPrompt(
                            text = expandedContent,
                            role = message.role,
                            character = request.character,
                            userName = request.persona?.name ?: "User",
                            depth = visibleHistory.lastIndex - index,
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
        val preferCharPrompt = request.metadata["preferCharacterPrompt"]
            ?.jsonPrimitive?.booleanOrNull ?: true
        val presetOrderedMessages = applyPresetPromptOrder(
            messages = rawMessages,
            preset = request.preset,
            context = macroContext,
            character = expandedCharacter,
            personaDescription = request.persona?.description.orEmpty(),
            worldScan = worldScan,
            preferCharPrompt = preferCharPrompt,
        )
        val orderedMessages = applyGroupChatOrdering(presetOrderedMessages, request.metadata)

        // 8. Apply ST-Prompt-Template compatible EJS processing before token trimming/provider formatting.
        // Macro expansion above can mutate LOCAL/GLOBAL variables. Feed the
        // live scoped snapshots into EJS so execution order is preserved:
        // {{setvar::x::1}} followed by incvar('x') must produce 2, not 1.
        val variableStore = (macroEngine as? DefaultMacroEngine)?.variableStore
        val templateMetadata = if (variableStore == null) request.metadata else {
            val liveLocal = variableStore.localObject()
            val liveGlobal = variableStore.globalObject()
            JsonObject(request.metadata.toMutableMap().apply {
                put("promptTemplateLocalVariables", liveLocal)
                put("promptTemplateGlobalVariables", liveGlobal)
                put("promptTemplateVariables", buildJsonObject {
                    liveGlobal.forEach { (key, value) -> put(key, value) }
                    liveLocal.forEach { (key, value) -> put(key, value) }
                })
            })
        }
        val promptTemplateResult = promptTemplateProcessor.process(
            PromptTemplateRequest(
                messages = orderedMessages,
                context = macroContext,
                metadata = templateMetadata,
                worldEntries = promptTemplateWorldEntries,
                worldCatalog = promptTemplateWorldCatalog,
                currentWorldBookId = StDataStore.embeddedCharacterBookId(request.character.id),
            ),
        )
        val templatedMessages = promptTemplateResult.messages
        val templatedSystemPrompt = templatedMessages.firstOrNull()?.content ?: systemPrompt

        // 9. Apply token budget
        // templatedSystemPrompt already contains the world info + character
        // description text (both buildSystemPrompt and the context-template
        // path insert them into the system prompt). Passing them again as
        // separate worldInfo/characterDescription would double-count their
        // tokens against the budget and trim legitimate chat messages too
        // eagerly. Pass empties so each token is reserved only once.
        // Injections (extension prompts + WI AT_DEPTH) are collected first so
        // their tokens are reserved from the chat budget instead of being
        // spliced in unaccounted AFTER trimming (audit §12.2).
        val extensionInjections = collectExtensionInjections(request.metadata, worldScan.atDepth)
        val preferCharJailbreak = request.metadata["preferCharacterJailbreak"]
            ?.jsonPrimitive?.booleanOrNull ?: true
        val characterInjections = collectCharacterCardInjections(request.character, macroContext, preferCharJailbreak)
        val allInjectionsForBudget = extensionInjections + characterInjections
        val injectionTokens = injectionTokenCost(allInjectionsForBudget)
        val budgetedMessages = TokenBudget.fitToBudget(
            systemPrompt = templatedSystemPrompt,
            worldInfo = emptyList(),
            characterDescription = "",
            messages = templatedMessages.drop(1), // Drop system message, fitToBudget adds its own
            budget = (maxContextTokens - (request.preset.maxCompletionTokens ?: request.preset.maxTokens).orElse(0) - injectionTokens)
                .coerceAtLeast(0),
        )

        // 9.5. Splice in extension-injected prompts (authored by loaded
        // extensions via the ST-compatible `injectPrompts` JS API) and
        // character-card injections (depth_prompt + post_history_instructions).
        // Applied after budget trimming so injected system/user/assistant
        // messages survive into the request and instruct mode sees them too.
        val withInjections = applyExtensionInjections(budgetedMessages, allInjectionsForBudget)

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
            maxTokens = request.preset.maxCompletionTokens ?: request.preset.maxTokens,
            providerType = request.providerType,
            diagnostics = PromptDiagnostics(
                activatedWorldEntryIds = activatedEntries.map { it.id },
                estimatedTokenCount = estimatedTokens,
                warnings = compatibilityWarnings(request) + promptTemplateResult.warnings,
            ),
            promptTemplateVariableUpdates = promptTemplateResult.variableUpdates,
        )
    }

    private fun buildMacroContext(request: PromptBuildRequest): MacroContext {
        val visible = request.messages.filterNot { it.isHidden }
        val lastMessage = visible.lastOrNull()?.let(::messageContent).orEmpty()
        val lastUserMessage = visible
            .lastOrNull { it.role == MessageRole.User }
            ?.let(::messageContent)
            .orEmpty()
        val lastCharMessage = visible
            .lastOrNull { it.role == MessageRole.Character || it.role == MessageRole.Assistant }
            ?.let(::messageContent)
            .orEmpty()

        val groupMemberNames = extractGroupMemberNames(request.metadata)
        // js-slash-runner message scope: the last message that carries a
        // variables object at its current swipe.
        val messageVariables = visible
            .lastOrNull { it.variables.getOrNull(it.swipeIndex) != null }
            ?.let { it.variables[it.swipeIndex] }

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
            maxPromptTokens = request.preset.maxCompletionTokens ?: request.preset.maxTokens ?: 0,
            maxContextTokens = request.preset.maxContextTokens
                ?: extractMaxContextTokens(request.metadata)
                ?: DEFAULT_MAX_CONTEXT_TOKENS,
            // ── Step 5 gap-fill: SillyTavern macro parity ──
            personaDescription = request.persona?.description.orEmpty(),
            modelName = extractModelName(request.metadata, request.providerType),
            maxResponseTokens = extractMaxResponseTokens(request.metadata) ?: request.preset.maxCompletionTokens ?: request.preset.maxTokens ?: 0,
            inputText = request.userInput,
            lastUserMessage = lastUserMessage,
            lastCharMessage = lastCharMessage,
            lastMessageId = visible.lastIndex.toString(),
            alternateGreetings = request.character.alternateGreetings,
            messageVariables = messageVariables,
            characterVariables = extractCharacterVariables(request.character),
        )
    }

    /** Resolves the active content of a message, honoring the current swipe. */
    private fun messageContent(message: ChatMessage): String =
        message.swipes.getOrNull(message.swipeIndex) ?: message.content

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

    private fun buildSystemPrompt(
        request: PromptBuildRequest,
        expandedCharacter: CharacterCard,
        worldScan: WorldInfoScanner.ScanResult,
        macroContext: MacroContext,
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
        // ↑Char (position 0) + outlet (7) folded into before.
        appendWorldInfo(worldScan.before + worldScan.outlet)
        appendBlock("Character description", expandedCharacter.description)
        // ↓Char (position 1).
        appendWorldInfo(worldScan.after)
        appendBlock("Personality", expandedCharacter.personality)
        appendBlock("Scenario", expandedCharacter.scenario)
        // ↑AT (position 2) before persona.
        appendWorldInfo(worldScan.anTop)
        appendBlock("Persona", request.persona?.description?.let { macroEngine.expand(it, macroContext) }.orEmpty())
        // ↓AT (position 3) after persona.
        appendWorldInfo(worldScan.anBottom)
        // ↑EM (position 5) before example messages.
        appendWorldInfo(worldScan.emTop)
        appendBlock("Example messages", expandedCharacter.exampleMessages)
        // ↓EM (position 6) after example messages.
        appendWorldInfo(worldScan.emBottom)
        // Note: AT_DEPTH (position 4) entries are not part of the system
        // prompt; they are spliced into the chat history by
        // [applyExtensionInjections] as depth-based messages.
    }.trim()

    private fun StringBuilder.appendWorldInfo(entries: List<WorldInfoScanner.ActivatedEntry>) {
        val nonBlank = entries.map { it.content.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return
        appendLine("World info:")
        nonBlank.forEach { appendLine(it) }
    }

    private fun buildSystemPromptWithContextTemplate(
        expandedCharacter: CharacterCard,
        contextPreset: ContextPreset,
        macroContext: MacroContext,
        worldScan: WorldInfoScanner.ScanResult,
        request: PromptBuildRequest,
    ): String {
        // The context template only exposes wiBefore/wiAfter slots, so map the
        // 8 ST positions onto the two: before-character positions (before,
        // outlet, ANTop, EMTop) → wiBefore; after-character positions (after,
        // ANBottom, EMBottom) → wiAfter. AT_DEPTH is handled as chat-history
        // injection, not here.
        val entriesBefore = (worldScan.before + worldScan.outlet + worldScan.anTop + worldScan.emTop)
            .joinToString("\n") { it.content }
        val entriesAfter = (worldScan.after + worldScan.anBottom + worldScan.emBottom)
            .joinToString("\n") { it.content }

        // Resolve the system prompt content so {{system}} renders actual text (audit M10).
        val preferCharPrompt = request.metadata["preferCharacterPrompt"]?.jsonPrimitive?.booleanOrNull ?: true
        val charSysPrompt = (expandedCharacter.raw["data"] as? JsonObject)?.get("system_prompt")?.jsonPrimitive?.content?.trim()
        val systemContent = if (preferCharPrompt && !charSysPrompt.isNullOrEmpty()) {
            macroEngine.expand(charSysPrompt, macroContext)
        } else {
            "You are ${expandedCharacter.name}."
        }

        val enrichedContext = macroContext.copy(
            characterDescription = expandedCharacter.description,
            characterPersonality = expandedCharacter.personality,
            characterScenario = expandedCharacter.scenario,
            exampleMessages = expandedCharacter.exampleMessages,
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
    private fun applyPresetPromptOrder(
        messages: List<PromptMessage>,
        preset: GenerationPreset,
        context: MacroContext,
        character: CharacterCard,
        personaDescription: String,
        worldScan: WorldInfoScanner.ScanResult,
        preferCharPrompt: Boolean = true,
    ): List<PromptMessage> {
        if (preset.prompts.isEmpty() || messages.isEmpty()) return messages
        val unused = preset.promptsUnused.map { it.identifier }.toSet()
        val enabled = preset.prompts
            .filter { it.enabled && it.identifier !in unused }
            .sortedWith(compareBy({ it.order }, { it.identifier }))
        val system = messages.firstOrNull { it.role == MessageRole.System }
        val history = messages.filterNot { it === system }
        val worldBefore = (worldScan.before + worldScan.outlet + worldScan.anTop + worldScan.emTop)
            .joinToString("\n") { it.content.trim() }
        val worldAfter = (worldScan.after + worldScan.anBottom + worldScan.emBottom)
            .joinToString("\n") { it.content.trim() }
        val relativeMessages = mutableListOf<Pair<Int, PromptMessage>>()
        val ordered = mutableListOf<PromptMessage>()

        fun roleFor(value: String): MessageRole = when (value.lowercase()) {
            "user" -> MessageRole.User
            "assistant", "character" -> MessageRole.Assistant
            else -> MessageRole.System
        }

        enabled.forEach { prompt ->
            val identifier = prompt.identifier.lowercase().replace("_", "").replace("-", "")
            if (identifier in setOf("chathistory", "history")) {
                ordered += history
                return@forEach
            }
            val component = when (identifier) {
                "main", "system", "systemprompt" -> {
                    // Prefer the character card's system_prompt when the preset
                    // slot is empty and preferCharPrompt is enabled (ST:
                    // `chat_metadata.system_prompt ||
                    // character.data?.system_prompt`).
                    val charSysPrompt = (character.raw["data"] as? JsonObject)
                        ?.get("system_prompt")?.jsonPrimitive?.content?.trim()
                    prompt.content.takeIf { it.isNotBlank() }
                        ?: (if (preferCharPrompt) charSysPrompt?.takeIf { it.isNotBlank() } else null)
                        ?: "You are ${character.name}."
                }
                "worldinfobefore" -> worldBefore
                "worldinfoafter" -> worldAfter
                "chardescription", "characterdescription" -> character.description
                "charpersonality", "characterpersonality" -> character.personality
                "scenario" -> character.scenario
                "personadescription", "persona" -> macroEngine.expand(personaDescription, context)
                "dialogueexamples", "examplemessages", "examples" -> character.exampleMessages
                else -> prompt.content
            }
            if (component.isBlank()) return@forEach
            val message = PromptMessage(
                role = roleFor(prompt.role),
                content = macroEngine.expand(component, context),
            )
            if (prompt.relative) relativeMessages += prompt.depth.coerceAtLeast(0) to message
            else ordered += message
        }

        relativeMessages.forEach { (depth, message) ->
            val index = (ordered.size - depth).coerceIn(0, ordered.size)
            ordered.add(index, message)
        }
        return ordered
    }


    private fun applyGroupChatOrdering(
        messages: List<PromptMessage>,
        metadata: JsonObject,
    ): List<PromptMessage> {
        val memberNames = groupMemberNamesList(metadata)
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

    /**
     * Extract group member names from metadata. ChatViewModel writes
     * `groupMembers` as an array of name strings; the older object form
     * (`[{"name": ...}]`) is also accepted.
     */
    private fun groupMemberNamesList(metadata: JsonObject): List<String> {
        val groupMembers = metadata["groupMembers"] as? JsonArray ?: return emptyList()
        return groupMembers.mapNotNull { element ->
            when (element) {
                is JsonObject -> element["name"]?.jsonPrimitive?.content
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    private fun extractGroupMemberNames(metadata: JsonObject): String =
        groupMemberNamesList(metadata).joinToString(", ")

    private fun extractMaxContextTokens(metadata: JsonObject): Int? {
        val element = metadata["maxContextTokens"] ?: return null
        return try {
            element.jsonPrimitive.intOrNull
        } catch (_: Exception) {
            null
        }
    }

    /** {{model}} — selected model id, falling back to the provider type. */
    private fun extractModelName(metadata: JsonObject, providerType: String): String {
        val element = metadata["modelName"] ?: return providerType
        return try {
            element.jsonPrimitive.content
        } catch (_: Exception) {
            providerType
        }
    }

    /** {{maxResponse}} — max response tokens, falling back to the preset value. */
    private fun extractMaxResponseTokens(metadata: JsonObject): Int? {
        val element = metadata["maxResponseTokens"] ?: return null
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
     * Collect extension-injected prompts (ST `injectPrompts` API) and
     * world-info AT_DEPTH entries as splice-ready injections. Collected
     * BEFORE token trimming so their tokens can be reserved from the chat
     * budget — mirroring ST, where injections are added to the message pool
     * before the trim loop and real chat messages are dropped first
     * (openai.js:1325).
     */
    private fun collectExtensionInjections(
        metadata: JsonObject,
        wiDepthEntries: List<WorldInfoScanner.ActivatedEntry> = emptyList(),
    ): List<ExtensionInjection> {
        val injectedObj = metadata["injectedPrompts"] as? JsonObject ?: buildJsonObject { }

        val entries = mutableListOf<ExtensionInjection>()
        for ((_, entryElement) in injectedObj) {
            val entry = entryElement as? JsonObject ?: continue
            val value = runCatching { entry["value"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            if (value.isBlank()) continue
            val included = runCatching { entry["filter"]?.jsonPrimitive?.booleanOrNull }
                .getOrNull() ?: true
            if (!included) continue
            val position = runCatching { entry["position"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0
            val depth = runCatching { entry["depth"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 4
            val role = resolveExtensionInjectionRole(
                runCatching { entry["role"]?.jsonPrimitive?.content }.getOrNull(),
            )
            if (position == -1) continue // extension_prompt_types.NONE
            entries.add(ExtensionInjection(value, position, depth, role, entries.size))
        }
        // World-info AT_DEPTH entries → IN_CHAT (position 1) injections at the
        // entry's depth, with the entry's role. Reuses the same depth+role
        // grouping logic as extension injections.
        for (wi in wiDepthEntries) {
            if (wi.content.isBlank()) continue
            val role = resolveExtensionInjectionRole(wi.entry.role.toString())
            entries.add(ExtensionInjection(wi.content, 1, wi.entry.depth, role, entries.size))
        }
        return entries
    }

    /**
     * Character-card injections: depth_prompt and post_history_instructions.
     *
     * - `data.extensions.depth_prompt` is a character-specific author's note
     *   injected at [depth] (default 4) in the chat history with [role]
     *   (default "system"). Mirrors ST script.js:4422-4426
     *   `setExtensionPrompt(inject_ids.DEPTH_PROMPT, …, IN_CHAT, depth, …, role)`.
     * - `data.post_history_instructions` is a jailbreak prompt injected at
     *   depth 0 (very end of chat) as a system message. Mirrors ST's
     *   "jailbreak" / post-history behavior in openai.js prompt assembly.
     *
     * Both are macro-expanded before injection.
     */
    private fun collectCharacterCardInjections(
        character: CharacterCard,
        context: MacroContext,
        preferCharJailbreak: Boolean = true,
    ): List<ExtensionInjection> {
        val entries = mutableListOf<ExtensionInjection>()
        val data = character.raw["data"] as? JsonObject ?: character.raw
        val extensions = data["extensions"] as? JsonObject

        // depth_prompt
        val depthPrompt = extensions?.get("depth_prompt") as? JsonObject
        val depthPromptText = depthPrompt?.get("prompt")?.jsonPrimitive?.content?.trim()
        if (!depthPromptText.isNullOrEmpty()) {
            val expanded = macroEngine.expand(depthPromptText, context)
            if (expanded.isNotBlank()) {
                val depth = depthPrompt["depth"]?.jsonPrimitive?.intOrNull ?: 4
                val role = resolveExtensionInjectionRole(
                    depthPrompt["role"]?.jsonPrimitive?.content,
                )
                entries.add(ExtensionInjection(expanded, 1, depth, role, entries.size))
            }
        }

        // post_history_instructions (jailbreak) — injected at depth 0 as system.
        val jailbreak = data["post_history_instructions"]?.jsonPrimitive?.content?.trim()
        if (!jailbreak.isNullOrEmpty()) {
            val expanded = macroEngine.expand(jailbreak, context)
            if (expanded.isNotBlank()) {
                entries.add(ExtensionInjection(expanded, 1, 0, MessageRole.System, entries.size))
            }
        }

        return entries
    }

    /**
     * Extract `data.extensions.tavern_helper.variables` from the character card
     * as a JsonObject for the character variable scope. Returns null when the
     * card has no tavern_helper variables.
     */
    private fun extractCharacterVariables(character: CharacterCard): JsonObject? {
        val data = character.raw["data"] as? JsonObject ?: character.raw
        val extensions = data["extensions"] as? JsonObject ?: return null
        val tavernHelper = extensions["tavern_helper"] as? JsonObject ?: return null
        // tavern_helper might be stored as an array of [key, value] pairs (old format)
        val variables = tavernHelper["variables"]
        return when {
            variables is JsonObject -> variables
            variables is JsonArray -> {
                // Old format: [[key, value], ...] -> object
                val map = variables.mapNotNull { element ->
                    val pair = element as? JsonArray ?: return@mapNotNull null
                    val key = pair.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
                    val value = pair.getOrNull(1) ?: return@mapNotNull null
                    key to value
                }.toMap()
                if (map.isEmpty()) null else JsonObject(map)
            }
            else -> null
        }
    }
    private fun injectionTokenCost(entries: List<ExtensionInjection>): Int =
        entries.sumOf { TokenBudget.estimateTokens(it.value) + 4 }

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
        entries: List<ExtensionInjection>,
    ): List<PromptMessage> {
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

/** Prompt injections marked for scanning contribute keys but are not necessarily sent (position NONE). */
internal fun extensionInjectionScanText(metadata: JsonObject): List<String> {
    val injected = metadata["injectedPrompts"] as? JsonObject ?: return emptyList()
    return injected.values.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        val included = runCatching { entry["filter"]?.jsonPrimitive?.booleanOrNull }
            .getOrNull() ?: true
        if (!included) return@mapNotNull null
        val shouldScan = runCatching {
            (entry["shouldScan"] ?: entry["should_scan"])?.jsonPrimitive?.booleanOrNull
        }.getOrNull() == true
        if (!shouldScan) return@mapNotNull null
        runCatching { entry["value"]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
