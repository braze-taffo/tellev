package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 `ui-primitives/src/rank-by-name.ts` 的契约复刻测试。
 * 用例对应上游 tests 的同名场景。
 */
class ModelSearchRankingTest {

    private data class Cand(
        override val name: String,
        override val label: String? = null,
    ) : NamedCandidate

    private fun rank(query: String, vararg names: String): List<String> =
        ModelSearchRanking.rankByName(names.map { Cand(it) }, query).map { it.name }

    @Test
    fun `empty query returns input order`() {
        assertEquals(listOf("b-model", "a-model"), rank("", "b-model", "a-model"))
    }

    @Test
    fun `query must be an ordered subsequence`() {
        // "dm" 是 "deepseek-model" 的子序列（d..m），但不是 "model" 的。
        assertEquals(listOf("deepseek-model"), rank("dm", "model", "deepseek-model"))
    }

    @Test
    fun `prefix hits rank first`() {
        // my-gpt-4 不是前缀命中（分数 16 < 19），排最后。
        // gpt-4 与 gpt-4o 都是前缀命中且同为 19 分，按源顺序决胜。
        val result = rank("gpt", "my-gpt-4", "gpt-4o", "gpt-4")
        assertEquals(listOf("gpt-4o", "gpt-4", "my-gpt-4"), result)
    }

    @Test
    fun `label is a second search key`() {
        val cands = listOf(
            Cand(name = "m-001", label = "DeepSeek Reasoner"),
            Cand(name = "m-002", label = "Claude Sonnet"),
        )
        val result = ModelSearchRanking.rankByName(cands, "reasoner")
        assertEquals(listOf("m-001"), result.map { it.name })
    }

    @Test
    fun `stronger alignment beats source order`() {
        // "ab" 在 "ab-x" 里相邻连续（+4 加权），在 "a-x-b" 里跳格。
        val result = rank("ab", "a-x-b", "ab-x")
        assertEquals(listOf("ab-x", "a-x-b"), result)
    }

    @Test
    fun `tied scores fall back to source order`() {
        // gpt-4 与 gpt_4 对 "gpt4" 都不是前缀命中，且同为 27 分
        // （分隔符都在 index 3，边界加成相同），所以源顺序决定。
        val result = rank("gpt4", "gpt-4", "gpt_4")
        assertEquals(listOf("gpt-4", "gpt_4"), result)
        val reversed = rank("gpt4", "gpt_4", "gpt-4")
        assertEquals(listOf("gpt_4", "gpt-4"), reversed)
    }

    @Test
    fun `boundary bonus separates different alignments`() {
        // "a-b" 的 b 落在 - 边界（+8），"axb" 的 b 不在边界（+0），
        // 两者都不是 "ab" 的前缀，所以分数差直接决定顺序。
        val result = rank("ab", "axb", "a-b")
        assertEquals(listOf("a-b", "axb"), result)
    }

    @Test
    fun `non matching query yields empty`() {
        assertTrue(rank("zzz", "gpt-4", "claude").isEmpty())
    }

    @Test
    fun `case insensitive`() {
        assertEquals(listOf("GPT-4o"), rank("gpt", "GPT-4o"))
    }

    @Test
    fun `query longer than name never matches`() {
        assertTrue(rank("gpt-4o-mini-extra", "gpt-4o").isEmpty())
    }

    // ── 服务商排序 ────────────────────────────────────────────

    private data class Group(override val id: String, val label: String) : ProviderGroup

    @Test
    fun `account and official providers sort first`() {
        val groups = listOf(
            Group("custom-a", "A"),
            Group("deepseek-official", "Official"),
            Group("openai-compatible", "OpenAI"),
            Group("deepseek-account", "Account"),
        )
        assertEquals(
            listOf("deepseek-account", "deepseek-official", "custom-a", "openai-compatible"),
            orderModelProviders(groups).map { it.id },
        )
    }

    @Test
    fun `other providers keep relative order`() {
        val groups = listOf(
            Group("z-last", "Z"),
            Group("a-first", "A"),
            Group("m-middle", "M"),
        )
        assertEquals(
            listOf("z-last", "a-first", "m-middle"),
            orderModelProviders(groups).map { it.id },
        )
    }
}
