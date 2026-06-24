package app.tellev.feature.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionManifest
import app.tellev.core.extension.ExtensionPermission
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.regex.CharacterRegexApplier
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

data class ExtensionInfo(
    val id: String,
    val name: String,
    val description: String,
    val loaded: Boolean,
    val permissions: List<String>,
    val locked: Boolean = false,
    val installed: Boolean = false,
)

data class CharacterAssetInfo(
    val characterId: String,
    val characterName: String,
    val worldBookId: String?,
    val regexScripts: Int,
    val regexScriptSummaries: List<CharacterRegexApplier.RegexScriptSummary>,
    val tavernHelperScripts: Int,
    val tavernHelperData: Int,
)

data class ExtensionsUiState(
    val extensions: List<ExtensionInfo> = emptyList(),
    val characterAssets: List<CharacterAssetInfo> = emptyList(),
    val disabledRegexScripts: Map<String, Set<String>> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ExtensionsViewModel(
    private val dataStore: StDataStore,
    private val extensionHost: ExtensionHost,
    private val permissionManager: ExtensionPermissionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtensionsUiState())
    val uiState: StateFlow<ExtensionsUiState> = _uiState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Permissions that are safe to auto-grant on extension install without
     * explicit runtime approval.  `Secrets`, `ProviderRequest`, and `Network`
     * are excluded — they gate API keys, paid API calls, and arbitrary
     * outbound traffic respectively, so they must go through the
     * `permission_requested` → UI prompt → `deliverPermissionResult` flow.
     */
    private val safeInstallPermissions: Set<ExtensionPermission> = setOf(
        ExtensionPermission.Storage,
        ExtensionPermission.UiPanel,
        ExtensionPermission.Clipboard,
    )

    /** Manifests for extensions discovered on disk, keyed by id. */
    private val installedManifests = mutableMapOf<String, ExtensionManifest>()

    /** Script source for installed extensions, keyed by id. */
    private val installedScripts = mutableMapOf<String, String>()

    init {
        loadExtensions()
        observePermissionRequests()
    }

    private fun loadExtensions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val assets = withContext(Dispatchers.IO) {
                    dataStore.bootstrap()
                    readCharacterAssets()
                }
                val disabledRegex = withContext(Dispatchers.IO) {
                    dataStore.readDisabledRegexScriptIds()
                }
                val installed = withContext(Dispatchers.IO) { scanInstalledExtensions() }
                _uiState.update {
                    it.copy(
                        extensions = builtInExtensions() + installed,
                        characterAssets = assets,
                        disabledRegexScripts = disabledRegex,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        extensions = builtInExtensions(),
                        isLoading = false,
                        error = "加载扩展资源失败：${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Toggle a non-locked extension.  Built-in locked modules cannot be
     * toggled.  Installed extensions are actually loaded into / unloaded
     * from the [ExtensionHost] WebView instead of just flipping a boolean.
     */
    fun toggleExtension(id: String) {
        val ext = _uiState.value.extensions.find { it.id == id } ?: return
        if (ext.locked) return

        viewModelScope.launch {
            runCatching {
                if (ext.loaded) {
                    extensionHost.unload(id)
                    updateExtension(id) { it.copy(loaded = false) }
                } else {
                    val manifest = installedManifests[id]
                    val script = installedScripts[id] ?: ""
                    if (manifest == null) {
                        _uiState.update { s -> s.copy(error = "未找到扩展清单：$id") }
                        return@runCatching
                    }
                    // Only pre-grant low-risk permissions (Storage, UiPanel,
                    // Clipboard).  High-risk permissions (Secrets,
                    // ProviderRequest, Network) require explicit runtime
                    // approval via requestPermissionAsync → permission_requested
                    // event → UI prompt → deliverPermissionResult.
                    val safePermissions = manifest.permissions.filter {
                        it in safeInstallPermissions
                    }
                    permissionManager.grantAll(id, safePermissions)
                    extensionHost.load(manifest, script)
                    updateExtension(id) { it.copy(loaded = true) }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = "扩展切换失败：${e.message}") }
            }
        }
    }

    fun refreshExtensions() {
        loadExtensions()
    }

    /**
     * Toggle a single character-card regex script on/off. The disabled set is
     * keyed per character (script ids are only unique within a card) and
     * persisted via [StDataStore.saveDisabledRegexScriptIds]; absent scripts
     * are active by default. Mirrors the world-book activation toggle.
     */
    fun toggleRegexScript(characterId: String, scriptId: String) {
        viewModelScope.launch {
            runCatching {
                val current = _uiState.value.disabledRegexScripts
                val charSet = current[characterId] ?: emptySet()
                val updatedSet = if (scriptId in charSet) charSet - scriptId else charSet + scriptId
                val updatedMap = if (updatedSet.isEmpty()) {
                    current - characterId
                } else {
                    current + (characterId to updatedSet)
                }
                dataStore.saveDisabledRegexScriptIds(updatedMap)
                _uiState.update { it.copy(disabledRegexScripts = updatedMap) }
            }.onFailure { e ->
                _uiState.update { it.copy(error = "更新正则开关失败：${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── permission request handling ────────────────────────────────────

    /**
     * Listen for `permission_requested` events fired by extensions via
     * `Tellev.requestPermissionAsync`.  If the extension's manifest
     * declares the requested permission, grant it and resolve the pending
     * JS Promise with `true`; otherwise resolve with `false`.  This keeps
     * the async permission flow closed-loop instead of hanging forever.
     */
    private fun observePermissionRequests() {
        viewModelScope.launch {
            extensionHost.events.collect { event ->
                if (event.name != "permission_requested") return@collect
                val extensionId = event.extensionId ?: return@collect
                val requestId = event.payload["requestId"]?.jsonPrimitive?.contentOrNull ?: return@collect
                val permName = event.payload["permission"]?.jsonPrimitive?.contentOrNull ?: return@collect
                val perm = runCatching { ExtensionPermission.valueOf(permName) }.getOrNull()
                val declared = installedManifests[extensionId]
                    ?.permissions
                    ?.contains(perm) == true

                runCatching {
                    if (declared && perm != null) {
                        permissionManager.grantPermission(extensionId, perm)
                        extensionHost.deliverPermissionResult(requestId, true)
                    } else {
                        extensionHost.deliverPermissionResult(requestId, false)
                    }
                }
            }
        }
    }

    // ── built-in compatibility modules ─────────────────────────────────

    private fun builtInExtensions(): List<ExtensionInfo> = listOf(
        ExtensionInfo(
            id = "tavern-helper-compat",
            name = "酒馆助手兼容",
            description = "SillyTavern 事件总线、getContext、TavernHelper、fetch /api/* 桥与角色卡脚本运行",
            loaded = true,
            permissions = listOf("WebView", "角色资源", "虚拟 API"),
            locked = true,
        ),
        ExtensionInfo(
            id = "character-regex",
            name = "角色卡正则",
            description = "自动读取并应用角色卡内嵌 regex_scripts",
            loaded = true,
            permissions = listOf("聊天显示", "角色资源"),
            locked = true,
        ),
        ExtensionInfo(
            id = "embedded-world-book",
            name = "角色卡世界书",
            description = "自动导入 character_book 并随角色注入提示词",
            loaded = true,
            permissions = listOf("提示词", "世界书"),
            locked = true,
        ),
    )

    // ── installed extension discovery ──────────────────────────────────

    /**
     * Scan the SillyTavern-compatible `extensions/` directory for
     * subdirectories containing a `manifest.json`.  Each manifest is
     * decoded into an [ExtensionManifest] and its entry script
     * (`script.js` or the `main` field) is read into memory so the host
     * can load it on demand.  Directories starting with `_` are reserved
     * (e.g. `_permissions`) and skipped.
     */
    private fun scanInstalledExtensions(): List<ExtensionInfo> {
        val root = dataStore.layout.extensions
        if (!Files.isDirectory(root)) return emptyList()
        val results = mutableListOf<ExtensionInfo>()
        Files.list(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isDirectory(it) }
                .filter { !it.fileName.toString().startsWith("_") }
                .forEach { dir ->
                    val manifestPath = dir.resolve("manifest.json")
                    if (!Files.isRegularFile(manifestPath)) return@forEach
                    val raw = runCatching {
                        Files.readAllBytes(manifestPath).toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@forEach
                    val parsed = runCatching {
                        json.decodeFromString(ExtensionManifest.serializer(), raw)
                    }.getOrNull() ?: return@forEach

                    // For installed extensions, the id is the directory name
                    // (ST manifests don't have an "id" field).
                    val dirName = dir.fileName.toString()
                    val manifest = parsed.copy(id = parsed.id.ifBlank { dirName })

                    // Resolve the entry script: prefer the manifest's "js"
                    // field (e.g. "dist/index.js"), then fall back to common
                    // filenames in the extension root.
                    val scriptFile = manifest.js
                        .takeIf { it.isNotBlank() }
                        ?.let { dir.resolve(it) }
                        ?.takeIf { Files.isRegularFile(it) }
                        ?: manifest.metadata["main"]?.jsonPrimitive?.contentOrNull
                            ?.let { dir.resolve(it) }
                            ?.takeIf { Files.isRegularFile(it) }
                        ?: dir.resolve("dist/index.js").takeIf { Files.isRegularFile(it) }
                        ?: dir.resolve("script.js").takeIf { Files.isRegularFile(it) }
                        ?: dir.resolve("index.js").takeIf { Files.isRegularFile(it) }

                    val script = scriptFile?.let {
                        runCatching { Files.readAllBytes(it).toString(Charsets.UTF_8) }.getOrNull()
                    } ?: ""

                    installedManifests[manifest.id] = manifest
                    installedScripts[manifest.id] = script

                    results.add(
                        ExtensionInfo(
                            id = manifest.id,
                            name = manifest.effectiveName,
                            description = manifest.description,
                            loaded = false,
                            permissions = manifest.permissions.map { it.name },
                            locked = false,
                            installed = true,
                        ),
                    )
                }
        }
        return results.sortedBy { it.name }
    }

    // ── character asset discovery ──────────────────────────────────────

    private fun readCharacterAssets(): List<CharacterAssetInfo> {
        val root = dataStore.layout.extensions.resolve("character-assets")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.iterator().asSequence()
                .filter { Files.isDirectory(it) }
                .mapNotNull { dir ->
                    val manifest = dir.resolve("manifest.json")
                    if (!Files.isRegularFile(manifest)) return@mapNotNull null
                    val obj = json.parseToJsonElement(manifest.toFile().readText()).jsonObject
                    val regexSummaries = readRegexScriptSummaries(dir.resolve("regex_scripts.json"))
                    CharacterAssetInfo(
                        characterId = obj.stringValue("character_id") ?: dir.fileName.toString(),
                        characterName = obj.stringValue("character_name") ?: dir.fileName.toString(),
                        worldBookId = obj.stringValue("world_book_id"),
                        regexScripts = obj.intValue("regex_scripts"),
                        regexScriptSummaries = regexSummaries,
                        tavernHelperScripts = obj.intValue("TavernHelper_scripts"),
                        tavernHelperData = obj.intValue("tavern_helper"),
                    )
                }
                .sortedBy { it.characterName }
                .toList()
        }
    }

    private fun readRegexScriptSummaries(path: Path): List<CharacterRegexApplier.RegexScriptSummary> {
        if (!Files.isRegularFile(path)) return emptyList()
        val array = runCatching { json.parseToJsonElement(path.toFile().readText()) as? JsonArray }
            .getOrNull() ?: return emptyList()
        return CharacterRegexApplier.summarizeScripts(array)
    }

    private fun updateExtension(id: String, transform: (ExtensionInfo) -> ExtensionInfo) {
        _uiState.update { state ->
            state.copy(extensions = state.extensions.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intValue(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: 0
}

class ExtensionsViewModelFactory(
    private val dataStore: StDataStore,
    private val extensionHost: ExtensionHost,
    private val permissionManager: ExtensionPermissionManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ExtensionsViewModel::class.java)) {
            return ExtensionsViewModel(dataStore, extensionHost, permissionManager) as T
        }
        throw IllegalArgumentException("未知 ViewModel 类型：${modelClass.name}")
    }
}
