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
