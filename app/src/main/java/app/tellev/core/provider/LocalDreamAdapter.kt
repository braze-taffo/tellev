package app.tellev.core.provider

import app.tellev.core.ldream.LocalDreamCore
import app.tellev.core.ldream.LocalDreamCoreState
import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 本地 MNN（OpenCL）生图设置。模型以目录形式存放于 st-data/user/models-mnn/
 * <modelDirName>/（safetensors 导入后由核心转换成 MNN 布局）。加密持久化见
 * ProviderConfigPersistence。
 */
@Serializable
data class LocalDreamSettings(
    /** models-mnn 下的目录名。空 = 未配置。 */
    val modelDirName: String = "",
    val steps: Int = 20,
    val cfgScale: Double = 7.0,
    val width: Int = 512,
    val height: Int = 768,
    /** 核心 Pipeline.hpp 接受：dpm / euler / euler_a / lcm / dpm_sde（含 *_karras）。 */
    val scheduler: String = "euler_a",
    /** -1 每次随机。 */
    val seed: Long = -1L,
    val negativePrompt: String = "",
    val clipSkip: Int = 2,
    val useOpencl: Boolean = true,
) {
    companion object {
        val SCHEDULERS = listOf("euler_a", "euler", "dpm", "dpm_sde", "lcm")
    }
}

/**
 * /generate 的 SSE 协议（text/event-stream）：`event: progress|complete|error`
 * 后跟一行 `data: {json}`。只保留纯解析与请求组装，便于 JVM 单测。
 */
internal object LocalDreamProtocol {
    sealed interface Event {
        data class Progress(val step: Int, val totalSteps: Int) : Event

        data class Complete(val imageBase64: String, val generationTimeMs: Long) : Event

        data class Error(val message: String) : Event
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析一对 SSE 事件行/数据行；不匹配（未知事件/坏 JSON）返回 null。 */
    fun parse(eventName: String?, dataLine: String?): Event? {
        if (eventName == null || dataLine == null) return null
        val data = dataLine.removePrefix("data: ").trim()
        if (data.isEmpty()) return null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when (eventName) {
            "progress" -> {
                val step = obj["step"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
                val total = obj["total_steps"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
                Event.Progress(step, total)
            }
            "complete" -> {
                val image = obj["image"]?.jsonPrimitive?.contentOrNull ?: return null
                val time = obj["generation_time_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                Event.Complete(image, time)
            }
            "error" -> Event.Error(obj["message"]?.jsonPrimitive?.contentOrNull ?: "生成失败")
            else -> null
        }
    }

    /** 组装 /generate 的 JSON 请求体。 */
    fun buildRequestBody(
        prompt: String,
        negativePrompt: String,
        settings: LocalDreamSettings,
        seed: Long,
    ): String = buildJsonObject {
        put("prompt", prompt)
        put("negative_prompt", negativePrompt)
        put("steps", settings.steps)
        put("cfg", settings.cfgScale)
        put("width", settings.width)
        put("height", settings.height)
        put("scheduler", settings.scheduler)
        put("use_opencl", settings.useOpencl)
        put("seed", seed)
        put("output_format", "png")
    }.toString()
}

/**
 * 本地 MNN（OpenCL）生图适配器：进程管理在 [LocalDreamCore]，这里负责
 * 状态、模型目录列举与 /generate SSE 流式生成。契约与 ComfyUiAdapter
 * 一致（Completed 携带 base64 PNG）。
 */
class LocalDreamAdapter(
    private val modelsRoot: File?,
    private val clientFactory: () -> OkHttpClient = { defaultClient() },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.LOCAL_DREAM
    override val displayName: String = "本地生图（MNN）"
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.Images)

    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        if (!LocalDreamCore.isBinaryAvailable()) {
            return ProviderStatus(available = false, message = "核心二进制缺失（libstable_diffusion_core.so）")
        }
        val detail = when (val s = LocalDreamCore.state.value) {
            is LocalDreamCoreState.Ready -> "，引擎运行中（127.0.0.1:${s.port}，${s.modelDirName}）"
            is LocalDreamCoreState.Starting -> "，引擎启动中…"
            is LocalDreamCoreState.Generating -> "，正在生成（${s.step}/${s.totalSteps} 步）"
            is LocalDreamCoreState.Converting -> "，模型转换中：${s.line}"
            is LocalDreamCoreState.Failed -> "，${s.reason}"
            LocalDreamCoreState.Idle -> ""
        }
        return ProviderStatus(available = true, message = "本地 MNN 引擎就绪$detail")
    }

    /** 已完成转换的模型目录（存在 finished 标记）。 */
    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> {
        val root = modelsRoot ?: return emptyList()
        val dirs = root.listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs
            .filter { File(it, "finished").isFile }
            .sortedByDescending { it.lastModified() }
            .map { dir ->
                val weightMb = dir.listFiles().orEmpty().sumOf { it.length() } / (1024 * 1024)
                ProviderModel(
                    id = dir.name,
                    displayName = "${dir.name}（${weightMb} MB）",
                    capabilities = capabilities,
                )
            }
    }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val settings = request.metadata[SETTINGS_METADATA_KEY]
            ?.let { element ->
                runCatching { json.decodeFromJsonElement(LocalDreamSettings.serializer(), element) }.getOrNull()
            }
            ?: LocalDreamSettings()

        if (settings.modelDirName.isBlank()) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "local_dream_model_missing",
                        message = "未选择本地生图模型：请先在设置中导入并转换模型",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }
        val modelDir = modelsRoot?.let { File(it, settings.modelDirName) }
        if (modelDir == null || !File(modelDir, "finished").isFile) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "local_dream_model_missing",
                        message = "模型不存在或未完成转换：${settings.modelDirName}（请在设置中重新导入）",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }

