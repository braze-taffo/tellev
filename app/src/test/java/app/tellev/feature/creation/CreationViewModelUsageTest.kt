package app.tellev.feature.creation

import androidx.lifecycle.ViewModelStore
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
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Draft usage must survive the engine's checkpoint and final state updates:
 * those snapshots are built before the turn's own requests were counted, so
 * assigning them verbatim would erase the aggregate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreationViewModelUsageTest {

    private class Fixture {
        /** Round 1 is a tool call, round 2 is the final prose reply. */
        val provider = object : ProviderAdapter {
            private var calls = 0
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> =
                if (++calls == 1) {
                    flow {
                        emit(GenerateChunk.Completed(
                            "<tool_call>{\"name\":\"set_card_fields\",\"arguments\":{\"name\":\"用量角色\"}}</tool_call>",
                            usage = usageFrame(11, 3),
                        ))
                    }
                } else {
                    flow {
                        emit(GenerateChunk.Completed("完成。", usage = usageFrame(22, 5)))
                    }
                }
        }

        private fun usageFrame(prompt: Int, completion: Int) = buildJsonObject {
            put("prompt_tokens", prompt)
            put("completion_tokens", completion)
            put("total_tokens", prompt + completion)
        }
    }

    private fun noSecrets(): SecretStore = object : SecretStore {
        override suspend fun putSecret(id: String, value: String) = Unit
        override suspend fun readSecret(id: String): String? = null
        override suspend fun deleteSecret(id: String) = Unit
        override suspend fun listSecretIds(): List<String> = emptyList()
    }

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }

    @Test
    fun usageSurvivesCheckpointFinalSaveAndReopen() = runBlocking {
        val root = Files.createTempDirectory("creation-usage-vm-")
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
                val sessionId = requireNotNull(vm.state.value.current).id
                assertTrue(vm.send("创建一个角色"))
                waitUntil { !vm.state.value.busy }

                // Both completed requests were counted, and neither the
                // checkpoint nor the final assignment erased them.
                val usage = requireNotNull(vm.state.value.current).usage
                assertEquals(2, usage.completedRequests)
                assertEquals(33L, usage.promptTokens)
                assertEquals(8L, usage.completionTokens)
                assertEquals(41L, usage.totalTokens)
                assertEquals(0, usage.estimatedCount)
            }
            // A reopened draft reads the same aggregate back from disk.
            val reopened = CreationRepository(root.toFile()).load(
                requireNotNull(CreationRepository(root.toFile()).list().firstOrNull()).id,
            )
            assertEquals(2, reopened.usage.completedRequests)
            assertEquals(41L, reopened.usage.totalTokens)
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun effortChoicePersistsWithTheDraft() = runBlocking {
        val root = Files.createTempDirectory("creation-effort-vm-")
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
                vm.setReasoningEffort(app.tellev.core.model.ReasoningEffort.High)
                assertEquals(
                    app.tellev.core.model.ReasoningEffort.High,
                    requireNotNull(vm.state.value.current).reasoningEffort,
                )
            }
            val repository = CreationRepository(root.toFile())
            // persist() writes on the view model scope; wait for the disk copy.
            var draftId = ""
            withContext(main) {
                withTimeout(10_000) {
                    while (true) {
                        draftId = repository.list().firstOrNull()?.id.orEmpty()
                        if (draftId.isNotEmpty() &&
                            repository.load(draftId).reasoningEffort ==
                            app.tellev.core.model.ReasoningEffort.High
                        ) break
                        delay(10)
                    }
                }
            }
            assertEquals(
                app.tellev.core.model.ReasoningEffort.High,
                repository.load(draftId).reasoningEffort,
            )
        } finally {
            withContext(main) { models.clear() }
            Dispatchers.resetMain()
            main.close()
            root.toFile().deleteRecursively()
        }
    }
}
