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
 *   ST default semantics apply a global cap).
 */
class WorldInfoScanner(
    private val random: () -> Double = { Math.random() },
    private val maxRecursionSteps: Int = 5,
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
     */
    fun scan(
        entries: List<WorldBookEntry>,
        searchText: String,
        expand: (WorldBookEntry) -> String,
    ): ScanResult {
        val candidates = entries.filter { it.enabled || it.constant }
        val activated = LinkedHashMap<WorldBookEntry, String>()
        val failedProbability = mutableSetOf<WorldBookEntry>()

        // ── Initial pass ──────────────────────────────────────────────────
        val initialMatches = candidates.filter { !it.delayUntilRecursion && matchEntry(it, searchText) }
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
                // Build the recursion text from entries activated in the
                // previous pass, skipping those that opt out of feeding
                // recursion. preventRecursion entries do not feed further
                // recursion (but were still activated themselves).
                val recursionText = buildString {
                    newlyActivated
                        .filter { !it.preventRecursion && !it.excludeRecursion }
                        .forEach { append(activated[it]); append('\n') }
                }
                if (recursionText.isBlank()) break

                // Combine the original search text with the recursion buffer,
                // mirroring ST's buffer.get() which appends recursion content.
                val combinedText = "$searchText\n$recursionText"
                // Recursion candidates: anything not yet activated and not
                // already failed a probability roll. delayUntilRecursion
                // entries were skipped in the initial pass and get their
                // first chance here.
                val recursionCandidates = candidates.filter {
                    !activated.containsKey(it) && !failedProbability.contains(it)
                }

                val matchedThisRound = recursionCandidates
                    .filter { matchEntry(it, combinedText) }

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
        val sorted = activated.entries.toList().sortedWith(
            compareByDescending<Map.Entry<WorldBookEntry, String>> { it.key.priority }
                .thenBy { it.key.insertionOrder },
        )

        val all = sorted.map { ActivatedEntry(it.key, it.value) }
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

    private fun passesProbability(entry: WorldBookEntry): Boolean {
        if (!entry.useProbability || entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return random() * 100 <= entry.probability
    }

    /**
     * True if [entry] matches [text] under its primary-key + selective-logic
     * rules. constant entries are handled by the caller (they skip matching).
     */
    private fun matchEntry(entry: WorldBookEntry, text: String): Boolean {
        if (entry.constant) return true
        val primaryMatched = entry.keys.any { it.isNotBlank() && matchKey(it, text, entry) }
        if (!entry.selective) return primaryMatched
        if (!primaryMatched) return false
        return evaluateSecondary(entry, text)
    }

    private fun evaluateSecondary(entry: WorldBookEntry, text: String): Boolean {
        val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
        if (secondary.isEmpty()) return true
        val logic = WorldInfoLogic.of(entry.selectiveLogic)
        return when (logic) {
            WorldInfoLogic.AND_ANY -> secondary.any { matchKey(it, text, entry) }
            WorldInfoLogic.AND_ALL -> secondary.all { matchKey(it, text, entry) }
            WorldInfoLogic.NOT_ANY -> secondary.none { matchKey(it, text, entry) }
            WorldInfoLogic.NOT_ALL -> secondary.any { !matchKey(it, text, entry) }
        }
    }

    /**
     * Match a single key against [haystack], honoring [WorldBookEntry.useRegex],
     * [WorldBookEntry.matchWholeWords], and [WorldBookEntry.caseSensitive].
     * Mirrors ST's matchKeys (world-info.js ~L337).
     */
    private fun matchKey(needle: String, haystack: String, entry: WorldBookEntry): Boolean {
        val trimmed = needle.trim()
        if (trimmed.isEmpty()) return false

        // Regex keys override all other options.
        if (entry.useRegex) {
            val regex = runCatching {
                val pattern = if (trimmed.startsWith("/") && trimmed.lastIndexOf('/') > 0) {
                    trimmed.trim('/').substringBeforeLast('/')
                } else trimmed
                Regex(pattern, if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            }.getOrNull() ?: return false
            return regex.containsMatchIn(haystack)
        }

        val hay = if (entry.caseSensitive) haystack else haystack.lowercase()
        val key = if (entry.caseSensitive) trimmed else trimmed.lowercase()

        if (!entry.matchWholeWords) return hay.contains(key)

        // Whole-word matching. Multi-word keys fall back to contains (ST does the same).
        if (key.contains(' ')) return hay.contains(key)
        val boundary = runCatching {
            Regex("(?:^|\\W)(${Regex.escape(key)})(?:\$|\\W)")
        }.getOrNull() ?: return hay.contains(key)
        return boundary.containsMatchIn(hay)
    }
}
