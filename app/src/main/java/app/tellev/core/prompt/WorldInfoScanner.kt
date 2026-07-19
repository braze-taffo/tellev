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
     * content when it is activated (so recursion feeds expanded text and the
     * returned [ActivatedEntry] carries the expanded content).
     * [keyExpand] is applied to primary/secondary keys before matching,
     * mirroring ST's substituteParams on keys (world-info.js:4803-4804,4835).
     */
    fun scan(
        entries: List<WorldBookEntry>,
        searchText: String,
        expand: (WorldBookEntry) -> String,
        keyExpand: (String) -> String = { it },
    ): ScanResult {
        // SillyTavern always rejects disabled entries before considering the
        // constant flag. A disabled constant is still disabled.
        val candidates = entries.filter { it.enabled }
        val activated = LinkedHashMap<WorldBookEntry, String>()
        val failedProbability = mutableSetOf<WorldBookEntry>()

        // ── Initial pass ──────────────────────────────────────────────────
        // ST suppresses delayUntilRecursion entries outside recursion scans
        // (world-info.js:4749-4752), regardless of the delay level.
        val initialMatches = candidates.filter { it.delayUntilRecursion <= 0 && matchEntry(it, searchText, keyExpand) }
        for (entry in initialMatches) {
            if (passesProbability(entry)) {
                val content = expand(entry)
                activated[entry] = content
            } else {
                failedProbability.add(entry)
            }
        }

        // ── Recursive passes ──────────────────────────────────────────────
        if (maxRecursionSteps > 0) {
            var newlyActivated = activated.keys.toList()
            var steps = 0
            while (newlyActivated.isNotEmpty() && steps < maxRecursionSteps) {
                val currentLevel = steps + 1
                // Build the recursion text from entries activated in the
                // previous pass, skipping those that opt out of feeding
                // recursion. preventRecursion entries do not feed further
                // recursion (but were still activated themselves).
                val recursionText = buildString {
                    newlyActivated
                        .filter { !it.preventRecursion }
                        .forEach { append(activated[it]); append('\n') }
                }
                if (recursionText.isBlank()) break

                // Combine the original search text with the recursion buffer,
                // mirroring ST's buffer.get() which appends recursion content.
                val combinedText = "$searchText\n$recursionText"
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
                    .filter { matchEntry(it, combinedText, keyExpand) }

                val nextNew = mutableListOf<WorldBookEntry>()
                for (entry in matchedThisRound) {
                    if (activated.containsKey(entry)) continue
                    if (passesProbability(entry)) {
                        activated[entry] = expand(entry)
                        nextNew.add(entry)
                    } else {
                        failedProbability.add(entry)
                    }
                }
                newlyActivated = nextNew
                steps++
            }
        }

        // ── Sort + bucket by position ────────────────────────────────────
        // ST sorts descending by `order` for budget selection (world-info.js:88),
        // then unshifts into the position buckets, so the final text order
        // inside each bucket is ascending by `order`.
        val sorted = activated.entries.toList()
            .sortedByDescending { it.key.insertionOrder }

        val budgeted = applyTokenBudget(sorted)
        val all = budgeted.asReversed().map { ActivatedEntry(it.key, it.value) }
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

    private fun applyTokenBudget(
        entries: List<Map.Entry<WorldBookEntry, String>>,
    ): List<Map.Entry<WorldBookEntry, String>> {
        val budget = maxContentTokens?.takeIf { it > 0 } ?: return entries
        val included = mutableListOf<Map.Entry<WorldBookEntry, String>>()
        var usedTokens = 0

        for (entry in entries) {
            val contentTokens = TokenBudget.estimateTokens(entry.value)
            if (entry.key.ignoreBudget || usedTokens + contentTokens < budget) {
                included += entry
                usedTokens += contentTokens
            }
        }
        return included
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
     * params in keys, world-info.js:4803-4804,4835).
     */
    private fun matchEntry(entry: WorldBookEntry, text: String, keyExpand: (String) -> String): Boolean {
        if (entry.constant) return true
        val primaryMatched = entry.keys.any { it.isNotBlank() && matchKey(keyExpand(it), text, entry) }
        if (!entry.selective) return primaryMatched
        if (!primaryMatched) return false
        return evaluateSecondary(entry, text, keyExpand)
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
