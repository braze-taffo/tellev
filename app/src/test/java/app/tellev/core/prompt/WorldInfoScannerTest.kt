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
        delayUntilRecursion: Boolean = false,
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
        .scan(entries, text) { it.content }

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
    fun `excludeRecursion keeps entry content out of recursion text`() {
        val a = entry("a", keys = listOf("start"), content = "this mentions dragon", excludeRecursion = true)
        val b = entry("b", keys = listOf("dragon"), content = "dragon lore")
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
    fun `delayUntilRecursion only matches during recursion`() {
        val a = entry("a", keys = listOf("start"), content = "trigger delayed")
        val delayed = entry("d", keys = listOf("delayed"), content = "delayed content", delayUntilRecursion = true)
        // Initial text does NOT contain "delayed" — only the recursion text does.
        val result = scan(listOf(a, delayed), "start here")
        assertEquals(setOf("a", "d"), ids(result.allActivated))
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
    fun `entries sort by priority desc then insertionOrder asc`() {
        val a = entry("a", keys = listOf("k"), priority = 10, insertionOrder = 5)
        val b = entry("b", keys = listOf("k"), priority = 10, insertionOrder = 1)
        val c = entry("c", keys = listOf("k"), priority = 100, insertionOrder = 9)
        val result = scan(listOf(a, b, c), "k")
        assertEquals(listOf("c", "b", "a"), result.before.map { it.entry.id })
    }

    @Test
    fun `disabled entries are skipped`() {
        val e = entry("e", keys = listOf("k"), enabled = false)
        assertTrue(scan(listOf(e), "k").allActivated.isEmpty())
    }
}
