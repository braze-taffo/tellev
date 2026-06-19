package app.tellev.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CharacterImporterTest {

    private val importer = CharacterImporter()

    @Test
    fun `importFromJson parses V1 character card`() {
        val v1Json = """
        {
            "name": "OldFormat Char",
            "description": "A character in the old V1 format",
            "personality": "Brave and bold",
            "scenario": "A quest begins",
            "first_mes": "Welcome, adventurer!",
            "mes_example": "Example dialogue here"
        }
        """.trimIndent()

        val card = importer.importFromJson(v1Json)

        assertEquals("OldFormat Char", card.name)
        assertEquals("A character in the old V1 format", card.description)
        assertEquals("Brave and bold", card.personality)
        assertEquals("A quest begins", card.scenario)
        assertEquals("Welcome, adventurer!", card.firstMessage)
        assertEquals("Example dialogue here", card.exampleMessages)
    }

    @Test
    fun `importFromJson parses V2 character card`() {
        val v2Json = """
        {
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": {
                "name": "V2 Character",
                "description": "A V2 spec character",
                "personality": "Wise and patient",
                "scenario": "In a mountain monastery",
                "first_mes": "Peace be with you, traveler.",
                "mes_example": "",
                "creator_notes": "Created for testing purposes",
                "character_version": "1.0",
                "tags": ["fantasy", "monk"],
                "system_prompt": "",
                "post_history_instructions": "",
                "alternate_greetings": [],
                "extensions": {}
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(v2Json)

        assertEquals("V2 Character", card.name)
        assertEquals("A V2 spec character", card.description)
        assertEquals("Wise and patient", card.personality)
        assertEquals("In a mountain monastery", card.scenario)
        assertEquals("Peace be with you, traveler.", card.firstMessage)
        assertEquals("Created for testing purposes", card.creatorNotes)
        assertEquals(listOf("fantasy", "monk"), card.tags)
    }

    @Test
    fun `importFromJson parses V2 with character book`() {
        val v2WithBook = """
        {
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": {
                "name": "Lorekeeper",
                "description": "Has a character book",
                "personality": "",
                "scenario": "",
                "first_mes": "Hello",
                "mes_example": "",
                "tags": [],
                "character_book": {
                    "entries": {
                        "0": {
                            "uid": 0,
                            "key": ["magic"],
                            "keysecondary": ["spell"],
                            "content": "Magic is the manipulation of arcane energy.",
                            "constant": false,
                            "selective": true,
                            "order": 100,
                            "disable": false,
                            "depth": 3
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(v2WithBook)

        assertEquals("Lorekeeper", card.name)
        assertNotNull(card.characterBook)
        assertEquals(1, card.characterBook!!.entries.size)

        val entry = card.characterBook!!.entries[0]
        assertEquals(listOf("magic"), entry.keys)
        assertEquals(listOf("spell"), entry.secondaryKeys)
        assertEquals("Magic is the manipulation of arcane energy.", entry.content)
        assertEquals(true, entry.selective)
        assertEquals(false, entry.constant)
        assertEquals(3, entry.depth)
    }

    @Test
    fun `importFromJson parses V2 character book entries array`() {
        val v2WithArrayBook = """
        {
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": {
                "name": "Array Lorekeeper",
                "description": "Uses the official character book entries array",
                "character_book": {
                    "name": "Array Book",
                    "entries": [
                        {
                            "id": 7,
                            "keys": ["gate"],
                            "secondary_keys": ["silver"],
                            "content": "The silver gate opens at dawn.",
                            "enabled": true,
                            "insertion_order": 42,
                            "extensions": { "depth": 2 }
                        }
                    ]
                }
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(v2WithArrayBook)

        assertEquals("Array Lorekeeper", card.name)
        assertNotNull(card.characterBook)
        assertEquals("Array Book", card.characterBook!!.name)
        val entry = card.characterBook!!.entries.single()
        assertEquals("7", entry.id)
        assertEquals(listOf("gate"), entry.keys)
        assertEquals(listOf("silver"), entry.secondaryKeys)
        assertEquals(42, entry.insertionOrder)
        assertEquals(2, entry.depth)
    }

    @Test
    fun `importFromJson handles V2 card wrapped in data key without spec field`() {
        val wrappedJson = """
        {
            "data": {
                "name": "Wrapped Char",
                "description": "Wrapped in data key but no spec",
                "personality": "",
                "scenario": "",
                "first_mes": "Hi there",
                "mes_example": ""
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(wrappedJson)
        assertEquals("Wrapped Char", card.name)
        assertEquals("Wrapped in data key but no spec", card.description)
    }

    @Test
    fun `importFromJson parses Gradio Pygmalion character card`() {
        val gradioJson = """
        {
            "char_name": "Gradio Char",
            "char_persona": "Persona text",
            "world_scenario": "World text",
            "char_greeting": "Greeting text",
            "example_dialogue": "Example text",
            "creatorcomment": "Creator comment"
        }
        """.trimIndent()

        val card = importer.importFromJson(gradioJson)

        assertEquals("Gradio Char", card.name)
        assertEquals("Persona text", card.description)
        assertEquals("World text", card.scenario)
        assertEquals("Greeting text", card.firstMessage)
        assertEquals("Example text", card.exampleMessages)
        assertEquals("Creator comment", card.creatorNotes)
    }

    @Test
    fun `importFromBytes detects JSON format from extension`() {
        val jsonString = """{"name":"Byte Char","description":"Imported from bytes"}"""
        val card = importer.importFromBytes(jsonString.toByteArray(Charsets.UTF_8), "test.json")
        assertEquals("Byte Char", card.name)
    }

    @Test
    fun `importFromBytes detects PNG format`() {
        val testJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"PNG Char","description":"From a PNG"}}"""
        val png = PngCardParser.createMinimalPng()
        val embedded = PngCardParser.embedCardJson(png, testJson)

        val card = importer.importFromBytes(embedded, "character.png")
        assertEquals("PNG Char", card.name)
    }

    @Test
    fun `exportToJson preserves embedded extensions and character book raw data`() {
        val cardJson = """
        {
            "spec": "chara_card_v3",
            "spec_version": "3.0",
            "data": {
                "name": "Helper Card",
                "description": "Uses helper assets",
                "first_mes": "[start]",
                "extensions": {
                    "regex_scripts": [
                        {
                            "id": "r1",
                            "scriptName": "Render HTML",
                            "findRegex": "/\\[start\\]/g",
                            "replaceString": "<body>ok</body>",
                            "placement": [2],
                            "disabled": false
                        }
                    ],
                    "TavernHelper_scripts": [{ "type": "script", "value": "console.log(1)" }],
                    "tavern_helper": [{ "type": "state", "name": "vars" }]
                },
                "character_book": {
                    "name": "Embedded Book",
                    "entries": [
                        {
                            "keys": ["gate"],
                            "content": "The gate is hidden.",
                            "enabled": true
                        }
                    ]
                }
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(cardJson)
        val exported = CharacterExporter().exportToJson(card)
        val exportedObj = FileStDataStore.defaultJson.parseToJsonElement(exported).jsonObject
        val data = exportedObj["data"]!!.jsonObject
        val extensions = data["extensions"]!!.jsonObject

        assertEquals("chara_card_v3", exportedObj["spec"]!!.jsonPrimitive.content)
        assertEquals(1, extensions["regex_scripts"]!!.jsonArray.size)
        assertEquals(1, extensions["TavernHelper_scripts"]!!.jsonArray.size)
        assertEquals(1, extensions["tavern_helper"]!!.jsonArray.size)
        assertEquals("Embedded Book", data["character_book"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `importFromJson generates sanitized ID from character name`() {
        val json = """{"name":"My Cool Character! (v2)","description":"test"}"""
        val card = importer.importFromJson(json)
        assertEquals("my_cool_character_v2", card.id)
    }

    @Test
    fun `importFromJson handles missing optional fields gracefully`() {
        val minimalJson = """{"name":"Minimal"}"""
        val card = importer.importFromJson(minimalJson)

        assertEquals("Minimal", card.name)
        assertEquals("", card.description)
        assertEquals("", card.personality)
        assertEquals("", card.scenario)
        assertEquals("", card.firstMessage)
        assertEquals("", card.exampleMessages)
    }

    @Test
    fun `importFromJson parses V3 character card`() {
        val v3Json = """
        {
            "spec": "chara_card_v3",
            "spec_version": "3.0",
            "data": {
                "name": "V3 Character",
                "description": "A V3 spec character",
                "personality": "Adventurous",
                "scenario": "Deep space",
                "first_mes": "Greetings from the stars!",
                "mes_example": "",
                "creator_notes": "V3 spec test",
                "tags": ["scifi", "space"]
            }
        }
        """.trimIndent()

        val card = importer.importFromJson(v3Json)
        assertEquals("V3 Character", card.name)
        assertEquals("A V3 spec character", card.description)
        assertEquals("V3 spec test", card.creatorNotes)
        assertEquals(listOf("scifi", "space"), card.tags)
    }

    @Test
    fun `importFromBytes detects CHARX zip format`() {
        val cardJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"CHARX Char","description":"From CHARX"}}"""

        // Build a minimal ZIP in memory
        val zipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("card.json"))
                zos.write(cardJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()

        val card = importer.importFromBytes(zipBytes, "character.charx")
        assertEquals("CHARX Char", card.name)
    }

    @Test
    fun `importFromBytes detects BYAF zip format`() {
        val manifest = """
        {
            "characters": [
                {
                    "name": "BYAF Char",
                    "description": "A character from BYAF format with {character} and {user} macros",
                    "personality": "",
                    "scenario": "",
                    "first_mes": "#{character}: Hello #{user}: welcome"
                }
            ]
        }
        """.trimIndent()

        val zipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()

        val card = importer.importFromBytes(zipBytes, "character.byaf")
        assertEquals("BYAF Char", card.name)
        // Verify macro replacements
        assertTrue(card.description.contains("{{char}}"))
        assertTrue(card.description.contains("{{user}}"))
        assertTrue(card.firstMessage.contains("{{char}}:"))
        assertTrue(card.firstMessage.contains("{{user}}:"))
    }

    @Test
    fun `importFromBytes detects BYAF manifest paths`() {
        val manifest = """
        {
            "author": { "name": "Tester", "backyardURL": "https://example.test/author" },
            "characters": ["characters/hero.json"],
            "scenarios": ["scenarios/default.json"]
        }
        """.trimIndent()
        val character = """
        {
            "name": "Path BYAF Char",
            "persona": "Speaks with {user} and {character}.",
            "images": []
        }
        """.trimIndent()
        val scenario = """
        {
            "narrative": "A room for #{character}: and #{user}:",
            "firstMessages": [
                { "text": "Hello {user}" },
                { "text": "Second greeting" }
            ],
            "exampleMessages": [
                { "type": "human", "text": "Hi" },
                { "type": "ai", "text": "Welcome" }
            ]
        }
        """.trimIndent()

        val zipBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zos.write(manifest.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("characters/hero.json"))
                zos.write(character.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("scenarios/default.json"))
                zos.write(scenario.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()

        val card = importer.importFromBytes(zipBytes, "character.byaf")

        assertEquals("Path BYAF Char", card.name)
        assertTrue(card.description.contains("{{user}}"))
        assertTrue(card.description.contains("{{char}}"))
        assertTrue(card.scenario.contains("{{char}}:"))
        assertTrue(card.firstMessage.contains("{{user}}"))
        assertTrue(card.exampleMessages.contains("{{user}}: Hi"))
        assertTrue(card.exampleMessages.contains("{{char}}: Welcome"))
    }
}
