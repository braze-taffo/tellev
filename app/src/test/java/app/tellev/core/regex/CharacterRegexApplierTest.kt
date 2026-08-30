package app.tellev.core.regex

import app.tellev.core.model.MessageRole
import app.tellev.core.model.GenerationPreset
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
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

    @Test
    fun `normal display and prompt phases merge card before preset`() {
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/x/g","replaceString":"card","placement":[2]},
              {"findRegex":"/SHOW/g","replaceString":"<details>shown</details>","placement":[2],"markdownOnly":true}
            ]}}}""",
        )
        val preset = GenerationPreset(
            id = "p", name = "p", providerType = "openai",
            extensions = buildJsonObject { putJsonArray("regex_scripts") {
                add(buildJsonObject {
                    put("findRegex", "/card/g"); put("replaceString", "preset")
                    put("placement", buildJsonArray { add(JsonPrimitive(2)) })
                })
                add(buildJsonObject {
                    put("findRegex", "/SECRET/g"); put("replaceString", "")
                    put("placement", buildJsonArray { add(JsonPrimitive(2)) }); put("promptOnly", true)
                })
            } },
        )

        assertEquals("preset", CharacterRegexApplier.applyNormal("x", MessageRole.Character, card, preset))
        assertEquals(
            "<details>shown</details>",
            CharacterRegexApplier.applyForDisplay("SHOW", MessageRole.Character, card, preset = preset, includeNormal = false),
        )
        assertEquals(
            "",
            CharacterRegexApplier.applyForPrompt("SECRET", MessageRole.Character, card, depth = 0, preset = preset, includeNormal = false),
        )
    }

    @Test
    fun `edit skips normal scripts unless runOnEdit is true`() {
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/a/g","replaceString":"b","placement":[2]},
              {"findRegex":"/b/g","replaceString":"c","placement":[2],"runOnEdit":true}
            ]}}}""",
        )
        assertEquals("a", CharacterRegexApplier.applyNormal("a", MessageRole.Character, card, isEdit = true))
        assertEquals("c", CharacterRegexApplier.applyNormal("b", MessageRole.Character, card, isEdit = true))
    }

    @Test
    fun `world info prompt path runs only promptOnly scripts like SillyTavern`() {
        // Regression guard locking in the ST 1.18 semantics found during the
        // adversarial review: world-info entries are fetched with isPrompt=true
        // (world-info.js:5086), and the regex engine only runs normal scripts
        // when neither isMarkdown nor isPrompt is set (regex engine.js:348-354).
        // So on WI text, only promptOnly scripts may run; unflagged (normal)
        // scripts must NOT touch world-info content.
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/ALPHA/g","replaceString":"beta","placement":[5],"promptOnly":true},
              {"findRegex":"/GAMMA/g","replaceString":"delta","placement":[5]}
            ]}}}""",
        )

        assertEquals("beta and GAMMA", CharacterRegexApplier.applyWorldInfoForPrompt("ALPHA and GAMMA", card))
    }


}
