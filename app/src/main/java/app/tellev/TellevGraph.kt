package app.tellev

import android.content.Context
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.ExtensionSettingsStore
import app.tellev.core.extension.VirtualApiRouter
import app.tellev.core.extension.WebViewJsExtensionHost
import app.tellev.core.prompt.DefaultMacroEngine
import app.tellev.core.prompt.DefaultPromptEngine
import app.tellev.core.prompt.MacroEngine
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.AnthropicAdapter
import app.tellev.core.provider.AzureAdapter
import app.tellev.core.provider.GeminiAdapter
import app.tellev.core.provider.GoogleTranslateAdapter
import app.tellev.core.provider.HordeAdapter
import app.tellev.core.provider.KoboldAdapter
import app.tellev.core.provider.KoboldCppAdapter
import app.tellev.core.provider.LlamaCppAdapter
import app.tellev.core.provider.NovelAiAdapter
import app.tellev.core.provider.OllamaAdapter
import app.tellev.core.provider.OpenAiCompatibleAdapter
import app.tellev.core.provider.OpenAiImageAdapter
import app.tellev.core.provider.OpenAiSpeechAdapter
import app.tellev.core.provider.OpenRouterAdapter
import app.tellev.core.provider.ProviderCatalog
import app.tellev.core.provider.ProviderRegistry
import app.tellev.core.provider.StableDiffusionAdapter
import app.tellev.core.provider.TextGenAdapter
import app.tellev.core.security.AndroidKeystoreSecretStore
import app.tellev.core.security.SecretStore
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TellevGraph private constructor(
    val dataStore: StDataStore,
    val providerRegistry: ProviderRegistry,
    val secretStore: SecretStore,
    val promptEngine: PromptEngine,
    val macroEngine: MacroEngine,
    val extensionHost: ExtensionHost,
    val permissionManager: ExtensionPermissionManager,
    val apiRouter: VirtualApiRouter,
) {
    val importedCardSignal = MutableStateFlow(0L)
    companion object {
        fun create(context: Context): TellevGraph {
            val root = context.filesDir.toPath().resolve("st-data")
            val layout = StDirectoryLayout.fromRoot(root)

            val macroEngine = DefaultMacroEngine()
            val promptEngine = DefaultPromptEngine(macroEngine)

            val providerRegistry = ProviderRegistry(
                adapters = listOf(
                    OpenAiCompatibleAdapter(),
                    OpenAiCompatibleAdapter(
                        providerId = ProviderCatalog.DEEPSEEK,
                        providerDisplayName = "DeepSeek",
                        defaultModel = "deepseek-v4-flash",
                        modelsPath = "/models",
                        chatCompletionsPath = "/chat/completions",
                    ),
                    OpenAiCompatibleAdapter(
                        providerId = ProviderCatalog.VOLCENGINE_CODING_PLAN,
                        providerDisplayName = "火山引擎 Coding Plan",
                        modelsPath = "/models",
                        chatCompletionsPath = "/chat/completions",
                        supportsModelListing = false,
                    ),
                    AnthropicAdapter(),
                    GeminiAdapter(),
                    OpenRouterAdapter(),
                    OllamaAdapter(),
                    KoboldAdapter(),
                    KoboldCppAdapter(),
                    NovelAiAdapter(),
                    TextGenAdapter(),
                    AzureAdapter(),
                    HordeAdapter(),
                    LlamaCppAdapter(),
                    StableDiffusionAdapter(),
                    OpenAiImageAdapter(),
                    OpenAiSpeechAdapter(),
                    GoogleTranslateAdapter(),
                ),
            )

            val dataStore = FileStDataStore(layout)
            val secretStore = AndroidKeystoreSecretStore(context)

            // ── Extension layer assembly ───────────────────────────────
            // The WebView JS extension host, virtual API router, settings
            // store, and permission manager were previously dead code:
            // constructed nowhere and unreachable from the UI.  Wire them
            // up here so the 酒馆助手 compatibility layer is actually live.
            val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val extensionSettingsStore = ExtensionSettingsStore(layout.extensions)
            val permissionManager = ExtensionPermissionManager(
                persistenceDir = layout.extensions.resolve("_permissions"),
            )
            val apiRouter = VirtualApiRouter(dataStore, providerRegistry, secretStore)
            val extensionHost = WebViewJsExtensionHost(
                context = context,
                scope = extensionScope,
                apiRouter = apiRouter,
                settingsStore = extensionSettingsStore,
                permissionManager = permissionManager,
                macroEngine = macroEngine,
            )
            // Load persisted permission grants off the main thread.
            extensionScope.launch { permissionManager.load() }

            return TellevGraph(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                secretStore = secretStore,
                promptEngine = promptEngine,
                macroEngine = macroEngine,
                extensionHost = extensionHost,
                permissionManager = permissionManager,
                apiRouter = apiRouter,
            )
        }
    }
}
