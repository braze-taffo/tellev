package app.tellev.feature.characters

import app.tellev.core.model.CharacterSummary
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色分组持久化与「搜索 × 分组」联合过滤的回归守护：
 *  - 分组读写往返、损坏文件回落空表（不抛）
 *  - 筛选语义：全部 / 未分组 / 指定分组 × 关键词交集
 */
class CharacterGroupsTest {

    private fun summary(id: String, name: String, tags: List<String> = emptyList()) =
        CharacterSummary(id = id, name = name, avatarRelativePath = "characters/$id.png", tags = tags)

    @Test
    fun `write and read round trip preserves groups`() {
        val root = Files.createTempDirectory("char-groups")
        val groups = listOf(
            CharacterGroup(id = "cg_a", label = "主要", members = listOf("char_1", "char_2")),
            CharacterGroup(id = "cg_b", label = "配角", members = emptyList()),
        )
        assertTrue(CharacterGroups.write(root, groups))
        assertEquals(groups, CharacterGroups.read(root))
    }

    @Test
    fun `read missing file yields empty list`() {
        val root = Files.createTempDirectory("char-groups-empty")
        assertEquals(emptyList<CharacterGroup>(), CharacterGroups.read(root))
    }

    @Test
    fun `read corrupt file falls back to empty list`() {
        val root = Files.createTempDirectory("char-groups-corrupt")
        Files.write(root.resolve("character-groups.json"), "{not json".toByteArray())
        assertEquals(emptyList<CharacterGroup>(), CharacterGroups.read(root))
    }

    @Test
    fun `read drops blank id or label entries`() {
        val root = Files.createTempDirectory("char-groups-blank")
        Files.write(
            root.resolve("character-groups.json"),
            """{"groups":[{"id":"","label":"x","members":[]},{"id":"cg_ok","label":"","members":[]},{"id":"cg_keep","label":"保留","members":["char_1"]}]}"""
                .toByteArray(),
        )
        val groups = CharacterGroups.read(root)
        assertEquals(listOf("cg_keep"), groups.map { it.id })
    }

    @Test
    fun `new group id is unique and prefixed`() {
        val ids = (1..20).map { CharacterGroups.newGroupId() }.toSet()
        assertEquals(20, ids.size)
        assertTrue(ids.all { it.startsWith("cg_") })
    }

    @Test
    fun `filter ungrouped excludes cards in any group`() {
        val characters = listOf(summary("c1", "甲"), summary("c2", "乙"), summary("c3", "丙"))
        val groups = listOf(CharacterGroup("g1", "主", listOf("c1")))
        val filtered = CharactersFilter.filter(characters, "", groups, CharactersViewModel.UNGROUPED_GROUP_ID)
        assertEquals(listOf("c2", "c3"), filtered.map { it.id })
    }

    @Test
    fun `filter by group keeps only members`() {
        val characters = listOf(summary("c1", "甲"), summary("c2", "乙"), summary("c3", "丙"))
        val groups = listOf(CharacterGroup("g1", "主", listOf("c1", "c3")))
        val filtered = CharactersFilter.filter(characters, "", groups, "g1")
        assertEquals(listOf("c1", "c3"), filtered.map { it.id })
    }

    @Test
    fun `filter intersects group and query`() {
        val characters = listOf(
            summary("c1", "甲", listOf("勇者")),
            summary("c2", "乙", listOf("法师")),
            summary("c3", "丙", listOf("勇者")),
        )
        val groups = listOf(CharacterGroup("g1", "主", listOf("c1", "c2")))
        // 分组 g1 内只有 c1 命中关键词。
        val filtered = CharactersFilter.filter(characters, "勇者", groups, "g1")
        assertEquals(listOf("c1"), filtered.map { it.id })
    }

    @Test
    fun `filter all with blank query returns everyone`() {
        val characters = listOf(summary("c1", "甲"), summary("c2", "乙"))
        val filtered = CharactersFilter.filter(characters, "", emptyList(), null)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `membership toggling is additive and removable`() {
        val root = Files.createTempDirectory("char-groups-toggle")
        var groups = listOf(CharacterGroup("g1", "主", emptyList()))
        // 加入
        groups = groups.map { it.copy(members = (it.members + "c1").distinct()) }
        assertEquals(listOf("c1"), groups.single().members)
        // 重复加入不产生副本
        groups = groups.map { it.copy(members = (it.members + "c1").distinct()) }
        assertEquals(listOf("c1"), groups.single().members)
        // 移除
        groups = groups.map { it.copy(members = it.members.filterNot { id -> id == "c1" }) }
        assertTrue(groups.single().members.isEmpty())
        assertTrue(CharacterGroups.write(root, groups))
        assertEquals(groups, CharacterGroups.read(root))
    }
}
