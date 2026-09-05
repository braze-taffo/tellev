package app.tellev.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyWorkflowTemplateTest {

    private val workflow = """
        {
          "3": {"class_type": "KSampler", "inputs": {"seed": "%seed%", "steps": "%steps%", "cfg": "%scale%", "denoise": "%denoise%", "clip_skip": "%clip_skip%"}},
          "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "%prompt%"}},
          "5": {"class_type": "CLIPTextEncode", "inputs": {"text": "%negative_prompt%"}},
          "6": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "%model%"}},
          "7": {"class_type": "KSampler", "inputs": {"sampler_name": "%sampler%", "scheduler": "%scheduler%"}},
          "8": {"class_type": "EmptyLatentImage", "inputs": {"width": "%width%", "height": "%height%"}},
          "9": {"class_type": "Note", "inputs": {"text": "leave me %alone%"}}
        }
    """.trimIndent()

    @Test
    fun `parse accepts json objects only`() {
        assertNotNull(ComfyWorkflowTemplate.parse(workflow))
        assertNull(ComfyWorkflowTemplate.parse("""[1, 2]"""))
        assertNull(ComfyWorkflowTemplate.parse("""{"unterminated": """))
        assertNull(ComfyWorkflowTemplate.parse("  "))
    }

    @Test
    fun `render replaces prompt and negative with proper json escaping`() {
        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = workflow,
            prompt = "1girl, \"quoted\", back\\slash",
            negativePrompt = "lowres, bad hands",
            settings = ComfyUiSettings(),
            model = null,
            seed = 42,
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "1girl, \"quoted\", back\\slash",
            obj["4"]!!.jsonObject["inputs"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "lowres, bad hands",
            obj["5"]!!.jsonObject["inputs"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `render substitutes numeric parameters as json numbers`() {
        val settings = ComfyUiSettings(
            steps = 28,
            cfgScale = 4.5,
            width = 832,
            height = 1216,
            denoise = 1.0,
            clipSkip = 2,
        )
        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = workflow,
            prompt = "p",
            negativePrompt = "n",
            settings = settings,
            model = null,
            seed = 1234567890123L,
        )
        val sampler = Json.parseToJsonElement(rendered).jsonObject["3"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(1234567890123L, sampler["seed"]!!.jsonPrimitive.long)
        assertEquals(28, sampler["steps"]!!.jsonPrimitive.int)
        assertEquals(4.5, sampler["cfg"]!!.jsonPrimitive.double, 0.0)
        // SillyTavern convention: clip stop layer is the negated clip skip.
        assertEquals(-2, sampler["clip_skip"]!!.jsonPrimitive.int)
        val latent = Json.parseToJsonElement(rendered).jsonObject["8"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(832, latent["width"]!!.jsonPrimitive.int)
        assertEquals(1216, latent["height"]!!.jsonPrimitive.int)
    }

    @Test
    fun `render leaves blank optional placeholders untouched`() {
        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = workflow,
            prompt = "p",
            negativePrompt = "n",
            settings = ComfyUiSettings(sampler = "", scheduler = ""),
            model = null,
            seed = 1,
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "%model%",
            obj["6"]!!.jsonObject["inputs"]!!.jsonObject["ckpt_name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "%sampler%",
            obj["7"]!!.jsonObject["inputs"]!!.jsonObject["sampler_name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "%scheduler%",
            obj["7"]!!.jsonObject["inputs"]!!.jsonObject["scheduler"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `render replaces provided optional placeholders`() {
        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = workflow,
            prompt = "p",
            negativePrompt = "n",
            settings = ComfyUiSettings(sampler = "euler", scheduler = "karras"),
            model = "sd_xl_refiner.safetensors",
            seed = 1,
        )
        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "sd_xl_refiner.safetensors",
            obj["6"]!!.jsonObject["inputs"]!!.jsonObject["ckpt_name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "euler",
            obj["7"]!!.jsonObject["inputs"]!!.jsonObject["sampler_name"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "karras",
            obj["7"]!!.jsonObject["inputs"]!!.jsonObject["scheduler"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `render keeps unknown placeholders`() {
        val rendered = ComfyWorkflowTemplate.render(
            workflowJson = workflow,
            prompt = "p",
            negativePrompt = "n",
            settings = ComfyUiSettings(),
            model = null,
            seed = 1,
        )
        assertTrue(rendered.contains("%alone%"))
    }
}
