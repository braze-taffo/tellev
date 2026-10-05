package app.tellev.core.prompt

import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.LocalVariableBackend
import app.tellev.core.model.ChatMessage
import app.tellev.core.model.MessageRole
import app.tellev.core.model.WorldBookEntry
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
import java.util.IdentityHashMap

interface PromptEngine {
    fun build(request: PromptBuildRequest): PromptBuildResult

    fun processChatText(
        text: String, role: MessageRole, character: app.tellev.core.model.CharacterCard?,
        preset: app.tellev.core.model.GenerationPreset?, context: MacroContext,
        expandMacros: Boolean = true, depth: Int = 0, isEdit: Boolean = false,
        includeNormal: Boolean = true,
    ): ProcessedChatText = ChatTextProcessing.process(text, role, character, preset, context,
        expandMacros = expandMacros, depth = depth, isEdit = isEdit, includeNormal = includeNormal)

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

    override fun processChatText(
        text: String, role: MessageRole, character: app.tellev.core.model.CharacterCard?,
        preset: app.tellev.core.model.GenerationPreset?, context: MacroContext,
        expandMacros: Boolean, depth: Int, isEdit: Boolean,
        includeNormal: Boolean,
    ): ProcessedChatText = ChatTextProcessing.process(text, role, character, preset, context, macroEngine,
        expandMacros, depth, isEdit, includeNormal)

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
        val rawGeneration = request.metadata["tavernRawGeneration"]?.jsonPrimitive?.booleanOrNull == true
        // 1. Build MacroContext from request data
        val macroContext = PromptMacroContextBuilder.buildMacroContext(request)

        // 2. Expand macros in all text fields
        val expandedCharacter = PromptMacroContextBuilder.expandCharacterFields(request.character, macroContext, macroEngine)
        val expandedUserInput = if (request.metadata["userInputNormalProcessed"]?.jsonPrimitive?.booleanOrNull == true) {
            request.userInput
        } else macroEngine.expand(request.userInput, macroContext)
        val quietPrompt = request.quietPrompt?.takeIf { it.isNotBlank() }
            ?.let { macroEngine.expand(it, macroContext) }

        // 3. Build search text for world book key matching
        val worldInfoScanDepth = request.metadata["worldInfoScanDepth"]?.jsonPrimitive?.intOrNull
            ?.coerceAtLeast(1) ?: 12
        val userName = request.persona?.name ?: "User"
        val scanMessages = (request.messages.filterNot { it.isHidden }.map {
            "${it.name.ifBlank { if (it.role == MessageRole.User) userName else request.character.name }}: ${it.swipes.getOrNull(it.swipeIndex) ?: it.content}"
        } + if (quietPrompt == null) listOf("$userName: $expandedUserInput") else emptyList()).asReversed()
        fun searchTextAtDepth(depth: Int) = buildString {
            if (depth <= 0) return@buildString
            // WorldInfoBuffer.get: newest first, depth includes the pending user floor.
            scanMessages.take(depth.coerceAtMost(1000)).forEach { append('\u0001'); appendLine(it.trim()) }
            PromptInjectionProcessor.extensionInjectionScanText(request.metadata).forEach(::appendLine)
            quietPrompt?.let(::appendLine)
        }
        val searchText = searchTextAtDepth(worldInfoScanDepth)

        val maxContextTokens = request.preset.maxContextTokens
            ?: PromptMacroContextBuilder.extractMaxContextTokens(request.metadata)
            ?: DEFAULT_MAX_CONTEXT_TOKENS
        // The output budget only rides the request when actually configured —
        // sending the internal default (131072) as max_tokens 400s strict
        // relays and local servers. The defaulted value still bounds the
        // internal context math; adapters fall back to provider-appropriate
        // behavior when it is null (Anthropic's required field → 8192).
        val configuredMaxCompletionTokens = request.preset.maxCompletionTokens
            ?: request.preset.maxTokens
            ?: PromptMacroContextBuilder.extractMaxResponseTokens(request.metadata)
        val maxCompletionTokens = configuredMaxCompletionTokens ?: DEFAULT_MAX_COMPLETION_TOKENS
        val worldInfoTokenBudget = maxContextTokens
            ?.let { ((it.toLong() * 25L) / 100L).toInt() }
            ?.coerceAtLeast(1)

