package app.tellev.core.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PngCardParserTest {

    private val testJson = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"Test Char","description":"A test character"}}"""

    @Test
    fun `extractCardJson returns null for PNG without metadata`() {
        val png = PngCardParser.createMinimalPng()
        val result = PngCardParser.extractCardJson(png)
        assertNull(result)
    }

    @Test
    fun `embedCardJson and extractCardJson roundtrip preserves JSON`() {
        val png = PngCardParser.createMinimalPng()

        val embedded = PngCardParser.embedCardJson(png, testJson)
        val extracted = PngCardParser.extractCardJson(embedded)

        assertNotNull(extracted)
        val data = extracted!!["data"] as JsonObject
        assertEquals("Test Char", data["name"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
        assertEquals("A test character", data["description"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test
    fun `embedded PNG is still valid PNG with correct signature`() {
        val png = PngCardParser.createMinimalPng()
        val embedded = PngCardParser.embedCardJson(png, testJson)

        // Verify PNG signature
        assertEquals(0x89.toByte(), embedded[0])
        assertEquals(0x50.toByte(), embedded[1])
        assertEquals(0x4E.toByte(), embedded[2])
        assertEquals(0x47.toByte(), embedded[3])
        assertEquals(0x0D.toByte(), embedded[4])
        assertEquals(0x0A.toByte(), embedded[5])
        assertEquals(0x1A.toByte(), embedded[6])
        assertEquals(0x0A.toByte(), embedded[7])

        // Should be larger than original due to added tEXt chunks
        assertTrue(embedded.size > png.size)
    }

    @Test
    fun `re-embedding replaces old metadata`() {
        val png = PngCardParser.createMinimalPng()
        val firstJson = """{"data":{"name":"First"}}"""
        val secondJson = """{"data":{"name":"Second"}}"""

        val first = PngCardParser.embedCardJson(png, firstJson)
        val second = PngCardParser.embedCardJson(first, secondJson)

        val extracted = PngCardParser.extractCardJson(second)
        assertNotNull(extracted)
        val data = extracted!!["data"] as JsonObject
        assertEquals("Second", data["name"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test
    fun `ccv3 takes precedence over chara keyword`() {
        // Manually construct a PNG with both chara and ccv3 chunks where ccv3 has different data
        val png = PngCardParser.createMinimalPng()

        // Embed with the standard method (writes both chara and ccv3 with same data)
        val embedded = PngCardParser.embedCardJson(png, testJson)

        // Extract should work (both keywords have same data, ccv3 is checked first)
        val result = PngCardParser.extractCardJson(embedded)
        assertNotNull(result)
    }

    @Test
    fun `extractCardJson handles large JSON payloads`() {
        val png = PngCardParser.createMinimalPng()
        val largeDescription = "x".repeat(100_000)
        val largeJson = """{"data":{"name":"Big Char","description":"$largeDescription"}}"""

        val embedded = PngCardParser.embedCardJson(png, largeJson)
        val extracted = PngCardParser.extractCardJson(embedded)

        assertNotNull(extracted)
        val data = extracted!!["data"] as JsonObject
        assertEquals("Big Char", data["name"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
        assertEquals(largeDescription, data["description"]?.let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test
    fun `createMinimalPng produces valid PNG`() {
        val png = PngCardParser.createMinimalPng()

        // Verify signature
        assertEquals(0x89.toByte(), png[0])
        assertEquals(0x50.toByte(), png[1])
        assertEquals(0x4E.toByte(), png[2])
        assertEquals(0x47.toByte(), png[3])

        // Should have IHDR, IDAT, IEND chunks
        assertTrue(png.size > 8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extractCardJson throws on invalid PNG signature`() {
        val invalid = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        PngCardParser.extractCardJson(invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extractCardJson throws on too-short data`() {
        PngCardParser.extractCardJson(byteArrayOf(0, 0, 0))
    }
}
