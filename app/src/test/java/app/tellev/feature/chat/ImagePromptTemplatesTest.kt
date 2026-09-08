package app.tellev.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ImagePromptTemplatesTest {

    @Test
    fun `English tag parser accepts short tags and normalizes wrappers`() {
        assertEquals("1girl, solo, blue eyes, long_hair, garden",
            ImagePromptTemplates.processEnglishTags("```text\n1girl， solo, blue eyes, long_hair, garden\n```"))
    }

    @Test
    fun `English tag parser rejects Chinese mixed text headings and prose`() {
        listOf(
            "教室，女孩微笑",
            "1girl, 蓝色眼睛, garden",
            "Here are the tags: 1girl, solo",
            "A girl with beautiful blue eyes sits quietly in the sunny garden, flowers",
            "she is sitting, blue eyes",
            "1girl, solo. She sits in the garden.",
            "1girl\nsolo\ngarden",
            "",
        ).forEach { assertNull(it, ImagePromptTemplates.processEnglishTags(it)) }
    }

    @Test
    fun `tag engines retry invalid scene output once with format instructions`() = runBlocking {
        for (engine in ChatImageEngine.entries.filter { it.usesEnglishTags }) {
            val instructions = mutableListOf<String>()
            val result = ImagePromptTemplates.summarize(engine) { instruction ->
                instructions += instruction
                if (instructions.size == 1) "女孩坐在花园里" else "1girl, solo, sitting, garden"
            }
            assertEquals("1girl, solo, sitting, garden", result)
            assertEquals(2, instructions.size)
            assertTrue(instructions.first().contains("English visual tags"))
            assertTrue(instructions.last().contains("Try again"))
        }
    }

    @Test
    fun `tag engines never pass invalid fallback text to image generation`() = runBlocking {
        var attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) {
            attempts++
            "1girl, 女孩微笑"
        })
        assertEquals(2, attempts)
    }

    @Test
    fun `valid tags and provider failures do not trigger extra requests`() = runBlocking {
        var attempts = 0
        assertEquals("1girl, solo", ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) {
            attempts++
            "1girl, solo"
        })
        assertEquals(1, attempts)
        attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.NovelAi) { attempts++; null })
        assertEquals(1, attempts)
    }

    @Test
    fun `ComfyUI retries complete invalid response without stripping Chinese into fragments`() = runBlocking {
        for (invalid in listOf("魔法少女和使魔走在回家的路上.", "女孩撑着红伞，1girl, solo", ".", "123")) {
            var attempts = 0
            val expected = "A magical girl and her small animal familiar walk home along a quiet street."
            val result = ImagePromptTemplates.summarize(ChatImageEngine.ComfyUi) {
                attempts++
                if (attempts == 1) invalid else expected
            }
            assertEquals(expected, result)
            assertEquals(2, attempts)
        }
    }

    @Test
    fun `ComfyUI rejects invalid second output and provider failure`() = runBlocking {
        var attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.ComfyUi) { attempts++; "教室，女孩微笑." })
        assertEquals(2, attempts)
        attempts = 0
        assertNull(ImagePromptTemplates.summarize(ChatImageEngine.ComfyUi) { attempts++; null })
        assertEquals(1, attempts)
    }

    @Test
    fun `ComfyUI preserves descriptive phrases weights and punctuation`() {
        val prompt = "A magical girl walking home with a small winged familiar, (red umbrella:1.2), evening light."
        assertEquals(prompt, ImagePromptTemplates.processComfyScene("```text\n$prompt\n```"))
        assertEquals("1girl, solo, street", ImagePromptTemplates.processComfyScene("1girl, solo, street"))
        assertEquals("red dress, white shoes", ImagePromptTemplates.processComfyScene("red dress,\n white shoes"))
    }

    @Test
    fun `ComfyUI rejects explanations sections and mixed language as a whole`() {
        listOf(".", "123", "1girl, 魔法少女", "Here is your prompt: a girl walking home",
            "Positive prompt: a girl\nNegative prompt: blurry", "Sorry, I cannot describe this scene.",
            "{\"prompt\": \"a girl walking home\"}", "# Scene\nA girl walking home",
        ).forEach { assertNull(it, ImagePromptTemplates.processComfyScene(it)) }
    }

    @Test
    fun `scene history skips only hidden and generated image placeholders and preserves active narrative swipes`() {
        val scene = app.tellev.core.model.ChatMessage("scene", app.tellev.core.model.MessageRole.Character,
            "Alice", "old scene", 1L, swipes = listOf("old scene", "walking home"), swipeIndex = 1)
        val image = scene.copy(id = "image", content = "【图片】", swipes = listOf("【图片】"), swipeIndex = 0,
            metadata = kotlinx.serialization.json.buildJsonObject {
                put("image_prompt", kotlinx.serialization.json.JsonPrimitive("wrong previous image"))
            })
        val edited = image.copy(id = "edited", content = "a new narrative scene", swipes = listOf("a new narrative scene"))
        assertEquals(listOf(scene, edited), ImagePromptTemplates.sceneHistory(listOf(scene, image, scene.copy(isHidden = true), edited)))
    }
}
