package app.tellev.feature.chat

import androidx.lifecycle.ViewModelStore
import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Exercises the actual chat dispatch without paid API calls or Android image decoding. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatImageGenerationTest {
    @Test
    fun `official chat exposes remote image engines only`() {
        assertEquals(setOf(ProviderCatalog.COMFYUI, ProviderCatalog.NOVELAI_IMAGE),
            ChatImageEngine.entries.map { it.providerId }.toSet())
        assertNull(ChatImageEngine.fromProviderId("local-dream"))
        assertTrue(ChatImageEngine.NovelAi.usesEnglishTags)
    }

    @Test
    fun `chat dispatches the chosen NovelAI engine and blocks removed configuration before summarizing`() = runBlocking {
        val root = Files.createTempDirectory("tellev-image-dispatch-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            store.saveCharacter(CharacterCard("fixture", "Fixture"))
            val secrets = TestSecrets()
            secrets.putSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey", "test-token")
            ProviderConfigPersistence.saveNovelAiImageSettings(secrets, NovelAiImageSettings(model = "nai-diffusion-3", steps = 20))
            ProviderConfigPersistence.saveComfySettings(secrets, ComfyUiSettings(workflowJson = "{}"))
            ProviderConfigPersistence.saveImageEngine(secrets, ProviderCatalog.COMFYUI)
            val novel = RecordingAdapter(ProviderCatalog.NOVELAI_IMAGE)
            val comfy = RecordingAdapter(ProviderCatalog.COMFYUI)
            val vm = withContext(main) {
                ChatViewModel(store, ProviderRegistry(listOf(novel, comfy)), object : PromptEngine {
                    override fun build(request: PromptBuildRequest): PromptBuildResult =
                        error("Manual or unconfigured image requests must not call the chat model")
                }, secrets, host(), ExtensionPermissionManager()).also { models.put("chat", it) }
            }
            // The initial default isLoading=false precedes the asynchronous bootstrap.
            waitUntil { vm.uiState.value.characters.any { it.id == "fixture" } && !vm.uiState.value.isLoading }
            withContext(main) { vm.selectCharacter("fixture") }
            waitUntil { vm.uiState.value.selectedCharacter != null && !vm.uiState.value.isLoading }
            assertTrue(vm.uiState.value.imageGenAvailable)
            assertEquals(ProviderCatalog.COMFYUI, vm.uiState.value.imageEngine)

            withContext(main) { vm.generateImage("1girl, solo, garden", "blurry", false, ProviderCatalog.NOVELAI_IMAGE) }
            waitUntil { vm.uiState.value.imageGenError?.contains("test_finished") == true && !vm.uiState.value.isGeneratingImage }
            assertEquals(0, comfy.requests.size)
            val (config, request) = novel.requests.single()
            assertEquals(ProviderCatalog.NOVELAI_IMAGE, config.providerType)
            assertEquals("test-token", config.apiKey)
            assertEquals("1girl, solo, garden", request.prompt.messages.single().content)
            assertEquals("blurry", request.metadata["negative_prompt"]?.jsonPrimitive?.content)
            assertEquals("nai-diffusion-3", request.metadata["novelai_settings"]?.jsonObject?.get("model")?.jsonPrimitive?.content)
            assertEquals("Fixture", request.metadata["macro_char"]?.jsonPrimitive?.content)
            assertEquals(ProviderCatalog.NOVELAI_IMAGE, ProviderConfigPersistence.loadImageEngine(secrets))

            // Even stale callers from the erroneous integrated package cannot route to a local engine.
            withContext(main) { vm.generateImage("1girl, solo", "", false, "local-dream") }
            waitUntil { vm.uiState.value.imageGenError?.contains("请选择生图引擎") == true && !vm.uiState.value.isGeneratingImage }
            assertEquals(1, novel.requests.size)
            assertEquals(0, comfy.requests.size)

            secrets.deleteSecret("provider-${ProviderCatalog.NOVELAI_IMAGE}-apikey")
            withContext(main) { vm.generateImage("", "", true, ProviderCatalog.NOVELAI_IMAGE) }
            waitUntil { vm.uiState.value.imageGenError?.contains("NovelAI 未配置") == true && !vm.uiState.value.isGeneratingImage }
            assertEquals(1, novel.requests.size)
            assertEquals(0, comfy.requests.size)
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

    private class RecordingAdapter(override val id: String) : ProviderAdapter {
        override val displayName = id
        override val capabilities = setOf(ProviderCapability.Images)
        val requests = mutableListOf<Pair<ProviderConfig, GenerateRequest>>()
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "test")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest) =
            flowOf<GenerateChunk>(GenerateChunk.Failed(TellevError("test_finished", "Captured request"))).also {
                requests += config to request
            }
    }

    private class TestSecrets : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private fun host(): ExtensionHost {
        val events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 32)
        return Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
            when (method.name) {
                "setContextProvider", "setLocalVariableBackend", "setMessageVariableBackend", "unload", "flushWrites" -> Unit
                "getEvents" -> events
                "emit", "reportHostEvent" -> { events.tryEmit(args[0] as ExtensionEvent); Unit }
                "snapshotExtensionSettings", "collectInjectedPrompts" -> JsonObject(emptyMap())
                else -> error("Unexpected host call: ${method.name}")
            }
        } as ExtensionHost
    }
}
