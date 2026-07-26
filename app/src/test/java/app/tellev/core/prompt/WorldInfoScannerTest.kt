package app.tellev.core.prompt

import app.tellev.core.model.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldInfoScannerTest {

    private fun entry(
        id: String,
        keys: List<String>,
        content: String = "",
        secondaryKeys: List<String> = emptyList(),
        selective: Boolean = false,
        selectiveLogic: Int = 0,
        constant: Boolean = false,
        position: Int = 0,
        probability: Int = 100,
        useProbability: Boolean = false,
        role: Int = 0,
        depth: Int = 4,
        matchWholeWords: Boolean = false,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        delayUntilRecursion: Int = 0,
        ignoreBudget: Boolean = false,
        priority: Int = 0,
        insertionOrder: Int = 100,
        enabled: Boolean = true,
    ) = WorldBookEntry(
        id = id,
        keys = keys,
        content = content,
        secondaryKeys = secondaryKeys,
        selective = selective,
        selectiveLogic = selectiveLogic,
        constant = constant,
        position = position,
        probability = probability,
        useProbability = useProbability,
        role = role,
        depth = depth,
        matchWholeWords = matchWholeWords,
        useRegex = useRegex,
        caseSensitive = caseSensitive,
        excludeRecursion = excludeRecursion,
        preventRecursion = preventRecursion,
        delayUntilRecursion = delayUntilRecursion,
        ignoreBudget = ignoreBudget,
        priority = priority,
        insertionOrder = insertionOrder,
        enabled = enabled,
    )

    private fun scan(
        entries: List<WorldBookEntry>,
        text: String,
        random: () -> Double = { 0.0 },
        maxRecursion: Int = 5,
    ) = WorldInfoScanner(random = random, maxRecursionSteps = maxRecursion)
        .scan(entries, text, expand = { it.content })

    private fun ids(activated: List<WorldInfoScanner.ActivatedEntry>) = activated.map { it.entry.id }.toSet()

    // ── selectiveLogic ───────────────────────────────────────────────────

    @Test
    fun `AND_ANY activates when any secondary matches`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 0)
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha beta").allActivated))
    }

    @Test
    fun `AND_ANY does not activate when no secondary matches`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 0)
        assertTrue(scan(listOf(e), "alpha delta").allActivated.isEmpty())
    }

    @Test
    fun `AND_ALL activates when all secondary match`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 3)
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha beta gamma").allActivated))
    }

    @Test
    fun `AND_ALL does not activate when a secondary is missing`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 3)
        assertTrue(scan(listOf(e), "alpha beta").allActivated.isEmpty())
    }

    @Test
    fun `NOT_ANY activates when no secondary matches`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 2)
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha delta").allActivated))
    }

    @Test
    fun `NOT_ANY does not activate when any secondary matches`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 2)
        assertTrue(scan(listOf(e), "alpha beta").allActivated.isEmpty())
    }

    @Test
    fun `NOT_ALL activates when any secondary does not match`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("beta", "gamma"), selective = true, selectiveLogic = 1)
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha beta").allActivated))
    }

    // ── probability ──────────────────────────────────────────────────────

    @Test
    fun `probability rolls below threshold activates`() {
        val e = entry("e", keys = listOf("alpha"), probability = 60, useProbability = true)
        // random()=0.5 → 0.5*100=50 <= 60 → activate
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha", random = { 0.5 }).allActivated))
    }

    @Test
    fun `probability rolls above threshold rejects`() {
        val e = entry("e", keys = listOf("alpha"), probability = 40, useProbability = true)
        // random()=0.5 → 50 > 40 → reject
        assertTrue(scan(listOf(e), "alpha", random = { 0.5 }).allActivated.isEmpty())
    }

    @Test
    fun `useProbability false always activates`() {
        val e = entry("e", keys = listOf("alpha"), probability = 1, useProbability = false)
        assertEquals(setOf("e"), ids(scan(listOf(e), "alpha", random = { 0.99 }).allActivated))
    }

    @Test
    fun `constant entries also pass probability`() {
        val e = entry("e", keys = emptyList(), constant = true, probability = 40, useProbability = true)
        assertTrue(scan(listOf(e), "", random = { 0.5 }).allActivated.isEmpty())
        assertEquals(setOf("e"), ids(scan(listOf(e), "", random = { 0.0 }).allActivated))
    }

    // ── recursion ────────────────────────────────────────────────────────

    @Test
    fun `recursion activates entry whose key appears in another entry content`() {
        val a = entry("a", keys = listOf("start"), content = "this mentions dragon")
        val b = entry("b", keys = listOf("dragon"), content = "dragon lore")
        val result = scan(listOf(a, b), "start here")
        assertEquals(setOf("a", "b"), ids(result.allActivated))
    }

    @Test
    fun `preventRecursion blocks further activation`() {
        val a = entry("a", keys = listOf("start"), content = "this mentions dragon", preventRecursion = true)
        val b = entry("b", keys = listOf("dragon"), content = "dragon lore")
        val result = scan(listOf(a, b), "start here")
        assertEquals(setOf("a"), ids(result.allActivated))
    }

    @Test
    fun `excludeRecursion prevents entry from activating during recursion`() {
        val a = entry("a", keys = listOf("start"), content = "this mentions dragon")
        val b = entry("b", keys = listOf("dragon"), content = "dragon lore", excludeRecursion = true)
        val result = scan(listOf(a, b), "start here")
        assertEquals(setOf("a"), ids(result.allActivated))
    }

    @Test
    fun `maxRecursionSteps caps recursion`() {
        // Chain a→b→c where each content mentions the next key.
        val a = entry("a", keys = listOf("k1"), content = "k2")
        val b = entry("b", keys = listOf("k2"), content = "k3")
        val c = entry("c", keys = listOf("k3"), content = "k4")
        val result = scan(listOf(a, b, c), "k1", maxRecursion = 0)
        // No recursion allowed → only a activates.
        assertEquals(setOf("a"), ids(result.allActivated))
    }

    @Test
    fun `recursion is disabled by default like SillyTavern`() {
        val a = entry("a", keys = listOf("start"), content = "dragon")
        val b = entry("b", keys = listOf("dragon"), content = "dragon lore")
        val result = WorldInfoScanner(random = { 0.0 })
            .scan(listOf(a, b), "start", expand = { it.content })

        assertEquals(setOf("a"), ids(result.allActivated))
    }

    @Test
    fun `delayUntilRecursion only matches during recursion`() {
        val a = entry("a", keys = listOf("start"), content = "trigger delayed")
        val delayed = entry("d", keys = listOf("delayed"), content = "delayed content", delayUntilRecursion = 1)
        // Initial text does NOT contain "delayed" — only the recursion text does.
        val result = scan(listOf(a, delayed), "start here")
        assertEquals(setOf("a", "d"), ids(result.allActivated))
    }

    @Test
    fun `delayUntilRecursion level 2 waits for the second recursion pass`() {
        val a = entry("a", keys = listOf("start"), content = "mentions lvl1")
        val d1 = entry("d1", keys = listOf("lvl1"), content = "mentions lvl2", delayUntilRecursion = 1)
        val d2 = entry("d2", keys = listOf("lvl2"), content = "lvl2 lore", delayUntilRecursion = 2)
        // Full run: d1 at level 1, d2 at level 2.
        assertEquals(setOf("a", "d1", "d2"), ids(scan(listOf(a, d1, d2), "start").allActivated))
        // Capped at one recursion pass: d2 is not eligible yet.
        assertEquals(setOf("a", "d1"), ids(scan(listOf(a, d1, d2), "start", maxRecursion = 1).allActivated))
    }

    @Test
    fun `delayUntilRecursion entry is suppressed even when its key is in the scan text`() {
        val a = entry("a", keys = listOf("start"), content = "mentions lvl1")
        val d1 = entry("d1", keys = listOf("lvl1"), content = "chain", delayUntilRecursion = 1)
        val d2 = entry("d2", keys = listOf("lvl2"), content = "lvl2 lore", delayUntilRecursion = 2)
        // "lvl2" is present in the initial scan text, but d2 must still wait
        // for recursion level 2 — it must not activate in earlier passes.
        assertEquals(setOf("a", "d1"), ids(scan(listOf(a, d1, d2), "start lvl2", maxRecursion = 1).allActivated))
        assertEquals(setOf("a", "d1", "d2"), ids(scan(listOf(a, d1, d2), "start lvl2", maxRecursion = 2).allActivated))
    }

    // ── position bucketing ───────────────────────────────────────────────

    @Test
    fun `entries bucket by position`() {
        val entries = (0..7).map { i ->
            entry("e$i", keys = listOf("k"), content = "c$i", position = i)
        }
        val result = scan(entries, "k")
        assertEquals(listOf("e0"), result.before.map { it.entry.id })
        assertEquals(listOf("e1"), result.after.map { it.entry.id })
        assertEquals(listOf("e2"), result.anTop.map { it.entry.id })
        assertEquals(listOf("e3"), result.anBottom.map { it.entry.id })
        assertEquals(listOf("e4"), result.atDepth.map { it.entry.id })
        assertEquals(listOf("e5"), result.emTop.map { it.entry.id })
        assertEquals(listOf("e6"), result.emBottom.map { it.entry.id })
        assertEquals(listOf("e7"), result.outlet.map { it.entry.id })
    }

    // ── matching options ─────────────────────────────────────────────────

    @Test
    fun `matchWholeWords does not match substrings`() {
        val e = entry("e", keys = listOf("cat"), matchWholeWords = true)
        assertTrue(scan(listOf(e), "category").allActivated.isEmpty())
        assertEquals(setOf("e"), ids(scan(listOf(e), "a cat sat").allActivated))
    }

    @Test
    fun `useRegex matches via regex`() {
        val e = entry("e", keys = listOf("foo\\d+"), useRegex = true)
        assertEquals(setOf("e"), ids(scan(listOf(e), "foo123 bar").allActivated))
        assertTrue(scan(listOf(e), "foo bar").allActivated.isEmpty())
    }

    @Test
    fun `caseSensitive respects case`() {
        val e = entry("e", keys = listOf("Alpha"), caseSensitive = true)
        assertTrue(scan(listOf(e), "alpha beta").allActivated.isEmpty())
        assertEquals(setOf("e"), ids(scan(listOf(e), "Alpha beta").allActivated))
    }

    // ── sorting ──────────────────────────────────────────────────────────

    @Test
    fun `entries sort ascending by insertionOrder inside buckets like ST`() {
        // ST sorts descending by `order` for budget selection, then unshifts
        // into buckets, so the final in-bucket order is ascending by `order`.
        val a = entry("a", keys = listOf("k"), priority = 10, insertionOrder = 5)
        val b = entry("b", keys = listOf("k"), priority = 10, insertionOrder = 1)
        val c = entry("c", keys = listOf("k"), priority = 100, insertionOrder = 9)
        val result = scan(listOf(a, b, c), "k")
        assertEquals(listOf("b", "a", "c"), result.before.map { it.entry.id })
    }

    @Test
    fun `token budget keeps higher-order entries`() {
        // Budget selection follows ST's descending-order pass: the entry with
        // the higher `order` wins the limited budget.
        val low = entry("low", keys = listOf("k"), content = "aaaa", insertionOrder = 1)
        val high = entry("high", keys = listOf("k"), content = "bbbb", insertionOrder = 9)
        val result = WorldInfoScanner(random = { 0.0 }, maxContentTokens = 2)
            .scan(listOf(low, high), "k", expand = { it.content })
        assertEquals(setOf("high"), ids(result.allActivated))
    }

    // ── ST-style regex keys (/pattern/flags) ─────────────────────────────

    @Test
    fun `slash regex key matches without entry useRegex flag`() {
        val e = entry("e", keys = listOf("/foo\\d+/"))
        assertEquals(setOf("e"), ids(scan(listOf(e), "foo123 bar").allActivated))
        assertTrue(scan(listOf(e), "foo bar").allActivated.isEmpty())
    }

    @Test
    fun `regex key flags are honored`() {
        // i flag: case-insensitive
        assertEquals(setOf("e"), ids(scan(listOf(entry("e", keys = listOf("/FOO/i"))), "foo").allActivated))
        // no flag: case-sensitive
        assertTrue(scan(listOf(entry("e", keys = listOf("/FOO/"))), "foo").allActivated.isEmpty())
        // s flag: dot matches newline
        assertEquals(setOf("e"), ids(scan(listOf(entry("e", keys = listOf("/a.c/s"))), "a\nc").allActivated))
    }

    @Test
    fun `regex key with escaped inner slash matches`() {
        val e = entry("e", keys = listOf("/a\\/b/"))
        assertEquals(setOf("e"), ids(scan(listOf(e), "a/b").allActivated))
    }

    @Test
    fun `key with unescaped inner slash falls back to literal matching`() {
        // "/a/b/" is not a valid ST regex (unescaped delimiter) and is treated
        // as a plain key, like ST's matchKeys does.
        val e = entry("e", keys = listOf("/a/b/"))
        assertEquals(setOf("e"), ids(scan(listOf(e), "see /a/b/ here").allActivated))
        assertTrue(scan(listOf(e), "see a/b here").allActivated.isEmpty())
    }

    // ── key macro substitution ───────────────────────────────────────────

    @Test
    fun `primary keys are macro-expanded before matching`() {
        val e = entry("e", keys = listOf("{{k}}"))
        val result = WorldInfoScanner(random = { 0.0 }, maxRecursionSteps = 0)
            .scan(listOf(e), "dragon lore", expand = { it.content }, keyExpand = { if (it == "{{k}}") "dragon" else it })
        assertEquals(setOf("e"), ids(result.allActivated))
        // Without expansion the raw "{{k}}" key does not match.
        assertTrue(scan(listOf(e), "dragon lore").allActivated.isEmpty())
    }

    @Test
    fun `secondary keys are macro-expanded before matching`() {
        val e = entry("e", keys = listOf("alpha"), secondaryKeys = listOf("{{s}}"), selective = true, selectiveLogic = 0)
        val result = WorldInfoScanner(random = { 0.0 }, maxRecursionSteps = 0)
            .scan(listOf(e), "alpha beta", expand = { it.content }, keyExpand = { if (it == "{{s}}") "beta" else it })
        assertEquals(setOf("e"), ids(result.allActivated))
    }

    @Test
    fun `disabled entries are skipped`() {
        val e = entry("e", keys = listOf("k"), enabled = false)
        assertTrue(scan(listOf(e), "k").allActivated.isEmpty())
    }

    @Test
    fun `disabled constant entries are skipped`() {
        val e = entry("e", keys = emptyList(), constant = true, enabled = false)
        assertTrue(scan(listOf(e), "").allActivated.isEmpty())
    }

    @Test
    fun `world info budget is enforced and ignoreBudget can exceed it`() {
        val regular = entry("regular", keys = emptyList(), content = "long regular lore", constant = true)
        val forced = entry(
            "forced",
            keys = emptyList(),
            content = "long forced lore",
            constant = true,
            ignoreBudget = true,
        )
        val result = WorldInfoScanner(random = { 0.0 }, maxContentTokens = 1)
            .scan(listOf(regular, forced), "", expand = { it.content })

        assertEquals(setOf("forced"), ids(result.allActivated))
    }

    @Test
    fun `budget overflow latches and drops all later non-ignoreBudget entries`() {
        // ST world-info.js:4900-4954: once the budget is crossed, every later
        // entry is skipped even if it would individually fit; only
        // ignoreBudget entries still insert.
        val big = entry(
            "big", keys = emptyList(), constant = true, insertionOrder = 3,
            content = "long lore ".repeat(40), // ~100 tokens
        )
        val smallLater = entry(
            "smallLater", keys = emptyList(), constant = true, insertionOrder = 2,
            content = "tiny",
        )
        val forcedLater = entry(
            "forcedLater", keys = emptyList(), constant = true, insertionOrder = 1,
            content = "forced", ignoreBudget = true,
        )
        val result = WorldInfoScanner(random = { 0.0 }, maxContentTokens = 50)
            .scan(listOf(big, smallLater, forcedLater), "", expand = { it.content })

        // `big` (order 3, scanned first) crosses the 50-token budget: dropped,
        // and `smallLater` is dropped with it despite fitting on its own.
        assertEquals(setOf("forcedLater"), ids(result.allActivated))
    }
}
