package app.tellev.core.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SafeStoragePathsTest {
    @Test
    fun colonContainingNamesRemainUsable() = withUnixPaths { root ->
        val worlds = Files.createDirectories(root.resolve("worlds"))
        for (name in listOf("Lore: Main", "Lore:Main:Act II", "世界书: 主线", ":Leading")) {
            val path = safeStorageChild(worlds, name, ".json")
            assertEquals(worlds.resolve("$name.json"), path)
            path.writeText("original")
            assertEquals("original", path.readText())
            safeStorageChild(worlds, name, ".json").writeText("updated")
            assertEquals("updated", path.readText())
            Files.delete(safeStorageChild(worlds, name, ".json"))
            assertFalse(Files.exists(path))
        }
    }

    @Test
    fun listedLegacyColonWorldBookCanBeReadAndDeleted() = withUnixPaths { root ->
        runBlocking {
            val layout = StDirectoryLayout.fromRoot(root)
            Files.createDirectories(layout.worlds)
            val name = "Lore: Main"
            val path = layout.worlds.resolve("$name.json")
            val raw = Json.parseToJsonElement("""{"name":"Lore: Main","entries":{},"custom-field":"preserved"}""").jsonObject
            // Seed a file made before path validation was introduced, without using the new writer.
            path.writeText(raw.toString())
            val disk = FileStDataStore(layout)
            val listed = disk.listWorldBooks().single()
            assertEquals(name, listed.id)
            assertEquals(raw, disk.readWorldBook(listed.id).raw)
            disk.deleteWorldBook(listed.id)
            assertFalse(Files.exists(path))
            assertTrue(disk.listWorldBooks().isEmpty())
        }
    }

    @Test
    fun driveQualifiedAndTraversalNamesStayRejectedOnUnixPaths() = withUnixPaths { root ->
        val worlds = Files.createDirectories(root.resolve("worlds"))
        val sentinel = root.resolve("sentinel.json")
        sentinel.writeText("original")
        val names = listOf(
            "C:escape", "c:escape", "Z:", "z:", "C:/escape", "c:\\escape",
            "../sentinel", "..\\sentinel", "/escape", "folder/name", "folder\\name",
            ".", "..", "", " ", "Lore\u0000:Main",
        )
        for (name in names) {
            val error = runCatching { safeStorageChild(worlds, name, ".json") }.exceptionOrNull()
            assertTrue("Accepted invalid name '$name': $error", error is IllegalArgumentException)
        }
        assertEquals("original", sentinel.readText())
    }

    private fun withUnixPaths(block: (Path) -> Unit) {
        // ZIP paths allow Android-valid colons even when unit tests run on Windows.
        val archive = Files.createTempFile("safe-storage-paths-", ".zip")
        Files.delete(archive)
        try {
            FileSystems.newFileSystem(URI.create("jar:${archive.toUri()}"), mapOf("create" to "true")).use { fs ->
                block(Files.createDirectories(fs.getPath("/data")))
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }
}
