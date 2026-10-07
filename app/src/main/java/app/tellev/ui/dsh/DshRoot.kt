package app.tellev.ui.dsh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.core.guide.GuideKind
import app.tellev.core.guide.StartupGuide
import app.tellev.core.guide.decideStartupGuide
import app.tellev.core.guide.hasAnyUserData
import app.tellev.feature.guide.GuideOverlay
import app.tellev.feature.characters.CharacterDetailScreen
import app.tellev.feature.characters.CharactersListScreen
import app.tellev.feature.characters.CharactersViewModel
import app.tellev.feature.characters.CharactersViewModelFactory
import app.tellev.feature.community.CommunityScreen
import app.tellev.feature.creation.CreationEditorScreen
import app.tellev.feature.creation.CreationHomeScreen
import app.tellev.feature.creation.CreationViewModel
import app.tellev.feature.creation.CreationViewModelFactory
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.ChatViewModelFactory
import app.tellev.feature.extensions.ExtensionsScreen
import app.tellev.feature.extensions.ExtensionsViewModel
import app.tellev.feature.extensions.ExtensionsViewModelFactory
import app.tellev.feature.metrics.UsageStatsRoute
import app.tellev.feature.settings.SettingsScreen
import app.tellev.feature.settings.SettingsViewModel
import app.tellev.feature.settings.SettingsViewModelFactory
import app.tellev.feature.update.UpdateViewModel
import app.tellev.feature.update.UpdateViewModelFactory
import app.tellev.feature.world.WorldBookDetailScreen
import app.tellev.feature.world.WorldBookEntryEditScreen
import app.tellev.feature.world.WorldBooksListScreen
import app.tellev.feature.world.WorldViewModel
import app.tellev.feature.world.WorldViewModelFactory
import app.tellev.ui.theme.isDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withContext

private enum class DshTab(val route: String, val labelRes: Int, val icon: ImageVector) {
    Workshop("creation/home", R.string.nav_tab_workshop, Icons.Default.AutoFixHigh),
    Community("community", R.string.nav_tab_community, Icons.Default.Forum),
    Characters("characters", R.string.nav_tab_characters, Icons.Default.PlayCircle),
    Settings("settings", R.string.nav_tab_settings, Icons.Default.Settings),
}

/** 顶层路由（保底提交 c9f4ad1 的语义：这些叶子保留底栏）。 */
internal fun isDshTopLevel(route: String?): Boolean = route in setOf(
    "characters/list", "creation/home", "community", "settings", "chat",
)

internal fun shouldConfirmDshExit(route: String?): Boolean = isDshTopLevel(route)

/**
 * dsh 外壳：底部四 tab（角色工坊/类脑/角色卡/设置），聊天不再是 tab——
 * 从角色卡点进 DshChatScreen（自带图五抽屉）。
 * 旧 TellevRoot 不复存在；启动弹窗（更新/指引/群通知）这轮不移植。
 */
@Composable
fun DshRoot() {
    val graph = LocalTellevGraph.current
    // dsh token 随应用的主题模式切换；这里只提供调色板（不动 MaterialTheme），
    // 底栏等 dsh 元素吃到正确底色，其余 tab 继续用各自的强调色主题。
    val themeMode by graph.themeModeFlow.collectAsState()
    val dshDark = themeMode.isDarkTheme(isSystemInDarkTheme())
    ProvideDshPalette(darkTheme = dshDark) {
        DshRootContent()
    }
}

