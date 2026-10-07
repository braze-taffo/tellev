package app.tellev.feature.creation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 完成度评分：权重合计、硬门槛、缺失项与建议的回归。 */
class CharacterCompletionTest {

    @Test
    fun `empty draft scores zero and blocks saving`() {
        val report = CharacterCompletion.evaluate(CharacterDraft())
        assertEquals(0, report.score)
        assertFalse(report.canChat)
        assertEquals(listOf("名称", "简介", "开场白"), report.blockers)
        assertTrue(report.missing.isNotEmpty())
    }

    @Test
    fun `required fields alone unblock chat but score stays partial`() {
        val report = CharacterCompletion.evaluate(
            CharacterDraft(
                name = "沈清霜",
                description = "清冷师尊",
                firstMessage = "夜深了，她提灯立在阶前。",
            ),
        )
        assertTrue(report.canChat)
        assertEquals(14 + 18 + 16, report.score)
        assertTrue("性格" in report.missing)
        assertTrue("示例对话" in report.missing)
        assertTrue(report.suggestions.isNotEmpty())
    }

    @Test
    fun `fully filled draft scores hundred and suggests nothing`() {
        val report = CharacterCompletion.evaluate(
            CharacterDraft(
                name = "沈清霜",
                description = "清冷师尊，外冷内热。",
                personality = "寡言、护短",
                scenario = "外门弟子入门夜。",
                firstMessage = "……跟上。",
                alternateGreetings = listOf("雪夜初遇。"),
                exampleMessages = "<START>\n{{user}}: 师尊……",
                systemPrompt = "你是沈清霜。",
                postHistoryInstructions = "",
                creatorNotes = "ver 1",
                tags = listOf("古风", "师尊"),
                frontendHtml = "<div>…</div>",
                coverPrompt = "cold fairy, white hair",
            ),
        )
        assertEquals(100, report.score)
        assertTrue(report.canChat)
        assertTrue(report.missing.isEmpty())
        assertTrue(report.suggestions.isEmpty())
    }

    @Test
    fun `post history instructions count as system prompt`() {
        val withPost = CharacterCompletion.evaluate(
            CharacterDraft(
                name = "n", description = "d", firstMessage = "f",
                postHistoryInstructions = "keep terse",
            ),
        )
        val without = CharacterCompletion.evaluate(
            CharacterDraft(name = "n", description = "d", firstMessage = "f"),
        )
        assertEquals(6, withPost.score - without.score)
        assertTrue("系统提示" !in withPost.missing)
        assertTrue("系统提示" in without.missing)
    }

    @Test
    fun `blank alternate greetings do not count`() {
        val filled = CharacterDraft(
            name = "n", description = "d", firstMessage = "f",
            alternateGreetings = listOf("hi"),
        )
        val blank = CharacterDraft(
            name = "n", description = "d", firstMessage = "f",
            alternateGreetings = listOf("  ", ""),
        )
        assertEquals(4, CharacterCompletion.evaluate(filled).score - CharacterCompletion.evaluate(blank).score)
    }
}
