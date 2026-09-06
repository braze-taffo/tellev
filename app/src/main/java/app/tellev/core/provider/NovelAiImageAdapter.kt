package app.tellev.core.provider

import app.tellev.core.model.MessageRole
import app.tellev.core.model.TellevError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * NovelAI image generation settings. Defaults mirror SillyTavern's
 * stable-diffusion extension for the "novel" source: scale 7, steps 20,
 * 512x512, karras scheduler (defaultSettings), and SillyTavern's default
 * prompt prefix / negative prompt which the extension combines into every
 * request (index.js defaultPrefix / defaultNegative).
 */
@Serializable
data class NovelAiImageSettings(
    val model: String = "nai-diffusion-4-5-full",
    val sampler: String = "k_euler_ancestral",
    val scheduler: String = "karras",
    val steps: Int = 20,
    val scale: Double = 7.0,
    val width: Int = 512,
    val height: Int = 512,
    /** -1 rolls a fresh seed on every generation (SillyTavern seed -1 default). */
    val seed: Long = -1L,
    val sm: Boolean = false,
    val smDyn: Boolean = false,
    val decrisper: Boolean = false,
    val varietyBoost: Boolean = false,
    val anlasGuard: Boolean = false,
    /** >1 runs NovelAI's /ai/upscale second pass (SillyTavern "Upscale by", default 1.0 = off). */
    val upscaleRatio: Double = 1.0,
    /** SillyTavern prompt_prefix: combined in front of every prompt; "{prompt}" splices it in place. */
    val promptPrefix: String = "best quality, absurdres, aesthetic,",
    /** SillyTavern negative_prompt: appended after any per-request negative. */
    val negativePrompt: String = "lowres, bad anatomy, bad hands, text, error, cropped, worst quality, " +
        "low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry",
) {
    companion object {
        /** SillyTavern loadNovelModels(): fixed catalogue, NovelAI exposes no listing endpoint. */
        val MODELS: List<Pair<String, String>> = listOf(
            "nai-diffusion-4-5-full" to "NAI Diffusion Anime V4.5 (Full)",
            "nai-diffusion-4-5-curated" to "NAI Diffusion Anime V4.5 (Curated)",
            "nai-diffusion-4-full" to "NAI Diffusion Anime V4 (Full)",
            "nai-diffusion-4-curated-preview" to "NAI Diffusion Anime V4 (Curated)",
            "nai-diffusion-3" to "NAI Diffusion Anime V3",
            "nai-diffusion-2" to "NAI Diffusion Anime V2",
            "nai-diffusion-furry-3" to "NAI Diffusion Furry V3",
        )

        /** SillyTavern loadNovelSamplers(). */
        val SAMPLERS = listOf(
            "k_euler_ancestral",
            "k_euler",
            "k_dpmpp_2m",
            "k_dpmpp_sde",
            "k_dpmpp_2s_ancestral",
            "k_dpm_fast",
            "ddim",
        )

        /** SillyTavern loadNovelSchedulers(). */
        val SCHEDULERS = listOf("karras", "native", "exponential", "polyexponential")
    }
}

/**
 * Pure port of SillyTavern's NovelAI request assembly so identical inputs
 * produce a byte-identical /ai/generate-image body:
 * - combinePrefixes and the prompt/negative prefix combination come from
 *   sendGenerationRequest (public/scripts/extensions/stable-diffusion/index.js);
 * - parameter clamping (steps<=50, scheduler whitelist, sm disabling, Anlas
 *   guard) comes from getNovelParams (same file);
 * - the fixed body shape comes from src/endpoints/novelai.js POST
 *   /generate-image (params_version 3, prefer_brownian, ucPreset 0,
 *   qualityToggle false, V4 dual-caption structure, sigma 58/19 formula).
 */
internal object NovelAiImageProtocol {

    /** SillyTavern combinePrefixes: join two comma fragments, or splice [macro] in [str1]. */
    fun combinePrefixes(str1: String, str2: String, macro: String = ""): String {
        if (str2.isBlank()) return str1

        fun process(value: String): String =
            value.trim().replace(Regex("^,|,$"), "").trim()

        val a = process(str1)
        val b = process(str2)
        val result = if (macro.isNotEmpty() && a.contains(macro)) {
            val index = a.indexOf(macro)
            a.substring(0, index) + b + a.substring(index + macro.length)
        } else {
            "$a, $b,"
        }
        return process(result)
    }

