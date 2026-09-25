package app.tellev.feature.creation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationBriefTest {
    @Test fun guidedEnsembleKeepsEachCharacterAndUserRole() {
        val prompt = CreationBrief(
            kind = CreationKind.Character,
            guided = true,
            title = "旧城旅店",
            premise = "两位旧友重逢",
            userPersona = "用户扮演旅店新主人",
            characters = "阿岚｜童年好友｜警惕\n小雨｜房客｜隐瞒身份",
            detail = CreationDetail.Rich,
        ).toPrompt()
        assertTrue(prompt.contains("阿岚｜童年好友｜警惕"))
        assertTrue(prompt.contains("小雨｜房客｜隐瞒身份"))
        assertTrue(prompt.contains("用户扮演旅店新主人"))
        assertTrue(prompt.contains("详细"))
        assertTrue(prompt.contains("只问当前最关键的 1 至 2 个问题"))
    }

    @Test fun directLoreDraftRespectsEntryConfirmation() {
        val prompt = CreationBrief(
            kind = CreationKind.WorldBook,
            guided = false,
            title = "海港",
            premise = "潮汐决定航路",
            loreOneByOne = true,
        ).toPrompt()
        assertTrue(prompt.contains("世界书名：海港"))
        assertTrue(prompt.contains("先逐条讨论"))
        assertTrue(prompt.contains("等我确认后再写入草稿"))
        assertFalse(prompt.contains("角色与用户的关系"))
    }

    @Test fun originalBriefSurvivesLongConversationWithLaterChangesTakingPriority() {
        val brief = CreationBrief(CreationKind.Character, guided = true,
            title = "旧城旅店", detail = CreationDetail.Rich).toPrompt()
        val turns = listOf(CreationTurn("user", brief)) +
            (1..12).map { CreationTurn("user", "第 $it 轮修改") }
        val context = creationConversationContext(CreationSession(kind = CreationKind.Character, turns = turns))
        assertTrue(context.contains("最初创作起点（后续用户修改优先）"))
        assertTrue(context.contains("旧城旅店"))
        assertTrue(context.contains("第 12 轮修改"))
    }
}
