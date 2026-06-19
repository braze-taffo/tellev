package app.tellev.core.prompt

import app.tellev.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructModeTest {

    private val macroEngine = DefaultMacroEngine()
    private val macroContext = MacroContext(
        characterName = "Alice",
        userName = "Bob",
    )

    @Test
    fun `loadPreset parses Alpaca-style JSON`() {
        val json = """
        {
            "input_sequence": "\n### Instruction:\n",
            "output_sequence": "\n### Response:\n",
            "last_output_sequence": "\n### Response:\n",
            "first_input_sequence": "\n### Instruction:\n",
            "last_input_sequence": "\n### Instruction:\n",
            "system_sequence": "",
            "stop_sequence": "",
            "activation_regex": "",
            "wrap": false,
            "macro": true,
            "names": false,
            "names_force_groups": false,
            "name": "Alpaca"
        }
        """.trimIndent()

        val preset = InstructMode.loadPreset(json)
        assertEquals("Alpaca", preset.name)
        assertEquals("\n### Instruction:\n", preset.inputSequence)
        assertEquals("\n### Response:\n", preset.outputSequence)
        assertEquals("\n### Response:\n", preset.lastOutputSequence)
        assertEquals("\n### Instruction:\n", preset.firstInputSequence)
        assertEquals("\n### Instruction:\n", preset.lastInputSequence)
        assertEquals("", preset.systemSequence)
        assertEquals("", preset.stopSequence)
        assertEquals(false, preset.wrap)
        assertEquals(true, preset.macro)
        assertEquals(false, preset.names)
        assertEquals(false, preset.namesForceGroups)
    }

    @Test
    fun `applyInstruct produces Alpaca-style output`() {
        val preset = InstructPreset(
            name = "Alpaca",
            inputSequence = "\n### Instruction:\n",
            outputSequence = "\n### Response:\n",
            lastOutputSequence = "\n### Response:\n",
            firstInputSequence = "\n### Instruction:\n",
            lastInputSequence = "\n### Instruction:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.System, content = "You are a helpful assistant."),
            PromptMessage(role = MessageRole.User, content = "Hello!"),
            PromptMessage(role = MessageRole.Assistant, content = "Hi there!"),
            PromptMessage(role = MessageRole.User, content = "How are you?"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        // System message has no system_sequence, so just content
        assertTrue("Should contain system content", result.contains("You are a helpful assistant."))
        // User messages should be wrapped with input sequence
        assertTrue("Should contain instruction sequence", result.contains("### Instruction:"))
        // Assistant messages should be wrapped with output sequence
        assertTrue("Should contain response sequence", result.contains("### Response:"))
        // Both user messages should be present
        assertTrue("Should contain first user message", result.contains("Hello!"))
        assertTrue("Should contain last user message", result.contains("How are you?"))
        // Assistant response should be present
        assertTrue("Should contain assistant response", result.contains("Hi there!"))
    }

    @Test
    fun `first user message uses firstInputSequence`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n## Input:\n",
            outputSequence = "\n## Output:\n",
            lastOutputSequence = "\n## Final Output:\n",
            firstInputSequence = "\n## First Input:\n",
            lastInputSequence = "\n## Last Input:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "First message"),
            PromptMessage(role = MessageRole.Assistant, content = "Response"),
            PromptMessage(role = MessageRole.User, content = "Last message"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        // First user message should use firstInputSequence
        assertTrue("Should contain First Input sequence", result.contains("## First Input:"))
        // Last user message should use lastInputSequence
        assertTrue("Should contain Last Input sequence", result.contains("## Last Input:"))
        // Last assistant message should use lastOutputSequence
        assertTrue("Should contain Final Output sequence", result.contains("## Final Output:"))
    }

    @Test
    fun `last assistant message uses lastOutputSequence`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n### In:\n",
            outputSequence = "\n### Out:\n",
            lastOutputSequence = "\n### Final:\n",
            firstInputSequence = "\n### In:\n",
            lastInputSequence = "\n### In:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hi"),
            PromptMessage(role = MessageRole.Assistant, content = "Hello"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        // The assistant message is the last one, so it should use lastOutputSequence
        assertTrue("Should contain Final sequence for last assistant", result.contains("### Final:"))
        assertTrue("Should contain Hello", result.contains("Hello"))
    }

    @Test
    fun `system messages use systemSequence`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n### In:\n",
            outputSequence = "\n### Out:\n",
            lastOutputSequence = "\n### Out:\n",
            firstInputSequence = "\n### In:\n",
            lastInputSequence = "\n### In:\n",
            systemSequence = "### System:\n",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.System, content = "You are helpful."),
            PromptMessage(role = MessageRole.User, content = "Hello"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        assertTrue("Should contain system sequence", result.contains("### System:"))
        assertTrue("Should contain system content", result.contains("You are helpful."))
    }

    @Test
    fun `wrap appends stopSequence when true`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n### In:\n",
            outputSequence = "\n### Out:\n",
            lastOutputSequence = "\n### Out:\n",
            firstInputSequence = "\n### In:\n",
            lastInputSequence = "\n### In:\n",
            systemSequence = "",
            stopSequence = "\n### END\n",
            activationRegex = "",
            wrap = true,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hello"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        assertTrue("Should contain stop sequence when wrap is true", result.contains("### END"))
        assertTrue("Stop sequence should be at the end", result.endsWith("\n### END\n"))
    }

    @Test
    fun `names option prepends speaker name`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n### In:\n",
            outputSequence = "\n### Out:\n",
            lastOutputSequence = "\n### Out:\n",
            firstInputSequence = "\n### In:\n",
            lastInputSequence = "\n### In:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = true,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.User, name = "Bob", content = "Hello"),
            PromptMessage(role = MessageRole.Assistant, name = "Alice", content = "Hi Bob!"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        assertTrue("Should contain user name prefix", result.contains("Bob: "))
        assertTrue("Should contain assistant name prefix", result.contains("Alice: "))
    }

    @Test
    fun `macro expansion in sequences when macro is true`() {
        val preset = InstructPreset(
            name = "Custom",
            inputSequence = "\n### {{user}} Input:\n",
            outputSequence = "\n### {{char}} Response:\n",
            lastOutputSequence = "\n### {{char}} Final:\n",
            firstInputSequence = "\n### {{user}} First:\n",
            lastInputSequence = "\n### {{user}} Last:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = true,
            names = false,
            namesForceGroups = false,
        )

        val messages = listOf(
            PromptMessage(role = MessageRole.User, content = "Hello"),
            PromptMessage(role = MessageRole.Assistant, content = "Hi!"),
        )

        val result = InstructMode.applyInstruct(messages, preset, macroEngine, macroContext)

        assertTrue("Should expand {{user}} in first input sequence", result.contains("### Bob First:"))
        assertTrue("Should expand {{char}} in last output sequence", result.contains("### Alice Final:"))
    }

    @Test
    fun `empty message list returns empty string`() {
        val preset = InstructPreset(
            name = "Empty",
            inputSequence = "\n### In:\n",
            outputSequence = "\n### Out:\n",
            lastOutputSequence = "\n### Out:\n",
            firstInputSequence = "\n### In:\n",
            lastInputSequence = "\n### In:\n",
            systemSequence = "",
            stopSequence = "",
            activationRegex = "",
            wrap = false,
            macro = false,
            names = false,
            namesForceGroups = false,
        )

        val result = InstructMode.applyInstruct(emptyList(), preset, macroEngine, macroContext)
        assertEquals("", result)
    }
}
