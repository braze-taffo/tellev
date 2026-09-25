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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * 回合失败/取消的事务性：工具写入只改工作副本，回合成功才落盘；
 * 中途取消时，磁盘草稿必须停在用户消息那一版，第一轮工具写入不残留。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreationViewModelCancelTest {

    private class Fixture {
        val secondRoundStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        /** Round 1 applies a lore write; round 2 blocks until released/cancelled. */
        val provider = object : ProviderAdapter {
            private var calls = 0
            override val id = "openai-compatible"
            override val displayName = "Fake"
            override val capabilities = setOf(ProviderCapability.Chat)
            override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "ok")
            override suspend fun listModels(config: ProviderConfig): List<ProviderModel> = emptyList()
            override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> =
                if (++calls == 1) {
                    flowOf(
                        GenerateChunk.Completed(
                            "<tool_call>{\"name\":\"upsert_lore\",\"arguments\":{\"entries\":" +
                                "[{\"title\":\"新城\",\"keys\":[\"新城\"],\"content\":\"新城内容。\"}]}}</tool_call>",
                        ),
                    )
                } else {
                    flow {
                        secondRoundStarted.complete(Unit)
                        release.await()
                        emit(GenerateChunk.Completed("完成。"))
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

    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(10)
    }

    @Test
    fun cancelledTurnRollsBackToolWritesAndKeepsOnlyUserMessage() = runBlocking {
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
                waitUntil { vm.state.value.current != null }
                val sessionId = vm.state.value.current!!.id

                vm.send("加一条新城设定")
                fixture.secondRoundStarted.await()
                vm.cancelGeneration()
                waitUntil { !vm.state.value.busy }

                assertTrue(vm.state.value.modelPhase.contains("未应用"))
                val persisted = repository.load(sessionId)
                assertTrue("取消后不应残留第一轮工具写入", persisted.lore.isEmpty())
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
}
