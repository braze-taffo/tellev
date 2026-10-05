package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.network.CleartextGuard
import app.tellev.core.model.MessageRole
import app.tellev.core.model.MessageReasoning
import app.tellev.core.model.TellevError
import app.tellev.core.model.GenerationPreset
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.OpenAiCompatibleAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.presetCategoryForProvider
import app.tellev.core.provider.supportsChatGeneration
import app.tellev.core.storage.StDataStore
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

internal data class SourceChunk(val start: Int, val end: Int, val text: String)

/** Only a failed TCP connect is safe to replay: no HTTP request reached the provider. */
internal fun shouldRetryCreationConnect(
    error: TellevError,
    receivedDelta: Boolean,
    alreadyRetried: Boolean,
): Boolean = !receivedDelta && !alreadyRetried &&
    error.code == "provider_network" &&
    error.causeType in setOf("SocketTimeoutException", "ConnectException") &&
    (error.message.startsWith("failed to connect to", ignoreCase = true) ||
        error.message.contains("connect timed out", ignoreCase = true))

/**
 * A strict relay/provider rejected the request because the advertised output
 * budget exceeds the model cap (O4). Halving and retrying adapts to whatever
 * the upstream allows without hand-maintaining a per-provider cap table — a
 * fixed low cap starved reasoning models that spend budget on thinking first.
 */
internal fun isCreationOutputBudgetRejection(error: TellevError): Boolean {
    if (error.code !in setOf("provider_http_400", "provider_http_413", "provider_http_422")) return false
    val message = error.message.lowercase()
    return listOf("max_tokens", "max_completion_tokens", "max_output_tokens", "max_length", "num_predict")
        .any { it in message }
}

/** Covers the entire source, including a final short tail, without silent truncation. */
internal fun nextSourceChunk(source: String, cursor: Int, maxChars: Int = 7_000): SourceChunk? {
    require(maxChars >= 1_000)
    if (cursor >= source.length) return null
    val hardEnd = (cursor + maxChars).coerceAtMost(source.length)
    val end = if (hardEnd == source.length) hardEnd else {
        val breakAt = source.lastIndexOf('\n', hardEnd - 1)
        if (breakAt > cursor + maxChars / 2) breakAt + 1 else hardEnd
    }
    return SourceChunk(cursor, end, source.substring(cursor, end))
}

internal data class SourceChunkCount(val completed: Int, val total: Int)

internal fun countSourceChunks(source: String, savedCursor: Int): SourceChunkCount {
    var cursor = 0
    var completed = 0
    var total = 0
    while (true) {
        val chunk = nextSourceChunk(source, cursor) ?: break
        total++
        if (chunk.end <= savedCursor) completed++
        cursor = chunk.end
    }
    return SourceChunkCount(completed, total)
}

/** One turn of the tool loop: final reply text plus the session with all writes applied. */
internal data class ConverseResult(
    val message: String,
    val session: CreationSession,
    val rounds: Int,
    val capped: Boolean,
    /** Optional next-step suggestions extracted from the reply; clicking fills the input, never sends. */
    val suggestions: List<String> = emptyList(),
)

/** Asks the model to append an invisible next-step suggestions block it can offer. */
internal const val SUGGESTIONS_DIRECTIVE =
    "You may end your reply with an HTML comment listing 2-4 short next-step suggestions " +
        "in the user's language, formatted exactly: <!-- suggestions: [\"…\", \"…\"] -->. " +
        "It stays invisible to the user; omit it when suggestions would not help."

/**
 * Standard-profile protocol (character sessions only). The blueprint is a
 * single create-or-update bulk write; the exported card always regenerates
 * `data.extensions.tellev_standard_profile` from the draft, so the tool call
 * is the agent's one structured hand-off point, not a persistence target.
 */
internal const val BLUEPRINT_DIRECTIVE =
    "Standard profile protocol: when the character fields have settled, or the user asks for a complete " +
        "card / standard blueprint, call the creation_tool once with name \"write_blueprint\" and pass the " +
        "full blueprint as the arguments object, using snake_case keys: name (required), description, " +
        "personality, scenario, first_mes, alternate_greetings (array), mes_example, system_prompt, " +
        "post_history_instructions, creator_notes, tags (array), cover_prompt (a short English " +
        "comma-separated image-tag prompt for an AI-generated cover illustration of this character, or an " +
        "empty string), and lore (array of {title, keys, secondary_keys, content, constant, selective, " +
        "insertion_order, depth, position, probability, match_whole_words}). Applying the blueprint " +
        "overwrites the card fields with your values and upserts lore entries by title (it never deletes " +
        "existing entries). Do not repeat the call unless the user asks for another change."

