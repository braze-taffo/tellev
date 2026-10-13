package app.tellev.feature.creation

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import app.tellev.core.model.Attachment
import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.PresetCategory
import app.tellev.core.model.ReasoningEffort
import app.tellev.core.model.TellevError
import app.tellev.core.provider.CustomProviderConfig
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.OpenAiCompatibilitySettings
import app.tellev.core.provider.OpenAiCompatibleAdapter
import app.tellev.core.provider.ProviderAdapter
import app.tellev.core.provider.ProviderCapability
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderModel
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.ProviderStatus
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.PngCardParser
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64

class CreationEngineSelectionTest {
    private class Secrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun putSecret(id: String, value: String) { values[id] = value }
        override suspend fun readSecret(id: String): String? = values[id]
        override suspend fun deleteSecret(id: String) { values.remove(id) }
        override suspend fun listSecretIds(): List<String> = values.keys.toList()
    }

    private data class Capture(val config: ProviderConfig, val request: GenerateRequest)

    private class RecordingProvider(
        override val id: String = ProviderCatalog.OPENAI_COMPATIBLE,
        override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.Chat),
        private val reply: suspend (Int) -> GenerateChunk = { GenerateChunk.Completed("Done.") },
    ) : ProviderAdapter {
        val captures = mutableListOf<Capture>()
        override val displayName = "Fixture"
        override suspend fun checkStatus(config: ProviderConfig) = ProviderStatus(true, "fixture")
        override suspend fun listModels(config: ProviderConfig) = emptyList<ProviderModel>()
        override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
            captures += Capture(config, request)
            emit(reply(captures.size))
        }
    }

    @Test
    fun builtInProviderUsesItsPersistedSelectedModel() = runBlocking {
        for (id in listOf(ProviderCatalog.GEMINI, ProviderCatalog.OLLAMA)) {
            val secrets = Secrets()
            secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, id)
            secrets.putSecret("provider-$id-model", "chosen-$id")
            val provider = RecordingProvider(id = id)
            val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
            val snapshot = engine.resolveOperationSnapshot()
            engine.converse(CreationSession(kind = CreationKind.Character), "Create.", snapshot = snapshot)
            assertEquals(id, snapshot.selectedId)
            assertEquals(id, provider.captures.single().config.providerType)
            assertEquals("chosen-$id", provider.captures.single().config.model)
        }
    }

    @Test
    fun customSelectionKeepsIdentityEndpointHeadersAndModel() = runBlocking {
        val secrets = Secrets()
        ProviderConfigPersistence.saveCustomConfigs(secrets, listOf(
            CustomProviderConfig("first", "First", "https://first.invalid", model = "same-model"),
            CustomProviderConfig("second", "Second", "https://second.invalid", model = "same-model",
                advanced = OpenAiCompatibilitySettings(
                    chatCompletionsPath = "/messages", headers = mapOf("X-Fixture" to "second"),
                    supportsVision = true,
                )),
        ))
        val provider = RecordingProvider()
        val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
        for (id in listOf("first", "second")) {
            secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, "custom:$id")
            val snapshot = engine.resolveOperationSnapshot()
            engine.converse(CreationSession(kind = CreationKind.Character), "Create.", snapshot = snapshot)
            assertEquals("custom:$id", snapshot.selectedId)
            assertEquals("https://$id.invalid", provider.captures.last().config.baseUrl)
            assertEquals("same-model", provider.captures.last().config.model)
        }
        assertEquals(mapOf("X-Fixture" to "second"), provider.captures.last().config.headers)
        assertEquals("/messages", provider.captures.last().config.options["chatCompletionsPath"]?.jsonPrimitive?.content)
    }

    @Test
    fun toolAndBudgetRetryUseOneConfigAdapterAndPresetDespiteSavedChanges() = runBlocking {
        val root = Files.createTempDirectory("creation-snapshot-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val chosen = GenerationPreset(
                "chosen", "Chosen", ProviderCatalog.OPENAI_COMPATIBLE,
                category = PresetCategory.OpenAi, temperature = 0.31, topP = 0.87,
                maxCompletionTokens = 8192, stop = listOf("FIXTURE_END"),
                // 类型化字段才是持久化来源：raw 里的同名键在保存时会被清掉。
                reasoningEffort = ReasoningEffort.High,
                raw = buildJsonObject { put("reasoning_effort", "high") },
            )
            store.savePreset(chosen)
            store.selectPreset(PresetCategory.OpenAi, chosen.id)
            val secrets = Secrets()
            secrets.putSecret("provider-openai-compatible-model", "chosen-model")
            val other = RecordingProvider(id = ProviderCatalog.GEMINI)
            val provider = RecordingProvider { call ->
                when (call) {
                    1 -> {
                        secrets.putSecret("provider-openai-compatible-model", "changed-model")
                        secrets.putSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID, ProviderCatalog.GEMINI)
                        store.saveWorkingPreset(PresetCategory.OpenAi, chosen.copy(
                            temperature = 0.91, topP = 0.42, maxCompletionTokens = 16000,
                        ))
                        GenerateChunk.Completed("<tool_call>{\"name\":\"read_card\",\"arguments\":{}}</tool_call>")
                    }
                    2 -> GenerateChunk.Failed(TellevError("provider_http_400", "max_tokens too large"))
                    else -> GenerateChunk.Completed("Done.")
                }
            }
            CreationEngine(secrets, ProviderRegistry(listOf(provider, other)), store)
                .converse(CreationSession(kind = CreationKind.Character), "Create.")
            assertEquals(3, provider.captures.size)
            assertTrue(other.captures.isEmpty())
            assertTrue(provider.captures.all { it.config == provider.captures.first().config })
            assertTrue(provider.captures.all { it.config.model == "chosen-model" })
            assertEquals(listOf(8192, 8192, 4096), provider.captures.map { it.request.prompt.maxTokens })
            provider.captures.forEach { capture ->
                assertEquals(0.31, capture.request.preset.temperature!!, 0.000001)
                assertEquals(0.87, capture.request.preset.topP!!, 0.000001)
                assertEquals(listOf("FIXTURE_END"), capture.request.preset.stop)
                assertEquals(JsonPrimitive("high"), capture.request.preset.raw["reasoning_effort"])
                assertEquals(JsonPrimitive(true), capture.request.metadata["require_stream_terminator"])
                assertFalse(capture.request.metadata["tools"]!!.jsonArray.isEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun extractionRepairAndCondensedRetryUseOriginalConfig() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "extract-model")
        val provider = RecordingProvider { call ->
            secrets.putSecret("provider-openai-compatible-model", "changed-$call")
            GenerateChunk.Completed(if (call < 3) "invalid" else "{\"assistant_message\":\"Done\",\"lore\":[]}")
        }
        val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
        engine.extractChunk(SourceChunk(0, 6, "Source"), "fixture.txt")
        assertEquals(3, provider.captures.size)
        assertEquals(listOf(0.2, 0.0, 0.0), provider.captures.map { it.request.preset.temperature })
        assertTrue(provider.captures.all { it.config.model == "extract-model" })
    }

    @Test
    fun suppliedExtractionSnapshotStaysSelectedAcrossChunks() = runBlocking {
        val secrets = Secrets()
        secrets.putSecret("provider-openai-compatible-model", "extract-model")
        val provider = RecordingProvider { GenerateChunk.Completed("{\"assistant_message\":\"Done\",\"lore\":[]}") }
        val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)))
        val snapshot = engine.resolveOperationSnapshot()
        secrets.putSecret("provider-openai-compatible-model", "changed-model")
        for (start in listOf(0, 6)) {
            engine.extractChunk(SourceChunk(start, start + 6, "Source"), "fixture.txt", snapshot = snapshot)
        }
        assertEquals(2, provider.captures.size)
        assertTrue(provider.captures.all { it.config.model == "extract-model" })
    }

    @Test
    fun actualTextAndFileImageReachSharedAdapterWirePathAcrossRounds() = runBlocking {
        val root = Files.createTempDirectory("creation-attachments-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            val png = PngCardParser.createMinimalPng()
            Files.write(store.layout.userImages.resolve("fixture.png"), png)
            val image = Attachment("image", "fixture.png", "image/png", "user/images/fixture.png",
                metadata = buildJsonObject { put("base64", "must-not-trust-inline-metadata") })
            val document = Attachment("text", "source.txt", "text/plain", "user/files/source.txt",
                metadata = buildJsonObject { put("textContent", "Actual attached document.") })
            val older = document.copy(id = "older", metadata = buildJsonObject { put("textContent", "Older attached document.") })
            val secrets = Secrets()
            secrets.putSecret("provider-${ProviderCatalog.OPENAI_COMPATIBLE}-model", "vision-model")
            ProviderConfigPersistence.saveAdvanced(secrets, ProviderCatalog.OPENAI_COMPATIBLE,
                OpenAiCompatibilitySettings(supportsVision = true))
            val provider = RecordingProvider(capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Vision)) { call ->
                GenerateChunk.Completed(if (call == 1)
                    "<tool_call>{\"name\":\"read_card\",\"arguments\":{}}</tool_call>" else "Done.")
            }
            CreationEngine(secrets, ProviderRegistry(listOf(provider)), store).converse(
                CreationSession(kind = CreationKind.Character,
                    turns = listOf(CreationTurn("user", "Older turn", listOf(older)))),
                "Current turn", attachments = listOf(document, image),
            )
            assertEquals(2, provider.captures.size)
            provider.captures.forEach { capture ->
                val prompt = capture.request.prompt.messages[1].content
                assertTrue(prompt.contains("Actual attached document."))
                assertTrue(prompt.contains("Older attached document."))
                assertEquals(1, prompt.split("Current turn").size - 1)
                val sentImage = capture.request.attachments.single { it.id == image.id }
                assertTrue(png.contentEquals(Base64.getDecoder().decode(sentImage.metadata["base64"]!!.jsonPrimitive.content)))
                val payload = OpenAiCompatibleAdapter().completionPayload(capture.config, capture.request)
                val contents = payload["messages"]!!.jsonArray.mapNotNull { it.jsonObject["content"] as? kotlinx.serialization.json.JsonArray }
                val url = contents.single().last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                assertEquals("data:image/png;base64,${Base64.getEncoder().encodeToString(png)}", url)
            }
            assertEquals("must-not-trust-inline-metadata", image.metadata["base64"]!!.jsonPrimitive.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsupportedVisionFilesAndUnsafeImagePathsNeverHitProvider() = runBlocking {
        val root = Files.createTempDirectory("creation-invalid-attachment-")
        try {
            val store = FileStDataStore(StDirectoryLayout.fromRoot(root))
            store.bootstrap()
            Files.write(store.layout.userImages.resolve("fixture.png"), PngCardParser.createMinimalPng())
            val image = Attachment("image", "fixture.png", "image/png", "user/images/fixture.png")
            val secrets = Secrets()
            val provider = RecordingProvider(capabilities = setOf(ProviderCapability.Chat, ProviderCapability.Vision))
            val engine = CreationEngine(secrets, ProviderRegistry(listOf(provider)), store)
            val visionFailure = runCatching {
                engine.converse(CreationSession(kind = CreationKind.Character), "Inspect.", attachments = listOf(image))
            }.exceptionOrNull()
            assertEquals(UiStrings.get(S.crs_attachment_vision_unavailable), visionFailure?.message)
            ProviderConfigPersistence.saveAdvanced(secrets, ProviderCatalog.OPENAI_COMPATIBLE,
                OpenAiCompatibilitySettings(supportsVision = true))
            val invalid = listOf(
                Attachment("pdf", "fixture.pdf", "application/pdf", "user/files/fixture.pdf"),
                Attachment("audio", "fixture.wav", "audio/wav", "user/files/fixture.wav"),
                Attachment("blank", "fixture.txt", "text/plain", "user/files/fixture.txt"),
                image.copy(relativePath = "../../outside.png"),
                image.copy(relativePath = "user/images/../../../outside.png"),
                image.copy(relativePath = "user/images\\..\\..\\outside.png"),
                image.copy(relativePath = root.resolve("user/images/fixture.png").toString()),
                image.copy(relativePath = "user/images/missing.png"),
                image.copy(mimeType = "image/svg+xml"),
            )
            invalid.forEach { attachment ->
                assertTrue(runCatching {
                    engine.converse(CreationSession(kind = CreationKind.Character), "Inspect.", attachments = listOf(attachment))
                }.isFailure)
            }
            Files.write(store.layout.userImages.resolve("invalid.png"), "not an image".toByteArray())
            assertTrue(runCatching { readCreationImage(image.copy(relativePath = "user/images/invalid.png"), root) }.isFailure)
            RandomAccessFile(store.layout.userImages.resolve("oversized.png").toFile(), "rw").use {
                it.setLength(CREATION_IMAGE_BYTE_LIMIT + 1)
            }
            assertTrue(runCatching {
                validateCreationAttachments(listOf(image.copy(relativePath = "user/images/oversized.png")), root)
            }.isFailure)
            assertTrue(provider.captures.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyTurnsDecodeAndAttachmentHistoryRoundTrips() = runBlocking {
        val legacy = Json.decodeFromString<CreationTurn>("{\"role\":\"user\",\"text\":\"Legacy\"}")
        assertTrue(legacy.attachments.isEmpty())
        val root = Files.createTempDirectory("creation-attachment-history-").toFile()
        try {
            val attachment = Attachment("text", "fixture.txt", "text/plain", "user/files/fixture.txt",
                metadata = buildJsonObject { put("textContent", "Persisted document.") })
            val session = CreationSession(kind = CreationKind.Character,
                turns = listOf(legacy, CreationTurn("user", "Attached", listOf(attachment))))
            val repository = CreationRepository(root)
            repository.save(session)
            assertEquals(session, repository.load(session.id))
            assertEquals(2, repository.list().single().turnsCount)
            assertTrue(creationConversationContext(repository.load(session.id)).contains("Persisted document."))
        } finally {
            root.deleteRecursively()
        }
    }
}