        val promptText = request.prompt.messages
            .filter { it.role != MessageRole.System }
            .joinToString(" ") { it.content }
        val negativeOverride = request.metadata["negative_prompt"]?.jsonPrimitive?.contentOrNull
        val negativePrompt = negativeOverride?.takeIf { it.isNotBlank() } ?: settings.negativePrompt
        val seed = if (settings.seed >= 0) settings.seed else Random.nextLong(0, Long.MAX_VALUE)

        val port = try {
            LocalDreamCore.ensureStarted(modelDir)
        } catch (e: IllegalStateException) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "local_dream_start_failed",
                        message = e.message ?: "本地生图引擎启动失败",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }

        val client = clientFactory()
        val body = LocalDreamProtocol.buildRequestBody(promptText, negativePrompt, settings, seed)
        val call = client.newCall(
            Request.Builder()
                .url("http://127.0.0.1:$port/generate")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        )
        // 协程取消 → 断开连接；核心把客户端断连视为取消生成的信号，立即停止采样。
        coroutineContext[Job]?.invokeOnCompletion { if (!call.isCanceled()) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        GenerateChunk.Failed(
                            TellevError(
                                code = "local_dream_request_failed",
                                message = "生图请求失败：HTTP ${response.code}",
                                retryable = true,
                            ),
                        ),
                    )
                    return@flow
                }
                val source = response.body?.source() ?: return@flow
                var eventName: String? = null
                while (true) {
                    coroutineContext.ensureActive()
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event: ") -> eventName = line.removePrefix("event: ").trim()

                        line.startsWith("data: ") -> {
                            when (val event = LocalDreamProtocol.parse(eventName, line)) {
                                is LocalDreamProtocol.Event.Progress -> LocalDreamCore.reportGenerationProgress(
                                    settings.modelDirName,
                                    event.step,
                                    event.totalSteps,
                                )

                                is LocalDreamProtocol.Event.Complete -> {
                                    android.util.Log.i(
                                        "tellev-img",
                                        "local-dream generated in ${event.generationTimeMs}ms",
                                    )
                                    emit(GenerateChunk.Completed(event.imageBase64, "image_generated"))
                                }

                                is LocalDreamProtocol.Event.Error -> emit(
                                    GenerateChunk.Failed(
                                        TellevError(
                                            code = "local_dream_failed",
                                            message = event.message,
                                            retryable = false,
                                        ),
                                    ),
                                )

                                null -> Unit
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "local_dream_failed",
                        message = e.message ?: "本地生图失败",
                        retryable = false,
                        causeType = e::class.simpleName,
                    ),
                ),
            )
        } finally {
            // 完成/失败/取消一律把引擎状态从 Generating 回落 Ready，
            // 否则状态流和保活通知会永远停在「生成中」。
            LocalDreamCore.reportGenerationFinished()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SETTINGS_METADATA_KEY = "local_dream_settings"
        val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // SSE 全程单连接：相邻事件之间可能相隔数十秒（单步 3~5s）。
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(900, TimeUnit.SECONDS)
            .build()
    }
}
