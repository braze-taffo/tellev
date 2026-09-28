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
import org.junit.Assert.assertTrue
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

    @Test
    fun `unescaped slashes inside a pattern are not treated as the delimiter`() {
        // 梦鲸思客V4 ships `[🦋美化]思客大调查` with an unescaped closing slash:
        //   /^<dream_big_discuss>\s*([\s\S]*?)\s*</dream_big_discuss>/gm
        // SillyTavern's regexFromString splits at the LAST slash, so the pattern
        // keeps `</dream_big_discuss>`. Splitting at the first slash produced the
        // flags "dream_big_discuss>/gm", dropped the rule, and left the raw
        // <dream_big_discuss>/<q>/<a> text visible in the chat.
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/^<dream_big_discuss>\\s*([\\s\\S]*?)\\s*</dream_big_discuss>/gm","replaceString":"<body>panel:$1</body>","placement":[2],"markdownOnly":true}
            ]}}}""",
        )

        val message = "<dream_big_discuss>\n<q content=\"问\">\n<a>答</a>\n</q>\n</dream_big_discuss>"

        assertEquals(
            "<body>panel:<q content=\"问\">\n<a>答</a>\n</q></body>",
            CharacterRegexApplier.applyForDisplay(message, MessageRole.Character, card),
        )

        // The escaped spelling (`<\/dream_big_discuss>`) is the conservative way
        // for a preset to write the same rule: JavaScript treats `\/` as `/`, and
        // both SillyTavern's splitter and this one produce the same pattern.
        val escapedCard = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/^<dream_big_discuss>\\s*([\\s\\S]*?)\\s*<\\/dream_big_discuss>/gm","replaceString":"<body>panel:$1</body>","placement":[2],"markdownOnly":true}
            ]}}}""",
        )

        assertEquals(
            "<body>panel:<q content=\"问\">\n<a>答</a>\n</q></body>",
            CharacterRegexApplier.applyForDisplay(message, MessageRole.Character, escapedCard),
        )
    }

    @Test
    fun `rules that strip trailing wrapper tags still run`() {
        // `[🥷隐藏]隐藏多余格式内容` and `[🥷隐藏]删除额外标签` also carry unescaped
        // closing slashes. When they were dropped, every message kept its tail:
        // `</dream_after_format> </dream_plot>`.
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/(<dream_body>|</dream_body>|<dream_after_format>|</dream_after_format>)(?:\\r?\\n)?/g","replaceString":"","placement":[2],"markdownOnly":true,"promptOnly":true},
              {"findRegex":"/(</think>|</dream_delete>|<dream_done/>|<dream_plot>|</dream_plot>|<paragraph>|</paragraph>|<dream_check_done/>)/g","replaceString":"","placement":[2],"markdownOnly":true,"promptOnly":true},
              {"findRegex":"/<UpdateVariable>[\\s\\S]*?</UpdateVariable>/gi","replaceString":"","placement":[2],"markdownOnly":true}
            ]}}}""",
        )

        val message = "<dream_body>正文</dream_body> </dream_after_format> </dream_plot>" +
            "<UpdateVariable>{\"a\":1}</UpdateVariable>"

        assertEquals(
            "正文  ",
            CharacterRegexApplier.applyForDisplay(message, MessageRole.Character, card),
        )
    }

    @Test
    fun `illegal trailing letters fall back to the whole literal as pattern`() {
        // SillyTavern: when m[3] is not a legal flag set it compiles the entire
        // input as a pattern, so `/a/b` matches the literal text "/a/b" instead of
        // becoming the pattern `a` with the (illegal) flag `b`.
        val card = CharacterImporter().importFromJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"C","extensions":{"regex_scripts":[
              {"findRegex":"/a/b","replaceString":"HIT","placement":[2]}
            ]}}}""",
        )

        assertEquals("xHIT", CharacterRegexApplier.applyNormal("x/a/b", MessageRole.Character, card))
    }

    /** A 思客-style reply: prose plus dream-scene tags and option blocks. */
    private fun sikeStyleMessage(blocks: Int): String = buildString {
        repeat(blocks) { i ->
            append("第").append(i).append("段正文:他说了一句话。她回答了他。剧情继续推进,文字平淡而绵长,像一条静静流淌的河。\n\n")
            append("<dream_scene>\n<date> 第").append(i).append("天 </date>\n<time> 上午 </time>\n<location> 学校 </location>\n</dream_scene>\n\n")
            append("<dream_option id=o").append(i).append(">\n<strong>A.</strong> 选项内容甲\n<strong>B.</strong> 选项内容乙\n</dream_option>\n\n")
        }
    }

    private fun presetWith(scriptJson: String): GenerationPreset = GenerationPreset(
        id = "p", name = "p", providerType = "openai",
        extensions = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"regex_scripts":[$scriptJson]}""",
        ).let { it as kotlinx.serialization.json.JsonObject },
    )

    @Test
    fun `lookahead-guarded rule skips think-less preset history without backtracking`() {
        // Kemini's `Aether思维链转义标签`: on 思客 history without `</think>` the
        // rule can never match, but ICU retried a full-text lookahead at every
        // `<`…`>` pair — quadratic per message, on the compose thread.
        val preset = presetWith(
            """{"scriptName":"Aether思维链转义标签",
                "findRegex":"/<([\\s\\S]*?)>(?=[\\s\\S]*?<\\/think>)/g",
                "replaceString":"$1","placement":[2],"markdownOnly":true}""",
        )
        val message = sikeStyleMessage(80)

        val start = System.nanoTime()
        val result = CharacterRegexApplier.applyForDisplay(
            message, MessageRole.Character, character = null, preset = preset,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(message, result)
        assertTrue("display regex took ${elapsedMs}ms for ${message.length} chars", elapsedMs < 2_000)
    }

    @Test
    fun `lookahead-guarded rule still applies when the think tag is present`() {
        val preset = presetWith(
            """{"scriptName":"Aether思维链转义标签",
                "findRegex":"/<([\\s\\S]*?)>(?=[\\s\\S]*?<\\/think>)/g",
                "replaceString":"$1","placement":[2],"markdownOnly":true}""",
        )

        assertEquals(
            "abc</think>",
            CharacterRegexApplier.applyForDisplay(
                "a<b>c</think>", MessageRole.Character, character = null, preset = preset,
            ),
        )
    }

    @Test
    fun `literal-headed rules are skipped when no branch literal is present`() {
        // `润色2` from Kemini plus 思客's option rule: neither `<refine>` nor
        // `<dream_option` appears in this text, so both must no-op.
        val preset = presetWith(
            """{"scriptName":"润色2","findRegex":"/<refine>([\\s\\S]*?)<\\/refine>/g",
                "replaceString":"润色:$1","placement":[2],"markdownOnly":true},
               {"scriptName":"梦境选项框","findRegex":"/<dream_option\\b[^>]*>\\s*([\\s\\S]*?)\\s*<\\/dream_option>/gi",
                "replaceString":"[$1]","placement":[2],"markdownOnly":true}""",
        )
        val message = "一段普通的正文,没有任何标记。".repeat(200)

        val start = System.nanoTime()
        val result = CharacterRegexApplier.applyForDisplay(
            message, MessageRole.Character, character = null, preset = preset,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(message, result)
        assertTrue("display regex took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `literal-headed rules still apply when their literal is present`() {
        val preset = presetWith(
            """{"scriptName":"润色2","findRegex":"/<refine>([\\s\\S]*?)<\\/refine>/g",
                "replaceString":"润色:$1","placement":[2],"markdownOnly":true}""",
        )

        assertEquals(
            "开头 润色:好的 结尾",
            CharacterRegexApplier.applyForDisplay(
                "开头 <refine>好的</refine> 结尾",
                MessageRole.Character, character = null, preset = preset,
            ),
        )
    }

    @Test
    fun `alternation branches gate only when every branch literal is absent`() {
        // Shape of Kemini's `aether opus正则二`: several literal-headed branches.
        // One branch literal present → the rule must still run and match.
        val preset = presetWith(
            """{"scriptName":"opus","findRegex":"/<Disclaimer>[\\s\\S]*?<\\/Disclaimer>|<正文>|<done>|(.*?<\\/think(ing)?>)/gsi",
                "replaceString":"","placement":[2],"markdownOnly":true}""",
        )

        assertEquals(
            "废话保留下文",
            CharacterRegexApplier.applyForDisplay(
                "<done>废话<正文>保留下文", MessageRole.Character, character = null, preset = preset,
            ),
        )

        val plain = "一段没有任何标记的正文。".repeat(100)
        assertEquals(
            plain,
            CharacterRegexApplier.applyForDisplay(plain, MessageRole.Character, character = null, preset = preset),
        )
    }

    @Test
    fun `alternation inside a required group gates on either branch literal`() {
        // 思客's `思考正则格式化` (Normal phase, runs on every render):
        // `^([\s\S]*\S[\s\S]*)(?:</think>|<dream_plot>…)` burned 1.35s per
        // 11KB message on device ICU when both literals were absent. The gate
        // must treat the inner alternation as OR evidence: skip only when
        // neither literal appears.
        val preset = presetWith(
            """{"scriptName":"思考正则格式化",
                "findRegex":"^(?!<think>)([\\s\\S]*\\S[\\s\\S]*)(?:</think>|(<dream_plot>)(?=\\r?\\n))",
                "replaceString":"<think>\n$1\n</think>\n$2","placement":[2]}""",
        )
        val plain = "他停下了脚步。".repeat(800)

        val start = System.nanoTime()
        val skipped = CharacterRegexApplier.applyForDisplay(
            plain, MessageRole.Character, character = null, preset = preset,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(plain, skipped)
        assertTrue("normal regex took ${elapsedMs}ms for ${plain.length} chars", elapsedMs < 2_000)

        // Either literal present → the rule still applies verbatim.
        val withPlot = "正文段落。\n<dream_plot>\n后续"
        assertEquals(
            "<think>\n正文段落。\n\n</think>\n<dream_plot>\n后续",
            CharacterRegexApplier.applyForDisplay(
                withPlot, MessageRole.Character, character = null, preset = preset,
            ),
        )
    }

    @Test
    fun `gate literals honor the case-insensitive flag`() {
        // With /i the gate must not skip a rule whose literal appears in the
        // other case — `contains` has to ignore case exactly like the engine.
        val preset = presetWith(
            """{"scriptName":"upper","findRegex":"/<summary>([\\s\\S]*?)<\\/summary>/gi",
                "replaceString":"S","placement":[2],"markdownOnly":true}""",
        )

        assertEquals(
            "x S y",
            CharacterRegexApplier.applyForDisplay(
                "x <SUMMARY>细</SUMMARY> y", MessageRole.Character, character = null, preset = preset,
            ),
        )
    }

    @Test
    fun `negative lookbehind literals do not gate the rule`() {
        // Shape of Kemini's `aether摘要一` (fixed-width lookbehind so the JVM
        // engine compiles it too): the lookbehind's literal must NOT precede a
        // match, so it must not become the gate — only `<summary>` may. If the
        // lookbehind literal were wrongly gated on, the second assertion would
        // return its input unchanged instead of applying.
        val preset = presetWith(
            """{"scriptName":"摘要","findRegex":"/(?<!<\\/details>)<summary>([\\s\\S]*?)<\\/summary>/gi",
                "replaceString":"SUMMARY","placement":[2],"markdownOnly":true}""",
        )

        // Lookbehind blocks this match; nothing is replaced.
        assertEquals(
            "<details>x</details><summary>细</summary>",
            CharacterRegexApplier.applyForDisplay(
                "<details>x</details><summary>细</summary>",
                MessageRole.Character, character = null, preset = preset,
            ),
        )

        assertEquals(
            "前文 SUMMARY 后文",
            CharacterRegexApplier.applyForDisplay(
                "前文 <summary>细</summary> 后文", MessageRole.Character, character = null, preset = preset,
            ),
        )

        val plain = "没有摘要的正文。".repeat(100)
        assertEquals(
            plain,
            CharacterRegexApplier.applyForDisplay(plain, MessageRole.Character, character = null, preset = preset),
        )
    }
}
