package app.tellev.feature.world

import app.tellev.core.model.WorldBookEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TELLER-4-6：世界书条目编辑器脏判定（未保存更改保护）的纯函数部分。
 *
 * 归一化必须与保存路径一致（逗号分词/trim、空数字回退、概率与延迟层级 clamp），
 * 否则「改了但保存后会回退」的字段要么漏报，要么在刚进入页面时就误弹确认框。
 */
class EditorGuardWorldEntryDirtyTest {

    private val entry = WorldBookEntry(
        id = "entry_1",
        keys = listOf("dragon", "wyrm"),
        secondaryKeys = listOf("fire"),
        content = "A dragon breathes fire.",
        enabled = true,
        selective = true,
        constant = false,
        priority = 10,
        insertionOrder = 200,
        depth = 6,
        position = 4,
        selectiveLogic = 2,
        useProbability = true,
        probability = 75,
        role = 1,
        matchWholeWords = true,
        useRegex = false,
        caseSensitive = true,
        comment = "note",
        excludeRecursion = true,
        preventRecursion = true,
        delayUntilRecursion = 2,
        // 编辑器没有暴露这些字段，但保存必须原样保留。
        ignoreBudget = true,
        group = "beasts",
        groupWeight = 42,
        useGroupScoring = true,
        scanDepth = 3,
        raw = buildJsonObject { put("custom", "keep") },
    )

    /** 与界面 rememberSaveable/remember 初始化等效的当前值。 */
    private fun fieldsOf(base: WorldBookEntry) = WorldEntryEditorFields(
        keys = base.keys.joinToString(", "),
        secondaryKeys = base.secondaryKeys.joinToString(", "),
        content = base.content,
        enabled = base.enabled,
        selective = base.selective,
        constant = base.constant,
        priority = base.priority.toString(),
        insertionOrder = base.insertionOrder.toString(),
        depth = base.depth.toString(),
        position = base.position,
        selectiveLogic = base.selectiveLogic,
        useProbability = base.useProbability,
        probability = base.probability.toString(),
        role = base.role,
        matchWholeWords = base.matchWholeWords,
        useRegex = base.useRegex,
        caseSensitive = base.caseSensitive,
        excludeRecursion = base.excludeRecursion,
        preventRecursion = base.preventRecursion,
        delayUntilRecursion = base.delayUntilRecursion > 0,
        delayUntilRecursionLevel = base.delayUntilRecursion.coerceAtLeast(1).toString(),
        comment = base.comment,
    )

    @Test
    fun `untouched editor is clean`() {
        assertFalse(fieldsOf(entry).isDirty(entry))
        assertEquals(entry, fieldsOf(entry).toEntry(entry))
    }

    @Test
    fun `brand new entry starts clean`() {
        val fresh = WorldBookEntry(id = "entry_new", keys = emptyList(), content = "", enabled = true)
        assertFalse(fieldsOf(fresh).isDirty(fresh))
    }

    @Test
    fun `content edit is dirty`() {
        val fields = fieldsOf(entry).copy(content = "changed")
        assertTrue(fields.isDirty(entry))
        assertNotEquals(entry, fields.toEntry(entry))
    }

    @Test
    fun `keys normalize the same way as saving`() {
        // 逗号分词/trim/去空与保存完全一致时，纯排版差异不算脏。
        val respaced = fieldsOf(entry).copy(keys = " dragon ,, wyrm ", secondaryKeys = " fire ")
        assertFalse(respaced.isDirty(entry))
        assertTrue(fieldsOf(entry).copy(keys = "dragon, basilisk").isDirty(entry))
        assertTrue(fieldsOf(entry).copy(keys = "").isDirty(entry))
    }

    @Test
    fun `non numeric numbers fall back exactly like saving`() {
        // priority 无法解析时保存写 0：与已存值 0 相同即干净，与 10 不同即脏。
        assertTrue(fieldsOf(entry).copy(priority = "abc").isDirty(entry))
        assertFalse(fieldsOf(entry).copy(priority = "abc").isDirty(entry.copy(priority = 0)))
        assertFalse(fieldsOf(entry).copy(insertionOrder = "").isDirty(entry.copy(insertionOrder = 100)))
        assertFalse(fieldsOf(entry).copy(depth = "-").isDirty(entry.copy(depth = 4)))
    }

    @Test
    fun `probability clamps like saving`() {
        assertFalse(fieldsOf(entry).copy(probability = "150").isDirty(entry.copy(probability = 100)))
        assertTrue(fieldsOf(entry).copy(probability = "999").isDirty(entry))
        assertFalse(fieldsOf(entry).copy(probability = "").isDirty(entry.copy(probability = 100)))
    }

    @Test
    fun `delayed recursion level clamps like saving`() {
        val levelOne = entry.copy(delayUntilRecursion = 1)
        // 开关打开时空/0 层级都会被 coerceAtLeast(1) 抬到 1。
        assertFalse(fieldsOf(levelOne).copy(delayUntilRecursionLevel = "0").isDirty(levelOne))
        assertFalse(fieldsOf(levelOne).copy(delayUntilRecursionLevel = "").isDirty(levelOne))
        assertTrue(fieldsOf(levelOne).copy(delayUntilRecursionLevel = "5").isDirty(levelOne))
        // 关掉开关 = delayUntilRecursion 0，与已存的 1 不同。
        assertTrue(fieldsOf(levelOne).copy(delayUntilRecursion = false).isDirty(levelOne))
    }

    @Test
    fun `toggles and dropdowns are dirty`() {
        assertTrue(fieldsOf(entry).copy(enabled = false).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(selective = false).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(constant = true).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(useProbability = false).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(useRegex = true).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(preventRecursion = false).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(comment = "renamed").isDirty(entry))
        assertTrue(fieldsOf(entry).copy(position = 1).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(selectiveLogic = 3).isDirty(entry))
        assertTrue(fieldsOf(entry).copy(role = 2).isDirty(entry))
    }

    @Test
    fun `fields the editor does not expose survive the round trip`() {
        val updated = fieldsOf(entry).toEntry(entry)
        assertTrue(updated.ignoreBudget)
        assertEquals("beasts", updated.group)
        assertEquals(42, updated.groupWeight)
        assertTrue(updated.useGroupScoring)
        assertEquals(3, updated.scanDepth)
        assertEquals(buildJsonObject { put("custom", "keep") }, updated.raw)
    }
}
