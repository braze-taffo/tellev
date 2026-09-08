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

/** Exercises the real chat-to-image dispatch with deterministic model replies and no paid calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneImageGenerationTest {
    @Test
    fun `scene summary retries corrupted output and sends full English scene with current context`() = verifySceneSummary(true)

    @Test
    fun `scene summary fails closed after two invalid replies without calling image provider`() = verifySceneSummary(false)

    @Test
    fun `provider error is reported as provider failure not English format rejection`() = verifySceneSummary(false, "provider")

    @Test
    fun `reasoning only response reports missing body and finish reason`() = verifySceneSummary(false, "reasoning")

    @Test
    fun `successful scene image leaves chat context MVU variables and preset unchanged`() = verifySceneSummary(true, imageSucceeds = true)

    @Test
    fun `manual image does not invoke the chat model or change chat state`() = verifySceneSummary(true, imageSucceeds = true, summarize = false)

    @Test
    fun `cancelling an image leaves chat state and extension events unchanged`() = verifySceneSummary(true, cancelImage = true)

    private fun verifySceneSummary(retrySucceeds: Boolean, failureMode: String? = null, imageSucceeds: Boolean = false, cancelImage: Boolean = false, summarize: Boolean = true) = runBlocking {
        val root = Files.createTempDirectory("tellev-scene-audit-")
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val models = ViewModelStore()
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            store.saveCharacter(CharacterCard("fixture", "Fixture", firstMessage = "魔法少女和使魔正在回家的路上。"))
            store.savePreset(GenerationPreset("audit", "Audit", ProviderCatalog.OPENAI_COMPATIBLE,
                stop = listOf("roleplay-stop"), raw = buildJsonObject {
                    put("assistant_prefill", "我已输出完禁词，现在开始思考创作准则")
                    put("response_format", buildJsonObject { put("type", "json_object") })
                }))
            store.selectPreset(PresetCategory.OpenAi, "audit")
            store.saveWorldInfoSettings(WorldInfoSettings(scanDepth = 5, recursive = true, maxRecursionSteps = 3))
            val secrets = TestSecrets()
            ProviderConfigPersistence.saveComfySettings(secrets, ComfyUiSettings(workflowJson = "{}"))
            val comfy = RecordingAdapter(ProviderCatalog.COMFYUI, imageSucceeds, cancelImage)
            store.saveChatSession(ChatSession("scene", "Scene", "fixture", null,
                messages = listOf(ChatMessage("narrative", MessageRole.Character, "Fixture",
                    "魔法少女和使魔正在回家的路上。", 1L,
                    variables = listOf(buildJsonObject { putJsonObject("stat_data") { put("magic", 88) } }),
                    isEjsProcessed = listOf(JsonPrimitive(true)), variablesInitialized = listOf(JsonPrimitive(true)))),
                metadata = buildJsonObject { putJsonObject("variables") { putJsonObject("stat_data") { put("magic", 88) } } }))
            val hostCalls = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val chatRequests = mutableListOf<GenerateRequest>()
            val expected = "A magical girl and her small winged familiar walk home along a quiet street."
            val chat = object : ProviderAdapter {
                override val id = ProviderCatalog.OPENAI_COMPATIBLE
                override val displayName = "Audit Chat"
                override val capabilities = setOf(ProviderCapability.Chat)
                override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "test")
                override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
                override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): kotlinx.coroutines.flow.Flow<GenerateChunk> {
                    chatRequests += request
                    if (failureMode == "provider") return flowOf(GenerateChunk.Failed(TellevError("provider_http_400", "unsupported message role")))
                    if (failureMode == "reasoning") return flowOf(GenerateChunk.Completed("", "length", reasoning = "model reasoning"))
                    return flowOf(GenerateChunk.Completed(
                        if (chatRequests.size == 2 && retrySucceeds) expected else "魔法少女和使魔走在回家的路上.", "stop",
                    ))
                }
            }
            var allowChat = false
            val builds = mutableListOf<PromptBuildRequest>()
            val engine = object : PromptEngine {
                override fun build(request: PromptBuildRequest): PromptBuildResult {
                    builds += request
                    check(allowChat) { "Image extraction must not run the roleplay prompt engine" }
                    return DefaultPromptEngine().build(request)
                }
            }
            val vm = withContext(main) {
                ChatViewModel(store, ProviderRegistry(listOf(chat, comfy)), engine, secrets, host(hostCalls), ExtensionPermissionManager())
                    .also { models.put("chat", it) }
            }
            waitUntil { vm.uiState.value.characters.any { it.id == "fixture" } && !vm.uiState.value.isLoading }
            withContext(main) { vm.selectCharacter("fixture") }
            waitUntil { vm.uiState.value.selectedCharacter != null && !vm.uiState.value.isLoading }
            val before = vm.uiState.value
            val sessionBefore = store.readChatSession("scene")
            val chatPath = store.layout.chats.resolve("fixture/scene.jsonl")
            val bytesBefore = Files.readAllBytes(chatPath)
            val contextBefore = withContext(main) { vm.tavernMessageContextJson(vm.currentRuntimeToken("scene")) }
            val presetBefore = store.readPreset(PresetCategory.OpenAi, "audit")
            val requestBefore = PromptBuildRequest(before.selectedCharacter!!, before.selectedPersona,
                before.messages, emptyList(), before.selectedPreset!!, "继续", ProviderCatalog.OPENAI_COMPATIBLE)
            val promptBefore = DefaultPromptEngine().build(requestBefore)
            withContext(main) { hostCalls.clear(); vm.generateImage("A magical girl walking home.", "", summarize, ProviderCatalog.COMFYUI) }
            if (cancelImage) {
                waitUntil { comfy.requests.isNotEmpty() && vm.uiState.value.isGeneratingImage }
                withContext(main) { vm.stopImageGeneration() }
            }
            waitUntil { !vm.uiState.value.isGeneratingImage && (cancelImage || vm.uiState.value.imageGenError != null || vm.uiState.value.generatedImages.isNotEmpty()) }
            assertTrue("Image generation called extension host: $hostCalls", hostCalls.isEmpty())
            assertEquals(sessionBefore, store.readChatSession("scene"))
            assertArrayEquals(bytesBefore, Files.readAllBytes(chatPath))
            assertEquals(before.currentSession, vm.uiState.value.currentSession)
            assertEquals(before.messages, vm.uiState.value.messages)
            assertEquals(before.error, vm.uiState.value.error)
            assertEquals(contextBefore, withContext(main) { vm.tavernMessageContextJson(vm.currentRuntimeToken("scene")) })
            assertEquals(presetBefore, store.readPreset(PresetCategory.OpenAi, "audit"))
            assertEquals(promptBefore, DefaultPromptEngine().build(requestBefore.copy(messages = vm.uiState.value.messages)))
            if (imageSucceeds) assertNull(vm.uiState.value.imageGenError)
            val gallery = GeneratedImageStore(store.layout).read("scene")
            assertEquals(if (imageSucceeds) 1 else 0, gallery.size)
            if (imageSucceeds) {
                assertTrue(Files.exists(store.layout.root.resolve(gallery.single().attachments.single().relativePath)))
                assertEquals(gallery, vm.uiState.value.generatedImages)
            }
            assertTrue(builds.isEmpty())
            assertEquals(if (!summarize) 0 else if (failureMode == null) 2 else 1, chatRequests.size)
            chatRequests.forEach { request ->
                assertEquals(listOf(MessageRole.System, MessageRole.User), request.prompt.messages.map { it.role })
                assertTrue(request.prompt.messages.first().content.contains("NOT a roleplay"))
                assertTrue(request.prompt.messages.any { it.content.contains("魔法少女和使魔") })
                assertFalse(request.prompt.messages.any { it.content.contains("silver wings") })
                assertTrue(request.preset.raw.isEmpty())
                assertTrue(request.preset.stop.isEmpty())
                assertTrue(request.prompt.stop.isEmpty())
            }
            assertEquals("我已输出完禁词，现在开始思考创作准则",
                store.readPreset(PresetCategory.OpenAi, "audit")!!.raw["assistant_prefill"]!!.jsonPrimitive.content)
            if (retrySucceeds) {
                assertEquals(if (summarize) expected else "A magical girl walking home.", comfy.requests.single().second.prompt.messages.single().content)
                assertTrue(comfy.requests.single().second.preset.raw.isEmpty())
            } else if (failureMode != null) {
                assertTrue(comfy.requests.isEmpty())
                val details = vm.uiState.value.imageGenDiagnostic.orEmpty()
                if (failureMode == "provider") {
                    assertTrue(vm.uiState.value.imageGenError!!.contains("provider_http_400"))
                    assertTrue(details.contains("unsupported message role"))
                } else {
                    assertTrue(vm.uiState.value.imageGenError!!.contains("只返回推理"))
                    assertTrue(details.contains("length"))
                }
            } else {
                assertTrue(comfy.requests.isEmpty())
                assertTrue(vm.uiState.value.imageGenError!!.contains("已停止生图"))
                assertTrue(vm.uiState.value.imageGenDiagnostic!!.contains("魔法少女和使魔走在回家的路上."))
            }
            if (imageSucceeds) {
                // Exercise the actual next chat dispatch after an image was saved.
                withContext(main) { allowChat = true; assertTrue(vm.sendMessage("继续沿路回家")) }
                waitUntil { !vm.uiState.value.isGenerating && vm.uiState.value.messages.size >= 3 }
                assertNull(vm.uiState.value.error)
                assertEquals(before.messages, builds.single().messages)
                val nextChat = chatRequests.last()
                assertEquals(before.selectedPreset, nextChat.preset)
                assertTrue(nextChat.attachments.isEmpty())
                assertFalse(nextChat.prompt.messages.any { it.content.contains("【图片】") || it.content.contains("image_prompt") })
                assertEquals(gallery, GeneratedImageStore(store.layout).read("scene"))
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

    private class RecordingAdapter(override val id: String, private val succeed: Boolean, private val cancel: Boolean) : ProviderAdapter {
        override val displayName = id
        override val capabilities = setOf(ProviderCapability.Images)
        val requests = mutableListOf<Pair<ProviderConfig, GenerateRequest>>()
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "test")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): kotlinx.coroutines.flow.Flow<GenerateChunk> {
            requests += config to request
            if (cancel) return kotlinx.coroutines.flow.flow { awaitCancellation() }
            return if (succeed) flowOf(GenerateChunk.Completed(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6zX8AAAAASUVORK5CYII=", "stop"))
            else flowOf(GenerateChunk.Failed(TellevError("test_finished", "Captured request")))
        }
    }

    private class TestSecrets : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private fun host(calls: java.util.Queue<String>): ExtensionHost {
        val events = MutableSharedFlow<ExtensionEvent>(extraBufferCapacity = 32)
        return Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
            calls.add(method.name)
            when (method.name) {
                "setContextProvider", "setLocalVariableBackend", "setMessageVariableBackend", "unload", "flushWrites" -> Unit
                "getEvents" -> events
                "emit", "reportHostEvent" -> { events.tryEmit(args[0] as ExtensionEvent); Unit }
                "snapshotExtensionSettings" -> JsonObject(emptyMap())
                "collectInjectedPrompts" -> buildJsonObject {
                    putJsonObject("scene-state") {
                        put("value", "The familiar has silver wings.")
                        put("position", 1); put("depth", 0); put("role", "system")
                    }
                }
                else -> error("Unexpected host call: ${method.name}")
            }
        } as ExtensionHost
    }
}
