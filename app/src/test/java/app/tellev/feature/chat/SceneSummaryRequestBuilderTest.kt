package app.tellev.feature.chat

import app.tellev.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SceneSummaryRequestBuilderTest {
    @Test fun `extracts current story without custom thinking scripts or roleplay rules`() {
        val text = "<thinking>开始思考创作准则。下一幕回家做饭。".repeat(1) + "规则".repeat(60000) +
            "</thinking><scene>傍晚的商店街</scene><content>玲纱正和黑猫使魔咪咪走在回家的路上。</content>" +
            "<script>continueTheStory()</script>"
        val message = ChatMessage("m", MessageRole.Character, "玲纱", "old scene", 1,
            swipes = listOf("old scene", text), swipeIndex = 1)
        val request = SceneSummaryRequestBuilder.build(CharacterCard("card", "魔法少女"),
            Persona("user", "玲纱", "银发，蓝色连衣裙"), listOf(message), "deepseek", ImagePromptTemplates.COMFY_SCENE)
        val system = request.prompt.messages.first()
        val reference = request.prompt.messages.last().content
        assertEquals(MessageRole.System, system.role)
        assertEquals(MessageRole.User, request.prompt.messages.last().role)
        assertTrue(reference.contains("玲纱正和黑猫使魔咪咪走在回家的路上"))
        assertTrue(reference.contains("银发，蓝色连衣裙"))
        assertFalse(reference.contains("开始思考创作准则"))
        assertFalse(reference.contains("下一幕回家做饭"))
        assertFalse(reference.contains("continueTheStory"))
        assertFalse(reference.contains("old scene"))
        assertTrue(request.prompt.diagnostics.estimatedTokenCount!! < 3000)
        assertTrue(request.preset.prompts.isEmpty())
        assertTrue(request.preset.raw.isEmpty())
    }

    @Test fun `bounds reference material and excludes hidden messages and image placeholders`() {
        val base = ChatMessage("s", MessageRole.Character, "Alice", "a street with a black cat", 1)
        val image = base.copy(id = "img", content = "【图片】", metadata = buildJsonObject { put("image_prompt", "unrelated image") })
        val req = SceneSummaryRequestBuilder.build(CharacterCard("c", "Alice", description = "世界规则".repeat(60000)), null,
            listOf(base.copy(content = "history ".repeat(60000)), base, image, base.copy(isHidden = true, content = "hidden scene")),
            "openai-compatible", ImagePromptTemplates.COMFY_SCENE)
        val data = req.prompt.messages.last().content
        assertTrue(data.length < 6000)
        assertTrue(data.contains("a street with a black cat"))
        assertFalse(data.contains("hidden scene"))
        assertFalse(data.contains("unrelated image"))
    }
}
