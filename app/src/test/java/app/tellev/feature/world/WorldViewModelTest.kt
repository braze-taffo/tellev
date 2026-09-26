package app.tellev.feature.world

import androidx.lifecycle.ViewModelStore
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.writeText

@OptIn(ExperimentalCoroutinesApi::class)
class WorldViewModelTest {
    private val first = WorldBookEntry("1", listOf("first"), content = "original")
    private val second = WorldBookEntry("2", listOf("second"), content = "original")

    private class Fixture(val disk: FileStDataStore) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pauseWrites = false
        var failWrites = false
        var pauseRead = false
        val readStarted = CompletableDeferred<Unit>()
        val readRelease = CompletableDeferred<Unit>()
        val store = object : StDataStore by disk {
            override val worldBookChanges = emptyFlow<String>()
            override suspend fun readWorldBook(id: String): WorldBook {
                if (pauseRead && id == "a") {
                    // Simulate disk work which returns after cancellation.
                    withContext(NonCancellable) { readStarted.complete(Unit); readRelease.await() }
                }
                return disk.readWorldBook(id)
            }
            override suspend fun saveWorldBook(book: WorldBook) {
                if (pauseWrites) { started.complete(Unit); release.await() }
                if (failWrites) throw java.io.IOException("disk failure")
                disk.saveWorldBook(book)
            }
        }
    }

    private fun exercise(block: suspend Fixture.(WorldViewModel) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("world-lifecycle-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        val disk = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val fixture = Fixture(disk)
        try {
            disk.bootstrap()
            disk.saveWorldBook(WorldBook("a", "A", listOf(first, second)))
            disk.saveWorldBook(WorldBook("b", "B", emptyList()))
            withContext(main) {
                val vm = WorldViewModel(fixture.store).also { models.put("world", it) }
                waitUntil { !vm.uiState.value.isLoading && vm.uiState.value.worldBookSummaries.size == 2 }
                vm.selectBook("a")
                waitUntil { vm.uiState.value.selectedBook?.id == "a" }
                fixture.block(vm)
            }
        } finally {
            fixture.release.complete(Unit)
            fixture.readRelease.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `queued edits preserve both changes and refresh filtered rows`() = exercise { vm ->
        pauseWrites = true
        vm.searchEntries("edited")
        vm.saveEntry("a", first.copy(content = "edited first"))
        started.await()
        vm.saveEntry("a", second.copy(content = "edited second"))
        assertTrue(vm.uiState.value.filteredEntries.isEmpty())
        release.complete(Unit)
        waitUntil { !vm.uiState.value.isSaving }
        assertEquals(listOf("edited first", "edited second"), disk.readWorldBook("a").entries.map { it.content })
        assertEquals(2, vm.uiState.value.filteredEntries.size)
        vm.deleteEntry("a", "1")
        waitUntil { !vm.uiState.value.isSaving }
        assertEquals(listOf("2"), vm.uiState.value.filteredEntries.map { it.id })
    }

    @Test fun `failed save keeps editor and does not signal navigation success`() = exercise { vm ->
        vm.openEntry("a", "1")
        waitUntil { vm.uiState.value.selectedEntry != null }
        failWrites = true
        var returned = false
        vm.saveEntry("a", first.copy(content = "draft")) { returned = true }
        waitUntil { !vm.uiState.value.isSaving }
        assertFalse(returned)
        assertEquals("1", vm.uiState.value.selectedEntry?.id)
        assertTrue(vm.uiState.value.error.orEmpty().contains("disk failure"))
        assertEquals("original", disk.readWorldBook("a").entries.first().content)
    }

    @Test fun `success callback waits for durable save`() = exercise { vm ->
        pauseWrites = true
        var returned = false
        vm.saveEntry("a", first.copy(content = "saved")) { returned = true }
        started.await()
        assertFalse(returned)
        release.complete(Unit)
        waitUntil { !vm.uiState.value.isSaving }
        assertTrue(returned)
        assertEquals("saved", disk.readWorldBook("a").entries.first().content)
    }

    @Test fun `late save cannot replace destination or navigate away from it`() = exercise { vm ->
        pauseWrites = true
        var returned = false
        vm.saveEntry("a", first.copy(content = "saved")) { returned = true }
        started.await()
        vm.selectBook("b")
        waitUntil { vm.uiState.value.selectedBook?.id == "b" }
        release.complete(Unit)
        waitUntil { !vm.uiState.value.isSaving }
        assertEquals("b", vm.uiState.value.selectedBook?.id)
        assertTrue(vm.uiState.value.filteredEntries.isEmpty())
        assertFalse(returned)
        assertEquals("saved", disk.readWorldBook("a").entries.first().content)
    }

    @Test fun `superseded read cannot restore an old selection`() = exercise { vm ->
        pauseRead = true
        vm.selectBook("a")
        readStarted.await()
        vm.selectBook("b")
        waitUntil { vm.uiState.value.selectedBook?.id == "b" }
        readRelease.complete(Unit)
        delay(100)
        assertEquals("b", vm.uiState.value.selectedBook?.id)
        assertNull(vm.uiState.value.error)
    }

    @Test fun `listing keeps summaries only until a book is opened`() = exercise { vm ->
        // 世界书页只保留摘要：条目内容（及其 raw 树）要等打开某本时才读，
        // 否则整库常驻内存，正是真机上点世界书卡顿并闪退的来源。
        val summaries = vm.uiState.value.worldBookSummaries.associateBy { it.id }
        assertEquals(setOf("a", "b"), summaries.keys)
        assertEquals("A", summaries.getValue("a").name)
        assertEquals(2, summaries.getValue("a").entryCount)
        assertEquals(0, summaries.getValue("b").entryCount)
    }

    @Test fun `refresh after an external write updates the count without reloading everything`() = exercise { vm ->
        disk.saveWorldBook(
            WorldBook("a", "A", listOf(first, second, WorldBookEntry("3", listOf("third"), content = "added"))),
        )
        vm.loadBookSummaries()
        waitUntil { vm.uiState.value.worldBookSummaries.first { it.id == "a" }.entryCount == 3 }
    }

    @Test fun `reading one book does not decode unrelated malformed books`() = exercise { _ ->
        disk.layout.worlds.resolve("broken.json").writeText("""{"name":{},"entries":{}}""")
        assertEquals("A", disk.readWorldBook("a").name)
        for (id in listOf("../outside", "folder/book", "folder\\book")) {
            assertTrue(runCatching { disk.readWorldBook(id) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }
}