private const val SUGGESTIONS_COMMENT_OPEN = "<!--"
private const val SUGGESTIONS_COMMENT_CLOSE = "-->"
private const val SUGGESTIONS_BODY_PREFIX = "suggestions"

/**
 * Strips the trailing suggestions block from a reply and parses it.
 * Returns (cleanMessage, suggestions). Deterministic comment scanning (no
 * regex) so malformed or truncated blocks — including a suggestion comment
 * whose closing "-->" never arrived — are stripped or ignored silently and
 * never pollute the user-visible text.
 */
internal fun splitReplySuggestions(message: String): Pair<String, List<String>> {
    var searchFrom = 0
    while (true) {
        val open = message.indexOf(SUGGESTIONS_COMMENT_OPEN, searchFrom)
        if (open < 0) return message.trim() to emptyList()
        val bodyStart = skipHorizontalSpace(message, open + SUGGESTIONS_COMMENT_OPEN.length)
        val isSuggestions = message.regionMatches(
            bodyStart, SUGGESTIONS_BODY_PREFIX, 0, SUGGESTIONS_BODY_PREFIX.length,
        )
        if (!isSuggestions) {
            searchFrom = open + SUGGESTIONS_COMMENT_OPEN.length
            continue
        }
        // The suggestions comment is a reply-tail convention: close at the
        // LAST "-->" so JSON-string content containing "-->" cannot leak the
        // remainder into the user-visible message.
        val close = message.lastIndexOf(SUGGESTIONS_COMMENT_CLOSE)
        // Truncated tail: the comment never closed — strip everything after it.
        val cleaned = if (close < open) {
            message.substring(0, open).trimEnd()
        } else {
            (message.substring(0, open).trimEnd() + " " +
                message.substring(close + SUGGESTIONS_COMMENT_CLOSE.length).trimStart()).trim()
        }
        if (close < open) return cleaned to emptyList()
        var body = message.substring(bodyStart + SUGGESTIONS_BODY_PREFIX.length, close).trim()
        if (body.startsWith(":")) body = body.substring(1).trim()
        val suggestions = if (!body.startsWith("[")) emptyList() else runCatching {
            val end = body.indexOf(']')
            if (end < 0) return@runCatching emptyList<String>()
            val raw = kotlinx.serialization.json.Json.parseToJsonElement(body.substring(0, end + 1))
            (raw as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
        }.getOrDefault(emptyList())
        return cleaned to suggestions.distinct().take(4)
    }
}

private fun skipHorizontalSpace(text: String, from: Int): Int {
    var index = from
    while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
    return index
}

/** One native function keeps the transport contract small across compatible relays. */
internal fun creationNativeTools(): JsonArray = JsonArray(listOf(buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", "creation_tool")
        put("description", "Read or update the current character card/world book draft. Tool names and arguments are described in the system message.")
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf(
                            "read_card", "list_lore", "read_lore", "set_card_fields", "upsert_lore", "remove_lore",
                            "write_blueprint",
                            "list_assets", "read_script", "upsert_script", "read_regex", "upsert_regex",
                            "read_variables", "set_variables", "remove_asset", "ask_user",
                        ).map(::JsonPrimitive)))
                    })
                put("arguments", buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", true)
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("arguments"))))
        })
    })
}))

internal fun creationConversationContext(session: CreationSession): String {
    val recentTurns = session.turns.takeLast(12)
    val recent = recentTurns.joinToString("\n") {
        "${if (it.role == "user") UiStrings.get(S.creng_role_user) else "agent"}: ${it.text}"
    }
    val brief = session.turns.firstOrNull()?.takeIf {
        it.role == "user" && CREATION_BRIEF_MARKERS.any(it.text::startsWith) && it !in recentTurns
    }?.text.orEmpty()
    return buildString {
        if (brief.isNotBlank()) appendLine(UiStrings.get(S.creng_context_brief_header, brief))
        if (recent.isNotBlank()) appendLine(UiStrings.get(S.creng_context_recent_header, recent))
    }
}

internal data class CreationStreamUpdate(
    val phase: String,
    val output: String = "",
    val reasoning: String = "",
    val assistantMessage: String = "",
    val elapsedMillis: Long = 0,
    val firstDeltaMillis: Long? = null,
    val lastDeltaMillis: Long? = null,
    val deltaCount: Int = 0,
    val providerLabel: String = "",
)

