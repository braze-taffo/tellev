package app.tellev.core.prompt

import app.tellev.core.model.reasoningParts
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_MAX_CONTEXT_TOKENS = 1_000_000
private const val DEFAULT_MAX_COMPLETION_TOKENS = 128 * 1_024

/** [PromptMessage.channel] of the main/system prompt — anchor for BEFORE_PROMPT/IN_PROMPT injections. */
internal const val CHANNEL_MAIN = "main"

/** [PromptMessage.channel] of chat-history (and pending user input) messages — anchor span for depth injections. */
internal const val CHANNEL_CHAT = "chat"

/** [PromptMessage.channel] of structural markers ('[Start a new Chat]', '[Example Chat]') —
 * excluded from the prompt-template numeric index space so [GENERATE:N]/@INJECT index=N
 * keep addressing "system prompt, then chat messages". */
internal const val CHANNEL_MARKER = "marker"

/** Internal injection position: standalone message after the chat span (fallback-path PHI). */
private const val POSITION_AFTER_CHAT = 99

/** ST {{original}} macro inside character-card main/jailbreak overrides (PromptManager preparePrompt). */
private val ORIGINAL_MACRO = Regex("\\{\\{original\\}\\}", RegexOption.IGNORE_CASE)

/** ST splits example messages into chats on `<START>` lines (case-insensitive). */
private val EXAMPLE_CHAT_SPLIT = Regex("<START>", RegexOption.IGNORE_CASE)

interface PromptEngine {
    fun build(request: PromptBuildRequest): PromptBuildResult

    fun buildWithLocalVariableBackend(
        request: PromptBuildRequest,
        backend: LocalVariableBackend,
    ): PromptBuildResult = build(request)