    /** SillyTavern substituteParams subset that matters for image prompts: {{user}}/{{char}}. */
    fun substituteMacros(text: String, userName: String?, charName: String?): String {
        var out = text
        if (!userName.isNullOrBlank()) out = out.replace(Regex("\\{\\{user}}", RegexOption.IGNORE_CASE), userName)
        if (!charName.isNullOrBlank()) out = out.replace(Regex("\\{\\{char}}", RegexOption.IGNORE_CASE), charName)
        return out
    }

    /**
     * SillyTavern's prompt assembly: prefix + prompt ("{prompt}" macro splices
     * in place), then macro substitution over the combined string.
     */
    fun buildPrompt(settings: NovelAiImageSettings, rawPrompt: String, userName: String?, charName: String?): String =
        substituteMacros(combinePrefixes(settings.promptPrefix, rawPrompt, PROMPT_MACRO), userName, charName)

    /**
     * SillyTavern's negative assembly: the per-request negative first, then
     * the extension's global negative_prompt.
     */
    fun buildNegative(settings: NovelAiImageSettings, requestNegative: String, userName: String?, charName: String?): String =
        substituteMacros(combinePrefixes(requestNegative, settings.negativePrompt), userName, charName)

    /** SillyTavern calculateSkipCfgAboveSigma (novelai.js): sqrt(pixels/reference) * 19, 58 for V4.5. */
    fun skipCfgAboveSigma(width: Int, height: Int, model: String): Double {
        val magic = if (model.contains("nai-diffusion-4-5")) SIGMA_MAGIC_NUMBER_V4_5 else SIGMA_MAGIC_NUMBER
        val ratio = width.toDouble() * height / REFERENCE_PIXEL_COUNT
        return sqrt(ratio) * magic
    }

    /** Effective parameters after getNovelParams: steps clamped to 50, scheduler whitelisted, sm rules, Anlas guard. */
    fun resolveParams(settings: NovelAiImageSettings): NovelParams {
        var steps = minOf(settings.steps, MAX_STEPS)
        var width = settings.width
        var height = settings.height
        var sm = settings.sm
        var smDyn = settings.smDyn

        val scheduler = if (settings.scheduler in NovelAiImageSettings.SCHEDULERS) settings.scheduler else "karras"
        if (settings.sampler == "ddim" ||
            settings.model == "nai-diffusion-4-curated-preview" ||
            settings.model == "nai-diffusion-4-full"
        ) {
            sm = false
            smDyn = false
        }

        if (settings.anlasGuard) {
            if (width * height > ANLAS_MAX_PIXELS) {
                val ratio = sqrt(ANLAS_MAX_PIXELS.toDouble() / (width * height))
                var newWidth = (width * ratio).roundToInt()
                var newHeight = (height * ratio).roundToInt()
                if (newWidth % 64 != 0) newWidth -= newWidth % 64
                if (newHeight % 64 != 0) newHeight -= newHeight % 64
                while (newWidth * newHeight > ANLAS_MAX_PIXELS) {
                    if (newWidth > newHeight) newWidth -= 64 else newHeight -= 64
                }
                width = newWidth
                height = newHeight
            }
            if (steps > ANLAS_MAX_STEPS) steps = ANLAS_MAX_STEPS
        }
        return NovelParams(steps, width, height, sm, smDyn, scheduler)
    }

