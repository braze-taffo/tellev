package app.tellev.feature.creation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CreationRepositoryConcurrencyTest {
    @Test fun `readers and independent repositories can share an atomically replaced draft`() = runBlocking {
        val root = Files.createTempDirectory("creation-repository-concurrent-").toFile()
        try {
            val first = CreationRepository(root)
            val second = CreationRepository(root)
            val draft = CreationSession(kind = CreationKind.Character, card = CharacterDraft(name = "初始草稿"))
            first.save(draft)
            val start = CompletableDeferred<Unit>()
            val jobs = listOf(first, second).mapIndexed { writer, repository ->
                launch(Dispatchers.IO) {
                    start.await()
                    repeat(40) { index ->
                        repository.save(draft.copy(card = CharacterDraft(name = "作者 $writer / $index")))
                    }
                }
            } + launch(Dispatchers.IO) {
                start.await()
                repeat(100) {
                    assertEquals(draft.id, second.load(draft.id).id)
                    assertEquals(1, first.list().size)
                }
            }
            start.complete(Unit)
            withTimeout(15_000) { jobs.joinAll() }
            assertEquals(1, first.list().size)
            assertTrue(first.load(draft.id).card.name.endsWith("/ 39"))
        } finally { root.deleteRecursively() }
    }
}
