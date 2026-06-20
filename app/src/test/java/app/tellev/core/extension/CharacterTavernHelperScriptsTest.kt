package app.tellev.core.extension

import app.tellev.core.storage.CharacterImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterTavernHelperScriptsTest {
    @Test
    fun `extracts content scripts from tavern_helper`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "script", "id": "render", "name": "Render", "enabled": true, "content": "window.rendered = true;" }
                ]
            }
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals(1, scripts.size)
        assertEquals("render", scripts[0].id)
        assertEquals("Render", scripts[0].name)
        assertEquals("window.rendered = true;", scripts[0].content)
    }

    @Test
    fun `enabled defaults to false matching JSR`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "script", "id": "no-enabled", "content": "should NOT run;" }
                ]
            }
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertTrue("Script without enabled:true must not be extracted", scripts.isEmpty())
    }

    @Test
    fun `disabled field is not recognized`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "script", "id": "disabled-true", "enabled": true, "disabled": true, "content": "runs anyway;" }
                ]
            }
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals("disabled:true must be ignored, enabled:true wins", 1, scripts.size)
        assertEquals("runs anyway;", scripts[0].content)
    }

    @Test
    fun `supports legacy value wrapper and nested folders`() {
        val card = importCard(
            """
            "TavernHelper_scripts": [
                {
                    "type": "folder",
                    "enabled": true,
                    "scripts": [
                        { "type": "script", "id": "legacy", "enabled": true, "value": { "content": "console.log('legacy');" } }
                    ]
                }
            ]
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals(listOf("legacy"), scripts.map { it.id })
        assertEquals("console.log('legacy');", scripts.single().content)
    }

    @Test
    fun `skips scripts and folders with enabled false`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "script", "id": "off", "enabled": false, "content": "bad();" },
                    {
                        "type": "folder",
                        "enabled": false,
                        "scripts": [
                            { "type": "script", "id": "nested", "enabled": true, "content": "bad();" }
                        ]
                    },
                    { "type": "script", "id": "on", "enabled": true, "content": "good();" }
                ]
            }
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals(listOf("on"), scripts.map { it.id })
    }

    @Test
    fun `only content field is read as script source`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "script", "id": "s1", "enabled": true, "source": "ignored;" },
                    { "type": "script", "id": "s2", "enabled": true, "code": "ignored;" },
                    { "type": "script", "id": "s3", "enabled": true, "content": "executed();" }
                ]
            }
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals(listOf("s3"), scripts.map { it.id })
        assertEquals("executed();", scripts.single().content)
    }

    @Test
    fun `does not execute non-script types`() {
        val card = importCard(
            """
            "tavern_helper": {
                "scripts": [
                    { "type": "state", "name": "vars", "content": "{ \"score\": 1 }" },
                    { "type": "javascript", "id": "wrong-type", "enabled": true, "content": "ignored();" },
                    { "type": "script", "id": "runner", "enabled": true, "content": "run();" }
                ]
            }
            """.trimIndent(),
        )

        val source = CharacterTavernHelperScripts.buildScriptSource(card)

        assertTrue(source.contains("run();"))
        assertFalse(source.contains("score"))
        assertFalse(source.contains("ignored"))
    }

    @Test
    fun `reads legacy TavernHelper_scripts field`() {
        val card = importCard(
            """
            "TavernHelper_scripts": [
                { "type": "script", "id": "legacy-field", "enabled": true, "content": "legacy();" }
            ]
            """.trimIndent(),
        )

        val scripts = CharacterTavernHelperScripts.extract(card)

        assertEquals(1, scripts.size)
        assertEquals("legacy-field", scripts[0].id)
        assertEquals("legacy();", scripts[0].content)
    }

    private fun importCard(extensionBody: String) =
        CharacterImporter().importFromJson(
            """
            {
                "spec": "chara_card_v3",
                "spec_version": "3.0",
                "data": {
                    "name": "Script Card",
                    "description": "",
                    "extensions": {
                        $extensionBody
                    }
                }
            }
            """.trimIndent(),
        )
}
