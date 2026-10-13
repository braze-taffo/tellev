package app.tellev.core.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface SecretStore {
    val changes: Flow<String>
        get() = emptyFlow()

    suspend fun putSecret(id: String, value: String)

    /**
     * The value for [id], or null when the entry is absent **or unreadable**
     * (corrupt payload, lost or reset master key). Implementations must never
     * throw for either case: callers read before writing, so one corrupt entry
     * must not make its own overwrite impossible.
     */
    suspend fun readSecret(id: String): String?
    suspend fun deleteSecret(id: String)
    suspend fun listSecretIds(): List<String>
}

/**
 * 内部（框架自管）secret id：由 provider/image 配置链路写入，用户不应在设置页
 * 密钥区块看到或删除——误删任一项都会静默破坏所选服务商、自定义端点、生图引擎
 * 等状态（这些均在模型服务配置弹层内管理）。密钥区块只展示用户自定义条目。
 */
fun isInternalSecretId(id: String): Boolean =
    id.startsWith("provider-") ||
        id.startsWith("image-") ||
        id == "custom-provider-configs" ||
        id == "tellev-memory-settings-v1"
