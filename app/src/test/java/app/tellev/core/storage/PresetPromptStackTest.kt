package app.tellev.core.storage

import app.tellev.core.model.PresetCategory
import app.tellev.core.model.PresetPrompt
import app.tellev.core.storage.codec.PresetCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/**
 * 预设提示词栈契约：真实 ST 预设（tools/fixtures/mengjing-v4-0915.json）带
 * `prompts` + `prompt_order`，其中 `prompt_order` 同时承载顺序与启停。
 *
 * 解析层必须保留全量定义（raw 的 prompts 数组完整，虚拟 API 按它取全量），
 * 而「预设调节 → 提示词」页的启用列表只能含真正启用的条目——否则界面会列出
 * 模型根本不会注入的指令，用户看不出真实状态也没法管理这 75 条备用指令。
 */
class PresetPromptStackTest {

    private fun fixture(): JsonObject {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "tools/fixtures/mengjing-v4-0915.json")
            if (candidate.isFile) {
                return Json.parseToJsonElement(candidate.readText()).jsonObject
            }
            dir = dir.parentFile ?: error("cannot locate preset fixture from ${System.getProperty("user.dir")}")
        }
    }

    private fun parsed() = PresetCodec.parsePreset(
        Paths.get("mengjing.json"),
        fixture(),
        PresetCategory.OpenAi,
        "openai-compatible",
    )

    @Test
    fun `parse keeps the full definition list for the raw api contract`() {
        val raw = fixture()
        val definitions = parsePresetPrompts(raw["prompts"])
        val (ordered, outside) = applyPromptOrder(definitions, raw["prompt_order"])

        assertEquals(119, definitions.size)
        assertEquals(75, definitions.count { !it.enabled })
        // order 组 115 条（39 启用 + 76 停用），4 条标记型定义不在 order 里。
        assertEquals(115, ordered.size)
        assertEquals(4, outside.size)
        assertEquals(39, ordered.count { it.enabled })
        // 停用条目仍留在 prompts 里并带 enabled=false：raw 数组必须保持完整。
        assertEquals(76, ordered.count { !it.enabled })
    }

    @Test
    fun `editor stack lists only enabled prompts and keeps the rest re-enableable`() {
        val preset = parsed()
        val (active, standby) = presetPromptEditorStack(preset.prompts, preset.promptsUnused)

        assertEquals(39, active.size)
        assertTrue("启用列表不得包含已停用条目", active.all { it.enabled })
        // 76 条 order 停用 + 4 条不在 order 里 = 80 条可一键启用。
        assertEquals(80, standby.size)
        // 生成链实际注入的条目与界面启用列表一致。
        val generated = preset.prompts.filter { it.enabled && it.identifier !in preset.promptsUnused.map { it.identifier }.toSet() }
        assertEquals(generated.map { it.identifier }, active.map { it.identifier })
    }

    @Test
    fun `saving an untouched stack preserves enable state order and content`() {
        val raw = fixture()
        val definitions = parsePresetPrompts(raw["prompts"])
        val (ordered, outside) = applyPromptOrder(definitions, raw["prompt_order"])
        val (active, standby) = presetPromptEditorStack(ordered, outside)

        val savedOrder = PresetCodec.serializePromptOrder(raw["prompt_order"], active)
        val (reparsed, reparsedOutside) = applyPromptOrder(definitions, savedOrder)
        val (activeAgain, standbyAgain) = presetPromptEditorStack(reparsed, reparsedOutside)

        assertEquals(active.map { it.identifier }, activeAgain.map { it.identifier })
        assertTrue(activeAgain.all { it.enabled })
        assertEquals(standby.size, standbyAgain.size)
        // 内容逐字保留：宏/长文本不得被截断或改写。
        val byId = definitions.associateBy { it.identifier }
        activeAgain.forEach { prompt ->
            val source = byId.getValue(prompt.identifier)
            assertEquals(source.content, prompt.content)
            // 注入序与禁用角色卡覆盖同样逐字保留（保存不得悄悄重置它们）。
            assertEquals(source.injectionOrder, prompt.injectionOrder)
            assertEquals(source.forbidOverrides, prompt.forbidOverrides)
        }
    }

    @Test
    fun `re-enabling a standby prompt moves it back into the active stack`() {
        val raw = fixture()
        val definitions = parsePresetPrompts(raw["prompts"])
        val (ordered, outside) = applyPromptOrder(definitions, raw["prompt_order"])
        val (active, standby) = presetPromptEditorStack(ordered, outside)

        val promoted = standby.first { !it.enabled }.copy(enabled = true)
        val nextActive = active + promoted
        val nextStandby = standby.filterNot { it.identifier == promoted.identifier }

        val savedOrder = PresetCodec.serializePromptOrder(raw["prompt_order"], nextActive)
        val (reparsed, reparsedOutside) = applyPromptOrder(definitions, savedOrder)
        val (activeAgain, standbyAgain) = presetPromptEditorStack(reparsed, reparsedOutside)

        assertTrue(promoted.identifier in activeAgain.map { it.identifier }.toSet())
        assertTrue(activeAgain.first { it.identifier == promoted.identifier }.enabled)
        assertFalse(promoted.identifier in standbyAgain.map { it.identifier }.toSet())
        assertTrue(
            standby.filterNot { it.identifier == promoted.identifier }
                .all { it.identifier in standbyAgain.map { p -> p.identifier }.toSet() },
        )
    }

    @Test
    fun `preset without prompt_order falls back to the enabled flag`() {
        val definitions = parsePresetPrompts(fixture()["prompts"])
        val (ordered, unused) = applyPromptOrder(definitions, null)
        assertEquals(119, ordered.size)
        assertTrue(unused.isEmpty())
        val (active, standby) = presetPromptEditorStack(ordered, unused)
        assertEquals(119 - 75, active.size)
        assertEquals(75, standby.size)
    }

    @Test
    fun `editing injection order and forbid overrides survives serialize and parse`() {
        val raw = fixture()
        val definitions = parsePresetPrompts(raw["prompts"])
        val (ordered, outside) = applyPromptOrder(definitions, raw["prompt_order"])
        val (active, _) = presetPromptEditorStack(ordered, outside)

        // 最坏情况：raw 里没有这两个键的条目（编辑器新增的自定义提示词同形）。
        val edited = PresetPrompt(
            identifier = "custom-edit",
            name = "",
            role = "system",
            content = "stay in character",
        ).copy(injectionOrder = 7, forbidOverrides = true)
        val nextActive = active + edited

        val saved = buildJsonObject {
            put("prompts", JsonArray(nextActive.map(PresetCodec::serializePresetPrompt)))
            put("prompt_order", PresetCodec.serializePromptOrder(raw["prompt_order"], nextActive))
        }
        val reparsed = PresetCodec.parsePreset(
            Paths.get("edited.json"),
            saved,
            PresetCategory.OpenAi,
            "openai-compatible",
        )

        val back = reparsed.prompts.first { it.identifier == "custom-edit" }
        assertEquals("编辑后的注入序必须落盘", 7, back.injectionOrder)
        assertTrue("禁用角色卡覆盖必须落盘", back.forbidOverrides)
        // 同栈其它条目的既有值不受波及。
        val byId = reparsed.prompts.associateBy { it.identifier }
        active.forEach { prompt ->
            assertEquals(prompt.injectionOrder, byId.getValue(prompt.identifier).injectionOrder)
            assertEquals(prompt.forbidOverrides, byId.getValue(prompt.identifier).forbidOverrides)
        }
    }

    @Test
    fun `legacy prompts without the two keys keep the default semantics`() {
        val legacy = buildJsonObject {
            put("prompts", JsonArray(listOf(buildJsonObject {
                put("identifier", "legacy")
                put("name", "Legacy")
                put("role", "system")
                put("content", "old")
            })))
        }
        val prompt = PresetCodec.parsePreset(
            Paths.get("legacy.json"),
            legacy,
            PresetCategory.OpenAi,
            "openai-compatible",
        ).prompts.single()
        // 读侧默认语义：缺键 = ST 默认（100 / false），加载行为不变。
        assertEquals(100, prompt.injectionOrder)
        assertFalse(prompt.forbidOverrides)

        // 再存一轮（新序列化写显式等值键）→ 回读语义仍不变。
        val resaved = buildJsonObject {
            put("prompts", JsonArray(listOf(PresetCodec.serializePresetPrompt(prompt))))
        }
        val again = PresetCodec.parsePreset(
            Paths.get("legacy.json"),
            resaved,
            PresetCategory.OpenAi,
            "openai-compatible",
        ).prompts.single()
        assertEquals(100, again.injectionOrder)
        assertFalse(again.forbidOverrides)
    }
}