    /** The /ai/generate-image body, field-for-field as SillyTavern's novelai.js builds it. */
    fun buildRequestBody(
        settings: NovelAiImageSettings,
        params: NovelParams,
        prompt: String,
        negativePrompt: String,
        seed: Long,
    ): JsonObject = buildJsonObject {
        put("action", "generate")
        put("input", prompt)
        put("model", settings.model)
        put("parameters", buildJsonObject {
            put("params_version", 3)
            put("prefer_brownian", true)
            put("negative_prompt", negativePrompt)
            put("height", params.height)
            put("width", params.width)
            put("scale", settings.scale)
            put("seed", seed)
            put("sampler", settings.sampler)
            put("noise_schedule", params.scheduler)
            put("steps", params.steps)
            put("n_samples", 1)
            // NovelAI 官方客户端的字段垫背值，酒馆后端固定如此发送。
            put("ucPreset", 0)
            put("qualityToggle", false)
            put("add_original_image", false)
            put("controlnet_strength", 1)
            put("deliberate_euler_ancestral_bug", false)
            put("dynamic_thresholding", settings.decrisper)
            put("legacy", false)
            put("legacy_v3_extend", false)
            put("sm", params.sm)
            put("sm_dyn", params.smDyn)
            put("uncond_scale", 1)
            put(
                "skip_cfg_above_sigma",
                if (settings.varietyBoost) {
                    JsonPrimitive(skipCfgAboveSigma(params.width, params.height, settings.model))
                } else {
                    JsonNull
                },
            )
            put("use_coords", false)
            putJsonArray("characterPrompts") {}
            putJsonArray("reference_image_multiple") {}
            putJsonArray("reference_information_extracted_multiple") {}
            putJsonArray("reference_strength_multiple") {}
            put("v4_negative_prompt", buildJsonObject {
                put("caption", buildJsonObject {
                    put("base_caption", negativePrompt)
                    putJsonArray("char_captions") {}
                })
            })
            put("v4_prompt", buildJsonObject {
                put("caption", buildJsonObject {
                    put("base_caption", prompt)
                    putJsonArray("char_captions") {}
                })
                put("use_coords", false)
                put("use_order", true)
            })
        })
    }

    /** SillyTavern's extractFileFromZipBuffer for '.png': first entry matching, skipping __MACOSX. */
    fun extractFirstPng(zipBytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (!entry.isDirectory &&
                    !entry.name.startsWith("__MACOSX") &&
                    entry.name.endsWith(".png")
                ) {
                    return stream.readBytes()
                }
            }
        }
        return null
    }

    const val PROMPT_MACRO = "{prompt}"
    const val REFERENCE_PIXEL_COUNT = 1011712 // 832*1216 reference resolution
    const val SIGMA_MAGIC_NUMBER = 19
    const val SIGMA_MAGIC_NUMBER_V4_5 = 58
    const val MAX_STEPS = 50
    const val ANLAS_MAX_PIXELS = 1024 * 1024
    const val ANLAS_MAX_STEPS = 28
}

/** Effective generation parameters after SillyTavern's getNovelParams clamping. */
internal data class NovelParams(
    val steps: Int,
    val width: Int,
    val height: Int,
    val sm: Boolean,
    val smDyn: Boolean,
    val scheduler: String,
)

/**
 * NovelAI (novelai.net) image generation, mirroring SillyTavern's novel
 * source end to end: POST image.novelai.net/ai/generate-image with the fixed
 * V4-era body, unzip the returned archive, optionally run the /ai/upscale
 * second pass, and emit the PNG as base64 in a single Completed chunk (same
 * contract as ComfyUiAdapter).
 */