/** Show only reasoning supplied by the provider or explicitly written inside a leading think tag. */
internal fun visibleCreationStream(raw: String, providerReasoning: String): CreationStreamUpdate {
    val parts = MessageReasoning.fromResponse(raw, providerReasoning)
    if (parts.status == "parsed") {
        return CreationStreamUpdate(UiStrings.get(S.creng_phase_receiving_draft), parts.body, parts.reasoning,
            assistantMessage = proseWithoutToolBlocks(parts.body))
    }
    val opening = Regex("""^\s*<(think|reasoning)>""", RegexOption.IGNORE_CASE).find(raw)
    if (opening != null && parts.status == "ambiguous") {
        val inlineReasoning = raw.substring(opening.range.last + 1)
        return CreationStreamUpdate(UiStrings.get(S.creng_phase_model_thinking), reasoning = listOf(providerReasoning, inlineReasoning)
            .filter(String::isNotBlank).joinToString("\n\n"))
    }
    return CreationStreamUpdate(
        phase = if (raw.isNotBlank()) UiStrings.get(S.creng_phase_receiving_draft)
            else if (providerReasoning.isNotBlank()) UiStrings.get(S.creng_phase_model_thinking)
            else UiStrings.get(S.creng_phase_waiting_model),
        output = raw,
        reasoning = providerReasoning,
        assistantMessage = proseWithoutToolBlocks(raw),
    )
}

internal class AgentReplyFormatException(cause: Throwable) :
    IllegalArgumentException(UiStrings.get(S.creng_agent_reply_format_invalid), cause)

/** Extraction replies are single JSON objects; conversation replies go through the tool loop. */
internal data class AgentReply(
    val message: String,
    val worldName: String? = null,
    val lore: List<LoreDraft>? = null,
)

internal object CreationReplyParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AgentReply {
        try {
            val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = clean.indexOf('{')
            val end = clean.lastIndexOf('}')
            require(start >= 0 && end > start) { UiStrings.get(S.creng_reply_no_structured_draft) }
            val root = json.parseToJsonElement(clean.substring(start, end + 1)) as? JsonObject
                ?: error(UiStrings.get(S.creng_reply_not_json_object))
            val lore = root["lore"]?.let { json.decodeFromJsonElement<List<LoreDraft>>(it) }
            return AgentReply(
                message = root["assistant_message"]?.jsonPrimitive?.content.orEmpty(),
                worldName = root["world_name"]?.jsonPrimitive?.content,
                lore = lore,
            )
        } catch (e: Exception) {
            throw AgentReplyFormatException(e)
        }
    }
}

internal fun mergeExtractedLore(existing: List<LoreDraft>, incoming: List<LoreDraft>): List<LoreDraft> {
    val seen = existing.map { it.title.trim().lowercase() to it.content.trim() }.toMutableSet()
    return existing + incoming.filter { seen.add(it.title.trim().lowercase() to it.content.trim()) }
}

internal fun verifiedLoreFromChunk(
    chunk: SourceChunk,
    entries: List<LoreDraft>,
    sourceName: String,
    sourceSha256: String,
): List<LoreDraft> = entries.mapNotNull { entry ->
    val quote = entry.sourceQuote.trim()
    val localOffset = if (quote.isBlank()) -1 else chunk.text.indexOf(quote)
    if (localOffset < 0 || entry.content.isBlank()) null
    else entry.copy(
        sourceQuote = quote,
        sourceOffset = chunk.start + localOffset,
        sourceName = sourceName,
        sourceSha256 = sourceSha256,
    )
}

