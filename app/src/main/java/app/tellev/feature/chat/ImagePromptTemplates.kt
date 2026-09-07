package app.tellev.feature.chat

import java.text.Normalizer

/**
 * Scene-to-image prompt material ported from SillyTavern's stable-diffusion
 * extension. The NOW template instructs the current chat model to summarize
 * the last chat message into a comma-delimited list of visual keywords
 * (SillyTavern public/scripts/extensions/stable-diffusion/index.js,
 * generationMode.NOW, kept verbatim including its indentation).
 */
object ImagePromptTemplates {
    /** Compact visual tags for the local SD1.5 models and NovelAI. No forced two-person composition. */
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

    /** Retry format failures once; never fall back to untranslated text for a tag engine. */
    suspend fun summarize(
        engine: ChatImageEngine,
        generate: suspend (instruction: String) -> String?,
    ): String? {
        val template = if (engine.usesEnglishTags) ENGLISH_TAGS else NOW
        val first = generate(template) ?: return null
        if (!engine.usesEnglishTags) return processReply(first).ifBlank { processReplyLoose(first) }
        processEnglishTags(first)?.let { return it }
        val retried = generate("$template\n\n$TAG_RETRY") ?: return null
        return processEnglishTags(retried)
    }

    const val NOW = """Ignore previous instructions. Your next response must be formatted as a single comma-delimited list of concise keywords.  The list will describe of the visual details included in the last chat message.

    Only mention characters by using pronouns ('he','his','she','her','it','its') or neutral nouns ('male', 'the man', 'female', 'the woman').

    Ignore non-visible things such as feelings, personality traits, thoughts, and spoken dialog.

    Add keywords in this precise order:
    a keyword to describe the location of the scene,
    a keyword to mention how many characters of each gender or type are present in the scene (minimum of two characters:
    {{user}} and {{char}}, example: '2 men ' or '1 man 1 woman ', '1 man 3 robots'),

    keywords to describe the relative physical positioning of the characters to each other (if a commonly known term for the positioning is known use it instead of describing the positioning in detail) + 'POV',

    a single keyword or phrase to describe the primary act taking place in the last chat message,

    keywords to describe {{char}}'s physical appearance and facial expression,
    keywords to describe {{char}}'s actions,
    keywords to describe {{user}}'s physical appearance and actions.

    If character actions involve direct physical interaction with another character, mention specifically which body parts interacting and how.

    A correctly formatted example response would be:
    '(location),(character list by gender),(primary action), (relative character position) POV, (character 1's description and actions), (character 2's description and actions)'"""

    /**
     * Port of SillyTavern's processReply (index.js): strip quotes, turn
     * newlines into commas, drop non-ASCII noise and normalize into a clean
     * comma-separated tag list. Non-ASCII scripts (e.g. a Chinese reply) do
     * not survive the filter — callers should fall back to [processReplyLoose]
     * when this returns something blank.
     */
    fun processReply(input: String): String {
        var str = input.replace("\"", "").replace("“", "")
        str = str.replace("\n", ", ")
        str = Normalizer.normalize(str, Normalizer.Form.NFD)
        str = str.replace(Regex("[^a-zA-Z0-9.,:_(){}<>\\[\\]/\\-'|#]+"), " ")
        str = str.replace(Regex("\\s+"), " ")
        return str.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }

    /** Quote/newline/comma normalization without the ASCII filter, for replies in other scripts. */
    fun processReplyLoose(input: String): String {
        var str = input.replace("\"", "").replace("“", "")
        str = str.replace("\n", ", ")
        str = str.replace(Regex("\\s+"), " ")
        return str.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
    }
}
