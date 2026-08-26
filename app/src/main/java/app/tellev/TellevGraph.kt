package app.tellev

import android.content.Context
import app.tellev.core.extension.ExtensionHost
import app.tellev.core.extension.ExtensionSettingsStore
import app.tellev.core.extension.ExtensionPermissionManager
import app.tellev.core.extension.VirtualApiRouter
import app.tellev.core.extension.VariableStore
import app.tellev.core.extension.EjsTemplateSettings
import app.tellev.core.extension.TavernHelperSettings
import app.tellev.core.extension.WebViewJsExtensionHost
import app.tellev.core.prompt.DefaultMacroEngine
import app.tellev.core.prompt.DefaultPromptEngine
import app.tellev.core.prompt.MacroEngine
import app.tellev.core.prompt.PromptEngine
import app.tellev.core.provider.AnthropicAdapter
import app.tellev.core.provider.AzureAdapter
import app.tellev.core.provider.BetaRelayConfig
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
import app.tellev.core.storage.AppPreferences
import app.tellev.core.storage.FileStDataStore
import app.tellev.core.storage.StDataStore
import app.tellev.core.storage.StDirectoryLayout
import app.tellev.core.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TellevGraph private constructor(
    val dataStore: StDataStore,
    val providerRegistry: ProviderRegistry,
    val secretStore: SecretStore,
    val promptEngine: PromptEngine,
    val macroEngine: MacroEngine,
    val extensionHost: ExtensionHost,
    val permissionManager: ExtensionPermissionManager,
    val apiRouter: VirtualApiRouter,
    val extensionSettingsStore: ExtensionSettingsStore,
    val appPreferences: AppPreferences,
    val updateChecker: UpdateChecker,
) {
    val importedCardSignal = MutableStateFlow(0L)

    /**
     * Persist updated compat-module settings and push them to every
     * loaded extension WebView and to the [PromptEngine] so the change
     * takes effect immediately without an extension reload.
     *
     * Callers should call this from a coroutine scope (the methods are
     * suspend).
     */
    suspend fun applyEjsTemplateSettings(settings: EjsTemplateSettings) {
        extensionSettingsStore.saveEjsTemplateSettings(settings)
        (promptEngine as? DefaultPromptEngine)?.updateEjsSettings(settings)
        extensionHost.updateCompatModuleSettings(
            ejsSettings = settings,
            tavernHelperSettings = extensionSettingsStore.readTavernHelperSettings(),
        )
    }

    suspend fun applyTavernHelperSettings(settings: TavernHelperSettings) {
        extensionSettingsStore.saveTavernHelperSettings(settings)
        extensionHost.updateCompatModuleSettings(
            ejsSettings = extensionSettingsStore.readEjsTemplateSettings(),
            tavernHelperSettings = settings,
        )
    }
    companion object {
        fun create(context: Context): TellevGraph {
            val root = context.filesDir.toPath().resolve("st-data")
            val layout = StDirectoryLayout.fromRoot(root)

            val macroEngine = DefaultMacroEngine()
            val promptEngine = DefaultPromptEngine(macroEngine)
            val deepSeekClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val providerRegistry = ProviderRegistry(
                adapters = buildList {
                    if (BetaRelayConfig.enabled) {
                        val betaConfig = BetaRelayConfig.providerConfig()
                        add(
                            OpenAiCompatibleAdapter(
                                client = deepSeekClient,
                                providerId = ProviderCatalog.BETA_RELAY,
                                providerDisplayName = BetaRelayConfig.DISPLAY_NAME,
                                defaultModel = betaConfig.model,
                                supportsModelListing = false,
                                includeUsageByDefault = true,
                            ),
                        )
                    }
                    addAll(listOf(
                    OpenAiCompatibleAdapter(),
                    OpenAiCompatibleAdapter(
                        client = deepSeekClient,
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
                    ))
                },
            )

            val dataStore = FileStDataStore(layout)
            val secretStore = AndroidKeystoreSecretStore(context)

            // Update checker: short timeouts so a dead mirror is abandoned
            // quickly before falling back to the next one.
            val appPreferences = AppPreferences(context)
            val updateChecker = UpdateChecker(
                OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build(),
            )

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
            val apiRouter = VirtualApiRouter(dataStore, providerRegistry, secretStore, extensionSettingsStore)
            val variableStore = VariableStore(
                scope = extensionScope,
                settingsStore = extensionSettingsStore,
                settingsKey = WebViewJsExtensionHost.TAVERN_HELPER_VARS_KEY,
            )
            (macroEngine as? DefaultMacroEngine)?.variableStore = variableStore
            val extensionHost = WebViewJsExtensionHost(
                context = context,
                scope = extensionScope,
                apiRouter = apiRouter,
                settingsStore = extensionSettingsStore,
                permissionManager = permissionManager,
                macroEngine = macroEngine,
                variableStore = variableStore,
            )
            // Load persisted extension runtime state even when the user only
            // uses native prompt templates and never loads a JavaScript module.
            // Previously global variables were loaded lazily by the WebView
            // host, so {{getglobalvar::...}} started empty in that scenario.
            extensionScope.launch {
                variableStore.loadGlobal(
                    extensionSettingsStore.getSettings(WebViewJsExtensionHost.TAVERN_HELPER_VARS_KEY),
                )
                permissionManager.load()
            }

            // Load persisted EJS template settings so the prompt engine
            // respects the user's configuration from the first build call.
            extensionScope.launch {
                val ejsSettings = extensionSettingsStore.readEjsTemplateSettings()
                (promptEngine as? DefaultPromptEngine)?.updateEjsSettings(ejsSettings)
            }

            return TellevGraph(
                dataStore = dataStore,
                providerRegistry = providerRegistry,
                secretStore = secretStore,
                promptEngine = promptEngine,
                macroEngine = macroEngine,
                extensionHost = extensionHost,
                permissionManager = permissionManager,
                apiRouter = apiRouter,
                extensionSettingsStore = extensionSettingsStore,
                appPreferences = appPreferences,
                updateChecker = updateChecker,
            )
        }
    }
}
