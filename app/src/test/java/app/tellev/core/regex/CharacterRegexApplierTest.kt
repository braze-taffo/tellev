package app.tellev.core.regex

import app.tellev.core.model.MessageRole
import app.tellev.core.storage.CharacterImporter
import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterRegexApplierTest {
    @Test
    fun `applyForDisplay runs enabled character regex for AI output`() {
        val card = CharacterImporter().importFromJson(
            """
            {
                "spec": "chara_card_v3",
                "spec_version": "3.0",
                "data": {
                    "name": "Regex Card",
                    "description": "",
                    "extensions": {
                        "regex_scripts": [
                            {
                                "id": "r1",
                                "scriptName": "Render",
                                "findRegex": "/\\[start\\]/g",
                                "replaceString": "<body>ok</body>",
                                "placement": [2],
                                "disabled": false
                            }
                        ]
                    }
                }
            }
            """.trimIndent(),
        )

        val result = CharacterRegexApplier.applyForDisplay("[start]", MessageRole.Character, card)

        assertEquals("<body>ok</body>", result)
    }
}
