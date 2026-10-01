package app.tellev.feature.chat

import app.tellev.core.extension.*
import app.tellev.core.model.*
import app.tellev.core.prompt.*
import app.tellev.core.provider.*
import app.tellev.core.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files

class CompatibilityRepairTest {
    @Test fun `all completion settings and multipart content reach the real OpenAI request without changing preset`() = runBlocking {
        val originalPreset = GenerationPreset("preset", "preset", "openai-compatible", temperature = 0.8, maxTokens = 200, stop = listOf("original"))
        val originalConfig = ProviderConfig("openai-compatible", "https://fixture.invalid/v1", apiKey = "private", model = "original")
        val prompt = PromptBuildResult(listOf(PromptMessage(MessageRole.User, content = "original")), listOf("stop"), 200,
            "openai-compatible", PromptDiagnostics(emptyList()))
        val events = mutableListOf<String>()
        var wire: JsonObject? = null
        val adapter = OpenAiCompatibleAdapter(client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            wire = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            events += "http"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"reply"},"finish_reason":"stop"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val multipart = Json.parseToJsonElement("""[{"role":"user","content":[{"type":"text","text":"edited"},{"type":"image_url","image_url":{"url":"https://fixture.invalid/image.png"}}]}]""").jsonArray
        val host = host { event ->
            events += event.name
            val args = event.payload["args"]!!.jsonArray
            val data = args.first().jsonObject
            assertFalse(data.containsKey("apiKey"))
            val updated = when(event.name) {
                StEventCatalog.GENERATE_AFTER_DATA -> { assertEquals(JsonPrimitive(false), args[1]); JsonObject(data + ("prompt" to multipart) + ("model" to JsonPrimitive("after-model"))) }
                StEventCatalog.CHAT_COMPLETION_SETTINGS_READY -> { assertEquals("after-model", data["model"]!!.jsonPrimitive.content); JsonObject(data + buildJsonObject {
                    put("model", "edited-model"); put("temperature", 0.2); put("top_p", 0.6); put("max_tokens", 345)
                    put("stream", false); put("stop", JsonArray(listOf(JsonPrimitive("edited-stop"))))
                    put("response_format", buildJsonObject { put("type", "json_object") })
                }) }
                else -> data
            }
            buildJsonObject { put("args", JsonArray(listOf(updated) + args.drop(1))) }
        }
        val prepared = ChatCompletionEvents.prepare(host, originalConfig, GenerateRequest(prompt, originalPreset), adapter)
        val chunks = adapter.streamGenerate(prepared.config, prepared.request).toList()
        assertEquals("reply", chunks.filterIsInstance<GenerateChunk.Completed>().single().text)
        assertEquals(listOf(StEventCatalog.CHAT_COMPLETION_PROMPT_READY, StEventCatalog.GENERATE_AFTER_DATA,
            StEventCatalog.CHAT_COMPLETION_SETTINGS_READY, "http"), events)
        assertEquals(multipart, wire!!["messages"]); assertEquals("edited-model", wire!!["model"]!!.jsonPrimitive.content)
        assertEquals(0.2, wire!!["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(345, wire!!["max_tokens"]!!.jsonPrimitive.int)
        assertFalse(wire!!["stream"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("edited-stop"), wire!!["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("original", originalConfig.model); assertEquals(0.8, originalPreset.temperature!!, 0.0)
        assertEquals(listOf("original"), originalPreset.stop); assertEquals("original", prompt.messages.single().content)
    }

    @Test fun `raw ordered prompts omit preset main and honor user input order and component overrides`() = fixture { store, state, _ ->
        val options = Json.parseToJsonElement("""{"__tellev_use_preset":false,"ordered_prompts":["user_input",{"role":"assistant","content":"custom"},"char_description","world_info_before"],"overrides":{"char_description":"overridden","world_info_before":"world override"},"custom_api":{"temperature":"unset","max_tokens":333}}""").jsonObject
        val current = GenerationPreset("named", "named", "openai-compatible", temperature = 0.8,
            prompts = listOf(PresetPrompt("main", content = "must not leak")))
        val preset = ExtensionGenerationOptions.preset(options, current, store)
        val request = ExtensionGenerationOptions.promptRequest(options, PromptBuildRequest(state.value.selectedCharacter!!,
            null, state.value.messages, emptyList(), preset, "new input", "openai-compatible"))
        val result = DefaultPromptEngine().build(request)
        assertEquals(listOf("new input", "custom", "overridden", "world override"), result.messages.map { it.content })
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant, MessageRole.System, MessageRole.System), result.messages.map { it.role })
        assertNull(preset.temperature); assertEquals(333, result.maxTokens)
        assertEquals("original description", store.readCharacter("fixture").description)
        assertEquals("must not leak", current.prompts.single().content)
    }

    @Test fun `named generation preset and overridden history are request local`() = fixture { store, state, _ ->
        store.savePreset(GenerationPreset("extra", "extra", "openai-compatible", temperature = 0.3))
        val before = store.readSelectedPresetName(PresetCategory.OpenAi)
        val options = Json.parseToJsonElement("""{"preset_name":"extra","max_chat_history":1,"overrides":{"chat_history":{"prompts":[{"role":"user","content":"old override"},{"role":"assistant","content":"latest override"}]}},"injects":[{"position":"in_chat","depth":0,"role":"system","content":"injection"}],"custom_api":{"apiurl":"https://extra.invalid/v1","key":"extra-key","model":"extra-model"}}""").jsonObject
        val preset = ExtensionGenerationOptions.preset(options, state.value.selectedPreset!!, store)
        val config = ExtensionGenerationOptions.config(options, ProviderConfig("openai-compatible", "https://original.invalid/v1", headers = mapOf("Authorization" to "original")))
        assertEquals("extra", preset.id); assertEquals("https://extra.invalid/v1", config.baseUrl); assertTrue(config.headers.isEmpty())
        val request = ExtensionGenerationOptions.promptRequest(options, PromptBuildRequest(state.value.selectedCharacter!!,
            null, state.value.messages, emptyList(), preset, "new", "openai-compatible"))
        assertEquals(listOf("latest override"), request.messages.map { it.content })
        assertEquals("injection", request.metadata["injectedPrompts"]!!.jsonObject.values.single().jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(before, store.readSelectedPresetName(PresetCategory.OpenAi))
        assertEquals("history", store.readChatSession("session").messages.single().content)
    }

    @Test fun `canonical character and preset variables persist unknown fields and remain isolated on reload`() = fixture { store, state, runtime ->
        suspend fun replace(type: String, n: Int) = ChatTavernStorage.call("replaceVariables", buildJsonObject {
            put("type", type); put("variables", buildJsonObject { put("value", n) }) }, store, state, runtime)
        replace("character", 7)
        val card = store.readCharacter("fixture")
        assertEquals(JsonPrimitive("keep"), card.raw["data"]!!.jsonObject["extensions"]!!.jsonObject["unknown"])
        assertEquals(7, CharacterTavernHelperScripts.extractCharacterVariables(card)!!["value"]!!.jsonPrimitive.int)
        assertEquals(card, state.value.selectedCharacter)
        store.savePreset(GenerationPreset("A", "A", "openai-compatible", extensions = helperVariables(1)))
        store.savePreset(GenerationPreset("B", "B", "openai-compatible", extensions = helperVariables(2)))
        store.selectPreset(PresetCategory.OpenAi, "A"); replace("preset", 8)
        store.selectPreset(PresetCategory.OpenAi, "B")
        fun payload() = buildJsonObject { put("type", "preset") }
        assertEquals(2, ChatTavernStorage.call("getVariables", payload(), store, state, runtime)["variables"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        replace("preset", 9); store.selectPreset(PresetCategory.OpenAi, "A")
        assertEquals(8, ChatTavernStorage.call("getVariables", payload(), store, state, runtime)["variables"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(9, store.readPreset(PresetCategory.OpenAi, "B")!!.extensions["tavern_helper"]!!.jsonObject["variables"]!!.jsonObject["value"]!!.jsonPrimitive.int)
    }

    @Test fun `worldbook rebinding saves raw character and chat metadata and activates bound disabled books`() = fixture { store, state, runtime ->
        val book = WorldBook("book", "book", emptyList()); store.saveWorldBook(book); store.saveDisabledWorldIds(setOf("book"))
        ChatTavernStorage.call("rebindCharWorldbooks", Json.parseToJsonElement("""{"name":"current","binding":{"primary":null,"additional":["book"]}}""").jsonObject, store, state, runtime)
        val binding = ChatTavernStorage.call("getCharWorldbookNames", buildJsonObject { put("name", "current") }, store, state, runtime)
        assertEquals(JsonNull, binding["primary"]); assertEquals(JsonArray(listOf(JsonPrimitive("book"))), binding["additional"])
        val name = ChatTavernStorage.call("getOrCreateChatWorldbook", buildJsonObject { put("chat", "current"); put("name", "chat book") }, store, state, runtime)["name"]
        assertEquals(JsonPrimitive("chat book"), name)
        assertEquals(name, store.readChatSession("session").metadata["world_info"])
        assertEquals(1, store.readChatSession("session").metadata["variables"]!!.jsonObject["original"]!!.jsonPrimitive.int)
        val active = ChatTavernStorage.activeWorldBooks(emptyList(), store.listWorldBooks(), state.value.selectedCharacter!!, state.value.currentSession)
        assertEquals(setOf("book", "chat book"), active.map { it.name }.toSet())
        val materialized = WorldBook(StDataStore.embeddedCharacterBookId("fixture"), "embedded", emptyList())
        val embeddedCard = state.value.selectedCharacter!!.copy(characterBook = materialized.copy(id = "raw-embedded"))
        val materializedOnly = ChatTavernStorage.activeWorldBooks(listOf(materialized), listOf(materialized), embeddedCard, null)
        assertEquals(listOf(materialized), materializedOnly)
        try {
            ChatTavernStorage.call("rebindChatWorldbook", buildJsonObject { put("chat", "current"); put("name", "missing") }, store, state, runtime)
            fail("Missing worldbook accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(name, store.readChatSession("session").metadata["world_info"])
    }

    @Test fun `queued compatibility writes reject a replaced chat context`() = fixture { store, state, runtime ->
        val field = ChatTavernStorage::class.java.getDeclaredField("writes").apply { isAccessible = true }
        val mutex = field.get(null) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        val pending = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { ChatTavernStorage.call("replaceVariables", buildJsonObject {
                put("type", "character"); put("variables", buildJsonObject { put("value", 7) })
            }, store, state, runtime) }
        }
        state.value = state.value.copy(selectedCharacter = CharacterCard("other", "Other"))
        mutex.unlock()
        val failure = pending.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("expired context"))
        assertEquals(JsonObject(emptyMap()), CharacterTavernHelperScripts.extractCharacterVariables(store.readCharacter("fixture")))
    }

    @Test fun `explicit helper tools rejected when provider would omit them`() {
        val adapter = OpenRouterAdapter()
        val preset = GenerationPreset("p", "p", adapter.id)
        val prompt = PromptBuildResult(listOf(PromptMessage(MessageRole.User, content = "user")), emptyList(), 200, adapter.id, PromptDiagnostics(emptyList()))
        val options = Json.parseToJsonElement("""{"tools":[{"type":"function","function":{"name":"test","parameters":{"type":"object"}}}]}""").jsonObject
        val request = ExtensionGenerationOptions.request(options, prompt, preset, false)
        try {
            ExtensionGenerationOptions.validateCapabilities(options, ProviderConfig(adapter.id, "https://fixture.invalid"), request, adapter)
            fail("Unsupported tools silently accepted")
        } catch (failure: IllegalArgumentException) { assertTrue(failure.message!!.contains("cannot apply")) }
    }

    private fun helperVariables(n: Int) = buildJsonObject { put("tavern_helper", buildJsonObject {
        put("variables", buildJsonObject { put("value", n) }); put("scripts", JsonArray(emptyList())) }) }

    private fun fixture(block: suspend (FileStDataStore, MutableStateFlow<ChatUiState>, ChatSessionRuntime) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tellev-compat-repair-")
        val store = FileStDataStore(StDirectoryLayout.fromRoot(root)); store.bootstrap()
        val raw = Json.parseToJsonElement("""{"spec":"chara_card_v2","data":{"name":"Fixture","extensions":{"unknown":"keep","tavern_helper":{"variables":{}}}}}""").jsonObject
        val card = CharacterCard("fixture", "Fixture", description = "original description", raw = raw)
        store.saveCharacter(card)
        val initialSession = ChatSession("session", "Session", "fixture", null,
            listOf(ChatMessage("floor", MessageRole.User, "User", "history", 0)),
            metadata = buildJsonObject { put("variables", buildJsonObject { put("original", 1) }) })
        store.saveChatSession(initialSession)
        val session = store.readChatSession("session")
        val runtime = ChatSessionRuntime(store); runtime.activateSessionWrites(session)
        val state = MutableStateFlow(ChatUiState(selectedCharacter = card, selectedPreset = store.listPresets().first(), currentSession = session, messages = session.messages))
        try { block(store, state, runtime) } finally { runtime.sessionWriteScope.cancel(); root.toFile().deleteRecursively() }
    }

    private fun host(transform: (ExtensionEvent) -> JsonObject): ExtensionHost =
        Proxy.newProxyInstance(ExtensionHost::class.java.classLoader, arrayOf(ExtensionHost::class.java)) { _, method, args ->
            check(method.name == "emitMutable"); transform(args[0] as ExtensionEvent)
        } as ExtensionHost
}
