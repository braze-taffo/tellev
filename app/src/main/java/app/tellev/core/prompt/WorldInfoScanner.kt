package app.tellev.core.prompt

import app.tellev.core.model.WorldBookEntry

/** SillyTavern world_info_position enum. Mirrors public/scripts/world-info.js. */
enum class WorldInfoPosition(val value: Int) {
    BEFORE(0),     // ↑Char — before character definitions
    AFTER(1),      // ↓Char — after character definitions
    AN_TOP(2),     // ↑AT  — before author's note / persona block
    AN_BOTTOM(3),  // ↓AT  — after author's note / persona block
    AT_DEPTH(4),   // @D   — injected into chat history at [depth] as [role]
    EM_TOP(5),     // ↑EM  — before example messages
    EM_BOTTOM(6),  // ↓EM  — after example messages
    OUTLET(7);     // named outlet (currently folded into BEFORE)

    companion object {
        fun of(value: Int): WorldInfoPosition = entries.firstOrNull { it.value == value } ?: BEFORE
    }
}

/** SillyTavern world_info_logic enum. */
enum class WorldInfoLogic(val value: Int) {
    AND_ANY(0),  // any secondary key matches
    NOT_ALL(1),  // any secondary key does NOT match
    NOT_ANY(2),  // none of the secondary keys match
    AND_ALL(3);  // all secondary keys match

    companion object {
        fun of(value: Int): WorldInfoLogic = entries.firstOrNull { it.value == value } ?: AND_ANY
    }
}

/**
 * SillyTavern-compatible world-info activation engine.
 *
 * Performs keyword matching (with regex / whole-word / case-sensitive
 * options), selective-logic evaluation over secondary keys, probability
 * rolls, and recursive scanning (activated entries' content feeds the next
 * pass). Activated entries are bucketed by [WorldInfoPosition] so the prompt
 * engine can splice them into the correct slots.
 *
 * Extracted from [DefaultPromptEngine] so the activation logic is unit-testable
 * without Android dependencies.
 *
 * @param random injectable source of randomness in [0.0, 1.0) for probability
 *   rolls; defaults to [Math.random]. Tests pass a deterministic function.
 * @param maxRecursionSteps hard cap on recursive scan passes (0 = no recursion,
 *   matching SillyTavern's default).
 * @param maxContentTokens world-info token budget. Entries marked
 *   [WorldBookEntry.ignoreBudget] may exceed it.
 */
