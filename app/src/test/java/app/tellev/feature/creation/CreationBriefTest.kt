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
        assertTrue(prompt.contains("一次只问一个问题"))
        assertFalse((prompt + CreationPrompts.continueGuide).contains("1 至 2"))
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

    @Test fun guidedLoreDraftStillWaitsForConfirmationBeforeWriting() {
        for (guided in listOf(false, true)) {
            val prompt = CreationBrief(CreationKind.WorldBook, guided = guided,
                loreOneByOne = true).toPrompt()
            assertTrue(prompt.contains("先提议第一条世界书条目，等我确认后再写入草稿"))
            assertFalse(prompt.contains("实际填写可编辑的世界书条目草稿"))
            assertFalse(prompt.contains(CreationPrompts.guidedInstruction))
        }
    }

    @Test fun characterBriefUsesTheSameInstructionAsGenerateDraftButton() {
        val prompt = CreationBrief(CreationKind.Character, guided = false,
            premise = "日常，克制，只有两个人。").toPrompt()
        assertTrue(prompt.contains(CreationPrompts.characterDraftInstruction))
        assertTrue(prompt.contains("日常，克制，只有两个人"))
        assertTrue(prompt.contains("不新增或修改世界书"))
        assertFalse(prompt.contains("只有我允许 AI 修改世界书条目时才写条目"))
    }
}
