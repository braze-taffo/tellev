package app.tellev.ui.dsh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.tellev.core.model.ReasoningEffort
import app.tellev.feature.guide.GuideOverlay
import app.tellev.feature.characters.CharacterDetailScreen
import app.tellev.feature.characters.CharactersListScreen
import app.tellev.feature.characters.CharactersViewModel
import app.tellev.feature.characters.CharactersViewModelFactory
import app.tellev.feature.community.CommunityScreen
import app.tellev.feature.creation.CreationEditorScreen
import app.tellev.feature.creation.CreationHomeScreen
import app.tellev.feature.creation.CreationKind
import app.tellev.feature.creation.CreationViewModel
import app.tellev.feature.creation.CreationViewModelFactory
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.ChatViewModelFactory
import app.tellev.feature.chat.forProvider
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
import app.tellev.ui.AppBackAction
import app.tellev.ui.appBackAction
import app.tellev.ui.isDshTopLevel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withContext

private enum class DshTab(val route: String, val labelRes: Int, val icon: ImageVector) {
    Workshop("creation/home", R.string.nav_tab_workshop, Icons.Default.AutoFixHigh),
    Community("community", R.string.nav_tab_community, Icons.Default.Forum),
    Characters("characters", R.string.nav_tab_characters, Icons.Default.PlayCircle),
    Settings("settings", R.string.nav_tab_settings, Icons.Default.Settings),
}

/**
 * dsh 外壳：底部四 tab（角色工坊/类脑/角色卡/设置），聊天不再是 tab——
 * 从角色卡点进 DshChatScreen（自带图五抽屉）。
 * 主题（DshTheme）已在 MainActivity 根层挂载，这里不再嵌套。
 */