class WorldInfoScanner(
    private val random: () -> Double = { Math.random() },
    private val maxRecursionSteps: Int = 0,
    private val maxContentTokens: Int? = null,
) {

    /**
     * Optional per-scan collector for the context viewer. Passing null (the
     * default) leaves every decision path byte-identical to the legacy scan;
     * when present the scanner additionally records why each entry fired or
     * was rejected. Purely observational — never consulted for control flow.
     */
    class ScanDiagnostics {
        data class Hit(
            val entry: WorldBookEntry,
            val matchedKeys: List<String>,
            val matchedSecondaryKeys: List<String>,
            val unconditional: Boolean,
            val recursionLevel: Int,
            val tokens: Int,
        )

        data class Rejection(val entry: WorldBookEntry, val reason: String)

        val hits = mutableListOf<Hit>()
        val rejections = mutableListOf<Rejection>()
        private val rejectedIds = mutableSetOf<String>()

        /** First recorded reason for an entry wins. */
        fun reject(entry: WorldBookEntry, reason: String) {
            if (rejectedIds.add(entry.id)) rejections.add(Rejection(entry, reason))
        }

        fun hit(hit: Hit) {
            hits.add(hit)
        }
    }

    companion object {
        /** Stable rejection reason tokens (surface as localized labels in UI). */
        const val REJECT_DISABLED = "disabled"
        const val REJECT_KEYWORD_MISS = "keyword_miss"
        const val REJECT_PROBABILITY = "probability"
        const val REJECT_INCLUSION_GROUP = "inclusion_group"
        const val REJECT_BUDGET = "budget"
        const val REJECT_DELAYED = "delayed_until_recursion"
    }

    /** An activated entry together with its macro-expanded content. */
    data class ActivatedEntry(val entry: WorldBookEntry, val content: String)

    /** Result of a scan, bucketed by injection position. */
    data class ScanResult(
        val before: List<ActivatedEntry>,
        val after: List<ActivatedEntry>,
        val anTop: List<ActivatedEntry>,
        val anBottom: List<ActivatedEntry>,
        val atDepth: List<ActivatedEntry>,
        val emTop: List<ActivatedEntry>,
        val emBottom: List<ActivatedEntry>,
        val outlet: List<ActivatedEntry>,
        val allActivated: List<ActivatedEntry>,
    )

    /**
     * Scan [entries] against [searchText]. [expand] is applied to each entry's
     * stripped content when it is activated (so recursion feeds expanded text and
     * the returned [ActivatedEntry] carries the expanded content). The original
     * entry is passed as well so callers can cache expansion by entry identity.
     * [keyExpand] is applied to primary/secondary keys before matching,
     * mirroring ST's substituteParams on keys (world-info.js:4803-4804,4835).
     */
    fun scan(
        entries: List<WorldBookEntry>,
        searchText: String,
        expand: (WorldBookEntry, String) -> String,
        keyExpand: (String) -> String = { it },
        entrySearchText: (WorldBookEntry) -> String = { searchText },
        diagnostics: ScanDiagnostics? = null,
    ): ScanResult {
        // SillyTavern always rejects disabled entries before considering the
        // constant flag. A disabled constant is still disabled.
        val candidates = entries.filter { it.enabled }
        if (diagnostics != null) {
            entries.filterNot { it.enabled }.forEach { diagnostics.reject(it, REJECT_DISABLED) }
        }
        // Leading @@decorator lines are parsed out of the content before
        // anything else (world-info.js:4517 parseDecorators); [stripped]
        // carries the decorator-free entry passed to [expand] so decorator
        // lines never reach the prompt or the recursion buffer.
        val decorators = HashMap<WorldBookEntry, List<String>>()
        val stripped = HashMap<WorldBookEntry, WorldBookEntry>()
        for (entry in candidates) {
            val (decs, content) = parseDecorators(entry.content)
            decorators[entry] = decs
            stripped[entry] = if (content == entry.content) entry else entry.copy(content = content)
        }
        val activated = LinkedHashMap<WorldBookEntry, String>()
        val failedProbability = mutableSetOf<WorldBookEntry>()
        // 逐轮共享的预算计数（ST：每轮对当轮候选按 order 降序做预算选择，
        // 剩余预算跨轮累计；旧实现是全扫完后从尾部一次性裁剪）。
        var budgetUsed = 0

        fun processMatchedPass(matched: List<WorldBookEntry>, passText: (WorldBookEntry) -> String, level: Int): List<WorldBookEntry> {
            // Inclusion groups run on each pass's fresh matches BEFORE the
            // probability rolls (world-info.js:4893 filterByInclusionGroups).
            val survivors = filterByInclusionGroups(matched, activated.keys, passText, keyExpand)
            if (diagnostics != null) {
                matched.filterNot { it in survivors }.forEach { diagnostics.reject(it, REJECT_INCLUSION_GROUP) }
            }
            val rolled = mutableListOf<Pair<WorldBookEntry, String>>()
            for (entry in survivors) {
                if (activated.containsKey(entry)) continue
                if (passesProbability(entry)) {
                    rolled += entry to expand(entry, stripped.getValue(entry).content)
                } else {
                    failedProbability.add(entry)
                    diagnostics?.reject(entry, REJECT_PROBABILITY)
                }
            }
            // 预算闸门（world-info.js:4900-4954）：当轮候选按 order 降序；
            // 越过预算的条目丢弃并锁定本轮，其后仅 ignoreBudget 可继续。
            // 被拒条目从未激活，因此不会进入递归缓冲。
            val nextNew = mutableListOf<WorldBookEntry>()
            var overflowed = false
            for ((entry, content) in rolled.sortedByDescending { it.first.insertionOrder }) {
                val tokens = TokenBudget.estimateTokens(content)
                val gated = maxContentTokens?.takeIf { it > 0 }
                if (gated != null && !entry.ignoreBudget && (overflowed || budgetUsed + tokens >= gated)) {
                    overflowed = true
                    diagnostics?.reject(entry, REJECT_BUDGET)
                    continue
                }
                budgetUsed += tokens
                activated[entry] = content
                nextNew.add(entry)
                if (diagnostics != null) {
                    val (primary, secondary) = matchedKeysOf(entry, passText(entry), keyExpand, decorators.getValue(entry))
                    diagnostics.hit(
                        ScanDiagnostics.Hit(
                            entry = entry,
                            matchedKeys = primary,
                            matchedSecondaryKeys = secondary,
                            unconditional = entry.constant || "@@activate" in decorators.getValue(entry),
                            recursionLevel = level,
                            tokens = tokens,
                        ),
                    )
                }
            }
            return nextNew
        }

        // ── Initial pass ──────────────────────────────────────────────────
        // ST suppresses delayUntilRecursion entries outside recursion scans
        // (world-info.js:4749-4752), regardless of the delay level.
        val initialMatches = candidates.filter {
            it.delayUntilRecursion <= 0 && matchEntry(it, entrySearchText(it), keyExpand, decorators.getValue(it))
        }
        processMatchedPass(initialMatches, entrySearchText, level = 0)

        // ── Recursive passes ──────────────────────────────────────────────
        // ST 的 max_recursion_steps 是「总扫描次数含首扫」：0 不设上限，
        // 1 只扫一次（无递归），2 首扫 + 一次递归（world-info.js checkWorldInfo）。
        if (maxRecursionSteps > 0) {
            var newlyActivated = activated.keys.toList()
            // ST's recurse buffer accumulates across ALL passes
            // (WorldInfoBuffer.addRecurse pushes, never clears mid-scan).
            val recurseBuffer = StringBuilder()
            var stepsUsed = 1
            while (newlyActivated.isNotEmpty() && stepsUsed < maxRecursionSteps) {
                val currentLevel = stepsUsed
                // preventRecursion entries do not feed further recursion (but
                // were still activated themselves).
                val recursionText = buildString {
                    newlyActivated
                        .filter { !it.preventRecursion }
                        .forEach { append(activated[it]); append('\n') }
                }
                if (recursionText.isBlank() && recurseBuffer.isBlank()) break
                recurseBuffer.append(recursionText)

                val combinedText: (WorldBookEntry) -> String = {
                    if (it.scanDepth != null && it.scanDepth <= 0) "" else "${entrySearchText(it)}\n$recurseBuffer"
                }
                // Recursion candidates: anything not yet activated and not
                // already failed a probability roll. delayUntilRecursion
                // entries become eligible once the scan reaches their level
                // (world-info.js:4753-4756).
                val recursionCandidates = candidates.filter {
                    !activated.containsKey(it) &&
                        !failedProbability.contains(it) &&
                        !it.excludeRecursion &&
                        it.delayUntilRecursion <= currentLevel
                }

                val matchedThisRound = recursionCandidates
                    .filter { matchEntry(it, combinedText(it), keyExpand, decorators.getValue(it)) }

                newlyActivated = processMatchedPass(matchedThisRound, combinedText, level = currentLevel)
                stepsUsed++
            }
        }

        // ── Reconcile never-activated candidates (diagnostics only) ───────
        if (diagnostics != null) {
            for (entry in candidates) {
                if (activated.containsKey(entry)) continue
                // 步数预算含首扫：level d 需要第 d 轮（stepsUsed == d）发生，
                // 即 d <= maxRecursionSteps - 1；否则按「延迟未开放」记录。
                val delayed = entry.delayUntilRecursion > 0 &&
                    (maxRecursionSteps == 0 || entry.delayUntilRecursion >= maxRecursionSteps)
                diagnostics.reject(entry, if (delayed) REJECT_DELAYED else REJECT_KEYWORD_MISS)
            }
        }

        // ── Sort + bucket by position ────────────────────────────────────
        // ST sorts descending by `order` for budget selection (world-info.js:88),
        // then unshifts into the position buckets, so the final text order
        // inside each bucket is ascending by `order`. 预算已在逐轮闸门中处理。
        val all = activated.entries.toList()
            .sortedByDescending { it.key.insertionOrder }
            .asReversed()
            .map { ActivatedEntry(it.key, it.value) }
        return ScanResult(
            before = all.bucket(WorldInfoPosition.BEFORE),
            after = all.bucket(WorldInfoPosition.AFTER),
            anTop = all.bucket(WorldInfoPosition.AN_TOP),
            anBottom = all.bucket(WorldInfoPosition.AN_BOTTOM),
            atDepth = all.bucket(WorldInfoPosition.AT_DEPTH),
            emTop = all.bucket(WorldInfoPosition.EM_TOP),
            emBottom = all.bucket(WorldInfoPosition.EM_BOTTOM),
            outlet = all.bucket(WorldInfoPosition.OUTLET),
            allActivated = all,
        )
    }

    private fun List<ActivatedEntry>.bucket(pos: WorldInfoPosition): List<ActivatedEntry> =
        filter { WorldInfoPosition.of(it.entry.position) == pos }

    /**
     * Diagnostics-only mirror of [matchEntry]: which primary/secondary keys
     * actually matched [text]. Called after an entry already matched, so it
     * never changes activation outcomes.
     */
    private fun matchedKeysOf(
        entry: WorldBookEntry,
        text: String,
        keyExpand: (String) -> String,
        decorators: List<String>,
    ): Pair<List<String>, List<String>> {
        if ("@@activate" in decorators || entry.constant) return entry.keys.filter { it.isNotBlank() } to emptyList()
        val primary = entry.keys.filter { it.isNotBlank() && matchKey(keyExpand(it), text, entry) }
        if (!entry.selective) return primary to emptyList()
        val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
        val matchedSecondary = secondary.filter { matchKey(keyExpand(it), text, entry) }
        return primary to when (WorldInfoLogic.of(entry.selectiveLogic)) {
            WorldInfoLogic.NOT_ANY, WorldInfoLogic.NOT_ALL -> emptyList() // matched by absence, not by a key
            else -> matchedSecondary
        }
    }

    private fun passesProbability(entry: WorldBookEntry): Boolean {
        if (!entry.useProbability || entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return random() * 100 <= entry.probability
    }

    /**
     * True if [entry] matches [text] under its primary-key + selective-logic
     * rules. constant entries are handled by the caller (they skip matching).
     * Keys are macro-expanded via [keyExpand] before matching (ST substitutes
     * params in keys, world-info.js:4803-4804,4835). Decorators short-circuit
     * matching: @@activate forces the entry in, @@dont_activate keeps it out
     * (world-info.js:4763-4772; @@activate checked first).
     */
    private fun matchEntry(
        entry: WorldBookEntry,
        text: String,
        keyExpand: (String) -> String,
        decorators: List<String> = emptyList(),
    ): Boolean {
        if ("@@activate" in decorators) return true
        if ("@@dont_activate" in decorators) return false
        if (entry.constant) return true
        val primaryMatched = entry.keys.any { it.isNotBlank() && matchKey(keyExpand(it), text, entry) }
        if (!entry.selective) return primaryMatched
        if (!primaryMatched) return false
        return evaluateSecondary(entry, text, keyExpand)
    }

    /**
     * ST parseDecorators (world-info.js:4540-4585): leading lines starting
     * with `@@` are decorators; `@@@` marks a fallback that only applies when
     * the preceding `@@` line was unknown. Returns (decorators, content
     * without the decorator lines).
     */
    private fun parseDecorators(content: String): Pair<List<String>, String> {
        if (!content.startsWith("@@")) return emptyList<String>() to content
        val known = listOf("@@activate", "@@dont_activate")
        fun isKnown(line: String): Boolean {
            val data = if (line.startsWith("@@@")) line.substring(1) else line
            return known.any { data.startsWith(it) }
        }
        val lines = content.split("\n")
        val decorators = mutableListOf<String>()
        var newContent = ""
        var fallbacked = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("@@")) {
                if (line.startsWith("@@@") && !fallbacked) continue
                if (isKnown(line)) {
                    decorators.add(if (line.startsWith("@@@")) line.substring(1) else line)
                    fallbacked = false
                } else {
                    fallbacked = true
                }
            } else {
                newContent = lines.drop(i).joinToString("\n")
                break
            }
        }
        // Normalize to the bare decorator names for matching.
        val normalized = decorators.map { line -> known.first { line.startsWith(it) } }
        return normalized to newContent
    }

    /**
     * ST inclusion groups (world-info.js:5269-5361 filterByInclusionGroups +
     * 5173-5209 filterGroupsByScoring, minus timed effects which tellev does
     * not implement): among this pass's fresh matches that share a group
     * label, only one wins — by prioritize flag (sorted by insertion order),
     * else by weighted random roll. A group that already has an activated
     * entry admits no new members.
     */
    private fun filterByInclusionGroups(
        matched: List<WorldBookEntry>,
        alreadyActivated: Collection<WorldBookEntry>,
        passText: (WorldBookEntry) -> String,
        keyExpand: (String) -> String,
    ): List<WorldBookEntry> {
        val grouped = LinkedHashMap<String, MutableList<WorldBookEntry>>()
        for (entry in matched) {
            if (entry.group.isBlank()) continue
            entry.group.split(Regex(",\\s*")).filter { it.isNotEmpty() }.forEach { label ->
                grouped.getOrPut(label) { mutableListOf() }.add(entry)
            }
        }
        if (grouped.isEmpty()) return matched

        val removed = mutableSetOf<WorldBookEntry>()
        for ((label, group) in grouped) {
            val alive = group.filterNot { it in removed }.toMutableList()

            // Scoring pass: keep only the best key-match scorers among entries
            // that opted into group scoring (world-info.js:5173-5209).
            if (alive.any { it.useGroupScoring }) {
                val scores = alive.associateWith { groupScore(it, passText(it), keyExpand) }
                val maxScore = scores.values.maxOrNull() ?: 0
                alive.filter { it.useGroupScoring && (scores[it] ?: 0) < maxScore }
                    .forEach { removed.add(it); alive.remove(it) }
            }

            // ST compares the FULL group string against the label here
            // (world-info.js:5314 `x.group === key`), quirk included.
            if (alreadyActivated.any { it.group == label }) {
                alive.forEach { removed.add(it) }
                continue
            }
            if (alive.size <= 1) continue

            // Prioritized entries win by insertion order (sortFn: order desc).
            val prios = alive.filter { it.groupOverride }.sortedByDescending { it.insertionOrder }
            val winner: WorldBookEntry? = if (prios.isNotEmpty()) {
                prios.first()
            } else {
                // Weighted random roll (DEFAULT_WEIGHT = 100).
                val totalWeight = alive.sumOf { it.groupWeight.coerceAtLeast(0) }
                val rollValue = random() * totalWeight
                var currentWeight = 0
                var picked: WorldBookEntry? = null
                for (entry in alive) {
                    currentWeight += entry.groupWeight.coerceAtLeast(0)
                    if (rollValue <= currentWeight) {
                        picked = entry
                        break
                    }
                }
                picked
            }
            if (winner != null) {
                alive.filter { it !== winner }.forEach { removed.add(it) }
            }
        }
        return matched.filterNot { it in removed }
    }

    /** ST WorldInfoBuffer.getScore: count of matching primary keys, plus
     * secondary-key contributions per the entry's selective logic. */
    private fun groupScore(entry: WorldBookEntry, text: String, keyExpand: (String) -> String): Int {
        val primary = entry.keys.count { it.isNotBlank() && matchKey(keyExpand(it), text, entry) }
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return primary
        val secondary = entry.secondaryKeys.count { it.isNotBlank() && matchKey(keyExpand(it), text, entry) }
        return when (WorldInfoLogic.of(entry.selectiveLogic)) {
            WorldInfoLogic.AND_ANY, WorldInfoLogic.AND_ALL -> primary + secondary
            WorldInfoLogic.NOT_ANY, WorldInfoLogic.NOT_ALL -> primary
        }
    }

    private fun evaluateSecondary(entry: WorldBookEntry, text: String, keyExpand: (String) -> String): Boolean {
        val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
        if (secondary.isEmpty()) return true
        val logic = WorldInfoLogic.of(entry.selectiveLogic)
        return when (logic) {
            WorldInfoLogic.AND_ANY -> secondary.any { matchKey(keyExpand(it), text, entry) }
            WorldInfoLogic.AND_ALL -> secondary.all { matchKey(keyExpand(it), text, entry) }
            WorldInfoLogic.NOT_ANY -> secondary.none { matchKey(keyExpand(it), text, entry) }
            WorldInfoLogic.NOT_ALL -> secondary.any { !matchKey(keyExpand(it), text, entry) }
        }
    }

    /**
     * Match a single key against [haystack]. ST-style `/pattern/flags` regex
     * keys are detected per key (world-info.js parseRegexFromString); the
     * tellev-only entry-level [WorldBookEntry.useRegex] additionally treats
     * plain keys as regex. Otherwise honors [WorldBookEntry.matchWholeWords]
     * and [WorldBookEntry.caseSensitive]. Mirrors ST's matchKeys
     * (world-info.js ~L337).
     */
    private fun matchKey(needle: String, haystack: String, entry: WorldBookEntry): Boolean {
        val trimmed = needle.trim()
        if (trimmed.isEmpty()) return false

        // Per-key regex in ST's `/pattern/flags` syntax. Regex keys override
        // all other matching options and carry their own flags.
        parseRegexFromString(trimmed)?.let { regex ->
            return regex.containsMatchIn(haystack)
        }

        // tellev extension: entry-level useRegex treats plain keys as regex.
        if (entry.useRegex) {
            val regex = runCatching {
                Regex(trimmed, if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }.getOrNull() ?: return false
            return regex.containsMatchIn(haystack)
        }

        val hay = if (entry.caseSensitive) haystack else haystack.lowercase()
        val key = if (entry.caseSensitive) trimmed else trimmed.lowercase()

        if (!entry.matchWholeWords) return hay.contains(key)

        // Whole-word matching. Multi-word keys fall back to contains (ST does
        // the same, splitting on any whitespace: world-info.js:350).
        if (Regex("\\s").containsMatchIn(key)) return hay.contains(key)
        val boundary = runCatching {
            Regex("(?:^|\\W)(${Regex.escape(key)})(?:\$|\\W)")
        }.getOrNull() ?: return hay.contains(key)
        return boundary.containsMatchIn(hay)
    }

    /**
     * ST-compatible parseRegexFromString (world-info.js:2821): `/pattern/flags`
     * with flags from [gimsuy]. An unescaped inner slash invalidates the key,
     * and escaped `\/` sequences are unescaped before compiling. Returns null
     * when the key is not a regex or fails to compile.
     */
    private fun parseRegexFromString(input: String): Regex? {
        val match = Regex("^/([\\w\\W]+?)/([gimsuy]*)$").matchEntire(input) ?: return null
        val (rawPattern, flags) = match.destructured
        // An unescaped `/` inside the pattern is not a valid delimited regex.
        if (Regex("(^|[^\\\\])/").containsMatchIn(rawPattern)) return null
        val pattern = rawPattern.replace("\\/", "/")
        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options += RegexOption.IGNORE_CASE
        if ('m' in flags) options += RegexOption.MULTILINE
        if ('s' in flags) options += RegexOption.DOT_MATCHES_ALL
        // 'u'/'g'/'y' have no Kotlin RegexOption equivalent and are ignored.
        return runCatching { Regex(pattern, options) }.getOrNull()
    }
}
