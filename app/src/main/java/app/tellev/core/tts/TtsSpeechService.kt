package app.tellev.core.tts

import app.tellev.core.model.GenerationPreset
import app.tellev.core.model.MessageRole
import app.tellev.core.prompt.PromptBuildResult
import app.tellev.core.prompt.PromptDiagnostics
import app.tellev.core.prompt.PromptMessage
import app.tellev.core.provider.GenerateChunk
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderConfigPersistence
import app.tellev.core.provider.ProviderDefaults
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Base64

/** Wire inputs consumed by the existing OpenAiSpeechAdapter. */
data class TtsRequestPayload(
    val input: String,
    val model: String,
    val voice: String,
    val speed: Float,
    val responseFormat: String,
) {
    companion object {
        /**
         * Preserve OpenAiCompatibleAdapter.endpoint/applyHeaders semantics at the
         * boundary to the speech adapter, whose path and bearer auth are fixed.
         * Custom headers are applied last, just as in the chat adapter.
         */
        fun normalizeConfig(config: ProviderConfig): ProviderConfig {
            val headers = linkedMapOf<String, String>()
            config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                val name = (config.options["authHeader"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: "Authorization"
                val scheme = (config.options["authScheme"] as? JsonPrimitive)?.contentOrNull ?: "Bearer"
                headers[name] = if (scheme.isBlank()) key else "$scheme $key"
            }
            headers.putAll(config.headers)
            val base = config.baseUrl.trimEnd('/')
            return config.copy(
                baseUrl = if (base.endsWith("/v1", ignoreCase = true)) base.dropLast(3) else base,
                apiKey = null,
                headers = headers,
            )
        }
    }

    fun metadata() = buildJsonObject {
        put("voice", JsonPrimitive(voice))
        put("speed", JsonPrimitive(speed))
        put("response_format", JsonPrimitive(responseFormat))
    }

    fun toRequest(config: ProviderConfig): GenerateRequest = GenerateRequest(
        prompt = PromptBuildResult(
            messages = listOf(PromptMessage(MessageRole.User, content = input)),
            stop = emptyList(),
            maxTokens = null,
            providerType = config.providerType,
            diagnostics = PromptDiagnostics(emptyList()),
        ),
        preset = GenerationPreset(id = "tts", name = "tts", providerType = config.providerType),
        stream = false,
        metadata = metadata(),
    )
}

/** UI-thread coordinator; all disk operations run on IO. Android/audio is injectable. */
class TtsSpeechService(
    private val settings: () -> TtsSettingsValues,
    private val cache: TtsCache,
    private val player: TtsPlaybackController,
    private val providers: ProviderRegistry,
    private val secrets: SecretStore,
) {
    val state get() = player.state
    val errors get() = player.errors
    private val mutableRequestingId = MutableStateFlow<String?>(null)
    val requestingId = mutableRequestingId.asStateFlow()
    private var requestJob: Job? = null
    private var operation = 0L
    private var ownerSession: String? = null

    suspend fun speak(
        text: String,
        playbackId: String = stablePlaybackId(text),
        sessionId: String? = null,
    ): Result<File> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return Result.failure(TtsFailure.EmptyText.asException())
        val values = settings().validated()
        if (!values.enabled) return Result.failure(TtsFailure.Disabled.asException())

        // Cancel the previous synthesis as well as playback before changing owners.
        stop()
        val token = operation
        ownerSession = sessionId
        requestJob = currentCoroutineContext()[Job]
        mutableRequestingId.value = playbackId
        try {
            var file = withContext(Dispatchers.IO) { cache.get(cleanText, values) }
            if (file == null) {
                val selectedId = secrets.readSecret(ProviderDefaults.SELECTED_PROVIDER_SECRET_ID)
                    ?: ProviderCatalog.OPENAI_COMPATIBLE
                if (ProviderConfigPersistence.adapterIdFor(selectedId) != ProviderCatalog.OPENAI_COMPATIBLE) {
                    return Result.failure(TtsFailure.ProviderNotConfigured.asException())
                }
                val adapter = providers.find(ProviderCatalog.OPENAI_SPEECH)
                    ?: return Result.failure(TtsFailure.ProviderUnavailable.asException())
                val rawConfig = ProviderConfigPersistence.loadProviderConfig(secrets, selectedId)
                val config = TtsRequestPayload.normalizeConfig(rawConfig).copy(model = values.model)
                val payload = TtsRequestPayload(cleanText, values.model, values.voice, values.speed, values.format)
                var failure: TtsFailure? = null
                adapter.streamGenerate(config, payload.toRequest(config)).collect { chunk ->
                    when (chunk) {
                        is GenerateChunk.Completed -> {
                            val bytes = try {
                                Base64.getMimeDecoder().decode(chunk.text)
                            } catch (_: IllegalArgumentException) {
                                failure = TtsFailure.NoAudio
                                return@collect
                            }
                            if (bytes.isEmpty()) failure = TtsFailure.NoAudio
                            else file = withContext(Dispatchers.IO) { cache.put(cleanText, values, bytes) }
                        }
                        is GenerateChunk.Failed -> failure = when (chunk.error.code) {
                            "openai_speech_empty" -> TtsFailure.NoAudio
                            "provider_network" -> TtsFailure.Network
                            else -> TtsFailure.ProviderError(chunk.error.code)
                        }
                        is GenerateChunk.Delta -> Unit
                    }
                }
                failure?.let { return Result.failure(it.asException()) }
            }
            currentCoroutineContext().ensureActive()
            if (token != operation) throw CancellationException("tts_superseded")
            val audio = file ?: return Result.failure(TtsFailure.NoAudio.asException())
            player.enqueue(TtsQueueItem(playbackId, audio))
            return Result.success(audio)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: TtsException) {
            return Result.failure(failure)
        } catch (_: java.io.IOException) {
            return Result.failure(TtsFailure.CacheWrite.asException())
        } catch (_: Exception) {
            return Result.failure(TtsFailure.ProviderUnavailable.asException())
        } finally {
            if (token == operation) {
                requestJob = null
                mutableRequestingId.value = null
            }
        }
    }

    fun stop() {
        operation++
        requestJob?.cancel()
        requestJob = null
        mutableRequestingId.value = null
        ownerSession = null
        player.stop()
    }

    fun stopIfOwner(playbackId: String) {
        val current = when (val value = state.value) {
            is TtsPlaybackState.Loading -> value.item.id
            is TtsPlaybackState.Playing -> value.item.id
            is TtsPlaybackState.Paused -> value.item.id
            is TtsPlaybackState.Error -> value.item?.id
            TtsPlaybackState.Idle -> null
        }
        if (current == playbackId || requestingId.value == playbackId) stop()
    }

    fun onSessionChanged(sessionId: String?) {
        if (ownerSession != null && ownerSession != sessionId) stop()
    }

    fun release() { stop(); player.release() }

    companion object {
        fun stablePlaybackId(text: String): String = TtsCache.sha256Key(text)

        fun messagePlaybackId(sessionId: String?, messageId: String, swipeIndex: Int, text: String): String =
            TtsCache.sha256Key(JsonArray(listOf(
                JsonPrimitive(sessionId), JsonPrimitive(messageId), JsonPrimitive(swipeIndex), JsonPrimitive(text),
            )).toString())
    }
}