        // 4. Activate world book entries with depth/position support
        val worldInfoRecursive = request.metadata["worldInfoRecursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val worldInfoMaxRecursionSteps = request.metadata["worldInfoMaxRecursionSteps"]?.jsonPrimitive?.intOrNull ?: 0
        // 条目内容的宏展开每次构建只允许发生一次：incvar/setvar 等副作用宏若在
        // 扫描、catalog、激活列表三处重复执行，变量会在一轮生成里被多次累加。
        val expandedWorldContent = IdentityHashMap<WorldBookEntry, String>()
        fun expandWorldContentOnce(entry: WorldBookEntry, content: String = entry.content): String =
            expandedWorldContent.getOrPut(entry) { macroEngine.expand(content, macroContext) }
        val worldScanner = WorldInfoScanner(
            maxRecursionSteps = if (worldInfoRecursive) {
                if (worldInfoMaxRecursionSteps > 0) worldInfoMaxRecursionSteps else Int.MAX_VALUE
            } else {
                0
            },
            maxContentTokens = worldInfoTokenBudget,
        )
        val worldBookNamesByEntryId = request.worldBooks
            .flatMap { book -> book.entries.map { entry -> entry.id to book.name } }
            .toMap()
        val worldScanDiagnostics = WorldInfoScanner.ScanDiagnostics()
        val worldScan = worldScanner.scan(
            entries = request.worldBooks.flatMap { it.entries },
            searchText = searchText,
            diagnostics = worldScanDiagnostics,
            expand = { entry, content ->
                promptTemplateProcessor.systemPromptContentFor(
                    PromptTemplateWorldEntry(
                        id = entry.id,
                        content = CharacterRegexApplier.applyWorldInfoForPrompt(
                            text = expandWorldContentOnce(entry, content),
                            character = request.character,
                            userName = request.persona?.name ?: "User",
                            preset = request.preset,
                            macroExpander = { macroEngine.expand(it, macroContext) },
                        ),
                        raw = entry.raw,
                    ),
                )
            },
            keyExpand = { macroEngine.expand(it, macroContext) },
            entrySearchText = { entry -> searchTextAtDepth(entry.scanDepth ?: worldInfoScanDepth) },
        )
        val activatedEntries = worldScan.allActivated.map { it.entry }

        // 5. Build system prompt
        val contextPresetObj = request.metadata["contextPreset"] as? JsonObject
        val contextPreset = contextPresetObj?.let { ContextTemplate.loadPreset(it) }

        fun templateWorldEntry(book: app.tellev.core.model.WorldBook, entry: app.tellev.core.model.WorldBookEntry) =
            PromptTemplateWorldEntry(
                id = entry.id,
                content = CharacterRegexApplier.applyWorldInfoForPrompt(
                    text = expandWorldContentOnce(entry),
                    character = request.character,
                    userName = request.persona?.name ?: "User",
                    preset = request.preset,
                    macroExpander = { macroEngine.expand(it, macroContext) },
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
        // Both projections must share one evaluation of side-effecting macros.
        val expandedHistory = visibleHistory.map { message ->
            val body = message.reasoningParts().body
            // script.js expands chat[0].mes before filtering coreChat, including
            // a user first floor. Later stored bodies are not fully expanded.
            if (message.id == request.messages.firstOrNull()?.id) {
                macroEngine.expand(body, macroContext)
            } else body
        }
        // Expose the same processed floors to templates and the model, retaining
        // ST's message shape and its special handling of the original first floor.
        val chatSnippets = visibleHistory.mapIndexed { index, message ->
            PromptTemplateChatMessage(
                id = index,
                isUser = message.role == MessageRole.User,
                isSystem = message.role == MessageRole.System,
                name = message.name,
                content = expandedHistory[index],
            )
        }
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
                val expandedContent = expandedHistory[index]
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
                            macroExpander = { macroEngine.expand(it, macroContext) },
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
                        macroExpander = { macroEngine.expand(it, macroContext) },
                    ),
                    channel = if (rawGeneration) "user_input" else CHANNEL_CHAT,
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
            rawGeneration = rawGeneration,
            overrides = request.metadata["tavernPromptOverrides"] as? JsonObject ?: JsonObject(emptyMap()),
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
                messageVariables = macroContext.messageVariables,
                chat = chatSnippets,
            ),
        )
        val templatedMessages = promptTemplateResult.messages
        val templatedSystemPrompt = templatedMessages.firstOrNull()?.content ?: systemPrompt

