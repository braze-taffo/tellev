package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the ST-Prompt-Template prepareContext constants stubbed into
 * the template environment (ejs.ts:211 parity); template.js gets the same
 * scenarios in tools/template-eval/test.mjs for the WebView path.
 */
class TemplateConstantsCompatTest {

    private fun processor() = DefaultPromptTemplateProcessor()

    private fun render(
        content: String,
        context: MacroContext = MacroContext(
            characterName = "玄泽",
            userName = "旅人",
            lastMessage = "hi",
            lastUserMessage = "hi",
            lastCharMessage = "greet",
            lastMessageId = "3",
            lastUserMessageId = 2,
            lastCharMessageId = 3,
            characterId = "char-1",
            modelName = "test-model",
        ),
    ) = processor().process(
        PromptTemplateRequest(
            messages = listOf(PromptMessage(role = MessageRole.System, content = content)),
            context = context,
            metadata = buildJsonObject { },
            currentWorldBookId = "char-book",
        ),
    ).messages.single().content

    @Test
    fun `name constants resolve`() {
        assertEquals("旅人 玄泽 玄泽", render("<%= userName %> <%= charName %> <%= assistantName %>"))
    }

    @Test
    fun `last message constants resolve`() {
        assertEquals("hi greet hi", render("<%= lastUserMessage %> <%= lastCharMessage %> <%= lastMessage %>"))
    }

    @Test
    fun `message id constants are numeric`() {
        // ST types these as numbers; arithmetic must not concatenate strings.
        assertEquals("4", render("<%= lastMessageId + 1 %>"))
        assertEquals("2 3", render("<%= lastUserMessageId %> <%= lastCharMessageId %>"))
    }

    @Test
    fun `identity and phase constants resolve`() {
        assertEquals("char-1 test-model generate normal", render(
            "<%= characterId %> <%= model %> <%= runType %> <%= generateType %>"))
    }

    @Test
    fun `charLoreBook maps to the embedded world book id`() {
        assertEquals("char-book", render("<%= charLoreBook %>"))
        assertEquals("", render("<%= userLoreBook ?? '' %>"))
        assertEquals("", render("<%= chatLoreBook ?? '' %>"))
    }

    @Test
    fun `SillyTavern stub resolves without unsupported warning`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "<%= SillyTavern.getContext() %>")),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("", result.messages.single().content)
        assertTrue(result.warnings.none { it.contains("SillyTavern") })
    }

    @Test
    fun `unsupported references still degrade with warning instead of crash`() {
        val result = processor().process(
            PromptTemplateRequest(
                messages = listOf(PromptMessage(role = MessageRole.System, content = "A<%= neverDefined %>B")),
                context = MacroContext(),
                metadata = buildJsonObject { },
            ),
        )

        assertEquals("AB", result.messages.single().content)
        assertTrue(result.warnings.isNotEmpty())
    }
}
