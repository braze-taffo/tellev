package app.tellev.core.regex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneRegexImportTest {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    @Test
    fun `parses one SillyTavern script and replaces its id`() {
        val text = """
            {
              "id": "keep-me",
              "scriptName": "Render",
              "name": "ignored alias",
              "findRegex": "/\\[start\\]/g",
              "replaceString": "<body>ok</body>",
              "trimStrings": [" "],
              "placement": [2],
              "disabled": false,
              "markdownOnly": true,
              "promptOnly": false,
              "runOnEdit": true,
              "substituteRegex": 0,
              "minDepth": null,
              "maxDepth": 3,
              "vendorNote": "kept"
            }
        """.trimIndent()

        val first = StandaloneRegexImport.parse(text).single()
        val second = StandaloneRegexImport.parse(text).single()

        assertTrue(uuid.matches(first.string("id")))
        assertNotEquals("keep-me", first.string("id"))
        assertNotEquals(first.string("id"), second.string("id"))
        assertEquals("Render", first.string("scriptName"))
        assertEquals("ignored alias", first.string("name"))
        assertEquals("/\\[start\\]/g", first.string("findRegex"))
        assertEquals("<body>ok</body>", first.string("replaceString"))
        assertEquals(listOf(" "), first.stringArray("trimStrings"))
        assertEquals(listOf(2), first.intArray("placement"))
        assertEquals(false, first["disabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, first["markdownOnly"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, first["promptOnly"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(true, first["runOnEdit"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(0, first["substituteRegex"]!!.jsonPrimitive.intOrNull)
        assertTrue(first["minDepth"] is JsonNull)
        assertEquals(3, first["maxDepth"]!!.jsonPrimitive.intOrNull)
        assertEquals("kept", first.string("vendorNote"))
    }

    @Test
    fun `parses a SillyTavern bulk export array in order`() {
        val text = """
            [
              {
                "id": "a",
                "scriptName": "First",
                "findRegex": "/a/g",
                "replaceString": "A",
                "placement": [1]
              },
              {
                "id": "b",
                "scriptName": "Second",
                "findRegex": "/b/g",
                "replaceString": "B",
                "placement": [2, 5]
              }
            ]
        """.trimIndent()

        val scripts = StandaloneRegexImport.parse(text)

        assertEquals(listOf("First", "Second"), scripts.map { it.string("scriptName") })
        assertEquals(listOf("/a/g", "/b/g"), scripts.map { it.string("findRegex") })
        assertEquals(listOf("A", "B"), scripts.map { it.string("replaceString") })
        assertEquals(listOf(1), scripts[0].intArray("placement"))
        assertEquals(listOf(2, 5), scripts[1].intArray("placement"))
        assertTrue(scripts.all { uuid.matches(it.string("id")) })
        assertEquals(2, scripts.map { it.string("id") }.toSet().size)
        assertTrue(scripts.none { it.string("id") == "a" || it.string("id") == "b" })
    }

    @Test
    fun `normalizes an RP Hub script from the qunxing fixture`() {
        val card = Json.parseToJsonElement(resource("qunxing-regex.json")).jsonObject
        val source = card["data"]!!.jsonObject["extensions"]!!.jsonObject["regex_scripts"]!!
            .jsonArray.first().jsonObject

        val parsed = StandaloneRegexImport.parse(source.toString()).single()

        val rawPattern = source["regex"]!!.jsonPrimitive.content
        val flags = source["flags"]!!.jsonPrimitive.content
        assertEquals("科幻终端阅读面板", parsed.string("scriptName"))
        assertEquals("科幻终端阅读面板", parsed.string("name"))
        assertEquals("/$rawPattern/$flags", parsed.string("findRegex"))
        assertEquals(rawPattern, parsed.string("regex"))
        assertEquals(flags, parsed.string("flags"))
        assertEquals(source["replacement"]!!.jsonPrimitive.content, parsed.string("replaceString"))
        assertEquals(source["replacement"]!!.jsonPrimitive.content, parsed.string("replacement"))
        assertEquals(listOf(2), parsed.intArray("placement"))
        assertEquals(true, parsed["markdownOnly"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, parsed["promptOnly"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, parsed["disabled"]!!.jsonPrimitive.booleanOrNull)
        assertTrue(parsed["minDepth"] is JsonNull)
        assertTrue(parsed["maxDepth"] is JsonNull)
        assertTrue(uuid.matches(parsed.string("id")))
    }

    @Test
    fun `rejects character card wrappers`() {
        val qunxing = assertThrows(IllegalArgumentException::class.java) {
            StandaloneRegexImport.parse(resource("qunxing-regex.json"))
        }
        assertTrue(qunxing.message!!.contains("角色卡"))

        val specCard = """
            {
              "spec": "chara_card_v3",
              "scriptName": "Should not import",
              "findRegex": "/a/g",
              "data": { "extensions": { "regex_scripts": [] } }
            }
        """.trimIndent()
        val spec = assertThrows(IllegalArgumentException::class.java) {
            StandaloneRegexImport.parse(specCard)
        }
        assertTrue(spec.message!!.contains("角色卡"))
    }

    @Test
    fun `rejects a scripts wrapper instead of unwrapping it`() {
        val wrapped = """
            {"scripts":[{"scriptName":"Inner","findRegex":"/a/g","replaceString":"b"}]}
        """.trimIndent()
        val error = assertThrows(IllegalArgumentException::class.java) {
            StandaloneRegexImport.parse(wrapped)
        }
        assertTrue(error.message!!.contains("名称"))
    }

    @Test
    fun `rejects a script with a blank name or a blank pattern`() {
        val missingName = assertThrows(IllegalArgumentException::class.java) {
            StandaloneRegexImport.parse("""{"scriptName":"  ","findRegex":"/a/g"}""")
        }
        assertTrue(missingName.message!!.contains("名称"))

        val missingPattern = assertThrows(IllegalArgumentException::class.java) {
            StandaloneRegexImport.parse("""{"scriptName":"Render","findRegex":"  ","regex":""}""")
        }
        assertTrue(missingPattern.message!!.contains("查找"))
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { name }
            .bufferedReader().use { it.readText() }

    private fun JsonObject.string(key: String): String =
        this[key]!!.jsonPrimitive.content

    private fun JsonObject.stringArray(key: String): List<String> =
        (this[key] as JsonArray).map { it.jsonPrimitive.content }

    private fun JsonObject.intArray(key: String): List<Int> =
        (this[key] as JsonArray).map { it.jsonPrimitive.intOrNull ?: error(it) }
}
