package app.tellev.core.prompt

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object PromptInjectionProcessor {

    /**
     * Collect extension-injected prompts (ST `injectPrompts` API) and
     * world-info AT_DEPTH entries as splice-ready injections. Collected
     * BEFORE token trimming so their tokens can be reserved from the chat
     * budget — mirroring ST, where injections are added to the message pool
     * before the trim loop and real chat messages are dropped first
     * (openai.js:1325).
     */
    fun collectExtensionInjections(
        metadata: JsonObject,
        wiDepthEntries: List<WorldInfoScanner.ActivatedEntry> = emptyList(),
        macroContext: MacroContext? = null,
        macroEngine: MacroEngine,
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
     * Whether the preset declares a post-history-instructions slot, using the
     * same identifier normalisation as [PromptOrderProcessor.applyPresetPromptOrder].
     */
    fun GenerationPreset.hasJailbreakSlot(): Boolean =
        prompts.any {
            it.identifier.lowercase().replace("_", "").replace("-", "") in
                setOf("jailbreak", "posthistoryinstructions", "phi")
        }

    fun collectCharacterCardInjections(
        character: CharacterCard,
        context: MacroContext,
        preferCharJailbreak: Boolean = true,
        includeJailbreak: Boolean = true,
        macroEngine: MacroEngine,
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
    fun collectAuthorsNoteWorldInfo(worldScan: WorldInfoScanner.ScanResult): List<ExtensionInjection> {
        val payload = (worldScan.anTop + worldScan.anBottom)
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        if (payload.isBlank()) return emptyList()
        return listOf(
            ExtensionInjection(payload, 1, 4, MessageRole.System, 0, key = "2_floating_prompt"),
        )
    }

    fun injectionTokenCost(entries: List<ExtensionInjection>): Int =
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
    fun applyExtensionInjections(
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

    fun resolveExtensionInjectionRole(raw: String?): MessageRole {
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

    /** Prompt injections marked for scanning contribute keys but are not necessarily sent (position NONE). */
    fun extensionInjectionScanText(metadata: JsonObject): List<String> {
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
}
