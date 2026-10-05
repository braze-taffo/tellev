package app.tellev.core.storage

import app.tellev.core.model.CharacterSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CharacterBatchImporterTest {

    private lateinit var tempDir: Path
    private lateinit var layout: StDirectoryLayout
    private lateinit var store: FileStDataStore
    private val batchImporter = CharacterBatchImporter()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tellev-batch-test-")
        layout = StDirectoryLayout.fromRoot(tempDir)
        store = FileStDataStore(layout)
        runBlocking { store.bootstrap() }
    }

    @After
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun v2CardJson(name: String, description: String = "batch test card"): String = """
        {
            "spec": "chara_card_v2",
            "spec_version": "2.0",
            "data": {
                "name": "$name",
                "description": "$description",
                "first_mes": "Hello from $name.",
                "mes_example": "",
                "tags": ["batch"]
            }
        }
    """.trimIndent()

    private fun pngCardBytes(name: String): ByteArray {
        val json = v2CardJson(name)
        return PngCardParser.embedCardJson(PngCardParser.createMinimalPng(), json)
    }

    @Test
    fun `importAll imports mixed json and png cards`() = runBlocking {
        val report = batchImporter.importAll(
            listOf(
                BatchImportSource("sera.json", v2CardJson("Sera").toByteArray()),
                BatchImportSource("milo.png", pngCardBytes("Milo")),
            ),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(2, report.successCount)
        assertEquals(0, report.failedCount)
        val listed = store.listCharacters().map(CharacterSummary::id).toSet()
        assertEquals(setOf("sera", "milo"), listed)
        // PNG source keeps the embedded image as the avatar file.
        assertTrue(layout.characters.resolve("milo.png").toFile().exists())
        assertTrue(layout.characters.resolve("sera.json").toFile().exists())
    }

    @Test
    fun `same named cards in one batch get distinct ids`() = runBlocking {
        val report = batchImporter.importAll(
            listOf(
                BatchImportSource("a.json", v2CardJson("Alice").toByteArray()),
                BatchImportSource("b.json", v2CardJson("Alice", "second copy").toByteArray()),
            ),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(2, report.successCount)
        val ids = store.listCharacters().map(CharacterSummary::id)
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.contains("alice"))
        assertTrue(ids.any { it.startsWith("alice_") })
    }

    @Test
    fun `existing characters are not overwritten by batch import`() = runBlocking {
        val existingJson = v2CardJson("Alice", "original stored copy")
        store.importCharacter(
            CharacterImporter().importFromJson(existingJson),
            existingJson.toByteArray(),
            "alice.json",
        )
        val before = store.readCharacter("alice")
        assertEquals("original stored copy", before?.description)

        val report = batchImporter.importAll(
            listOf(BatchImportSource("a.json", v2CardJson("Alice", "incoming copy").toByteArray())),
            existingIds = setOf("alice"),
            store = store,
        )

        assertEquals(1, report.successCount)
        val after = store.readCharacter("alice")
        assertEquals("original stored copy", after?.description)
        assertTrue(store.listCharacters().any { it.id.startsWith("alice_") })
    }

    @Test
    fun `malformed file is isolated and reported`() = runBlocking {
        val report = batchImporter.importAll(
            listOf(
                BatchImportSource("broken.json", "not a card at all".toByteArray()),
                BatchImportSource("sera.json", v2CardJson("Sera").toByteArray()),
            ),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(1, report.successCount)
        assertEquals(1, report.failedCount)
        val failed = report.results.first { !it.isSuccess }
        assertEquals("broken.json", failed.fileName)
        assertNotNull(failed.error)
        assertNull(failed.cardId)
        assertEquals("sera", report.results.first { it.isSuccess }.cardId)
    }

    @Test
    fun `unreadable source is reported with its read error`() = runBlocking {
        val report = batchImporter.importAll(
            listOf(BatchImportSource("gone.png", null, readError = "permission denied")),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(0, report.successCount)
        assertEquals(1, report.failedCount)
        assertEquals("gone.png", report.results.single().fileName)
        assertEquals("permission denied", report.results.single().error)
    }

    @Test
    fun `placeholder id is minted for cards whose name sanitizes away`() = runBlocking {
        // A name of pure punctuation sanitizes to the importer's
        // "imported_character" placeholder; the batch importer mints a
        // real id for it instead of persisting the placeholder.
        val punctuated = """
            {
                "spec": "chara_card_v2",
                "spec_version": "2.0",
                "data": { "name": "!!!", "description": "no usable name" }
            }
        """.trimIndent()

        val report = batchImporter.importAll(
            listOf(BatchImportSource("nameless.json", punctuated.toByteArray())),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(1, report.successCount)
        val id = report.results.single().cardId
        assertNotNull(id)
        assertTrue("expected minted id, got $id", id!!.startsWith("char_"))
    }

    @Test
    fun `report counts add up for mixed outcomes`() = runBlocking {
        val report = batchImporter.importAll(
            listOf(
                BatchImportSource("ok.json", v2CardJson("Ok").toByteArray()),
                BatchImportSource("bad.json", "{".toByteArray()),
                BatchImportSource("unreadable.png", null, readError = "gone"),
            ),
            existingIds = emptySet(),
            store = store,
        )

        assertEquals(3, report.results.size)
        assertEquals(1, report.successCount)
        assertEquals(2, report.failedCount)
    }
}