@Composable
private fun DshRootContent() {
    val graph = LocalTellevGraph.current
    val navController = rememberNavController()

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(
            dataStore = graph.dataStore,
            providerRegistry = graph.providerRegistry,
            promptEngine = graph.promptEngine,
            secretStore = graph.secretStore,
            extensionHost = graph.extensionHost,
            permissionManager = graph.permissionManager,
            externalChatWritePort = graph.apiRouter.externalChatWrites,
            imageDownloader = graph.imageDownloader,
        ),
    )
    val charactersViewModel: CharactersViewModel = viewModel(
        factory = CharactersViewModelFactory(graph.dataStore, graph.importedCardSignal),
    )
    val worldViewModel: WorldViewModel = viewModel(
        factory = WorldViewModelFactory(graph.dataStore),
    )
    val creationViewModel: CreationViewModel = viewModel(
        factory = CreationViewModelFactory(
            root = androidx.compose.ui.platform.LocalContext.current.filesDir.resolve("ai-creation"),
            store = graph.dataStore,
            secrets = graph.secretStore,
            providers = graph.providerRegistry,
            imageDownloader = graph.imageDownloader,
        ),
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(
            dataStore = graph.dataStore,
            providerRegistry = graph.providerRegistry,
            secretStore = graph.secretStore,
            appPreferences = graph.appPreferences,
            themeModeFlow = graph.themeModeFlow,
            themeAccentFlow = graph.themeAccentFlow,
            chatBubbleAlphaFlow = graph.chatBubbleAlphaFlow,
            chatFontSizeSpFlow = graph.chatFontSizeSpFlow,
        ),
    )
    val extensionsViewModel: ExtensionsViewModel = viewModel(
        factory = ExtensionsViewModelFactory(
            dataStore = graph.dataStore,
            extensionHost = graph.extensionHost,
            permissionManager = graph.permissionManager,
            settingsStore = graph.extensionSettingsStore,
            promptEngine = graph.promptEngine,
        ),
    )
    val activityContext = androidx.compose.ui.platform.LocalContext.current
    val packageInfo = remember(activityContext) {
        activityContext.packageManager.getPackageInfo(activityContext.packageName, 0)
    }
    val updateViewModel: UpdateViewModel = viewModel(
        factory = UpdateViewModelFactory(
            appContext = activityContext.applicationContext,
            checker = graph.updateChecker,
            preferences = graph.appPreferences,
            currentVersion = packageInfo.versionName ?: "0.0.0",
        ),
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = DshTab.entries.find { tab ->
        currentRoute == tab.route || currentRoute?.startsWith("${tab.route}/") == true ||
            currentRoute?.startsWith("characters/") == true && tab == DshTab.Characters
    } ?: DshTab.Characters

    // ── 启动弹窗栈（第五轮重制时断掉，本轮按原语义补回）──────────────
    // 冷启动检查 GitHub 新版本；全新安装/覆盖升级给指引覆盖层；升级提示、
    // QQ 群通知、更新弹窗排队展示，一次最多弹一个。
    val currentVersion = packageInfo.versionName ?: "0.0.0"
    // 首次打开指引：覆盖升级看「本次更新了什么」，全新安装看新手引导。
    // 判定放在 IO 上——「全新安装」分支要枚举磁盘目录。
    var startupGuide by rememberSaveable { mutableStateOf<StartupGuide?>(null) }
    var manualGuide by rememberSaveable { mutableStateOf<GuideKind?>(null) }
    var showPresetLimitUpgradeNotice by rememberSaveable { mutableStateOf(false) }
    var showQqGroupNotice by rememberSaveable { mutableStateOf(false) }
    var presetFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var showExitConfirmation by rememberSaveable { mutableStateOf(false) }
    // 聊天式创建屏的 JSON 导出中转（SAF 选完 Uri 再写）。
    var pendingCreationExport by remember { mutableStateOf<ByteArray?>(null) }
    // 层级化返回：只有导航栈到栈底（当前页无上级可 pop）时，系统返回才
    // 是「退出应用」语义，弹确认；栈里还有页面时返回键交给 NavController
    // 正常回退，不确认。
    val canPopBackStack = navController.previousBackStackEntry != null
    val exitConfirmationEnabled = !canPopBackStack &&
        shouldConfirmDshExit(currentRoute) &&
        startupGuide == null && manualGuide == null &&
        !showPresetLimitUpgradeNotice && !showQqGroupNotice
    LaunchedEffect(Unit) { updateViewModel.checkOnLaunch() }
    LaunchedEffect(packageInfo.firstInstallTime, packageInfo.lastUpdateTime) {
        showPresetLimitUpgradeNotice = graph.appPreferences.shouldShowPresetLimitUpgradeNotice(
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
        )
    }
    LaunchedEffect(Unit) { showQqGroupNotice = graph.appPreferences.shouldShowQqGroupNotice() }
    LaunchedEffect(packageInfo.firstInstallTime, packageInfo.lastUpdateTime) {
        startupGuide = withContext(Dispatchers.IO) {
            decideStartupGuide(
                currentVersion = currentVersion,
                lastGuideVersion = graph.appPreferences.updateGuideShownVersion,
                onboardingShown = graph.appPreferences.onboardingShown,
                firstInstallTime = packageInfo.firstInstallTime,
                lastUpdateTime = packageInfo.lastUpdateTime,
                hasUserData = { hasAnyUserData(graph.dataStore.layout) },
            )
        }
    }

    fun navigateToSettings() {
        navController.navigate(DshTab.Settings.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }


    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (isDshTopLevel(currentRoute)) {
                NavigationBar(containerColor = Dsh.bgSurface, tonalElevation = 0.dp) {
                    DshTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Dsh.blue,
                                selectedTextColor = Dsh.blue,
                                indicatorColor = Dsh.blue.copy(alpha = 0.12f),
                                unselectedIconColor = Dsh.textTertiary,
                                unselectedTextColor = Dsh.textTertiary,
                            ),
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                            label = { Text(stringResource(tab.labelRes), fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = DshTab.Characters.route,
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            fun NavGraphBuilder.page(route: String, arguments: List<NamedNavArgument> = emptyList(), block: @Composable (NavBackStackEntry) -> Unit) {
                composable(route, arguments) { entry ->
                    // Compose gives priority to the LAST registered BackHandler.
                    // Registering the exit confirmation BEFORE the page content lets
                    // page-level handlers (editors' unsaved-changes guards, etc.) win;
                    // this handler only fires when no page handler is enabled.
                    BackHandler(enabled = exitConfirmationEnabled) { showExitConfirmation = true }
                    block(entry)
                }
            }

            page("chat") {
                // 聊天子树整体套 DshTheme：对话框/菜单/输入框等 Material 组件统一吃
                // dsh 配色与圆角，不再出现应用强调色主题与 dsh 弹层混搭的两种风格。
                DshTheme(darkTheme = LocalDshPalette.current.isDark) {
                    DshChatScreen(
                        viewModel = chatViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenWorldBooks = { navController.navigate("world/list") },
                        onOpenPlugins = { navController.navigate("extensions") },
                        onOpenImageGenSettings = { navController.navigate("settings/imagegen") },
                        onOpenUsageStats = { navController.navigate("settings/usage") },
                        onOpenSettings = { navigateToSettings() },
                    )
                }
            }

            navigation(startDestination = "characters/list", route = "characters") {
                page("characters/list") {
                    CharactersListScreen(
                        viewModel = charactersViewModel,
                        onCreateClick = { navController.navigate("characters/create") },
                        onCharacterClick = { id ->
                            // The chat screen renders chatViewModel; selecting on
                            // charactersViewModel only feeds the detail editor and
                            // left every card tap landing on the empty chat state.
                            chatViewModel.selectCharacter(id)
                            navController.navigate("chat")
                        },
                        onEditClick = { navController.navigate("characters/detail/$it") },
                        onEditWithAi = { navController.navigate("creation/edit/character/$it") },
                        onCreateWorldBookWithAi = { navController.navigate("creation/from-character/world/$it") },
                    )
                }
                page("characters/create") {
                    CharacterDetailScreen(
                        viewModel = charactersViewModel,
                        onBack = { navController.popBackStack() },
                        isCreating = true,
                        onCreated = { id ->
                            navController.navigate("characters/detail/$id") {
                                popUpTo("characters/create") { inclusive = true }
                            }
                        },
                    )
                }
                page(
                    route = "characters/detail/{characterId}",
                    arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("characterId").orEmpty()
                    androidx.compose.runtime.LaunchedEffect(id) { if (id.isNotBlank()) charactersViewModel.selectCharacter(id) }
                    CharacterDetailScreen(
                        viewModel = charactersViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            navigation(startDestination = "world/list", route = "world") {
                page("world/list") {
                    WorldBooksListScreen(
                        viewModel = worldViewModel,
                        onCreateWithAi = { navController.navigate("creation/chat") },
                        onEditWithAi = { navController.navigate("creation/edit/world/$it") },
                        onBookClick = { bookId ->
                            worldViewModel.selectBook(bookId)
                            navController.navigate("world/book/$bookId")
                        },
                    )
                }
                page(
                    route = "world/book/{bookId}",
                    arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
                ) { entry ->
                    val bookId = entry.arguments?.getString("bookId").orEmpty()
                    androidx.compose.runtime.LaunchedEffect(bookId) { if (bookId.isNotBlank()) worldViewModel.selectBook(bookId) }
                    WorldBookDetailScreen(
                        viewModel = worldViewModel,
                        onBack = { navController.popBackStack() },
                        onEditEntry = { entryId -> navController.navigate("world/book/$bookId/entry/$entryId") },
                    )
                }
                page(
                    route = "world/book/{bookId}/entry/{entryId}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("entryId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val bookId = entry.arguments?.getString("bookId").orEmpty()
                    val entryId = entry.arguments?.getString("entryId").orEmpty()
                    androidx.compose.runtime.LaunchedEffect(bookId, entryId) {
                        if (bookId.isNotBlank() && entryId.isNotBlank()) worldViewModel.openEntry(bookId, entryId)
                    }
                    WorldBookEntryEditScreen(viewModel = worldViewModel, onBack = { navController.popBackStack() })
                }
            }

            page("creation/home") {
                CreationHomeScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenEditor = { navController.navigate("creation/editor") },
                )
            }
            // 聊天式创建（PRD 主机屏）：角色卡会话以聊天气泡/输入框/流式完成；
            // 世界书会话与手动编辑仍走旧编辑器（高级资产在那里）。
            page("creation/chat") {
                // 保存前没有 current 会话（首页直接进来）→ 起一个新角色卡会话。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (creationViewModel.state.value.current == null) {
                        creationViewModel.start(app.tellev.feature.creation.CreationKind.Character)
                    }
                }
                val jsonSaver = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    pendingCreationExport?.let { bytes ->
                        activityContext.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    pendingCreationExport = null
                }
                DshCreationChatScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenManualEditor = { navController.navigate("creation/editor") },
                    onExportJson = { bytes -> pendingCreationExport = bytes; jsonSaver.launch("character.json") },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) charactersViewModel.loadCharacters()
                        else worldViewModel.loadBookSummaries()
                        navController.popBackStack()
                    },
                )
            }
            page(
                route = "creation/edit/character/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.StringType }),
            ) { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                androidx.compose.runtime.LaunchedEffect(cardId) { if (cardId.isNotBlank()) creationViewModel.startFromCharacter(cardId) }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) charactersViewModel.loadCharacters()
                        else worldViewModel.loadBookSummaries()
                    },
                )
            }
            page(
                route = "creation/edit/world/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                androidx.compose.runtime.LaunchedEffect(bookId) { if (bookId.isNotBlank()) creationViewModel.startFromWorldBook(bookId) }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) charactersViewModel.loadCharacters()
                        else worldViewModel.loadBookSummaries()
                    },
                )
            }
            page(
                route = "creation/from-character/world/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.StringType }),
            ) { entry ->
                val cardId = entry.arguments?.getString("cardId").orEmpty()
                androidx.compose.runtime.LaunchedEffect(cardId) { if (cardId.isNotBlank()) creationViewModel.startWorldBookFromCharacter(cardId) }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.WorldBook) worldViewModel.loadBookSummaries()
                    },
                )
            }
            page("creation/editor") {
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) charactersViewModel.loadCharacters()
                        else worldViewModel.loadBookSummaries()
                    },
                )
            }

            page("community") {
                CommunityScreen(dataStore = graph.dataStore, onImported = { charactersViewModel.loadCharacters() })
            }
            page("extensions") {
                ExtensionsScreen(viewModel = extensionsViewModel, onBack = { navController.popBackStack() })
            }
            page(DshTab.Settings.route) {
                // dsh Models 页语义：模型配置在设置页内就地弹层编辑（dsh+bre 布局），
                // 优化提示词的写作助手设置也移到这里。
                var showModelConfig by rememberSaveable { mutableStateOf(false) }
                var showOptimizer by rememberSaveable { mutableStateOf(false) }
                val chatState by chatViewModel.uiState.collectAsState()
                Column {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        updateViewModel = updateViewModel,
                        onOpenProviderSettings = { navController.navigate("settings/providers") },
                        onOpenImageGenSettings = { navController.navigate("settings/imagegen") },
                        onOpenUsageStats = { navController.navigate("settings/usage") },
                        onOpenExtensions = { navController.navigate("extensions") },
                        onOpenModelConfig = { showModelConfig = true },
                        onOpenPromptOptimizer = { showOptimizer = true },
                        onOpenGuide = { manualGuide = it },
                        presetFocusRequest = presetFocusRequest,
                    )
                }
                if (showModelConfig) {
                    val family = app.tellev.core.provider.ReasoningSupport.familyFor(
                        chatState.providerConfig?.providerType ?: chatState.selectedProvider,
                        chatState.providerConfig?.options ?: JsonObject(emptyMap()),
                    )
                    val effortLevels = remember(family) { app.tellev.core.provider.ReasoningSupport.uiLevels(family) }
                    val configModelId = chatState.providerConfig?.model.orEmpty()
                    var showProfile by remember { mutableStateOf(false) }
                    var profileState by remember(configModelId) {
                        mutableStateOf(chatViewModel.modelReasoningProfile(configModelId))
                    }
                    val suggestion = remember(configModelId) { chatViewModel.modelReasoningSuggestion(configModelId) }
                    DshModelConfigSheet(
                        providerLabel = chatState.providerConfig?.providerType ?: chatState.selectedProvider,
                        baseUrl = chatState.providerConfig?.baseUrl.orEmpty(),
                        apiKey = chatState.providerConfig?.apiKey.orEmpty(),
                        model = chatState.providerConfig?.model.orEmpty(),
                        effortLevels = effortLevels,
                        defaultEffort = app.tellev.core.provider.ReasoningSupport.sessionOverrideFrom(chatState.currentSession?.metadata),
                        profileSummary = profileState?.efforts?.keys?.joinToString("/")?.ifBlank { null }
                            ?: stringResource(R.string.dsh_profile_none),
                        onOpenProfile = { showProfile = true },
                        onTest = {},
                        onSave = { url, key, model, effort ->
                            chatViewModel.saveModelConfig(url, key, model, effort)
                            showModelConfig = false
                        },
                        onDismiss = { showModelConfig = false },
                    )
                    if (showProfile && configModelId.isNotBlank()) {
                        DshModelProfileEditor(
                            modelId = configModelId,
                            initial = profileState,
                            suggestion = suggestion,
                            onAutoAdapt = {
                                val ok = chatViewModel.autoAdaptModelReasoningProfile(configModelId)
                                if (ok) {
                                    profileState = app.tellev.core.provider.ModelReasoningProfile(
                                        efforts = suggestion.efforts.orEmpty(),
                                        defaultEffort = suggestion.defaultEffort,
                                        contextWindow = suggestion.contextWindow,
                                        maxTokens = suggestion.maxTokens,
                                        source = "knowledge",
                                    )
                                }
                                ok
                            },
                            onSave = { profile ->
                                chatViewModel.saveModelReasoningProfile(configModelId, profile)
                                profileState = profile
                                showProfile = false
                            },
                            onDismiss = { showProfile = false },
                        )
                    }
                }
                if (showOptimizer) {
                    // 只留一个动画（生成中），无其它动效。
                    app.tellev.ui.PromptOptimizationDialog(
                        providerLabel = chatState.providerConfig?.providerType,
                        onRun = { options, onPreview, onDone ->
                            chatViewModel.optimizeDraft("", options, onPreview, onDone)
                        },
                        onCancelRun = { chatViewModel.cancelPromptOptimization() },
                        onApply = { },
                        onDismiss = { showOptimizer = false },
                    )
                }
            }
            page("settings/usage") { UsageStatsRoute(onBack = { navController.popBackStack() }) }
            page("settings/imagegen") {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    onOpenProviderSettings = {},
                    imageGenDetailsOnly = true,
                    onBack = { navController.popBackStack() },
                )
            }
            page("settings/providers") {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    onOpenProviderSettings = {},
                    providerDetailsOnly = true,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    // 首次打开指引排在所有启动弹窗最前面：它是本版本发布的主角。看到它时
    // 下面几个通知与更新提示都让位，保证一次只弹一个。
    val guide = startupGuide
    if (guide != null) {
        GuideOverlay(
            kind = when (guide) {
                StartupGuide.Onboarding -> GuideKind.Onboarding
                StartupGuide.UpdateGuide -> GuideKind.Update
            },
            onDismiss = {
                if (guide == StartupGuide.Onboarding) {
                    graph.appPreferences.markOnboardingShown(currentVersion)
                } else {
                    graph.appPreferences.markUpdateGuideShown(currentVersion)
                }
                startupGuide = null
            },
        )
    }

    // 「设置 → 关于」里的重看入口。指引层只在这一层（Tab 栏之上）挂载：设置页的
    // 内容区盖不住底栏，所以那边只发请求，不自己渲染。
    manualGuide?.let { kind ->
        GuideOverlay(kind = kind, onDismiss = { manualGuide = null })
    }

    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text(stringResource(R.string.ui_exit_title)) },
            text = { Text(stringResource(R.string.ui_exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmation = false
                    activityContext.hostActivity()?.finish()
                }) { Text(stringResource(R.string.ui_exit_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text(stringResource(R.string.ui_exit_no))
                }
            },
        )
    }

    if (showPresetLimitUpgradeNotice && startupGuide == null) {
        fun closeNotice() {
            graph.appPreferences.markPresetLimitUpgradeNoticeHandled()
            showPresetLimitUpgradeNotice = false
        }
        AlertDialog(
            onDismissRequest = ::closeNotice,
            title = { Text(stringResource(R.string.nav_preset_limit_title)) },
            text = {
                Text(
                    stringResource(R.string.nav_preset_limit_message),
                )
            },
            dismissButton = {
                TextButton(onClick = ::closeNotice) { Text(stringResource(R.string.nav_later)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeNotice()
                        presetFocusRequest += 1
                        navigateToSettings()
                    },
                ) { Text(stringResource(R.string.nav_go_to_preset_settings)) }
            },
        )
    }

    // Queued after the guide and the notices above so at most one dialog is up
    // at a time.
    if (showQqGroupNotice && !showPresetLimitUpgradeNotice && startupGuide == null) {
        val clipboard = LocalClipboardManager.current
        fun closeNotice() {
            graph.appPreferences.markQqGroupNoticeHandled()
            showQqGroupNotice = false
        }
        AlertDialog(
            onDismissRequest = ::closeNotice,
            title = { Text(stringResource(R.string.nav_qq_group_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.qq_group_qrcode),
                        contentDescription = stringResource(R.string.nav_qq_qrcode_desc),
                        modifier = Modifier
                            .size(216.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.nav_qq_group_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.nav_qq_group_number),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.nav_qq_group_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = ::closeNotice) { Text(stringResource(R.string.nav_got_it)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString("754350480"))
                        Toast.makeText(activityContext, activityContext.getString(R.string.nav_group_number_copied), Toast.LENGTH_SHORT).show()
                        closeNotice()
                    },
                ) { Text(stringResource(R.string.nav_copy_group_number)) }
            },
        )
    }

    // New-version dialog: queued after the notices above so only one dialog
    // shows at a time. Dismissal is per-version and per-process — the next
    // cold start re-checks and re-prompts until the user updates.
    val updateState by updateViewModel.uiState.collectAsState()
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingUpdate = updateState.pendingUpdate
    if (
        pendingUpdate != null &&
        dismissedUpdateVersion != pendingUpdate.version &&
        !showPresetLimitUpgradeNotice && !showQqGroupNotice && startupGuide == null
    ) {
        AlertDialog(
            onDismissRequest = { dismissedUpdateVersion = pendingUpdate.version },
            title = { Text(stringResource(R.string.nav_update_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.nav_update_message, pendingUpdate.tagName, currentVersion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pendingUpdate.apkSize > 0) {
                        val apkSizeText = "%.1f".format(pendingUpdate.apkSize / 1024f / 1024f)
                        Text(
                            text = stringResource(R.string.nav_update_apk_size, apkSizeText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pendingUpdate.releaseNotes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pendingUpdate.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dismissedUpdateVersion = pendingUpdate.version }) {
                    Text(stringResource(R.string.nav_later))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissedUpdateVersion = pendingUpdate.version
                        updateViewModel.downloadAndInstall()
                        Toast.makeText(activityContext, activityContext.getString(R.string.nav_update_download_started), Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.nav_update_now)) }
            },
        )
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}