/** Independent authoring requests never pass through the chat prompt engine. */
internal class CreationEngine(
    private val secrets: SecretStore,
    private val providers: ProviderRegistry,
    /**
     * When provided, generation follows the CURRENT chat selection: the
     * selected provider plus the chat-selected preset for its category
     * (sampling, budgets, stop sequences and provider-specific raw fields).
     * The creation system instructions always stay authoritative; a chat
     * preset never injects prompts into the authoring loop. Null (tests)
     * keeps the legacy built-in agent preset.
     */
    private val store: StDataStore? = null,
) {
    private val json = Json { encodeDefaults = true }

    companion object {
        /** Safety valve against tool loops that never converge, not a work quota. */
        internal const val TOOL_ROUND_LIMIT = 32
        internal const val TOOL_ROUND_WARNING_FROM = 30

        /**
         * Reasoning models spend their completion budget on thinking first; an
         * 8K cap died mid-thought (~1900 streamed chunks). Modern models start
         * at 128K output, so ask for that and let the provider clamp if lower.
         */
        internal const val MAX_OUTPUT_TOKENS = 128_000
    }

    // Keep the creation agent's transport separate from chat. Some compatible
    // relays close an HTTP/2 connection before sending its initial SETTINGS
    // frame; using HTTP/1.1 here avoids a second, potentially billable POST.
    private val compatibleCreationAdapter by lazy {
        OpenAiCompatibleAdapter(client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(CleartextGuard)
            // The model may take minutes to answer, but opening a TCP socket should not.
            // A shorter connect limit lets OkHttp try another resolved address sooner.
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build())
    }

    suspend fun converse(
        session: CreationSession,
        userText: String,
        onProgress: (CreationStreamUpdate) -> Unit = {},
        onCheckpoint: suspend (CreationSession) -> Unit = {},
        onToolEvent: (CreationToolEvent) -> Unit = {},
        onAskUser: (suspend (CreationAgentQuestion) -> String)? = null,
    ): ConverseResult {
        val opener = creationConversationContext(session)
        val history = mutableListOf(
            PromptMessage(MessageRole.System, content = conversationSystemPrompt(session.kind, session.kind == CreationKind.WorldBook && session.originalCard != null)),
            PromptMessage(MessageRole.User, content = opener + UiStrings.get(S.creng_context_user_round, userText)),
        )
        val toolbox = CreationToolBox(session)
        var consecutiveBadRounds = 0
        var lastBadDetail = ""
        var round = 0
        while (true) {
            round++
            val generation = generate(history, temperatureOverride = if (consecutiveBadRounds > 0) 0.0 else null,
                onProgress = onProgress,
                phasePrefix = UiStrings.get(S.creng_round_prefix, round))
            val nativeBlocks = parseNativeCreationCalls(generation.toolCalls)
            val parsed = if (generation.toolCalls?.isNotEmpty() == true) {
                ToolBlocksParseResult(nativeBlocks, "", false)
            } else parseToolCallBlocks(generation.text)
            val validCalls = parsed.blocks.filterIsInstance<ToolCallBlock.Valid>().map { it.call }
            // Native calls travel outside message.content, so the loop replays
            // them as text blocks: the next request then shows the model which
            // calls it made and with what arguments, mirroring the text path.
            // Invalid calls keep their slot as a placeholder line so block
            // positions stay 1:1 with the indexed results; a content fragment
            // that never closed its <tool_call> is dropped instead of replayed.
            val assistantEcho = if (generation.toolCalls?.isNotEmpty() != true) generation.text else {
                buildString {
                    proseWithoutToolBlocks(generation.text).trim().takeIf(String::isNotEmpty)?.let(::appendLine)
                    parsed.blocks.forEachIndexed { blockIndex, block ->
                        when (block) {
                            is ToolCallBlock.Valid -> appendLine(renderToolCallReplay(block.call))
                            is ToolCallBlock.Invalid ->
                                appendLine(UiStrings.get(S.creng_native_call_incomplete, blockIndex + 1))
                        }
                    }
                }.trim().ifEmpty { UiStrings.get(S.creng_native_calls_requested, generation.toolCalls?.size ?: 0) }
            }
            if (validCalls.isEmpty()) {
                if (generation.finishReason != "length" && parsed.blocks.isEmpty() &&
                    !parsed.hasUnclosedBlock && parsed.prose.isNotBlank()
                ) {
                    // No tool calls: this round's prose is the reply to the user.
                    // The optional trailing suggestions comment never reaches the UI.
                    val (cleanMessage, suggestions) = splitReplySuggestions(parsed.prose)
                    return ConverseResult(
                        message = cleanMessage,
                        session = toolbox.session,
                        rounds = round,
                        capped = false,
                        suggestions = suggestions,
                    )
                }
                val truncated = generation.finishReason == "length"
                val truncatedMidBlock = truncated && parsed.hasUnclosedBlock
                val truncatedEmptyBody = truncated && parsed.prose.isBlank() && parsed.blocks.isEmpty()
                val reason = when {
                    truncatedMidBlock -> UiStrings.get(S.creng_bad_reason_truncated_mid_block)
                    truncatedEmptyBody -> UiStrings.get(S.creng_bad_reason_truncated_empty)
                    truncated -> UiStrings.get(S.creng_bad_reason_truncated)
                    parsed.hasUnclosedBlock -> UiStrings.get(S.creng_bad_reason_unclosed)
                    parsed.blocks.isNotEmpty() -> UiStrings.get(S.creng_bad_reason_unparseable)
                    else -> UiStrings.get(S.creng_bad_reason_empty)
                }
                val guidance = when {
                    truncatedMidBlock -> UiStrings.get(S.creng_bad_guidance_split)
                    truncatedEmptyBody -> UiStrings.get(S.creng_bad_guidance_condense)
                    truncated -> UiStrings.get(S.creng_bad_guidance_shorten)
                    else -> UiStrings.get(S.creng_bad_guidance_resend)
                }
                lastBadDetail = UiStrings.get(S.creng_bad_detail, reason,
                    generation.finishReason ?: UiStrings.get(S.creng_finish_reason_none),
                    generation.text.length, generation.toolCalls?.size ?: 0)
                history += PromptMessage(MessageRole.Assistant, content = assistantEcho)
                history += PromptMessage(MessageRole.User, content = buildString {
                    append("<tool_result name=\"system\" ok=\"false\">")
                    // Built through the JSON encoder so localized guidance with
                    // quotes (e.g. the resend example) stays valid JSON.
                    append(buildJsonObject {
                        put("error", UiStrings.get(S.creng_bad_round_error, reason, guidance))
                    })
                    append("</tool_result>")
                })
                if (++consecutiveBadRounds >= 3) {
                    error(UiStrings.get(S.creng_tool_loop_stalled, lastBadDetail))
                }
                continue
            }
            consecutiveBadRounds = 0
            val feedback = StringBuilder()
            var wroteDraft = false
            // Block index ties each result to the model's own call order, so a
            // multi-call round stays correlated even when names repeat.
            parsed.blocks.forEachIndexed { blockIndex, block ->
                when (block) {
                    is ToolCallBlock.Invalid -> {
                        feedback.appendLine(
                            "<tool_result index=\"${blockIndex + 1}\" name=\"unknown\" ok=\"false\">" +
                                buildJsonObject {
                                    put("error", UiStrings.get(S.creng_block_unparseable_feedback, block.reason))
                                } + "</tool_result>")
                        onToolEvent(CreationToolEvent(
                            round, blockIndex + 1, "unknown", ok = false,
                            detail = block.reason.take(80),
                        ))
                    }
                    is ToolCallBlock.Valid -> {
                        onProgress(CreationStreamUpdate(UiStrings.get(S.creng_phase_exec_tool, round, block.call.name)))
                        val before = toolbox.session
                        // ask_user is an interaction, not a draft tool: the loop
                        // suspends until the user taps an option, then the choice
                        // travels back as this call's tool result.
                        val result = if (block.call.name == "ask_user") {
                            val question = parseAskUserQuestion(block.call.arguments)
                            when {
                                question == null -> ToolResult(ok = false, name = "ask_user", payload = buildJsonObject {
                                    put("error", UiStrings.get(S.creng_ask_user_invalid))
                                })
                                onAskUser == null -> ToolResult(ok = false, name = "ask_user", payload = buildJsonObject {
                                    put("error", UiStrings.get(S.creng_ask_user_unsupported))
                                })
                                else -> {
                                    onProgress(CreationStreamUpdate(UiStrings.get(S.creng_phase_waiting_user)))
                                    ToolResult(ok = true, name = "ask_user", payload = buildJsonObject {
                                        put("answer", onAskUser(question))
                                    })
                                }
                            }
                        } else toolbox.execute(block.call)
                        feedback.appendLine(result.render(blockIndex + 1))
                        onToolEvent(CreationToolEvent(
                            round, blockIndex + 1, result.name, result.ok,
                            detail = toolEventDetail(result),
                        ))
                        if (result.ok && toolbox.session !== before) wroteDraft = true
                    }
                }
            }
            if (generation.finishReason == "length" && parsed.hasUnclosedBlock) {
                feedback.appendLine("<tool_result name=\"system\" ok=\"false\">" +
                    buildJsonObject { put("error", UiStrings.get(S.creng_truncated_final_notice)) } +
                    "</tool_result>")
            }
            if (parsed.recoveredUnclosedBlock) {
                // The relay swallowed the closing tag but the JSON arrived whole.
                // Tell the model the call ran, so it does not keep re-sending it.
                feedback.appendLine("<tool_result name=\"system\" ok=\"true\">" +
                    buildJsonObject { put("notice", UiStrings.get(S.creng_recovered_close_tag_notice)) } +
                    "</tool_result>")
            }
            // A later model request may fail after these complete calls. Keep the
            // validated draft changes before starting another billable request.
            if (wroteDraft) onCheckpoint(toolbox.session)
            if (round >= TOOL_ROUND_LIMIT) {
                // Normal close at the safety valve: keep every applied write.
                return ConverseResult(
                    message = UiStrings.get(S.creng_round_limit_reached, TOOL_ROUND_LIMIT),
                    session = toolbox.session,
                    rounds = round,
                    capped = true,
                )
            }
            if (round >= TOOL_ROUND_WARNING_FROM) {
                feedback.appendLine("<tool_result name=\"system\" ok=\"true\">" +
                    buildJsonObject { put("notice", UiStrings.get(S.creng_round_limit_notice, TOOL_ROUND_LIMIT)) } +
                    "</tool_result>")
            }
            history += PromptMessage(MessageRole.Assistant, content = assistantEcho)
            // Tool results travel as user messages with an explicit wrapper:
            // some relays reject a tool role that has no tool_call_id.
            history += PromptMessage(MessageRole.User, content = feedback.toString().trim())
            // The tool box remains authoritative. Keep the last four exchanges
            // so large world books do not grow the provider request without bound.
            while (history.size > 10) {
                history.removeAt(2)
                history.removeAt(2)
            }
        }
    }

    private fun conversationSystemPrompt(kind: CreationKind, hasSourceCard: Boolean = false): String {
        val base = UiStrings.get(S.creng_system_prompt,
            UiStrings.get(if (kind == CreationKind.Character) S.creng_kind_character else S.creng_kind_worldbook))
        val withSource = if (hasSourceCard) base + "\n" + UiStrings.get(S.creng_system_prompt_source_card) else base
        val withBlueprint = if (kind == CreationKind.Character) withSource + "\n" + BLUEPRINT_DIRECTIVE else withSource
        // Suggestions ride as an invisible HTML comment: degrade gracefully when
        // the model omits it, and strip it before the user sees the reply.
        return withBlueprint + "\n" + SUGGESTIONS_DIRECTIVE
    }


    suspend fun extractChunk(
        chunk: SourceChunk,
        sourceName: String,
        onProgress: (CreationStreamUpdate) -> Unit = {},
    ): AgentReply {
        val system = UiStrings.get(S.creng_extract_system)
        val prompt = UiStrings.get(S.creng_extract_source_line, sourceName, chunk.start, chunk.end) +
            "\n<source>\n${chunk.text}\n</source>"
        val first = generate(listOf(
            PromptMessage(MessageRole.System, content = system),
            PromptMessage(MessageRole.User, content = prompt),
        ), temperatureOverride = 0.2, onProgress = onProgress)
        try {
            return CreationReplyParser.parse(first.text)
        } catch (_: AgentReplyFormatException) {
        }
        if (first.finishReason == "length") {
            onProgress(CreationStreamUpdate(UiStrings.get(S.creng_phase_condensed_retry)))
            return condensedExtractionRetry(system, prompt, onProgress)
        }
        onProgress(CreationStreamUpdate(UiStrings.get(S.creng_phase_repair_format)))
        val repairSystem = UiStrings.get(S.creng_repair_system)
        val repaired = generate(
            listOf(
                PromptMessage(MessageRole.System, content = repairSystem),
                PromptMessage(MessageRole.User, content = UiStrings.get(S.creng_repair_input, json.encodeToString(first.text))),
            ),
            temperatureOverride = 0.0, onProgress = onProgress, phasePrefix = UiStrings.get(S.creng_repair_prefix),
        )
        try {
            return CreationReplyParser.parse(repaired.text)
        } catch (_: AgentReplyFormatException) {
            return condensedExtractionRetry(system, prompt, onProgress)
        }
    }

    /** Truncation or repeated format failure: re-extract with tighter per-entry limits. */
    private suspend fun condensedExtractionRetry(
        originalSystem: String,
        prompt: String,
        onProgress: (CreationStreamUpdate) -> Unit,
    ): AgentReply {
        val condensed = originalSystem + "\n" + UiStrings.get(S.creng_condensed_suffix)
        val retried = generate(
            listOf(
                PromptMessage(MessageRole.System, content = condensed),
                PromptMessage(MessageRole.User, content = prompt),
            ),
            temperatureOverride = 0.0, onProgress = onProgress, phasePrefix = UiStrings.get(S.creng_condensed_prefix),
        )
        return try {
            CreationReplyParser.parse(retried.text)
        } catch (e: AgentReplyFormatException) {
            throw IllegalArgumentException(
                UiStrings.get(S.creng_error_extract_invalid), e,
            )
        }
    }

    /**
     * [temperatureOverride] null = follow the chat preset's temperature (or
     * 0.7 when absent); explicit overrides win for repair/deterministic rounds.
     */
    /**
     * The chat-selected preset for [config]'s provider category, aligned to
     * the authoring request (agent id, provider type/category). Null when no
     * preset storage is wired or none exists — callers fall back to the
     * built-in agent defaults. Read failures degrade to null instead of
     * failing the turn: an authoring request must not die on preset I/O.
     */
    private suspend fun resolveChatPreset(store: StDataStore, config: app.tellev.core.provider.ProviderConfig): GenerationPreset? =
        runCatching {
            val category = presetCategoryForProvider(config.providerType)
            val candidates = store.listPresets().filter { it.category == category }
            val selectedName = store.readSelectedPresetName(category)
            val named = candidates.firstOrNull { it.id == selectedName } ?: candidates.firstOrNull() ?: return@runCatching null
            val working = if (named.id == selectedName) store.readPreset(category, "in_use") else null
            (working ?: named).copy(
                id = "ai-creation-agent",
                name = named.name,
                providerType = config.providerType,
                category = category,
            )
        }.getOrNull()

    private suspend fun generate(
        messages: List<PromptMessage>,
        temperatureOverride: Double?,
        onProgress: (CreationStreamUpdate) -> Unit,
        phasePrefix: String = "",
    ): RawGeneration {
        onProgress(CreationStreamUpdate(phasePrefix + UiStrings.get(S.creng_phase_prepare_request)))
        val selectedId = secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
            ?: ProviderCatalog.OPENAI_COMPATIBLE
        val adapterId = ProviderConfigPersistence.adapterIdFor(selectedId)
        val selectedAdapter = providers.find(adapterId)
            ?.takeIf { it.supportsChatGeneration }
            ?: error(UiStrings.get(S.creng_error_provider_not_supported))
        val adapter = if (adapterId == ProviderCatalog.OPENAI_COMPATIBLE &&
            selectedAdapter is OpenAiCompatibleAdapter
        ) compatibleCreationAdapter else selectedAdapter
        val config = ProviderConfigPersistence.loadProviderConfig(secrets, selectedId)
        // Authoring follows the CURRENT chat selection: the chat-selected
        // preset for this provider category owns sampling, budgets, stop
        // sequences and provider-specific raw fields (reasoning etc.). The
        // authoring instructions in [messages] stay authoritative — a chat
        // preset never injects prompt content here.
        val chatPreset = store?.let { resolveChatPreset(it, config) }
        val temperature = temperatureOverride ?: chatPreset?.temperature ?: 0.7
        val outputBudgetField = (chatPreset?.maxCompletionTokens ?: chatPreset?.maxTokens)
            ?.coerceIn(1_024, MAX_OUTPUT_TOKENS)
            ?: MAX_OUTPUT_TOKENS
        onProgress(CreationStreamUpdate(
            phase = phasePrefix + UiStrings.get(S.creng_phase_connecting),
            providerLabel = buildString {
                append(adapter.displayName)
                append(" · ")
                append(config.model?.takeIf(String::isNotBlank) ?: UiStrings.get(S.creng_default_model_label))
                chatPreset?.let { append(" · ").append(it.name) }
            },
        ))
        val agentPreset = (chatPreset ?: GenerationPreset(
            id = "ai-creation-agent",
            name = "AI 协作创作",
            providerType = config.providerType,
            category = presetCategoryForProvider(config.providerType),
            maxContextTokens = 1_000_000,
            maxCompletionTokens = MAX_OUTPUT_TOKENS,
        )).copy(
            id = "ai-creation-agent",
            providerType = config.providerType,
            category = presetCategoryForProvider(config.providerType),
            temperature = temperature,
        )
        val prompt = PromptBuildResult(
            // Defensive copy: the loop keeps mutating its history list; a request
            // must not alias it or later rounds rewrite earlier requests.
            messages = messages.toList(),
            stop = emptyList(),
            maxTokens = MAX_OUTPUT_TOKENS,
            providerType = config.providerType,
            diagnostics = PromptDiagnostics(emptyList()),
        )
        var completed: String? = null
        var completedReasoning: String? = null
        var completedFinishReason: String? = null
        var completedToolCalls: JsonArray? = null
        val deltas = StringBuilder()
        val reasoningDeltas = StringBuilder()
        val requestStartedAt = System.nanoTime()
        var firstDeltaMillis: Long? = null
        var lastDeltaMillis: Long? = null
        var deltaCount = 0
        var lastPublishedAt = 0L
        var publishedOutput = false
        var publishedAssistantMessage = false
        fun elapsedMillis() = (System.nanoTime() - requestStartedAt) / 1_000_000
        fun publish(force: Boolean = false) {
            val visible = visibleCreationStream(deltas.toString(), reasoningDeltas.toString())
            val now = System.currentTimeMillis()
            val firstVisibleText = (visible.output.isNotBlank() && !publishedOutput) ||
                (visible.assistantMessage.isNotBlank() && !publishedAssistantMessage)
            if (!force && !firstVisibleText && now - lastPublishedAt < 80) return
            lastPublishedAt = now
            publishedOutput = publishedOutput || visible.output.isNotBlank()
            publishedAssistantMessage = publishedAssistantMessage || visible.assistantMessage.isNotBlank()
            onProgress(visible.copy(
                phase = phasePrefix + visible.phase,
                elapsedMillis = elapsedMillis(), firstDeltaMillis = firstDeltaMillis,
                lastDeltaMillis = lastDeltaMillis, deltaCount = deltaCount,
            ))
        }
        onProgress(CreationStreamUpdate(phasePrefix + UiStrings.get(S.creng_phase_waiting_model)))
        var retriedConnect = false
        var budgetRetries = 0
        var outputBudget = outputBudgetField
        do {
            var retryConnect = false
            adapter.streamGenerate(
                config,
                GenerateRequest(prompt = prompt.copy(maxTokens = outputBudget), preset = agentPreset, stream = true,
                    metadata = buildJsonObject {
                        put("creation_agent", true)
                        put("require_stream_terminator", true)
                        put("tools", creationNativeTools())
                        put("tool_choice", "auto")
                    }),
            ).collect { chunk ->
                when (chunk) {
                    is GenerateChunk.Delta -> {
                        deltas.append(chunk.text)
                        reasoningDeltas.append(chunk.reasoning)
                        if (chunk.text.isNotEmpty() || chunk.reasoning.isNotEmpty()) {
                            deltaCount++
                            lastDeltaMillis = elapsedMillis()
                            if (firstDeltaMillis == null) firstDeltaMillis = lastDeltaMillis
                            publish()
                        }
                    }
                    is GenerateChunk.Completed -> {
                        completed = chunk.text
                        completedReasoning = chunk.reasoning
                        completedFinishReason = chunk.finishReason
                        completedToolCalls = chunk.toolCalls
                    }
                    is GenerateChunk.Failed -> {
                        if (deltaCount == 0 && deltas.isEmpty() && budgetRetries < 2 &&
                            isCreationOutputBudgetRejection(chunk.error)
                        ) {
                            budgetRetries++
                            outputBudget = (outputBudget / 2).coerceAtLeast(4096)
                            retryConnect = true
                            onProgress(CreationStreamUpdate(
                                phase = phasePrefix + UiStrings.get(S.creng_phase_budget_retry),
                                elapsedMillis = elapsedMillis(),
                            ))
                        } else if (adapter === compatibleCreationAdapter &&
                            shouldRetryCreationConnect(chunk.error, deltaCount > 0, retriedConnect)
                        ) {
                            retryConnect = true
                            onProgress(CreationStreamUpdate(
                                phase = phasePrefix + UiStrings.get(S.creng_phase_connect_retry),
                                elapsedMillis = elapsedMillis(),
                            ))
                        } else {
                            val stage = when {
                                deltas.isNotEmpty() -> UiStrings.get(S.creng_stage_body_output)
                                reasoningDeltas.isNotEmpty() -> UiStrings.get(S.creng_stage_reasoning_output)
                                else -> UiStrings.get(S.creng_stage_before_first_chunk)
                            }
                            error(UiStrings.get(S.creng_error_stream_interrupted, stage, deltaCount,
                                chunk.error.code, chunk.error.causeType?.let { "/$it" } ?: "",
                                chunk.error.message.take(300)))
                        }
                    }
                }
            }
            retriedConnect = retriedConnect || retryConnect
        } while (retryConnect)
        val raw = (completed?.takeIf(String::isNotBlank) ?: deltas.toString())
        if (raw.isBlank() && completedToolCalls.isNullOrEmpty()) {
            val reasoning = completedReasoning?.takeIf(String::isNotBlank) ?: reasoningDeltas.toString()
            if (reasoning.isBlank()) error(UiStrings.get(S.creng_error_no_content))
            // Reasoning consumed the whole output budget and no body arrived:
            // hand the truncation to the loop's feedback path instead of
            // failing the turn with a confusing "no content" error.
            return RawGeneration(text = "", finishReason = completedFinishReason ?: "length")
        }
        val visible = visibleCreationStream(raw, completedReasoning?.takeIf(String::isNotBlank) ?: reasoningDeltas.toString())
        onProgress(visible.copy(
            phase = phasePrefix + UiStrings.get(S.creng_phase_validating),
            elapsedMillis = elapsedMillis(), firstDeltaMillis = firstDeltaMillis,
            lastDeltaMillis = lastDeltaMillis, deltaCount = deltaCount,
        ))
        return RawGeneration(text = MessageReasoning.split(raw).body,
            finishReason = completedFinishReason, toolCalls = completedToolCalls)
    }
}

internal data class RawGeneration(val text: String, val finishReason: String?, val toolCalls: JsonArray? = null)
