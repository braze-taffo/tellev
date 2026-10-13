package app.tellev.core.storage

import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.storage.codec.PresetCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/**
 * 预设提示词「可选添加」契约：identifier 生成不重号、上限只约束用户新增
 * （custom- 前缀计数，ST 原生条目再多也不挡入口）、新增空行保存时不落盘，
 * 填过的自定义条目经序列化/解析往返保真。
 */
class PresetPromptCustomAddTest {

    @Test
    fun `generated identifiers stay unique`() {
        assertEquals("custom-1", nextCustomPromptIdentifier(emptyList()))
        assertEquals("custom-2", nextCustomPromptIdentifier(listOf("custom-1")))
        assertEquals("custom-3", nextCustomPromptIdentifier(listOf("main", "custom-1", "custom-2")))
    }

    @Test
    fun `add is capped at 64 custom prompts and native entries do not count`() {
        val below = List(63) { PresetPrompt(identifier = "custom-${it + 1}") }
        assertTrue(canAddCustomPrompt(below))
        // ST 原生条目（无 custom- 前缀）再多也不占用新增额度。
        assertTrue(canAddCustomPrompt(below + List(80) { PresetPrompt(identifier = "st-$it") }))
        val atLimit = List(PRESET_CUSTOM_PROMPT_LIMIT) { PresetPrompt(identifier = "custom-${it + 1}") }
        assertFalse(canAddCustomPrompt(atLimit))
    }

    @Test
    fun `untouched added rows are dropped while filled and pre-existing rows survive`() {
        val existingEmpty = PresetPrompt(identifier = "jailbreak", name = "jailbreak", content = "")
        val addedUntouched = PresetPrompt(identifier = "custom-1", name = "", content = "")
        val addedFilled = PresetPrompt(identifier = "custom-2", name = "", content = "stay in character")
        val disabledUntouched = PresetPrompt(identifier = "custom-3", name = "", content = "", enabled = false)

        val (active, unused) = withoutUntouchedCustomPrompts(
            active = listOf(existingEmpty, addedUntouched, addedFilled),
            unused = listOf(disabledUntouched),
            addedIdentifiers = setOf("custom-1", "custom-3"),
        )

        // 既有空条目（ST 预设里很常见）不动；加了没填的新增行从两份列表都不落盘。
        assertEquals(listOf("jailbreak", "custom-2"), active.map { it.identifier })
        assertTrue("没填的新增行不得落盘", unused.isEmpty())
    }

    @Test
    fun `filled custom prompt round trips through serialize and parse`() {
        val prompts = listOf(
            PresetPrompt(identifier = "main", name = "Main", content = "main text", order = 0),
            PresetPrompt(
                identifier = "custom-1",
                name = "Style",
                role = "assistant",
                content = "stay in character",
                order = 1,
            ),
        )
        val raw = buildJsonObject {
            put("prompts", JsonArray(prompts.map(PresetCodec::serializePresetPrompt)))
            put("prompt_order", PresetCodec.serializePromptOrder(null, prompts))
        }

        val parsed = PresetCodec.parsePreset(
            Paths.get("custom.json"),
            raw,
            PresetCategory.OpenAi,
            "openai-compatible",
        )

        assertEquals(listOf("main", "custom-1"), parsed.prompts.map { it.identifier })
        val custom = parsed.prompts.last()
        assertEquals("Style", custom.name)
        assertEquals("assistant", custom.role)
        assertEquals("stay in character", custom.content)
        assertTrue(custom.enabled)
    }
}