@Composable
fun DshRoot() {
    DshRootContent()
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
            // 与聊天共用同一个明细 writer：制卡记录进同一份账，且不会互相覆盖。
            metricsStore = chatViewModel.metricsStoreForCreation,
        ),
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(
            dataStore = graph.dataStore,
            providerRegistry = graph.providerRegistry,
            secretStore = graph.secretStore,
            appPreferences = graph.appPreferences,
            themeModeFlow = graph.themeModeFlow,
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
    // 模型服务统一弹层：聊天抽屉「模型服务」（root 三服务行）、输入栏模型键
    // （直达「对话」段）与设置 tab「模型服务」卡的管理按钮共用这一份渲染；
    // null = 关闭。服务商管理（自定义端点 CRUD/高级设置）已并入弹层
    // 「对话 → 模型服务商」段，原设置页独立「模型配置」页已删。
    var modelServicesPane by rememberSaveable { mutableStateOf<String?>(null) }
    // 聊天式创建屏的 JSON 导出中转：先落 cacheDir，SAF 选完 Uri 再写到用户位置并清理——
    // 选择器期间旋转/进程死亡时 remember 的字节会丢，磁盘副本不能再丢。
    // 只存路径（rememberSaveable 跨配置变更存活）；离开页面不清理：那正是要保住的场景。
    var pendingCreationExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    // 聊天输入框草稿：提到根层，写作助手（输入栏入口 + 设置 tab 入口）才能共同读写
    // 同一个草稿——设置 tab 打开写作助手时聊天页不在栈上，弹层够不着聊天屏的局部状态。
    var chatDraft by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val shellScope = rememberCoroutineScope()
    // 会话是否打开：必须订阅 uiState——组合期读 StateFlow.value 不产生快照订阅。
    val chatUiState by chatViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val conversationOpen = chatUiState.currentSession != null
    // 启动弹窗在栈时不抢返回：退出确认只在没有模态时可达。
    val startupModalsOpen = startupGuide != null || manualGuide != null ||
        showPresetLimitUpgradeNotice || showQqGroupNotice
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            // 过渡节奏：navigation-compose 的默认是 700ms 交叉淡化（NavHost.kt
            // 默认 tween(700)），对工具型应用每次切页都慢半拍。统一 200ms 淡化，
            // 落在会话内交互的 140-200ms 档；四个高频 tab 与聊天页共用同一份。
            enterTransition = { fadeIn(animationSpec = tween(200)) },
            exitTransition = { fadeOut(animationSpec = tween(200)) },
            popEnterTransition = { fadeIn(animationSpec = tween(200)) },
            popExitTransition = { fadeOut(animationSpec = tween(200)) },
        ) {
            fun NavGraphBuilder.page(route: String, arguments: List<NamedNavArgument> = emptyList(), block: @Composable (NavBackStackEntry) -> Unit) {
                composable(route, arguments) { entry ->
                    // dsh 返回语义（appBackAction 吃 isDshTopLevel 这一份集合）：二级页
                    // Parent 时本 handler 关闭，让系统默认 pop；首页 tab 在栈底返回 = 回
                    // 聊天首页；chat 有会话 = 关会话；chat 空会话 = 退出确认。
                    // 「栈底」= 当前路由本身是顶层页：tab 切换的
                    // popUpTo(findStartDestination()) 会把栈收敛到 [characters/list, 当前 tab]，
                    // 顶层页之上不可能再压页面，因此不能用 previousBackStackEntry 判栈底
                    // （那会让 ConfirmExit 恒不可达）。
                    // Compose 优先最后一次注册的 BackHandler：本 handler 注册在页面内容
                    // 之前，页面自己的 handler（编辑器未保存保护等）能先拿到事件。
                    val backAction = appBackAction(entry.destination.route, conversationOpen)
                    BackHandler(enabled = when (backAction) {
                        AppBackAction.ChatHome, AppBackAction.CloseConversation -> true
                        AppBackAction.ConfirmExit -> !startupModalsOpen
                        AppBackAction.None, AppBackAction.Parent -> false
                    }) {
                        when (backAction) {
                            AppBackAction.ChatHome -> navController.navigate("chat") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            AppBackAction.CloseConversation -> chatViewModel.deselectCharacter()
                            AppBackAction.ConfirmExit -> showExitConfirmation = true
                            AppBackAction.None, AppBackAction.Parent -> Unit
                        }
                    }
                    block(entry)
                }
            }

            page("chat") {
                // 主题在根层（MainActivity 的 DshTheme）已挂载，这里不再嵌套。
                DshChatScreen(
                    viewModel = chatViewModel,
                    draftText = chatDraft,
                    onDraftChange = { chatDraft = it },
                    onBack = { navController.popBackStack() },
                    onOpenWorldBooks = { navController.navigate("world/list") },
                    onOpenPlugins = { navController.navigate("extensions") },
                    onOpenModelServices = { modelServicesPane = "root" },
                    onOpenModelConfig = { modelServicesPane = "chat" },
                    onOpenUsageStats = { navController.navigate("settings/usage") },
                    onOpenSettings = { navigateToSettings() },
                )
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
                        onCreateWithAi = { navController.navigate("creation/chat?kind=WorldBook") },
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
                    // 必须显式接线：不传就走形参默认值，把聊天式创建静默改道去旧编辑器。
                    onOpenChatCreation = { kind -> navController.navigate("creation/chat?kind=$kind") },
                )
            }
            // 聊天式创建（PRD 主机屏）：角色卡会话以聊天气泡/输入框/流式完成；
            // 世界书会话与手动编辑仍走旧编辑器（高级资产在那里）。
            page(
                route = "creation/chat?kind={kind}",
                arguments = listOf(navArgument("kind") {
                    type = NavType.StringType
                    defaultValue = CreationKind.Character.name
                }),
            ) { entry ->
                val chatKind = creationKindOf(entry.arguments?.getString("kind"))
                // 会话类型由路由参数驱动：没有 current 会话（首页直接进来）或与路由类型
                // 不一致时起新会话；一致则沿用当前会话（卡片点击处已 start()，这里不重启）。
                androidx.compose.runtime.LaunchedEffect(chatKind) {
                    val current = creationViewModel.state.value.current
                    if (current == null || current.kind != chatKind) {
                        creationViewModel.start(chatKind)
                    }
                }
                val jsonSaver = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
                ) { uri ->
                    val pending = pendingCreationExportPath
                    pendingCreationExportPath = null
                    val staging = pending?.let(::File)
                    // uri == null = 用户取消选择器，不是失败，不提示。
                    if (uri == null) {
                        staging?.delete()
                        return@rememberLauncherForActivityResult
                    }
                    if (staging?.isFile != true) {
                        // 缓存副本已不在（选择器期间旋转/进程死亡）：必须给可见反馈，
                        // 不能像原来那样静默 no-op、什么文件都不创建。
                        shellScope.launch {
                            snackbarHostState.showSnackbar(
                                activityContext.getString(R.string.chat_export_failed),
                            )
                        }
                        return@rememberLauncherForActivityResult
                    }
                    shellScope.launch {
                        val failure = withContext(Dispatchers.IO) {
                            runCatching {
                                val out = activityContext.contentResolver.openOutputStream(uri) ?: error("")
                                out.use { stream -> staging.inputStream().use { it.copyTo(stream) } }
                            }.exceptionOrNull()
                        }
                        staging.delete()
                        failure?.let { error ->
                            snackbarHostState.showSnackbar(
                                activityContext.getString(
                                    R.string.crs_export_failed,
                                    error.message.orEmpty(),
                                ),
                            )
                        }
                    }
                }
                DshCreationChatScreen(
                    viewModel = creationViewModel,
                    modelState = chatUiState,
                    onRefreshModels = { chatViewModel.refreshModelCatalog(activeProviderOnly = true) },
                    onSelectModel = { model, providerId, onSelected ->
                        chatViewModel.selectModel(model, providerId, onSelected)
                    },
                    onBack = { navController.popBackStack() },
                    onOpenManualEditor = { navController.navigate("creation/editor") },
                    modelProfileOf = { modelId -> chatViewModel.modelReasoningProfile(modelId) },
                    onOpenUsageStats = { navController.navigate("settings/usage") },
                    onAssignModelGroup = { modelId, groupId, member ->
                        chatViewModel.assignModelGroup(modelId, groupId, member)
                    },
                    onCreateModelGroup = { label -> chatViewModel.createModelGroup(label) },
                    onExportJson = { bytes ->
                        // 先落 cacheDir 再开选择器：SAF 期间旋转/进程死亡仍能凭路径找回字节。
                        shellScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    File.createTempFile(
                                        "tellev-creation-",
                                        ".json",
                                        activityContext.cacheDir,
                                    ).apply { writeBytes(bytes) }
                                }
                            }.onSuccess { file ->
                                pendingCreationExportPath = file.path
                                jsonSaver.launch("character.json")
                            }.onFailure { error ->
                                snackbarHostState.showSnackbar(
                                    activityContext.getString(
                                        R.string.crs_export_failed,
                                        error.message.orEmpty(),
                                    ),
                                )
                            }
                        }
                    },
                    onSaved = { kind, _ ->
                        if (kind == CreationKind.Character) charactersViewModel.loadCharacters()
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
                        if (kind == CreationKind.Character) charactersViewModel.loadCharacters()
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
                        if (kind == CreationKind.Character) charactersViewModel.loadCharacters()
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
                        if (kind == CreationKind.WorldBook) worldViewModel.loadBookSummaries()
                    },
                )
            }
            page("creation/editor") {
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == CreationKind.Character) charactersViewModel.loadCharacters()
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
                // 模型服务统一弹层（对话/生图/语音，dsh+bre 布局）在根层渲染；
                // 本页「模型服务」卡的管理按钮打开同一弹层（服务商管理已并入
                // 弹层「对话 → 模型服务商」段，原设置页「模型服务配置」独立页已删）。
                var showOptimizer by rememberSaveable { mutableStateOf(false) }
                val chatState by chatViewModel.uiState.collectAsState()
                Column {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        updateViewModel = updateViewModel,
                        onOpenModelServices = { modelServicesPane = "root" },
                        onOpenUsageStats = { navController.navigate("settings/usage") },
                        onOpenExtensions = { navController.navigate("extensions") },
                        onOpenPromptOptimizer = { showOptimizer = true },
                        onOpenGuide = { manualGuide = it },
                        presetFocusRequest = presetFocusRequest,
                        // 弹层在根层渲染：打开期间反馈由弹层内联消费，本页跳过 snackbar。
                        suppressFeedbackSnackbar = modelServicesPane != null,
                    )
                }
                if (showOptimizer) {
                    // 只留一个动画（生成中），无其它动效。
                    app.tellev.ui.PromptOptimizationDialog(
                        providerLabel = chatState.providerConfig?.providerType,
                        onRun = { options, onPreview, onDone ->
                            // 草稿已上提到根层：这里不再传空串，优化的是输入框里的原文。
                            chatViewModel.optimizeDraft(chatDraft, options, onPreview, onDone)
                        },
                        onCancelRun = { chatViewModel.cancelPromptOptimization() },
                        onApply = { optimized ->
                            // 「替换草稿」：写回同一个草稿，回到 chat 页让用户看得见结果
                            // （这个入口常从设置 tab 打开，聊天页当时不一定在栈上）。
                            chatDraft = optimized
                            showOptimizer = false
                            navController.navigate("chat") {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onDismiss = { showOptimizer = false },
                    )
                }
            }
            page("settings/usage") { UsageStatsRoute(onBack = { navController.popBackStack() }) }
        }
    }

    // ── 模型服务统一弹层：对话模型 / 生图引擎 / 语音朗读三类服务一个 sheet ──
    // 抽屉「模型服务」与输入栏模型键共用这一份渲染。
    val openModelServicesPane = modelServicesPane
    if (openModelServicesPane != null) {
        val chatState = chatUiState
        // 打开时拉取当前服务商的完整模型目录（与聊天页模型菜单同款种子刷新）。
        LaunchedEffect(chatState.currentSession?.id) {
            chatViewModel.refreshModelCatalog(activeProviderOnly = true)
        }
        val family = app.tellev.core.provider.ReasoningSupport.familyFor(
            chatState.providerConfig?.providerType ?: chatState.selectedProvider,
            chatState.providerConfig?.options ?: JsonObject(emptyMap()),
        )
        // 档位按当前模型的思考档案收窄（与聊天页模型菜单同源逻辑）。
        val configModelId = chatState.providerConfig?.model?.takeIf(String::isNotBlank).orEmpty()
        val modelProfile = remember(configModelId) {
            configModelId.takeIf(String::isNotBlank)?.let { chatViewModel.modelReasoningProfile(it) }
        }
        val effortLevels = remember(family, modelProfile) {
            app.tellev.core.provider.ReasoningSupport.uiLevelsForModel(family, modelProfile)
        }
        val effortOnLabel = stringResource(R.string.chat_reasoning_effort_on)
        val currentEffort = sessionReasoningEffortOf(chatState)
        val currentEffortLabel = if (family == app.tellev.core.provider.ReasoningFamily.DeepSeek &&
            currentEffort != ReasoningEffort.Auto && currentEffort != ReasoningEffort.Off
        ) {
            // DeepSeek 中继只有开/关；Low..Max 在线上同义，标签统一为「开」。
            effortOnLabel
        } else {
            dshEffortLabel(currentEffort)
        }
        val activeProvider = chatState.selectedProvider
        // 思考档案编辑器（连接配置钻入行的二级对话框）。
        var showProfile by remember { mutableStateOf(false) }
        var profileState by remember(configModelId) {
            mutableStateOf(chatViewModel.modelReasoningProfile(configModelId))
        }
        val suggestion = remember(configModelId) { chatViewModel.modelReasoningSuggestion(configModelId) }
        val ttsSettings = remember(activityContext) { app.tellev.core.tts.TtsSettings(activityContext) }
        var ttsValues by remember { mutableStateOf(ttsSettings.load()) }
        val ttsProviderConfigured =
            (chatState.selectedProvider == app.tellev.core.provider.ProviderCatalog.OPENAI_COMPATIBLE ||
                chatState.selectedProvider.startsWith("custom:")) &&
                chatState.providerConfig?.apiKey?.isNotBlank() == true
        DshModelServicesSheet(
            initialPane = openModelServicesPane,
            options = modelOptions(
                chatState,
                groupCurrent = stringResource(R.string.model_group_current),
                groupRecent = stringResource(R.string.model_group_recent),
            ),
            groups = chatState.modelGroups,
            catalog = chatState.modelCatalog.forProvider(activeProvider),
            currentModel = chatState.providerConfig?.model,
            currentEffort = currentEffort,
            effortLevels = effortLevels,
            currentEffortLabel = currentEffortLabel,
            modelLabel = chatState.providerConfig?.model?.takeIf(String::isNotBlank)
                ?: activeProvider.takeIf(String::isNotBlank),
            selectionEnabled = !chatState.isGenerating && !chatState.isLoading && !chatState.isSelectingModel,
            onSelectModel = { model ->
                // 保存成功才关弹层；失败时 selectModel 已把原因写进 error，
                // 弹层保持打开，用户能立刻看到反馈并重试。
                chatViewModel.selectModel(model, activeProvider) { saved ->
                    if (saved) modelServicesPane = null
                }
            },
            onSelectEffort = { chatViewModel.setSessionReasoningEffort(it) },
            onRefreshCatalog = { chatViewModel.refreshModelCatalog(activeProviderOnly = true) },
            onAssignGroup = { modelId, groupId, member ->
                chatViewModel.assignModelGroup(modelId, groupId, member)
            },
            onCreateGroup = { label -> chatViewModel.createModelGroup(label) },
            providerLabel = chatState.providerConfig?.providerType ?: chatState.selectedProvider,
            profileSummary = profileState?.efforts?.keys?.joinToString("/")?.ifBlank { null }
                ?: stringResource(R.string.dsh_profile_none),
            onOpenProfile = { showProfile = true },
            imageViewModel = settingsViewModel,
            imageState = settingsState,
            ttsValues = ttsValues,
            onTtsSave = { next ->
                ttsValues = next.validated()
                ttsSettings.save(ttsValues)
            },
            ttsProviderConfigured = ttsProviderConfigured,
            onDismiss = { modelServicesPane = null },
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

/** creation/chat 的 kind 路由参数 → 会话类型；未知/缺失值回落角色卡（工坊卡片入口的默认类型）。 */
private fun creationKindOf(raw: String?): CreationKind =
    CreationKind.entries.firstOrNull { it.name == raw } ?: CreationKind.Character
