package app.tellev.core.regex

import app.tellev.core.model.MessageRole
import app.tellev.core.storage.CharacterImporter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
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

    @Test
    fun `applyForDisplay skips scripts whose id is in disabledScriptIds`() {
        val card = CharacterImporter().importFromJson(
            """
            {
                "spec": "chara_card_v3",
                "spec_version": "3.0",
                "data": {
                    "name": "Regex Card",
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

        // No disabled ids → script applies.
        assertEquals(
            "<body>ok</body>",
            CharacterRegexApplier.applyForDisplay("[start]", MessageRole.Character, card),
        )

        // The card's own disabled flag is authoritative.
        val disabledCard = CharacterRegexApplier.withScriptEnabled(card, "r1", enabled = false)
        assertEquals(
            "[start]",
            CharacterRegexApplier.applyForDisplay("[start]", MessageRole.Character, disabledCard),
        )
    }

    @Test
    fun `summarizeScripts exposes id and name with findRegex fallback`() {
        val card = CharacterImporter().importFromJson(
            """
            {
                "spec": "chara_card_v3",
                "spec_version": "3.0",
                "data": {
                    "name": "Regex Card",
                    "extensions": {
                        "regex_scripts": [
                            { "id": "r1", "scriptName": "Render", "findRegex": "/a/g", "placement": [2] },
                            { "findRegex": "/b/g", "placement": [2] }
                        ]
                    }
                }
            }
            """.trimIndent(),
        )
        val array = card.raw
            .getValue("data").jsonObject
            .getValue("extensions").jsonObject
            .getValue("regex_scripts") as JsonArray

        val summaries = CharacterRegexApplier.summarizeScripts(array)

        assertEquals(2, summaries.size)
        assertEquals("r1", summaries[0].id)
        assertEquals("Render", summaries[0].name)
        // No id → index-based key; no scriptName → fall back to findRegex.
        assertEquals("idx:1", summaries[1].id)
        assertEquals("/b/g", summaries[1].name)
    }
    @Test
    fun `display and prompt modes honor flags depth captures and character macros`() {
        val card = CharacterImporter().importFromJson(
            """
            {
              "spec":"chara_card_v3",
              "spec_version":"3.0",
              "data":{
                "name":"Alice",
                "extensions":{"regex_scripts":[
                  {
                    "id":"display",
                    "findRegex":"/(STATE)/g",
                    "replaceString":"<body>{{char}}/{{user}}-$1</body>",
                    "placement":[2],
                    "markdownOnly":true
                  },
                  {
                    "id":"prompt",
                    "findRegex":"/<secret>[\\s\\S]*?<\\/secret>/g",
                    "replaceString":"",
                    "placement":[2],
                    "promptOnly":true,
                    "minDepth":2,
                    "maxDepth":3
                  }
                ]}
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "<body>Alice/道友-STATE</body>",
            CharacterRegexApplier.applyForDisplay(
                "STATE",
                MessageRole.Character,
                card,
                userName = "道友",
            ),
        )
        assertEquals(
            "<secret>hidden</secret>",
            CharacterRegexApplier.applyForPrompt(
                "<secret>hidden</secret>",
                MessageRole.Character,
                card,
                depth = 1,
            ),
        )
        assertEquals(
            "",
            CharacterRegexApplier.applyForPrompt(
                "<secret>hidden</secret>",
                MessageRole.Character,
                card,
                depth = 2,
            ),
        )
    }

    @Test
    fun `javascript global flag controls first versus all replacements`() {
        fun card(findRegex: String) = CharacterImporter().importFromJson(
            """
            {
              "spec":"chara_card_v3",
              "spec_version":"3.0",
              "data":{
                "name":"Regex",
                "extensions":{"regex_scripts":[{
                  "findRegex":"$findRegex",
                  "replaceString":"[$1]",
                  "placement":[2]
                }]}
              }
            }
            """.trimIndent(),
        )

        assertEquals("[x]x", CharacterRegexApplier.applyForDisplay("xx", MessageRole.Character, card("/(x)/")))
        assertEquals("[x][x]", CharacterRegexApplier.applyForDisplay("xx", MessageRole.Character, card("/(x)/g")))
    }


}
