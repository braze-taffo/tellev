package app.tellev.core.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * 每模型思考档案（dsh-better-reasoning-effort 的 reasoningEfforts 声明复刻）。
 *
 * bre 的核心资产：一个模型必须先声明「接受哪些档位、每个档位在线上发什么」，
 * 思考控制才能对野生中转生效。这里把该声明做成 tellev 的一等数据：
 * 档位 → 线上拼写的映射，null 拼写 = 该档不发任何字段（端点默认）。
 *
 * 档案独立于供应商存在（model-reasoning-profiles.json，非机密不进 SecretStore），
 * 生成链解析档位时：用户档案 > family 硬编码兜底（无档案行为与旧版完全一致）。
 */
@Serializable
data class ModelReasoningProfile(
    /** 档位 → 线上拼写。键是 ReasoningEffort 名（off/low/…）；值为 null 表示该档不发字段。 */
    val efforts: Map<String, String?> = emptyMap(),
    /**
     * 新会话起点档位（bre defaultEffort）：厂商文档记载的默认档。
     * 记忆回退链的一环；空 = 无默认。
     */
    val defaultEffort: String? = null,
    /** 参考上下文窗口（仅展示建议，绝不自动写进容量输入）。 */
    val contextWindow: Long? = null,
    /** 参考最大输出（仅展示建议）。 */
    val maxTokens: Long? = null,
    /** 档案来源：user（用户声明）/ knowledge（知识库建议已套用）。 */
    val source: String = "user",
)

/** bre UNSET_MARKER 的对应物：「用户明确清空」与「还没填」是两个状态。 */
@Serializable
data class ModelReasoningProfileStore(
    /** modelId → 档案。 */
    val profiles: Map<String, ModelReasoningProfile> = emptyMap(),
    /** 用户明确 unset 的模型（自动适配不得再给它们填建议）。 */
    val unset: Set<String> = emptySet(),
)

/** 供请求链把档案序列化进 metadata 的共享 Json 实例。 */
internal val json = Json { encodeDefaults = true }

/** model-reasoning-profiles.json 读写：损坏文件降级为空存储，绝不抛出。 */
object ModelReasoningProfiles {
    private const val FILE_NAME = "model-reasoning-profiles.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(root: Path): ModelReasoningProfileStore {
        val file = root.resolve(FILE_NAME).toFile()
        if (!file.isFile) return ModelReasoningProfileStore()
        return runCatching {
            json.decodeFromString(ModelReasoningProfileStore.serializer(), file.readText())
        }.getOrDefault(ModelReasoningProfileStore())
    }

    fun write(root: Path, store: ModelReasoningProfileStore): Boolean = runCatching {
        val payload = json.encodeToString(ModelReasoningProfileStore.serializer(), store)
        app.tellev.core.storage.DurableFileOps.write(
            root.resolve(FILE_NAME),
            payload.toByteArray(Charsets.UTF_8),
        )
    }.isSuccess

    /** 该模型档案声明的参考上下文窗口（无档案/未声明 → null）。 */
    fun contextWindowFor(root: Path, modelId: String): Long? = read(root).profiles[modelId]?.contextWindow

    /** 套用一份建议（自动适配）：unset 名单里的模型绝不覆盖（bre 标记位纪律）。 */
    fun withSuggestion(store: ModelReasoningProfileStore, modelId: String, suggestion: ModelReasoningProfile): ModelReasoningProfileStore {
        if (modelId in store.unset) return store
        if (store.profiles[modelId]?.source == "user") return store
        return store.copy(profiles = store.profiles + (modelId to suggestion.copy(source = "knowledge")))
    }
}
