package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.model.reasoningParts
import app.tellev.core.prompt.PromptInjectionProcessor.hasJailbreakSlot
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

interface PromptEngine {
    fun build(request: PromptBuildRequest): PromptBuildResult

    fun buildWithLocalVariableBackend(
        request: PromptBuildRequest,
        backend: LocalVariableBackend,
    ): PromptBuildResult = build(request)

    suspend fun buildWithLocalVariableBackendAsync(request: PromptBuildRequest, backend: LocalVariableBackend): PromptBuildResult =
        withContext(Dispatchers.Default) {
            buildWithLocalVariableBackend(request, backend)
        }

    fun snapshotPromptTemplateVariables(): PromptTemplateVariableSnapshot = PromptTemplateVariableSnapshot()

    fun persistGlobalPromptTemplateVariables(variables: JsonObject) {}
}

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
        val macroContext = PromptMacroContextBuilder.buildMacroContext(request)

        // 2. Expand macros in all text fields
        val expandedCharacter = PromptMacroContextBuilder.expandCharacterFields(request.character, macroContext, macroEngine)
        val expandedUserInput = macroEngine.expand(request.userInput, macroContext)
        val quietPrompt = request.quietPrompt?.takeIf { it.isNotBlank() }
            ?.let { macroEngine.expand(it, macroContext) }

        // 3. Build search text for world book key matching
        val worldInfoScanDepth = request.metadata["worldInfoScanDepth"]?.jsonPrimitive?.intOrNull
            ?.coerceAtLeast(1) ?: 12
        val userName = request.persona?.name ?: "User"
        val searchText = buildString {
            append(userName)
            append(": ")
            append(expandedUserInput)
            append('\n')
            request.messages.filterNot { it.isHidden }.takeLast(worldInfoScanDepth).forEach {
                append(it.name.ifBlank { if (it.role == MessageRole.User) userName else request.character.name })
                append(": ")
                appendLine(it.swipes.getOrNull(it.swipeIndex) ?: it.content)
            }
            PromptInjectionProcessor.extensionInjectionScanText(request.metadata).forEach(::appendLine)
            quietPrompt?.let(::appendLine)
        }

        val maxContextTokens = request.preset.maxContextTokens
            ?: PromptMacroContextBuilder.extractMaxContextTokens(request.metadata)
            ?: DEFAULT_MAX_CONTEXT_TOKENS
        val maxCompletionTokens = request.preset.maxCompletionTokens
            ?: request.preset.maxTokens
            ?: PromptMacroContextBuilder.extractMaxResponseTokens(request.metadata)
            ?: DEFAULT_MAX_COMPLETION_TOKENS
        val worldInfoTokenBudget = maxContextTokens
            ?.let { ((it.toLong() * 25L) / 100L).toInt() }
            ?.coerceAtLeast(1)

        // 4. Activate world book entries with depth/position support
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
                            preset = request.preset,
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

        fun templateWorldEntry(book: app.tellev.core.model.WorldBook, entry: app.tellev.core.model.WorldBookEntry) =
            PromptTemplateWorldEntry(
                id = entry.id,
                content = CharacterRegexApplier.applyWorldInfoForPrompt(
                    text = macroEngine.expand(entry.content, macroContext),
                    character = request.character,
                    userName = request.persona?.name ?: "User",
                    preset = request.preset,
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
            PromptSystemBuilder.buildSystemPromptWithContextTemplate(
                expandedCharacter = expandedCharacter,
                contextPreset = contextPreset,
                macroContext = macroContext,
                worldScan = worldScan,
                request = request,
                macroEngine = macroEngine,
            )
        } else {
            PromptSystemBuilder.buildSystemPrompt(request, expandedCharacter, worldScan, macroContext, macroEngine)
        }

        // 6. Build prompt messages
        val visibleHistory = request.messages.filterNot { it.isHidden }
        val groupNames = PromptMacroContextBuilder.groupMemberNamesList(request.metadata)
        val newChatMarker = if (groupNames.size > 1) {
            request.preset.raw["new_group_chat_prompt"]?.jsonPrimitive?.contentOrNull
                ?: "[Start a new group chat. Group members: {{group}}]"
        } else {
            request.preset.raw["new_chat_prompt"]?.jsonPrimitive?.contentOrNull
                ?: "[Start a new Chat]"
        }
        val rawMessages = buildList {
            add(PromptMessage(role = MessageRole.System, content = systemPrompt, channel = CHANNEL_MAIN))
            if (newChatMarker.isNotBlank()) {
                add(
                    PromptMessage(
                        role = MessageRole.System,
                        content = macroEngine.expand(newChatMarker, macroContext),
                        channel = CHANNEL_MARKER,
                    ),
                )
            }
            visibleHistory.forEachIndexed { index, message ->
                val parts = message.reasoningParts()
                if (parts.body.isBlank() && parts.reasoning.isNotBlank()) return@forEachIndexed
                val expandedContent = macroEngine.expand(parts.body, macroContext)
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
                            depth = visibleHistory.drop(index + 1).count {
                                it.role != MessageRole.System && it.role != MessageRole.Tool && !it.isHidden
                            },
                            preset = request.preset,
                            includeNormal = !CharacterRegexApplier.isNormalProcessed(message),
                        ),
                        channel = CHANNEL_CHAT,
                    ),
                )
            }
            if (quietPrompt == null) add(
                PromptMessage(
                    role = MessageRole.User,
                    name = request.persona?.name,
                    content = CharacterRegexApplier.applyForPrompt(
                        text = expandedUserInput,
                        role = MessageRole.User,
                        character = request.character,
                        userName = request.persona?.name ?: "User",
                        depth = 0,
                        preset = request.preset,
                        includeNormal = request.metadata["userInputNormalProcessed"]
                            ?.jsonPrimitive?.booleanOrNull != true,
                    ),
                    channel = CHANNEL_CHAT,
                ),
            )
        }

        // 7. Handle preset prompt order and group chat ordering
        val preferCharPrompt = request.metadata["preferCharacterPrompt"]
            ?.jsonPrimitive?.booleanOrNull ?: true
        val preferCharJailbreak = request.metadata["preferCharacterJailbreak"]
            ?.jsonPrimitive?.booleanOrNull ?: true
        val presetOrder = PromptOrderProcessor.applyPresetPromptOrder(
            messages = rawMessages,
            preset = request.preset,
            context = macroContext,
            character = expandedCharacter,
            personaDescription = request.persona?.description.orEmpty(),
            worldScan = worldScan,
            preferCharPrompt = preferCharPrompt,
            preferCharJailbreak = preferCharJailbreak,
            macroEngine = macroEngine,
        )
        val orderedMessages = PromptOrderProcessor.applyGroupChatOrdering(presetOrder.messages, request.metadata)

        // 8. Apply ST-Prompt-Template compatible EJS processing
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
        val extensionInjections = PromptInjectionProcessor.collectExtensionInjections(
            request.metadata,
            worldScan.atDepth,
            macroContext,
            macroEngine,
        )
        val characterInjections = PromptInjectionProcessor.collectCharacterCardInjections(
            character = request.character,
            context = macroContext,
            preferCharJailbreak = preferCharJailbreak,
            includeJailbreak = !request.preset.hasJailbreakSlot(),
            macroEngine = macroEngine,
        )
        val anInjections = PromptInjectionProcessor.collectAuthorsNoteWorldInfo(worldScan)
        val allInjectionsForBudget =
            presetOrder.absoluteInjections + extensionInjections + characterInjections + anInjections
        val injectionTokens = PromptInjectionProcessor.injectionTokenCost(allInjectionsForBudget)
        val quietTokens = quietPrompt?.let { TokenBudget.estimateTokens(it) + 4 } ?: 0
        val budgetedRaw = TokenBudget.fitToBudget(
            systemPrompt = templatedSystemPrompt,
            worldInfo = emptyList(),
            characterDescription = "",
            messages = templatedMessages.drop(1),
            budget = (maxContextTokens - (maxCompletionTokens ?: 0) - injectionTokens - quietTokens)
                .coerceAtLeast(0),
        )
        val budgetedMessages = if (budgetedRaw.isEmpty()) budgetedRaw else {
            listOf(budgetedRaw.first().copy(channel = templatedMessages.firstOrNull()?.channel)) + budgetedRaw.drop(1)
        }

        // 9.5. Splice in extension-injected prompts and character-card injections
        val withInjections = PromptInjectionProcessor.applyExtensionInjections(budgetedMessages, allInjectionsForBudget)
        val withControlPrompt = if (quietPrompt == null) withInjections else
            withInjections + PromptMessage(MessageRole.System, content = quietPrompt)

        // 10. Check for instruct mode
        val instructPresetObj = request.metadata["instructPreset"] as? JsonObject
        val instructPreset = instructPresetObj?.let { InstructMode.loadPreset(it) }

        val namesApplied = (if (instructPreset != null) {
            val instructText = InstructMode.applyInstruct(
                messages = withControlPrompt,
                preset = instructPreset,
                macroEngine = macroEngine,
                macroContext = macroContext,
            )
            listOf(PromptMessage(role = MessageRole.User, content = instructText))
        } else {
            PromptPostProcessor.applyNamesBehavior(withControlPrompt, request.metadata, request.preset)
        }).filter { it.content.isNotBlank() }

        val squashed = if (request.preset.raw["squash_system_messages"]
                ?.jsonPrimitive?.booleanOrNull == true
        ) PromptPostProcessor.squashAdjacentSystemMessages(namesApplied) else namesApplied

        val assistantPrefill = if (quietPrompt != null) "" else request.preset.raw["assistant_prefill"]
            ?.jsonPrimitive?.contentOrNull.orEmpty()
            .let { macroEngine.expand(it, macroContext) }
        val finalMessages = if (assistantPrefill.isBlank() || instructPreset != null) squashed
            else squashed + PromptMessage(MessageRole.Assistant, content = assistantPrefill)

        // 11. Build stop sequences
        val stopSequences = PromptPostProcessor.buildStopSequences(request.preset.stop, instructPreset, contextPreset)

        // 12. Estimate token count for diagnostics
        val estimatedTokens = TokenBudget.estimateTotalTokens(finalMessages)

        return PromptBuildResult(
            messages = finalMessages,
            stop = stopSequences,
            maxTokens = maxCompletionTokens,
            providerType = request.providerType,
            diagnostics = PromptDiagnostics(
                activatedWorldEntryIds = activatedEntries.map { it.id },
                estimatedTokenCount = estimatedTokens,
                warnings = PromptPostProcessor.compatibilityWarnings(request) + promptTemplateResult.warnings,
            ),
            promptTemplateVariableUpdates = promptTemplateResult.variableUpdates,
        )
    }
}

/** Prompt injections marked for scanning contribute keys but are not necessarily sent (position NONE). */
internal fun extensionInjectionScanText(metadata: JsonObject): List<String> =
    PromptInjectionProcessor.extensionInjectionScanText(metadata)
