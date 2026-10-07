package app.tellev.feature.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 会话级 token 用量累计（dsh tokenUsage 投影复刻）。
 *
 * 四个互斥桶持久化在会话 metadata.tellev_token_usage：uncachedInput /
 * cacheRead / cacheWrite / output。与明细 jsonl（最近 100 条）正交——
 * 这里是全量、无界、切会话即得的账本；明细只服务“今日/累计”全局视野
 * 与单次生成详情。
 *
 * 替换语义（dsh addReplacing）：重新生成/继续给同一消息产生新 swipe 时，
 * 该消息旧 swipe 的桶先从累计里减去，再加新样本——重刷绝不重复计数。
 * 每条消息自己那次生成的桶存于消息 metadata.tellev_turn_usage，键即
 * 消息 id，被删/被裁剪时由调用方同步扣回。
 */
internal object ChatTokenUsageLedger {

    const val SESSION_KEY = "tellev_token_usage"
    const val MESSAGE_KEY = "tellev_turn_usage"

    /** 四互斥桶（dsh TokenUsageProjection）。reasoning 含在 output 内不另计。 */
    data class Buckets(
        val uncachedInput: Long = 0L,
        val cacheRead: Long = 0L,
        val cacheWrite: Long = 0L,
        val output: Long = 0L,
    ) {
        val billedInput: Long get() = uncachedInput + cacheRead + cacheWrite
        val total: Long get() = billedInput + output

        fun plus(other: Buckets) = Buckets(
            uncachedInput = uncachedInput + other.uncachedInput,
            cacheRead = cacheRead + other.cacheRead,
            cacheWrite = cacheWrite + other.cacheWrite,
            output = output + other.output,
        )

        fun minus(other: Buckets) = Buckets(
            uncachedInput = (uncachedInput - other.uncachedInput).coerceAtLeast(0L),
            cacheRead = (cacheRead - other.cacheRead).coerceAtLeast(0L),
            cacheWrite = (cacheWrite - other.cacheWrite).coerceAtLeast(0L),
            output = (output - other.output).coerceAtLeast(0L),
        )

        fun isZero() = uncachedInput == 0L && cacheRead == 0L && cacheWrite == 0L && output == 0L
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 读会话累计桶；无记录/损坏 → 零桶。 */
    fun sessionBuckets(sessionMetadata: JsonObject?): Buckets {
        val obj = sessionMetadata?.get(SESSION_KEY) as? JsonObject ?: return Buckets()
        return runCatching {
            Buckets(
                uncachedInput = obj.long("uncachedInput") ?: 0L,
                cacheRead = obj.long("cacheRead") ?: 0L,
                cacheWrite = obj.long("cacheWrite") ?: 0L,
                output = obj.long("output") ?: 0L,
            )
        }.getOrDefault(Buckets())
    }

    /** 读一条消息自己那次生成的桶（重试替换的回退依据）。 */
    fun messageBuckets(messageMetadata: JsonObject?): Buckets {
        val obj = messageMetadata?.get(MESSAGE_KEY) as? JsonObject ?: return Buckets()
        return runCatching {
            Buckets(
                uncachedInput = obj.long("uncachedInput") ?: 0L,
                cacheRead = obj.long("cacheRead") ?: 0L,
                cacheWrite = obj.long("cacheWrite") ?: 0L,
                output = obj.long("output") ?: 0L,
            )
        }.getOrDefault(Buckets())
    }

    /** 读一条消息自己那次生成的思考子集（reasoning ≤ output，写入时已校验）。 */
    fun messageReasoning(messageMetadata: JsonObject?): Long {
        val obj = messageMetadata?.get(MESSAGE_KEY) as? JsonObject ?: return 0L
        return runCatching { obj.long("reasoning") ?: 0L }.getOrDefault(0L)
    }

    /**
     * 会话 metadata 的纯函数改写：先减 [previous]（同消息旧 swipe，可为零桶）
     * 再加 [next]（dsh addReplacing）。返回 null 表示无需变更。
     */
    fun sessionMetadataWith(
        sessionMetadata: JsonObject,
        previous: Buckets,
        next: Buckets,
    ): JsonObject? {
        val current = sessionBuckets(sessionMetadata)
        val updated = current.minus(previous).plus(next)
        if (updated == current) return null
        return buildJsonObject {
            sessionMetadata.forEach { (k, v) -> put(k, v) }
            put(SESSION_KEY, updated.toJson())
        }
    }

    /** 消息 metadata 的纯函数改写：记录本 swipe 的桶（reasoning 为思考子集小注）。 */
    fun messageMetadataWith(
        messageMetadata: JsonObject,
        buckets: Buckets,
        reasoning: Long = 0L,
    ): JsonObject = buildJsonObject {
        messageMetadata.forEach { (k, v) -> put(k, v) }
        put(MESSAGE_KEY, buildJsonObject {
            put("uncachedInput", buckets.uncachedInput)
            put("cacheRead", buckets.cacheRead)
            put("cacheWrite", buckets.cacheWrite)
            put("output", buckets.output)
            put("reasoning", reasoning)
        })
    }

    /** 从一次生成指标提取桶。只信 provider 上报；估算样本给零桶（不入账）。 */
    fun bucketsOf(metric: app.tellev.core.metrics.GenerationMetrics): Buckets {
        // prompt 未上报（isEstimate 的样本）：桶不可信，不计入会话累计。
        if (metric.promptTokens == null || metric.isEstimate) return Buckets()
        val prompt = metric.promptTokens.toLong()
        val read = (metric.cacheReadTokens ?: metric.cachedTokens ?: 0).toLong().coerceAtMost(prompt)
        val write = (metric.cacheCreationTokens ?: 0).toLong().coerceAtMost(prompt - read)
        val output = (metric.completionTokens ?: 0).toLong()
        return Buckets(
            uncachedInput = (prompt - read - write).coerceAtLeast(0L),
            cacheRead = read,
            cacheWrite = write,
            output = output,
        )
    }

    private fun Buckets.toJson(): JsonObject = buildJsonObject {
        put("uncachedInput", uncachedInput)
        put("cacheRead", cacheRead)
        put("cacheWrite", cacheWrite)
        put("output", output)
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { primitive ->
            primitive.longOrNull ?: primitive.content.toLongOrNull()
        }
}
