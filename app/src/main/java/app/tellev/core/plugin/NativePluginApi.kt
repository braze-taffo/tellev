package app.tellev.core.plugin

import app.tellev.core.extension.ExtensionPermission
import app.tellev.core.provider.GenerateRequest
import app.tellev.core.provider.ProviderConfig
import app.tellev.core.provider.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path

interface NativePlugin {
    val manifest: NativePluginManifest

    suspend fun onLoad(api: NativePluginApi)
    suspend fun onUnload()
}

interface NativePluginApi {
    suspend fun readAppFile(relativePath: String): ByteArray
    suspend fun writeAppFile(relativePath: String, bytes: ByteArray)
    suspend fun resolveDataPath(relativePath: String): Path
    suspend fun requestProvider(config: ProviderConfig, request: GenerateRequest): Flow<String>
    suspend fun getProviderStatus(config: ProviderConfig): ProviderStatus
    fun events(): Flow<NativePluginEvent>
    suspend fun emit(event: NativePluginEvent)
}

@Serializable
data class NativePluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val permissions: Set<ExtensionPermission>,
    val metadata: JsonObject = buildJsonObject { },
)

@Serializable
data class NativePluginEvent(
    val name: String,
    val pluginId: String,
    val payload: JsonObject = buildJsonObject { },
)

