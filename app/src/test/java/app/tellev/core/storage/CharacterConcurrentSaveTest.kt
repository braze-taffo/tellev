package app.tellev.core.storage

import app.tellev.core.model.CharacterCard
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class CharacterConcurrentSaveTest {
    @Test fun `PNG readers never see truncated card metadata during saves`() = runBlocking {
        val root = Files.createTempDirectory("tellev-png-save-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a54kAAAAASUVORK5CYII=")
            val card = CharacterCard("fixture", "Stable card", raw = buildJsonObject {
                put("extensions", buildJsonObject { put("padding", "x".repeat(64_000)) })
            })
            store.importCharacter(card, png, "fixture.png")
            val start = CompletableDeferred<Unit>()
            val writer = launch(Dispatchers.IO) {
                start.await()
                repeat(30) { store.saveCharacter(card.copy(description = "revision $it")) }
            }
            val readers = List(3) {
                async(Dispatchers.IO) {
                    start.await()
                    var reads = 0
                    do {
                        val loaded = store.readCharacter(card.id)
                        assertEquals("A reader saw an incomplete PNG and a fallback card", card.name, loaded.name)
                        assertNotNull(loaded.raw["extensions"])
                        reads++
                    } while (writer.isActive || reads < 30)
                    reads
                }
            }
            start.complete(Unit)
            withTimeout(20_000) { writer.join(); readers.awaitAll() }
            assertEquals("revision 29", store.readCharacter(card.id).description)
            Unit
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