        // 9. Apply token budget
        val extensionInjections = PromptInjectionProcessor.collectExtensionInjections(
            request.metadata,
            if (request.metadata["tavernWithDepthEntries"]?.jsonPrimitive?.booleanOrNull == false) emptyList() else worldScan.atDepth,
            macroContext,
            macroEngine,
        )
        val characterInjections = PromptInjectionProcessor.collectCharacterCardInjections(
            character = request.character,
            context = macroContext,
            preferCharJailbreak = preferCharJailbreak,
            includeJailbreak = !rawGeneration && !request.preset.hasJailbreakSlot(),
            macroEngine = macroEngine,
        )
        val anInjections = PromptInjectionProcessor.collectAuthorsNoteWorldInfo(worldScan)
        val memoryInjection = request.metadata["tellevMemoryContext"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(ExtensionInjection(it, 0, 0, MessageRole.System, 0, key = "tellev-core-memory")) }
            ?: emptyList()
        val allInjectionsForBudget =
            presetOrder.absoluteInjections + extensionInjections + characterInjections + anInjections + memoryInjection
        val injectionTokens = PromptInjectionProcessor.injectionTokenCost(allInjectionsForBudget)
        val quietTokens = quietPrompt?.let { TokenBudget.estimateTokens(it) + 4 } ?: 0
        // Outgoing image attachments ride the final user turn (G2) and cost
        // real context — roughly 800 tokens per image at mainstream
        // resolutions. Reserving them keeps a with-image request from
        // overflowing the window after the history was fitted.
        val imageAttachmentCount = request.messages.lastOrNull { it.role == MessageRole.User }
            ?.attachments?.count { it.mimeType.startsWith("image/") } ?: 0
        val attachmentTokens = imageAttachmentCount * IMAGE_ATTACHMENT_TOKENS
        val budgetedRaw = TokenBudget.fitToBudget(
            systemPrompt = if (rawGeneration) "" else templatedSystemPrompt,
            worldInfo = emptyList(),
            characterDescription = "",
            messages = if (rawGeneration) templatedMessages else templatedMessages.drop(1),
            budget = (maxContextTokens - (maxCompletionTokens ?: 0) - injectionTokens - quietTokens - attachmentTokens)
                .coerceAtLeast(0),
        )
        val budgetedMessages = if (rawGeneration) budgetedRaw.drop(1) else if (budgetedRaw.isEmpty()) budgetedRaw else {
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

        fun worldEntryTitle(entry: app.tellev.core.model.WorldBookEntry): String? = entry.comment.takeIf { it.isNotBlank() }
        fun worldEntryBookName(entry: app.tellev.core.model.WorldBookEntry): String? =
            worldBookNamesByEntryId[entry.id]?.takeIf { it.isNotBlank() }

        return PromptBuildResult(
            messages = finalMessages,
            stop = stopSequences,
            maxTokens = configuredMaxCompletionTokens,
            providerType = request.providerType,
            diagnostics = PromptDiagnostics(
                activatedWorldEntryIds = activatedEntries.map { it.id },
                estimatedTokenCount = estimatedTokens,
                warnings = PromptPostProcessor.compatibilityWarnings(request) + promptTemplateResult.warnings,
                worldBookHits = worldScanDiagnostics.hits.map { hit ->
                    WorldEntryHit(
                        entryId = hit.entry.id,
                        title = worldEntryTitle(hit.entry),
                        bookName = worldEntryBookName(hit.entry),
                        matchedKeys = hit.matchedKeys,
                        matchedSecondaryKeys = hit.matchedSecondaryKeys,
                        unconditional = hit.unconditional,
                        recursionLevel = hit.recursionLevel,
                        tokens = hit.tokens,
                    )
                },
                rejectedWorldEntries = worldScanDiagnostics.rejections.map { rejection ->
                    WorldEntryRejection(
                        entryId = rejection.entry.id,
                        title = worldEntryTitle(rejection.entry),
                        bookName = worldEntryBookName(rejection.entry),
                        reason = rejection.reason,
                    )
                },
            ),
            promptTemplateVariableUpdates = promptTemplateResult.variableUpdates,
        )
    }
}

/** Prompt injections marked for scanning contribute keys but are not necessarily sent (position NONE). */
internal fun extensionInjectionScanText(metadata: JsonObject): List<String> =
    PromptInjectionProcessor.extensionInjectionScanText(metadata)