    suspend fun buildWithLocalVariableBackendAsync(request: PromptBuildRequest, backend: LocalVariableBackend): PromptBuildResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            buildWithLocalVariableBackend(request, backend)
        }

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
    /**
     * Which prompt slot this message belongs to. Depth injections anchor to the
     * span of "chat" messages and BEFORE_PROMPT/IN_PROMPT extension prompts
     * anchor to the "main" message, mirroring ST where injections splice into
     * the chat-history array (openai.js populationInjectionPrompts) and
     * relative extension prompts insert around the `main` prompt (injectToMain).
     */
    val channel: String? = null,
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

        // 3. Build search text for world book key matching. ST prefixes each
        // scanned message with the speaker name (script.js:4565 chatForWI,
        // world_info_include_names defaults to true), so name keys match.
        val worldInfoScanDepth = request.metadata["worldInfoScanDepth"]?.jsonPrimitive?.intOrNull
            ?.coerceAtLeast(1) ?: 12
        val userName = request.persona?.name ?: "User"
        val searchText = buildString {
            append(userName)
            append(": ")
            append(expandedUserInput)
            append('\n')
            // ST scans coreChat = chat.filter(x => !x.is_system) (script.js:4560-4565);
            // hidden messages never feed world-info activation.
            request.messages.filterNot { it.isHidden }.takeLast(worldInfoScanDepth).forEach {
                append(it.name.ifBlank { if (it.role == MessageRole.User) userName else request.character.name })
                append(": ")
                appendLine(it.swipes.getOrNull(it.swipeIndex) ?: it.content)
            }
            extensionInjectionScanText(request.metadata).forEach(::appendLine)
        }

        val maxContextTokens = request.preset.maxContextTokens
            ?: extractMaxContextTokens(request.metadata)
            ?: DEFAULT_MAX_CONTEXT_TOKENS
        val maxCompletionTokens = request.preset.maxCompletionTokens
            ?: request.preset.maxTokens
            ?: extractMaxResponseTokens(request.metadata)
            ?: DEFAULT_MAX_COMPLETION_TOKENS
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

        fun templateWorldEntry(book: WorldBook, entry: app.tellev.core.model.WorldBookEntry) =
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

        // 6. Build prompt messages. The combined system prompt is the "main"
        // anchor; history and the pending user input are "chat" — depth
        // injections must land inside the chat span only (ST splices
        // injections into the chat-history array, openai.js:1325).
        val visibleHistory = request.messages.filterNot { it.isHidden }
        // ST opens the chat-history block with the new-chat marker
        // (openai.js:107-108, populateChatHistory): '[Start a new Chat]', or
        // the group variant listing the members.
        val groupNames = groupMemberNamesList(request.metadata)
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
                // A reasoning-only completion is not an empty assistant turn for the API.
                if (parts.body.isBlank() && parts.reasoning.isNotBlank()) return@forEachIndexed
                val expandedContent = macroEngine.expand(
                    parts.body,
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
            add(
                PromptMessage(
                    role = MessageRole.User,
                    name = request.persona?.name,
                    // The turn being sent has to go through USER_INPUT regex
                    // scripts too (ST runs them on the outgoing message, not
                    // only once it has become history). Skipping it meant a
                    // card's input-rewriting regex took effect one turn late.
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
        val presetOrder = applyPresetPromptOrder(
            messages = rawMessages,
            preset = request.preset,
            context = macroContext,
            character = expandedCharacter,
            personaDescription = request.persona?.description.orEmpty(),
            worldScan = worldScan,
            preferCharPrompt = preferCharPrompt,
            preferCharJailbreak = preferCharJailbreak,
        )
        val orderedMessages = applyGroupChatOrdering(presetOrder.messages, request.metadata)

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
        val extensionInjections = collectExtensionInjections(request.metadata, worldScan.atDepth, macroContext)
        // PHI routes through the preset's `jailbreak` slot when a prompt-manager
        // preset is active (ST openai.js:1496-1504); the standalone depth-0
        // injection only applies in the presetless fallback path.
        val characterInjections = collectCharacterCardInjections(
            character = request.character,
            context = macroContext,
            preferCharJailbreak = preferCharJailbreak,
            // ...but only when the preset actually *has* that slot. Keying on
            // "preset has no prompts at all" silently dropped PHI for every
            // preset that simply lacks a jailbreak entry — including the
            // default preset shipped before it gained one, which is still on
            // disk for existing installs.
            includeJailbreak = !request.preset.hasJailbreakSlot(),
        )
        // WI ↑AT/↓AT entries attach to the author's note slot, which ST injects
        // in-chat at depth 4 as system by default (authors-note.js:272-274,
        // world-info.js:5149-5153). tellev has no AN text, so the entries alone
        // form the note payload.
        val anInjections = collectAuthorsNoteWorldInfo(worldScan)
        val allInjectionsForBudget =
            presetOrder.absoluteInjections + extensionInjections + characterInjections + anInjections
        val injectionTokens = injectionTokenCost(allInjectionsForBudget)
        val budgetedRaw = TokenBudget.fitToBudget(
            systemPrompt = templatedSystemPrompt,
            worldInfo = emptyList(),
            characterDescription = "",
            messages = templatedMessages.drop(1), // Drop system message, fitToBudget adds its own
            budget = (maxContextTokens - maxCompletionTokens.orElse(0) - injectionTokens)
                .coerceAtLeast(0),
        )
        // fitToBudget rebuilds the head system message from plain text, which
        // drops its channel marker; restore it so injection anchoring works.
        val budgetedMessages = if (budgetedRaw.isEmpty()) budgetedRaw else {
            listOf(budgetedRaw.first().copy(channel = templatedMessages.firstOrNull()?.channel)) + budgetedRaw.drop(1)
        }

        // 9.5. Splice in extension-injected prompts (authored by loaded
        // extensions via the ST-compatible `injectPrompts` JS API) and
        // character-card injections (depth_prompt + post_history_instructions).
        // Applied after budget trimming so injected system/user/assistant
        // messages survive into the request and instruct mode sees them too.
        val withInjections = applyExtensionInjections(budgetedMessages, allInjectionsForBudget)

        // 10. Check for instruct mode
        val instructPresetObj = request.metadata["instructPreset"] as? JsonObject
        val instructPreset = instructPresetObj?.let { InstructMode.loadPreset(it) }

        val namesApplied = (if (instructPreset != null) {
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
            applyNamesBehavior(withInjections, request.metadata, request.preset)
        }).filter { it.content.isNotBlank() }
        val squashed = if (request.preset.raw["squash_system_messages"]
                ?.jsonPrimitive?.booleanOrNull == true
        ) squashAdjacentSystemMessages(namesApplied) else namesApplied
        val assistantPrefill = request.preset.raw["assistant_prefill"]
            ?.jsonPrimitive?.contentOrNull.orEmpty()
            .let { macroEngine.expand(it, macroContext) }
        val finalMessages = if (assistantPrefill.isBlank() || instructPreset != null) squashed
            else squashed + PromptMessage(MessageRole.Assistant, content = assistantPrefill)

        // 11. Build stop sequences
        val stopSequences = buildStopSequences(request.preset.stop, instructPreset, contextPreset)

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
                warnings = compatibilityWarnings(request) + promptTemplateResult.warnings,
            ),
            promptTemplateVariableUpdates = promptTemplateResult.variableUpdates,
        )
    }

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
    private fun sanitizeCompletionName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_]"), "_").take(64)

    private fun applyNamesBehavior(
        messages: List<PromptMessage>,
        metadata: JsonObject,
        preset: GenerationPreset,
    ): List<PromptMessage> {
        val isGroup = groupMemberNamesList(metadata).size > 1
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

    private fun squashAdjacentSystemMessages(messages: List<PromptMessage>): List<PromptMessage> =
        messages.fold(emptyList()) { out, message ->
            val previous = out.lastOrNull()
            if (message.role == MessageRole.System && previous?.role == MessageRole.System) {
                out.dropLast(1) + previous.copy(content = previous.content + "\n\n" + message.content)
            } else out + message
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
            .lastOrNull { it.variables.getOrNull(it.swipeIndex) is JsonObject }
            ?.let { it.variables[it.swipeIndex] as JsonObject }
            ?: TavernInitVariables.extractMessageVariables(request.worldBooks)

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
            maxPromptTokens = request.preset.maxCompletionTokens
                ?: request.preset.maxTokens
                ?: DEFAULT_MAX_COMPLETION_TOKENS,
            maxContextTokens = request.preset.maxContextTokens
                ?: extractMaxContextTokens(request.metadata)
                ?: DEFAULT_MAX_CONTEXT_TOKENS,
            // ── Step 5 gap-fill: SillyTavern macro parity ──
            personaDescription = request.persona?.description.orEmpty(),
            modelName = extractModelName(request.metadata, request.providerType),
            maxResponseTokens = extractMaxResponseTokens(request.metadata)
                ?: request.preset.maxCompletionTokens
                ?: request.preset.maxTokens
                ?: DEFAULT_MAX_COMPLETION_TOKENS,
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
        message.reasoningParts().body

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
    private fun StringBuilder.appendWorldInfo(entries: List<WorldInfoScanner.ActivatedEntry>) {
        val nonBlank = entries.map { it.content.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return
        nonBlank.forEach { appendLine(it) }
    }

    private fun buildSystemPromptWithContextTemplate(
        expandedCharacter: CharacterCard,
        contextPreset: ContextPreset,
        macroContext: MacroContext,
        worldScan: WorldInfoScanner.ScanResult,
        request: PromptBuildRequest,
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
    /** Result of preset prompt ordering: the relative (in-order) messages plus
     * absolute (injection_position=1) prompts that must be depth-injected into
     * the chat history alongside extension/WI injections, matching ST's
     * absolutePrompts → populationInjectionPrompts flow (openai.js:1239-1325). */
    private data class PresetOrderResult(
        val messages: List<PromptMessage>,
        val absoluteInjections: List<ExtensionInjection> = emptyList(),
    )

    private fun applyPresetPromptOrder(
        messages: List<PromptMessage>,
        preset: GenerationPreset,
        context: MacroContext,
        character: CharacterCard,
        personaDescription: String,
        worldScan: WorldInfoScanner.ScanResult,
        preferCharPrompt: Boolean = true,
        preferCharJailbreak: Boolean = true,
    ): PresetOrderResult {
        if (preset.prompts.isEmpty() || messages.isEmpty()) return PresetOrderResult(messages)
        val unused = preset.promptsUnused.map { it.identifier }.toSet()
        val enabled = preset.prompts
            .filter { it.enabled && it.identifier !in unused }
            .sortedWith(compareBy({ it.order }, { it.identifier }))
        val system = messages.firstOrNull { it.role == MessageRole.System }
        val history = messages.filterNot { it === system }
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
                else -> null
            }
            val component = when (identifier) {
                "main", "system", "systemprompt" -> {
                    val base = prompt.content.takeIf { it.isNotBlank() } ?: "You are ${character.name}."
                    applyOverride(base, charSysPrompt, preferCharPrompt, prompt.forbidOverrides)
                }
                "jailbreak", "posthistoryinstructions", "phi" ->
                    applyOverride(prompt.content, charJailbreak, preferCharJailbreak, prompt.forbidOverrides)
                "worldinfobefore" -> worldBefore
                "worldinfoafter" -> worldAfter
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
        macroContext: MacroContext? = null,
    ): List<ExtensionInjection> {
        val injectedObj = metadata["injectedPrompts"] as? JsonObject ?: buildJsonObject { }

        val entries = mutableListOf<ExtensionInjection>()
        for ((promptKey, entryElement) in injectedObj) {
            val entry = entryElement as? JsonObject ?: continue
            val rawValue = runCatching { entry["value"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            // ST substitutes macros when the prompt is read at generation time
            // (script.js:3215, 3267 substituteParams).
            val value = macroContext?.let { macroEngine.expand(rawValue, it) } ?: rawValue
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
            entries.add(ExtensionInjection(value, position, depth, role, entries.size, key = promptKey))
        }
        // World-info AT_DEPTH entries: ST groups them per (depth, role) and
        // joins the group with newlines into ONE extension prompt keyed
        // customDepthWI (world-info.js:5116-5127, script.js:4609-4614).
        wiDepthEntries
            .filter { it.content.isNotBlank() }
            .groupBy { it.entry.depth to it.entry.role }
            .forEach { (depthRole, group) ->
                val (depth, roleInt) = depthRole
                val role = resolveExtensionInjectionRole(roleInt.toString())
                entries.add(
                    ExtensionInjection(
                        value = group.joinToString("\n") { it.content },
                        position = 1,
                        depth = depth,
                        role = role,
                        order = entries.size,
                        key = "customDepthWI-$depth-$roleInt",
                    ),
                )
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
    /**
     * Whether the preset declares a post-history-instructions slot, using the
     * same identifier normalisation as [applyPresetPromptOrder].
     */
    private fun GenerationPreset.hasJailbreakSlot(): Boolean =
        prompts.any {
            it.identifier.lowercase().replace("_", "").replace("-", "") in
                setOf("jailbreak", "posthistoryinstructions", "phi")
        }

    private fun collectCharacterCardInjections(
        character: CharacterCard,
        context: MacroContext,
        preferCharJailbreak: Boolean = true,
        includeJailbreak: Boolean = true,
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
                entries.add(ExtensionInjection(expanded, 1, depth, role, entries.size, key = "DEPTH_PROMPT"))
            }
        }

        // post_history_instructions (jailbreak). With an active prompt-manager
        // preset, ST routes PHI exclusively through the preset's `jailbreak`
        // slot (openai.js:1496-1504) — see applyPresetPromptOrder. Only the
        // presetless fallback appends it here, as its own message after the
        // chat history (mirroring the default order where jailbreak follows
        // chatHistory).
        if (includeJailbreak && preferCharJailbreak) {
            val jailbreak = data["post_history_instructions"]?.jsonPrimitive?.content?.trim()
            if (!jailbreak.isNullOrEmpty()) {
                val expanded = macroEngine.expand(jailbreak, context)
                if (expanded.isNotBlank()) {
                    entries.add(
                        ExtensionInjection(expanded, POSITION_AFTER_CHAT, 0, MessageRole.System, entries.size),
                    )
                }
            }
        }

        return entries
    }

    /**
     * WI ↑AT/↓AT entries ride the author's-note extension prompt
     * (world-info.js:5149-5153). tellev has no author's-note text, so the
     * joined entries form the whole payload, at the AN slot's defaults:
     * in-chat, depth 4, system role (authors-note.js:272-274).
     */
    private fun collectAuthorsNoteWorldInfo(worldScan: WorldInfoScanner.ScanResult): List<ExtensionInjection> {
        val payload = (worldScan.anTop + worldScan.anBottom)
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (payload.isBlank()) return emptyList()
        return listOf(
            ExtensionInjection(payload, 1, 4, MessageRole.System, 0, key = "2_floating_prompt"),
        )
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
     * Splice injected prompts into the message list, mirroring SillyTavern.
     *
     * - `position == 2` (BEFORE_PROMPT): insert before the `main` prompt
     *   (openai.js injectToMain with position 'start').
     * - `position == 0` (IN_PROMPT): insert right after the `main` prompt
     *   (injectToMain 'end'); without a main anchor, after the leading run of
     *   system messages.
     * - `position == 1` (IN_CHAT): depth-based insertion into the CHAT span
     *   only (openai.js:1325 populationInjectionPrompts operates on the chat
     *   history array). `depth == 0` lands right after the newest chat
     *   message; depths beyond the chat length clamp to the top of the chat.
     *   Within one depth, ST emits per order-group, per-role messages whose
     *   contents are JOINED with newlines (openai.js:824-855): chronological
     *   order is ascending order-group, each group assistant → user → system,
     *   so system sits closest to the end ("most important go lower").
     *   Within a role, preset absolute prompts come first, then
     *   extension-style prompts sorted by key (script.js:3250-3251).
     * - `position == 99` (internal AFTER_CHAT): standalone message appended
     *   after the chat span and any depth-0 injections (fallback-path PHI).
     * - `position == -1` (NONE): skipped.
     */
    private fun applyExtensionInjections(
        messages: List<PromptMessage>,
        entries: List<ExtensionInjection>,
    ): List<PromptMessage> {
        if (entries.isEmpty()) return messages

        val beforePrompts = entries.filter { it.position == 2 }
        val afterMainPrompts = entries.filter { it.position == 0 }
        val inChat = entries.filter { it.position == 1 }
        val afterChat = entries.filter { it.position == POSITION_AFTER_CHAT }

        val result = messages.toMutableList()

        fun mainIndex(): Int {
            val idx = result.indexOfFirst { it.channel == CHANNEL_MAIN }
            return idx
        }

        // BEFORE_PROMPT → before the main prompt, in arrival order.
        val beforeBase = mainIndex().coerceAtLeast(0)
        beforePrompts.forEachIndexed { i, entry ->
            result.add(beforeBase + i, PromptMessage(role = entry.role, content = entry.value))
        }

        // IN_PROMPT → right after the main prompt; fallback: after the leading
        // run of system messages.
        val mainIdx = mainIndex()
        var inPromptIdx = if (mainIdx >= 0) {
            mainIdx + 1
        } else {
            val firstNonSystem = result.indexOfFirst { it.role != MessageRole.System }
            if (firstNonSystem < 0) result.size else firstNonSystem
        }
        for (entry in afterMainPrompts) {
            result.add(inPromptIdx, PromptMessage(role = entry.role, content = entry.value))
            inPromptIdx++
        }

        // IN_CHAT at depth, anchored to the chat span.
        val chatStart = result.indexOfFirst { it.channel == CHANNEL_CHAT }
        val chatEndExclusive = result.indexOfLast { it.channel == CHANNEL_CHAT } + 1
        val anchorStart: Int
        val anchorEnd: Int
        if (chatStart < 0) {
            // No chat messages at all: injections land after the leading system run.
            val firstNonSystem = result.indexOfFirst { it.role != MessageRole.System }
            val base = if (firstNonSystem < 0) result.size else firstNonSystem
            anchorStart = base
            anchorEnd = base
        } else {
            anchorStart = chatStart
            anchorEnd = chatEndExclusive
        }

        // Iterate depths ascending: each deeper insertion index is strictly
        // smaller than all previous ones, so earlier splices stay valid.
        val byDepth = inChat.groupBy { it.depth }.toSortedMap()
        for ((depth, atDepth) in byDepth) {
            val insertIdx = (anchorEnd - depth).coerceIn(anchorStart, anchorEnd)
            result.addAll(insertIdx, buildInjectionBlock(atDepth))
        }

        // Fallback-path PHI: its own message after the chat and all depth-0
        // injections, like the default-order jailbreak slot after chatHistory.
        var afterChatIdx = if (chatStart < 0) result.size else {
            result.indexOfLast { it.channel == CHANNEL_CHAT } + 1 + insertedAtDepthZero(byDepth)
        }
        afterChatIdx = afterChatIdx.coerceIn(0, result.size)
        for (entry in afterChat) {
            result.add(afterChatIdx, PromptMessage(role = entry.role, content = entry.value))
            afterChatIdx++
        }

        return result
    }

    private fun insertedAtDepthZero(byDepth: Map<Int, List<ExtensionInjection>>): Int =
        byDepth[0]?.let { buildInjectionBlock(it).size } ?: 0

    /**
     * Build the chronological message block for one depth: ascending
     * order-group, each group assistant → user → system; same-group same-role
     * contents joined with '\n' (preset absolutes first, then extension
     * prompts sorted by key).
     */
    private fun buildInjectionBlock(atDepth: List<ExtensionInjection>): List<PromptMessage> {
        val block = mutableListOf<PromptMessage>()
        val byGroup = atDepth.groupBy { it.orderGroup }.toSortedMap()
        for ((_, group) in byGroup) {
            for (role in listOf(MessageRole.Assistant, MessageRole.User, MessageRole.System)) {
                val rolePrompts = group.filter { it.role == role }
                if (rolePrompts.isEmpty()) continue
                val presetParts = rolePrompts.filter { it.key == null }
                    .sortedBy { it.order }
                    .map { it.value.trim() }
                val extensionParts = rolePrompts.filter { it.key != null }
                    .sortedBy { it.key }
                    .map { it.value.trim() }
                val joined = (presetParts + extensionParts).filter { it.isNotEmpty() }.joinToString("\n")
                if (joined.isNotEmpty()) {
                    block.add(PromptMessage(role = role, content = joined))
                }
            }
        }
        return block
    }

    private fun resolveExtensionInjectionRole(raw: String?): MessageRole {
        if (raw == null) return MessageRole.System
        return when (raw.trim().lowercase()) {
            "0", "system" -> MessageRole.System
            "1", "user" -> MessageRole.User
            "2", "assistant", "char", "character" -> MessageRole.Assistant
            // ST extension_prompt_roles has no tool role; unknown roles coerce
            // to system so the injection is never silently dropped.
            else -> MessageRole.System
        }
    }

    private data class ExtensionInjection(
        val value: String,
        /** ST extension_prompt_types: -1 NONE, 0 IN_PROMPT, 1 IN_CHAT, 2 BEFORE_PROMPT; 99 internal AFTER_CHAT. */
        val position: Int,
        val depth: Int,
        val role: MessageRole,
        /** Arrival order; tiebreak within preset absolutes of one order group. */
        val order: Int,
        /** Extension-prompt key for ST's key-sorted joining (script.js:3250);
         * null marks a preset absolute prompt, which joins before keyed ones. */
        val key: String? = null,
        /** ST injection_order (default 100); groups merge ascending in the final prompt. */
        val orderGroup: Int = 100,
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
