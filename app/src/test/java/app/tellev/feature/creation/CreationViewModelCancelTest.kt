package app.tellev.feature.creation

import androidx.lifecycle.ViewModelStore
import app.tellev.core.model.TellevError
import app.tellev.core.model.WorldBook
import app.tellev.core.model.WorldBookEntry
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.storage.codec.WorldBookCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * 已完成的工具调用在下一个模型请求前落盘；取消后可以从草稿继续，
 * 避免长任务中已经生成的世界书条目全部丢失。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreationViewModelCancelTest {

    @Test
    fun characterDraftExportsItsEmbeddedWorldBook() = runBlocking {
        val root = Files.createTempDirectory("creation-character-worldbook-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val vm = CreationViewModel(
                CreationRepository(root.toFile()), store, noSecrets(),
                ProviderRegistry(listOf(Fixture().provider)),
            ).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.Character)
                vm.editWorldName("唐玫")
                vm.editLore(0, LoreDraft("核心人物档案", listOf("唐玫"), "唐玫的设定。"))

                val exported = Json.parseToJsonElement(vm.exportWorldBook().decodeToString()).jsonObject
                assertEquals("唐玫", exported["name"]!!.jsonPrimitive.content)
                val entry = WorldBookCodec.parseWorldBookEntries(exported).single()
                assertEquals("核心人物档案", entry.comment)
                assertEquals("唐玫的设定。", entry.content)
            }
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(
        private val failSecondRound: Boolean = false,
        private val askFirstRound: Boolean = false,
    ) {
        val secondRoundStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<GenerateRequest>()

        /** Round 1 applies a lore write; round 2 blocks until released/cancelled. */
        val provider = object : ProviderAdapter {
            private var calls = 0
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> {
                requests += request
                return if (++calls == 1 && askFirstRound) {
                    flowOf(GenerateChunk.Completed(
                        "<tool_call>{\"name\":\"ask_user\",\"arguments\":{\"question\":\"选择哪种基调？\"," +
                            "\"options\":[{\"label\":\"轻松\"},{\"label\":\"悬疑\"}]}}</tool_call>",
                    ))
                } else if (calls == 1) {
                    flowOf(
                        GenerateChunk.Completed(
                            "<tool_call>{\"name\":\"upsert_lore\",\"arguments\":{\"entries\":" +
                                "[{\"title\":\"新城\",\"keys\":[\"新城\"],\"content\":\"新城内容。\"}]}}</tool_call>",
                        ),
                    )
                } else if (failSecondRound && calls == 2) {
                    flowOf(GenerateChunk.Failed(TellevError(
                        code = "provider_network", message = "socket closed", retryable = true,
                    )))
                } else {
                    flow {
                        secondRoundStarted.complete(Unit)
                        release.await()
                        emit(GenerateChunk.Completed("完成。"))
                    }
                }
            }
        }
    }

    private fun noSecrets(): SecretStore = object : SecretStore {
        override suspend fun putSecret(id: String, value: String) = Unit
        override suspend fun readSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String) = Unit
        override suspend fun listSecretIds(): List<String> = emptyList()
    }

    @Test
    fun worldBookDraftBecomesCardAndEmbedsOnlyAfterExplicitMerge() = runBlocking {
        val root = Files.createTempDirectory("creation-world-card-workflow-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val repository = CreationRepository(root.resolve("drafts").toFile())
        val models = ViewModelStore()
        try {
            store.bootstrap()
            store.saveWorldBook(WorldBook("saved-book", "已有世界", listOf(
                WorldBookEntry("0", listOf("海岛"), content = "海岛设定。", comment = "海岛"))))
            val vm = CreationViewModel(repository, store, noSecrets(),
                ProviderRegistry(listOf(Fixture().provider))).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.WorldBook)
                vm.editWorldName("潮港")
                vm.editLore(0, LoreDraft("航路", listOf("潮汐"), "涨潮时才能进港。", id = "L1"))
                val worldId = vm.state.value.current!!.id
                vm.startRelatedDraft(CreationKind.Character).join()
                val cardId = vm.state.value.current!!.id
                assertTrue(cardId != worldId)
                assertTrue(vm.state.value.current!!.lore.isEmpty())
                assertEquals("潮港", vm.state.value.current!!.referenceBook!!.name)
                assertTrue(!vm.state.value.current!!.allowAgentLoreEdits)
                assertEquals("潮港", repository.load(worldId).worldName)
                val sources = vm.worldBookSources()
                assertTrue(sources.any { it.fromDraft && it.id == worldId })
                assertTrue(sources.any { !it.fromDraft && it.id == "saved-book" })

                vm.editCard { it.copy(name = "潮港导演", firstMessage = "船到了。") }
                val before = CharacterImporter().importFromBytes(vm.exportCharacter(CharacterExportFormat.Json), "card.json")
                assertEquals(null, before.characterBook)
                vm.embedReferenceWorldBook()
                vm.close()
                vm.open(cardId).join()
                assertEquals(1, vm.state.value.current!!.lore.size)
                vm.setCoverPng(PngCardParser.createMinimalPng())
                waitUntil { vm.state.value.current!!.coverSha256.isNotBlank() && !vm.state.value.busy }
                for (format in CharacterExportFormat.entries) {
                    val result = CharacterImporter().importFromBytes(vm.exportCharacter(format),
                        if (format == CharacterExportFormat.Png) "card.png" else "card.json")
                    assertEquals("涨潮时才能进港。", result.characterBook!!.entries.single().content)
                }
                vm.associateWorldBook(sources.first { !it.fromDraft }).join()
                assertEquals("已有世界", vm.state.value.current!!.referenceBook!!.name)
                assertEquals("涨潮时才能进港。", vm.state.value.current!!.lore.single().content)
                assertEquals("海岛设定。", store.readWorldBook("saved-book").entries.single().content)
            }
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cardWorldBookCardWorkflowKeepsCoverAndPreservesSavedSource() = runBlocking {
        val root = Files.createTempDirectory("creation-card-world-workflow-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val repository = CreationRepository(root.resolve("drafts").toFile())
        val models = ViewModelStore()
        try {
            store.bootstrap()
            val vm = CreationViewModel(repository, store, noSecrets(),
                ProviderRegistry(listOf(Fixture().provider))).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.Character)
                vm.editCard { it.copy(name = "旅人", description = "水手。", firstMessage = "潮水要来了！") }
                val sourceId = vm.state.value.current!!.id
                val cover = PngCardParser.createMinimalPng()
                vm.setCoverPng(cover)
                waitUntil { vm.state.value.current!!.coverSha256.isNotBlank() && !vm.state.value.busy }
                vm.saveArtifact { _, _ -> }
                waitUntil { vm.state.value.current!!.savedArtifactId.isNotBlank() && !vm.state.value.busy }
                val savedSourceId = vm.state.value.current!!.savedArtifactId
                vm.startRelatedDraft(CreationKind.WorldBook).join()
                val worldId = vm.state.value.current!!.id
                assertTrue(worldId != sourceId)
                assertEquals("旅人", vm.state.value.current!!.originalCard!!.name)
                vm.editLore(0, LoreDraft("航路", listOf("潮汐"), "涨潮时才能进港。", id = "L1"))
                vm.startRelatedDraft(CreationKind.Character).join()
                val returned = vm.state.value.current!!
                assertEquals("旅人", returned.card.name)
                assertEquals("水手。", returned.card.description)
                assertEquals("", returned.savedArtifactId)
                assertTrue(returned.lore.isEmpty())
                assertTrue(cover.contentEquals(repository.readCover(returned.id, returned.coverSha256)))
                vm.embedReferenceWorldBook()
                val exported = CharacterImporter().importFromBytes(vm.exportCharacter(CharacterExportFormat.Png), "card.png")
                assertEquals(1, exported.characterBook!!.entries.size)
                assertEquals("潮水要来了！", exported.firstMessage)
                assertTrue(repository.load(sourceId).lore.isEmpty())
                assertEquals(null, store.readCharacter(savedSourceId).characterBook)
                assertEquals(1, repository.load(worldId).lore.size)
            }
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }

    @Test
    fun typedCustomAnswerResumesPendingQuestion() = verifyQuestionAnswer("我想换成冒险题材。")

    @Test
    fun typedCombinedAnswerPreservesDetails() = verifyQuestionAnswer("轻松和悬疑都选。\n主角说：\"先查清楚再开玩笑。\"")

    @Test
    fun tappedOptionStillResumesPendingQuestion() = verifyQuestionAnswer("轻松", tapOption = true)

    private fun verifyQuestionAnswer(answer: String, tapOption: Boolean = false) = runBlocking {
        val root = Files.createTempDirectory("creation-question-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val fixture = Fixture(askFirstRound = true)
        val models = ViewModelStore()
        try {
            store.bootstrap()
            val vm = CreationViewModel(CreationRepository(root.toFile()), store, noSecrets(),
                ProviderRegistry(listOf(fixture.provider))).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.Character)
                waitUntil { vm.state.value.current != null && !vm.state.value.busy }
                vm.send("帮我创作一个角色")
                waitUntil { vm.state.value.pendingQuestion != null || vm.state.value.error != null }
                assertEquals("选择哪种基调？", vm.state.value.pendingQuestion?.question)
                assertTrue(vm.state.value.busy)

                vm.send("  \n  ")
                vm.answerAgentQuestion(" ")
                assertTrue(vm.state.value.pendingQuestion != null)
                assertEquals(1, fixture.requests.size)

                if (tapOption) vm.answerAgentQuestion(answer) else vm.send("  $answer  ")
                assertEquals(null, vm.state.value.pendingQuestion)
                // A double tap/send must not start another conversation or replace the answer.
                vm.send("重复发送")
                vm.answerAgentQuestion("重复选择")
                waitUntil { fixture.secondRoundStarted.isCompleted || vm.state.value.error != null }
                assertTrue("Question never resumed: ${vm.state.value.error}", fixture.secondRoundStarted.isCompleted)
                assertEquals(2, fixture.requests.size)
                val feedback = fixture.requests[1].prompt.messages.first {
                    it.role == app.tellev.core.model.MessageRole.User && it.content.contains("<tool_result")
                }.content
                assertTrue(feedback.contains("name=\"ask_user\" ok=\"true\""))
                assertTrue("Answer was changed: $feedback", feedback.contains(JsonPrimitive(answer).toString()))

                fixture.release.complete(Unit)
                waitUntil { !vm.state.value.busy }
                assertEquals(null, vm.state.value.error)
                assertEquals("agent", vm.state.value.current!!.turns.last().role)
                assertEquals(2, fixture.requests.size)
            }
        } finally {
            fixture.release.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelledTurnKeepsCompletedToolWritesAndOffersContinuation() = runBlocking {
        val root = Files.createTempDirectory("creation-cancel-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val repository = CreationRepository(root.toFile())
        val fixture = Fixture()
        val models = ViewModelStore()
        try {
            store.bootstrap()
            val vm = CreationViewModel(
                repository = repository,
                store = store,
                secrets = noSecrets(),
                providers = ProviderRegistry(listOf(fixture.provider)),
            ).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.WorldBook)
                waitUntil { vm.state.value.current != null && !vm.state.value.busy }
                val sessionId = vm.state.value.current!!.id

                vm.send("加一条新城设定")
                waitUntil { fixture.secondRoundStarted.isCompleted || vm.state.value.error != null }
                assertTrue("Second round never started: ${vm.state.value.error}", fixture.secondRoundStarted.isCompleted)
                vm.cancelGeneration()
                waitUntil { !vm.state.value.busy }

                assertTrue(vm.state.value.modelPhase.contains("已保存"))
                val persisted = repository.load(sessionId)
                assertEquals("新城", persisted.lore.single().title)
                assertTrue(persisted.partialTurnSaved)
                assertEquals(1, persisted.turns.size)
                assertEquals("user", persisted.turns.single().role)
            }
        } finally {
            fixture.release.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedSecondRoundKeepsDraftAcrossReopenAndContinue() = runBlocking {
        val root = Files.createTempDirectory("creation-failed-round-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
        val repository = CreationRepository(root.toFile())
        val fixture = Fixture(failSecondRound = true)
        val models = ViewModelStore()
        try {
            store.bootstrap()
            val vm = CreationViewModel(repository, store, noSecrets(),
                ProviderRegistry(listOf(fixture.provider))).also { models.put("creation", it) }
            withContext(main) {
                vm.start(CreationKind.WorldBook)
                waitUntil { vm.state.value.current != null && !vm.state.value.busy }
                val id = vm.state.value.current!!.id
                vm.send("加一条新城设定")
                waitUntil { !vm.state.value.busy && vm.state.value.error != null }
                assertTrue(
                    "phase=${vm.state.value.modelPhase} partial=${vm.state.value.current?.partialTurnSaved} " +
                        "error=${vm.state.value.error}",
                    vm.state.value.modelPhase.contains("已保存"),
                )
                vm.close()
                vm.open(id)
                waitUntil { vm.state.value.current?.id == id }
                assertTrue(vm.state.value.current!!.partialTurnSaved)
                assertEquals("新城", repository.load(id).lore.single().title)
                fixture.release.complete(Unit)
                vm.send("继续完成，勿重复新城条目")
                waitUntil { !vm.state.value.busy && vm.state.value.current?.turns?.lastOrNull()?.role == "agent" }
                assertEquals(1, repository.load(id).lore.size)
                assertTrue(!repository.load(id).partialTurnSaved)
            }
        } finally {
            fixture.release.complete(Unit)
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }
}
