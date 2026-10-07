package app.tellev.feature.chat

import app.tellev.core.model.GenerationPreset
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** raw 键全量可调：拍平 / 类型推断 / set/remove / 用户输入解析。 */
class PresetRawKeysTest {

    private val raw = buildJsonObject {
        put("temperature", 1)
        put("n", 1)
        put("stream_openai", true)
        put("assistant_prefill", "")
        put("names_behavior", -1)
        put("continue_postfix", " ")
        put("wrap_in_quotes", false)
        put("prompt_order", buildJsonObject { put("character_id", 100001) })
        put("extensions", buildJsonObject {
            put("SPreset", buildJsonObject {
                put("MacroNest", false)
                put("RegexBinding", buildJsonObject { put("regexes", kotlinx.serialization.json.JsonArray(emptyList())) })
            })
        })
    }

    @Test
    fun `flattens every leaf except typed and prompt keys`() {
        val rows = PresetRawKeys.rows(raw)
        val paths = rows.map { it.path }
        // 类型化键（temperature）与提示词页键（prompt_order）不出现。
        assertTrue("temperature" !in paths)
        assertTrue("prompt_order" !in paths)
        // 其余叶子全部出现，含嵌套点分路径。
        assertTrue(paths.containsAll(
            listOf("n", "stream_openai", "assistant_prefill", "names_behavior", "continue_postfix", "wrap_in_quotes",
                "extensions.SPreset.MacroNest", "extensions.SPreset.RegexBinding.regexes"),
        ))
    }

    @Test
    fun `kind inference matches json shape`() {
        val byPath = PresetRawKeys.rows(raw).associateBy { it.path }
        assertEquals(PresetRawKeys.Kind.Number, byPath.getValue("n").kind)
        assertEquals(PresetRawKeys.Kind.Switch, byPath.getValue("stream_openai").kind)
        assertEquals(PresetRawKeys.Kind.Text, byPath.getValue("assistant_prefill").kind)
        assertEquals(PresetRawKeys.Kind.Number, byPath.getValue("names_behavior").kind)
        // 空数组/对象归为 JSON 行。
        assertEquals(PresetRawKeys.Kind.Json, byPath.getValue("extensions.SPreset.RegexBinding.regexes").kind)
    }

    @Test
    fun `setPath writes deep key and preserves siblings`() {
        val updated = PresetRawKeys.setPath(raw, "extensions.SPreset.MacroNest", JsonPrimitive(true))
        val sp = updated["extensions"]!!.let { it as kotlinx.serialization.json.JsonObject }["SPreset"]!!
        assertEquals(JsonPrimitive(true), (sp as kotlinx.serialization.json.JsonObject)["MacroNest"])
        // 兄弟键逐字保留。
        assertTrue((sp as kotlinx.serialization.json.JsonObject).containsKey("RegexBinding"))
        // 顶层其它键未动。
        assertEquals(JsonPrimitive(1), updated["temperature"])
        assertEquals(JsonPrimitive(-1), updated["names_behavior"])
    }

    @Test
    fun `setPath creates missing intermediate objects`() {
        val updated = PresetRawKeys.setPath(raw, "new.deep.key", JsonPrimitive(5))
        assertEquals(
            JsonPrimitive(5),
            updated["new"]!!.let { it as kotlinx.serialization.json.JsonObject }["deep"]!!
                .let { it as kotlinx.serialization.json.JsonObject }["key"],
        )
    }

    @Test
    fun `removePath drops the key and prunes empty parents`() {
        val updated = PresetRawKeys.removePath(raw, "extensions.SPreset.RegexBinding.regexes")
        // RegexBinding 变空壳 → 被清掉；MacroNest 还在。
        val sp = updated["extensions"]!!.let { it as kotlinx.serialization.json.JsonObject }["SPreset"]!!
        val spMap = sp as kotlinx.serialization.json.JsonObject
        assertTrue("RegexBinding" !in spMap.keys)
        assertTrue("MacroNest" in spMap.keys)
        // 顶层键删除。
        val updated2 = PresetRawKeys.removePath(updated, "n")
        assertTrue("n" !in updated2.keys)
        assertNull(updated2["n"])
    }

    @Test
    fun `user input parses by shape`() {
        assertEquals(JsonPrimitive(true), PresetRawKeys.parseUserValue("true"))
        assertEquals(JsonPrimitive(false), PresetRawKeys.parseUserValue("false"))
        assertEquals(JsonPrimitive(-1L), PresetRawKeys.parseUserValue("-1"))
        assertEquals(JsonPrimitive(1.5), PresetRawKeys.parseUserValue("1.5"))
        assertEquals(JsonPrimitive("hello"), PresetRawKeys.parseUserValue("hello"))
        assertEquals(JsonPrimitive(""), PresetRawKeys.parseUserValue(""))
        // JSON 串按 JSON 解析（对象键保留）。
        val obj = PresetRawKeys.parseUserValue("""{"a":1}""")
        assertTrue(obj is kotlinx.serialization.json.JsonObject)
        // 坏 JSON 回落字符串，不抛。
        assertEquals(JsonPrimitive("{oops"), PresetRawKeys.parseUserValue("{oops"))
    }

    @Test
    fun `real sillytavern preset keeps all 41 top-level keys addressable`() {
        // 参数页之外的键（用户的卡里 27 个）必须全部可拍平编辑。
        val presetJson = buildJsonObject {
            put("temperature", 1.0)
            put("n", 1)
            put("stream_openai", true)
            put("send_if_empty", "")
            put("continue_prefill", true)
            put("continue_postfix", " ")
            put("new_chat_prompt", "")
            put("names_behavior", -1)
            put("wrap_in_quotes", false)
            put("personality_format", "{{personality}}")
            put("scenario_format", "{{scenario}}")
            put("wi_format", "{0}")
            put("max_context_unlocked", true)
            put("show_thoughts", true)
            put("image_inlining", true)
            put("video_inlining", true)
            put("inline_image_quality", "auto")
            put("bias_preset_selected", "Default (none)")
            put("prompts", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        val preset = GenerationPreset(
            id = "p", name = "p", providerType = "openai", raw = presetJson,
        )
        val paths = PresetRawKeys.rows(preset.raw).map { it.path }.toSet()
        listOf(
            "n", "stream_openai", "send_if_empty", "continue_prefill", "continue_postfix",
            "new_chat_prompt", "names_behavior", "wrap_in_quotes", "personality_format",
            "scenario_format", "wi_format", "max_context_unlocked", "show_thoughts",
            "image_inlining", "video_inlining", "inline_image_quality", "bias_preset_selected",
        ).forEach { key ->
            assertTrue("missing raw key: $key", key in paths)
        }
        // 类型化键与 prompts 不重复出现。
        assertTrue("temperature" !in paths)
        assertTrue("prompts" !in paths)
    }
}
