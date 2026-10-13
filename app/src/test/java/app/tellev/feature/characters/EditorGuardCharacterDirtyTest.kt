package app.tellev.feature.characters

import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterCastBinding
import app.tellev.core.model.CharacterWorldBinding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TELLER-4-6：角色卡编辑器脏判定（未保存更改保护）的纯函数部分。
 *
 * 快照只覆盖 saveCard 会写入的字段，且归一化（trim / 空白即未绑定 / 附属角色去重）
 * 必须与保存路径一致：否则要么漏判世界书绑定与附属角色的改动，要么在用户什么都没做时
 * 误弹确认框；保存成功后也必须判成干净。
 *
 * 断言一律走 [characterEditorSnapshot] 这个与界面同口径的入口，不用 data class 的 copy
 * 直接改字段——那会绕过归一化。
 */
class EditorGuardCharacterDirtyTest {

    // 磁盘上的卡片通常没有 data.creator / data.system_prompt 这些键，只有 spec/data.name。
    // 若脏判定去比较整张卡（raw），updatedCard 补写这些键就会把「没改过」判成脏。
    private val loaded = CharacterCard(
        id = "char_hero",
        name = "Alice",
        description = "A wandering knight.",
        personality = "brave",
        scenario = "A ruined keep.",
        firstMessage = "Well met.",
        alternateGreetings = listOf("Hello there.", "Greetings."),
        exampleMessages = "{{user}}: hi",
        creatorNotes = "notes",
        systemPrompt = "sys",
        postHistoryInstructions = "phi",
        creator = "me",
        characterVersion = "1.0",
        tags = listOf("fantasy", "knight"),
        raw = buildJsonObject {
            put("spec", "chara_card_v2")
            put("data", buildJsonObject { put("name", "Alice") })
        },
    )

    private val ally = CharacterCard(id = "char_ally", name = "Bob", description = "Squire")

    /** 界面当前值（默认取「加载完成的卡片」，可用参数模拟用户的改动）。 */
    private fun currentOf(
        card: CharacterCard,
        name: String = card.name,
        castMemberIds: List<String> = CharacterCastBinding.members(card).map { it.id },
        linkedWorldNames: List<String> = CharacterWorldBinding.linkedWorldBookNames(card),
        hasPendingAvatar: Boolean = false,
    ) = characterEditorSnapshot(
        name = name,
        description = card.description,
        personality = card.personality,
        scenario = card.scenario,
        firstMessage = card.firstMessage,
        alternateGreetings = card.alternateGreetings,
        exampleMessages = card.exampleMessages,
        creatorNotes = card.creatorNotes,
        systemPrompt = card.systemPrompt,
        postHistoryInstructions = card.postHistoryInstructions,
        creator = card.creator,
        characterVersion = card.characterVersion,
        tags = card.tags,
        castMemberIds = castMemberIds,
        linkedWorldNames = linkedWorldNames,
        hasPendingAvatar = hasPendingAvatar,
    )

    @Test
    fun `untouched editor is clean`() {
        // 前置条件：raw 里确实没有会被 updatedCard 补写的键，否则本条测不到「不该比较 raw」。
        val data = loaded.raw["data"] as JsonObject
        assertNull(data["creator"])
        assertNull(data["system_prompt"])
        assertNull(data["post_history_instructions"])
        assertNull(data["character_version"])

        assertEquals(loaded.toEditorSnapshot(), currentOf(loaded))
    }

    @Test
    fun `saved card matches the snapshot of the values that produced it`() {
        val saved = CharacterCastBinding.withMembers(
            CharacterWorldBinding.withLinkedWorldBookName(loaded, "Lore"),
            listOf(ally),
        )
        // 保存会把 name trim、绑定名 trim、附属角色按 id 去重——界面里的未 trim 原值
        // 必须归一到同一份快照，否则保存后再返回会被当成还有未保存更改。
        assertEquals(
            saved.toEditorSnapshot(),
            currentOf(
                card = loaded,
                name = loaded.name + " ",
                castMemberIds = listOf(ally.id, ally.id),
                linkedWorldNames = listOf("  Lore  "),
            ),
        )
    }

    @Test
    fun `world book binding change is dirty`() {
        val bound = CharacterWorldBinding.withLinkedWorldBookName(loaded, "Lore")
        val baseline = bound.toEditorSnapshot()
        assertEquals(baseline, currentOf(bound))
        assertEquals(listOf("Lore"), baseline.linkedWorldNames)

        // 换绑 / 解绑都属于未保存更改（写的是 raw 里的 data.extensions.world）。
        assertNotEquals(baseline, currentOf(bound, linkedWorldNames = listOf("Other")))
        assertNotEquals(baseline, currentOf(bound, linkedWorldNames = listOf("")))
        // 只多了空格仍是同一个绑定。
        assertEquals(baseline, currentOf(bound, linkedWorldNames = listOf("  Lore  ")))
    }

    @Test
    fun `legacy world key reads as the same binding`() {
        val legacy = loaded.copy(
            raw = buildJsonObject {
                put("data", buildJsonObject { put("world", "Legacy") })
            },
        )
        assertEquals(listOf("Legacy"), legacy.toEditorSnapshot().linkedWorldNames)
        assertEquals(legacy.toEditorSnapshot(), currentOf(legacy))
    }

    @Test
    fun `cast member change is dirty`() {
        val withAlly = CharacterCastBinding.withMembers(loaded, listOf(ally))
        val untouched = withAlly.toEditorSnapshot()
        assertEquals(untouched, currentOf(withAlly))
        assertEquals(listOf(ally.id), untouched.castMemberIds)

        assertNotEquals(untouched, currentOf(withAlly, castMemberIds = emptyList()))
        assertNotEquals(
            untouched,
            currentOf(withAlly, castMemberIds = untouched.castMemberIds + "char_other"),
        )
        // 附属列表去重后再比较：重复项不构成未保存更改。
        assertEquals(untouched, currentOf(withAlly, castMemberIds = listOf(ally.id, ally.id)))
    }

    @Test
    fun `card field edits are dirty`() {
        val untouched = loaded.toEditorSnapshot()
        assertNotEquals(untouched, untouched.copy(name = "Alicia"))
        assertNotEquals(untouched, untouched.copy(description = "changed"))
        assertNotEquals(untouched, untouched.copy(systemPrompt = "changed"))
        assertNotEquals(untouched, untouched.copy(postHistoryInstructions = "changed"))
        assertNotEquals(untouched, untouched.copy(creator = "changed"))
        assertNotEquals(untouched, untouched.copy(characterVersion = "2.0"))
        assertNotEquals(untouched, untouched.copy(tags = loaded.tags + "new"))
        assertNotEquals(untouched, untouched.copy(alternateGreetings = listOf("only one")))
        // 标签按 list 原样写盘，顺序变化也是改动。
        assertNotEquals(untouched, untouched.copy(tags = loaded.tags.reversed()))
    }

    @Test
    fun `picked avatar makes a creating draft dirty`() {
        val empty = CharacterCard(id = "char_new", name = "")
        val baseline = empty.toEditorSnapshot()
        assertEquals(baseline, currentOf(empty))

        // 新建态的头像只在点创建时随卡片落盘，因此必须单独计入脏。
        assertNotEquals(baseline, currentOf(empty, hasPendingAvatar = true))
    }
}
