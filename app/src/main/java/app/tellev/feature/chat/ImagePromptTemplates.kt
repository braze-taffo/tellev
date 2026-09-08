package app.tellev.feature.chat

import app.tellev.core.model.ChatMessage
import app.tellev.core.model.reasoningParts
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Scene instructions are sent as quiet control prompts, never as a new roleplay turn. */
object ImagePromptTemplates {
    /** Compact visual tags for tag-based image engines. No forced two-person composition. */
    const val ENGLISH_TAGS = """Summarize the current visible scene in the conversation as an image prompt. Do not continue the story or dialogue.
Output ONLY one comma-separated line of English visual tags, using common Danbooru-style tags where appropriate.
Use short tags such as 1girl, solo, long hair, blue eyes, white dress, sitting, garden, sunset. Do not copy this example unless it matches the scene.
Use at most 20 tags and about 60 English words in total. Put the visible subject count, appearance, clothing and main action first, followed by setting, composition and lighting. Each tag should contain at most 6 words.
Include only characters actually visible in the latest scene. Do not assume {{user}} or {{char}} is visible; use solo for a single visible person and do not invent a second person or force POV.
Use appearance tags instead of character names. Ignore feelings, thoughts, dialogue and non-visual story details.
No Chinese, full sentences, explanations, headings, Markdown, quotes, bullet lists, positive/negative sections or extra lines. Return only the English tags."""

    private const val TAG_RETRY = "The previous response did not meet the image prompt format. Try again from the same scene. Return ONLY short English tags separated by commas, without sentences, headings or explanation."

    /** Validate before normalization so Chinese and prose cannot be silently stripped into partial tags. */
    fun processEnglishTags(input: String): String? {
        var value = input.trim()
        if (value.startsWith("```") && value.endsWith("```")) {
            value = value.substringAfter('\n').removeSuffix("```").trim()
        }
        value = value.trim('"', '“', '”').replace('，', ',')
        if (value.any { it.code > 127 }) return null
        if (!Regex("[a-zA-Z0-9 ,_():{}\\[\\]/+'\\-\\r\\n]+").matches(value)) return null
        val tags = value.split(',').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        if (tags.size !in 2..30) return null
        if (tags.any { tag ->
                !tag.any { it in 'a'..'z' || it in 'A'..'Z' } ||
                    tag.split(Regex("[\\s_]+")).size > 6 ||
                    Regex("\\b(is|are|was|were|she|he|they|there|here)\\b", RegexOption.IGNORE_CASE).containsMatchIn(tag) ||
                    ':' in tag
            }) return null
        return tags.joinToString(", ")
    }

    /** ComfyUI can run both tag-based and natural-language models; do not impose SD1.5 tag limits. */
    const val COMFY_SCENE = """Describe the latest visible scene in the conversation as an image prompt in English. Translate visual details from any other language into English. Do not continue the story or dialogue.
Use the latest narrative message for the current action and location; use earlier context, character descriptions and world information only to resolve appearance and other details that still apply.
Output ONLY one concise English paragraph or comma-separated list of visual phrases, at most 120 words. Describe the visible subjects, their appearance and clothing, main action, spatial relationships, surroundings and lighting.
Include only subjects actually present. Preserve non-human companions as their actual creature type. Do not assume {{user}} or {{char}} is visible, invent extra people, force a minimum subject count or force POV.
Describe visible appearance instead of unexplained character names. Omit thoughts, feelings, dialogue, instructions and non-visual story details.
No Chinese, explanation, headings, Markdown, JSON, positive/negative sections or alternative prompts. Return only the English image prompt."""

    private const val SCENE_RETRY = "The response was not a usable English image prompt. Try again using the same scene. Translate ALL visual details into English, preserving the subjects, action and location. Return only the prompt, without explanation or headings."

    /** Validate the complete response before whitespace normalization; never delete non-English content. */
    fun processComfyScene(input: String): String? {
        var value = input.trim()
        if (value.startsWith("```") && value.endsWith("```")) {
            value = value.substringAfter('\n').removeSuffix("```").trim()
        }
        value = value.trim('"', '“', '”')
            .replace('“', '"').replace('”', '"').replace('’', '\'')
            .replace('–', '-').replace('—', '-').replace('，', ',')
        if (value.any { it.code > 127 }) return null
        if (Regex("[a-zA-Z]{2,}").findAll(value).count() < 2) return null
        if (value.length > 2000 || value.split(Regex("\\s+")).size > 200) return null
        // Explanations/sections are format failures, not text to strip into an apparently valid prompt.
        if (Regex("(?im)^\\s*(?:#{1,6}\\s|[-*]\\s|(?:positive|negative)(?: prompt)?\\s*:|(?:prompt|tags|description)\\s*:)").containsMatchIn(value)) return null
        if (Regex("(?i)^(?:here (?:is|are)\\b|(?:sure|sorry)\\b|(?:i|we) (?:cannot|can't|can not|am unable|are unable)\\b|as an ai\\b)").containsMatchIn(value)) return null
        if (value.startsWith("{") || value.startsWith("[") || '`' in value) return null
        return value.replace(Regex("\\s+"), " ").trim()
    }

    /** All engines retry invalid output once; no untranslated or partially stripped fallback. */
    suspend fun summarize(
        engine: ChatImageEngine,
        onRejected: (response: String, reason: String) -> Unit = { _, _ -> },
        generate: suspend (instruction: String) -> String?,
    ): String? {
        val template = if (engine.usesEnglishTags) ENGLISH_TAGS else COMFY_SCENE
        val validate = if (engine.usesEnglishTags) ::processEnglishTags else ::processComfyScene
        val first = generate(template) ?: return null
        validate(first)?.let { return it }
        onRejected(first, rejectionReason(engine, first))
        val retry = if (engine.usesEnglishTags) TAG_RETRY else SCENE_RETRY
        val retried = generate("$template\n\n$retry") ?: return null
        return validate(retried).also { if (it == null) onRejected(retried, rejectionReason(engine, retried)) }
    }

    private fun rejectionReason(engine: ChatImageEngine, input: String): String = when {
        input.isBlank() -> "回复为空"
        !Regex("[a-zA-Z]{2,}").containsMatchIn(input) -> "回复没有英文画面描述"
        input.any { it.code > 127 } -> "回复含非 ASCII 字符，当前格式校验未通过"
        input.length > 2000 || input.split(Regex("\\s+")).size > 200 -> "回复超过当前长度限制"
        engine.usesEnglishTags -> "回复不是所要求的短英文标签"
        else -> "回复带有标题、说明或结构化格式，当前格式校验未通过"
    }

    /** Generated-image placeholders must not become the latest narrative scene on repeated generation. */
    fun sceneHistory(messages: List<ChatMessage>): List<ChatMessage> = messages.filterNot { message ->
        message.isHidden || (
            message.metadata["image_prompt"]?.jsonPrimitive?.contentOrNull != null &&
                message.reasoningParts().body.trim().let { it.isEmpty() || it == "【图片】" }
            )
    }
}