class NovelAiImageAdapter(
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProviderAdapter {
    override val id: String = ProviderCatalog.NOVELAI_IMAGE
    override val displayName: String = "NovelAI"
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.Images)

    /** Verifies the token against /user/subscription (SillyTavern's "View my Anlas" endpoint). */
    override suspend fun checkStatus(config: ProviderConfig): ProviderStatus {
        val token = config.apiKey?.takeIf { it.isNotBlank() }
            ?: return ProviderStatus(available = false, message = "未配置 NovelAI 令牌")
        val request = Request.Builder()
            .url("$API_BASE/user/subscription")
            .get()
            .header("Authorization", "Bearer $token")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val extra = if (response.code == 401) "：令牌无效或已过期" else ""
                    return@use ProviderStatus(available = false, message = "HTTP ${response.code}$extra")
                }
                val body = response.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val active = root?.get("active")?.jsonPrimitive?.booleanOrNull ?: true
                val tierName = when (root?.get("tier")?.jsonPrimitive?.intOrNull) {
                    3 -> "Opus"
                    2 -> "Scroll"
                    1 -> "Tablet"
                    else -> "未知档位"
                }
                if (active) {
                    ProviderStatus(available = true, message = "令牌有效，订阅生效（$tierName）")
                } else {
                    ProviderStatus(available = false, message = "令牌有效，但订阅未生效（$tierName）")
                }
            }
        }.getOrElse {
            ProviderStatus(available = false, message = "连接 NovelAI 失败：${it.message}")
        }
    }

    override suspend fun listModels(config: ProviderConfig): List<ProviderModel> =
        NovelAiImageSettings.MODELS.map { (id, name) ->
            ProviderModel(id = id, displayName = name, capabilities = capabilities)
        }

    override fun streamGenerate(config: ProviderConfig, request: GenerateRequest): Flow<GenerateChunk> = flow {
        val token = config.apiKey?.takeIf { it.isNotBlank() }
        if (token == null) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "novelai_token_missing",
                        message = "未配置 NovelAI 令牌：请先在设置中填写并保存",
                        retryable = false,
                    ),
                ),
            )
            return@flow
        }

        val settings = request.metadata[SETTINGS_METADATA_KEY]
            ?.let { element -> runCatching { json.decodeFromJsonElement(NovelAiImageSettings.serializer(), element) }.getOrNull() }
            ?: NovelAiImageSettings()
        val rawPrompt = request.prompt.messages
            .filter { it.role != MessageRole.System }
            .joinToString(" ") { it.content }
        val requestNegative = request.metadata["negative_prompt"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userName = request.metadata["macro_user"]?.jsonPrimitive?.contentOrNull
        val charName = request.metadata["macro_char"]?.jsonPrimitive?.contentOrNull
        val prompt = NovelAiImageProtocol.buildPrompt(settings, rawPrompt, userName, charName)
        val negative = NovelAiImageProtocol.buildNegative(settings, requestNegative, userName, charName)
        val params = NovelAiImageProtocol.resolveParams(settings)
        // SillyTavern backend: seed >= 0 fixed, else floor(random * 9999999999).
        val seed = if (settings.seed >= 0) settings.seed else Random.nextLong(0, MAX_SEED)
        val body = NovelAiImageProtocol.buildRequestBody(settings, params, prompt, negative, seed).toString()

        try {
            coroutineContext.ensureActive()
            val png = execute(token, "$IMAGE_BASE/ai/generate-image", body)

            val imageBytes = if (settings.upscaleRatio > 1.0) {
                // SillyTavern: upscale failure falls back to the original image.
                runCatching {
                    execute(
                        token,
                        "$API_BASE/ai/upscale",
                        buildJsonObject {
                            put("image", Base64.getEncoder().encodeToString(png))
                            put("height", params.height)
                            put("width", params.width)
                            put("scale", settings.upscaleRatio)
                        }.toString(),
                    )
                }.getOrDefault(png)
            } else {
                png
            }
            emit(GenerateChunk.Completed(Base64.getEncoder().encodeToString(imageBytes), "image_generated"))
        } catch (e: NovelAiHttpException) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = e.code,
                        message = e.message ?: "NovelAI 请求失败",
                        retryable = e.retryable,
                    ),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                GenerateChunk.Failed(
                    TellevError(
                        code = "provider_network",
                        message = e.message ?: "Network error",
                        retryable = true,
                        causeType = e::class.simpleName,
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /** POSTs [body], unzips the archive and returns the first PNG entry's bytes. */
    private suspend fun execute(token: String, url: String, body: String): ByteArray {
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build(),
        )
        // 协程取消 → 断开连接，停止等待 NovelAI 的长耗时生成。
        coroutineContext[Job]?.invokeOnCompletion { if (!call.isCanceled()) call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string().orEmpty()
                throw NovelAiHttpException(
                    code = "novelai_http_${response.code}",
                    message = friendlyMessage(response.code, text),
                    retryable = response.code in 429..599,
                )
            }
            val zip = response.body?.bytes()
                ?: throw NovelAiHttpException("novelai_no_image_data", "NovelAI 响应为空", retryable = true)
            return NovelAiImageProtocol.extractFirstPng(zip)
                ?: throw NovelAiHttpException(
                    "novelai_no_png",
                    "NovelAI 返回的压缩包中没有 PNG 图片",
                    retryable = false,
                )
        }
    }

    private fun friendlyMessage(code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return when {
            detail != null -> detail
            code == 401 -> "令牌无效或已过期"
            code == 402 -> "当前订阅不支持该请求（可能需要消耗 Anlas）"
            else -> "NovelAI 请求失败：HTTP $code"
        }
    }

    private class NovelAiHttpException(
        val code: String,
        message: String,
        val retryable: Boolean,
    ) : Exception(message)

    companion object {
        const val SETTINGS_METADATA_KEY = "novelai_settings"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val API_BASE = "https://api.novelai.net"
        private const val IMAGE_BASE = "https://image.novelai.net"
        private const val MAX_SEED = 9_999_999_999L // exclusive: [0, 9999999998], SillyTavern range
    }
}
