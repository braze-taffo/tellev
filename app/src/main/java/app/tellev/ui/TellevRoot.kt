package app.tellev.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tellev.LocalTellevGraph
import app.tellev.R
import app.tellev.feature.about.AboutScreen
import app.tellev.feature.characters.CharacterDetailScreen
import app.tellev.feature.characters.CharactersListScreen
import app.tellev.feature.characters.CharactersViewModel
import app.tellev.feature.characters.CharactersViewModelFactory
import app.tellev.feature.chat.ChatScreen
import app.tellev.feature.chat.ChatViewModel
import app.tellev.feature.chat.ChatViewModelFactory
import app.tellev.feature.extensions.ExtensionsScreen
import app.tellev.feature.extensions.ExtensionsViewModel
import app.tellev.feature.extensions.ExtensionsViewModelFactory
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

private enum class TellevTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String = label,
) {
    Chat("chat", "聊天", Icons.Default.ChatBubble),
    Characters("characters", "角色", Icons.Default.People),
    World("world", "世界书", Icons.Default.Public),
    Extensions("extensions", "扩展", Icons.Default.Extension),
    Settings("settings", "设置", Icons.Default.Settings),
}

@Composable
fun TellevRoot() {
    val graph = LocalTellevGraph.current
    val navController = rememberNavController()

    // Create ViewModels scoped to the activity (survive navigation)
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(
            dataStore = graph.dataStore,
            providerRegistry = graph.providerRegistry,
            promptEngine = graph.promptEngine,
            secretStore = graph.secretStore,
            extensionHost = graph.extensionHost,
            permissionManager = graph.permissionManager,
        ),
    )

    val charactersViewModel: CharactersViewModel = viewModel(
        factory = CharactersViewModelFactory(
            dataStore = graph.dataStore,
            importedCardSignal = graph.importedCardSignal,
        ),
    )

    val worldViewModel: WorldViewModel = viewModel(
        factory = WorldViewModelFactory(
            dataStore = graph.dataStore,
        ),
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(
            dataStore = graph.dataStore,
            providerRegistry = graph.providerRegistry,
            secretStore = graph.secretStore,
            appPreferences = graph.appPreferences,
            themeModeFlow = graph.themeModeFlow,
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

    val activityContext = LocalContext.current
    val packageInfo = remember(activityContext) {
        activityContext.packageManager.getPackageInfo(activityContext.packageName, 0)
    }
    val currentVersion = packageInfo.versionName ?: "0.0.0"
    var showPresetLimitUpgradeNotice by rememberSaveable { mutableStateOf(false) }
    var showQqGroupNotice by rememberSaveable { mutableStateOf(false) }
    var presetFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    val updateViewModel: UpdateViewModel = viewModel(
        factory = UpdateViewModelFactory(
            appContext = activityContext.applicationContext,
            checker = graph.updateChecker,
            preferences = graph.appPreferences,
            currentVersion = currentVersion,
        ),
    )

    // Check GitHub for a newer release on every cold start (guarded to once
    // per process). A found update pops a dialog; the 关于 card on the
    // Settings tab keeps showing the persistent status.
    LaunchedEffect(Unit) {
        updateViewModel.checkOnLaunch()
    }
    LaunchedEffect(packageInfo.firstInstallTime, packageInfo.lastUpdateTime) {
        showPresetLimitUpgradeNotice = graph.appPreferences.shouldShowPresetLimitUpgradeNotice(
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
        )
    }
    LaunchedEffect(Unit) {
        showQqGroupNotice = graph.appPreferences.shouldShowQqGroupNotice()
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Determine which bottom tab is selected based on current destination
    val currentTab = TellevTab.entries.find { tab ->
        currentDestination?.hierarchy?.any { it.route?.startsWith(tab.route) == true } == true
    } ?: TellevTab.Chat

    // Hide bottom bar on detail/edit screens.
    val showBottomBar = currentDestination?.route in TellevTab.entries.map { it.route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TellevTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // Pop up to the graph's start destination to avoid
                                    // building up a large stack of destinations
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // Avoid multiple copies of the same destination
                                    launchSingleTop = true
                                    // Restore state when re-selecting a previously selected item
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.contentDescription) },
                            label = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TellevTab.Chat.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            // Chat tab - single screen
            composable(TellevTab.Chat.route) {
                ChatScreen(viewModel = chatViewModel)
            }

            // Characters tab with sub-navigation
            navigation(
                startDestination = "characters/list",
                route = TellevTab.Characters.route,
            ) {
                composable("characters/list") {
                    CharactersListScreen(
                        viewModel = charactersViewModel,
                        onCharacterClick = { characterId ->
                            charactersViewModel.selectCharacter(characterId)
                            navController.navigate("characters/detail/$characterId")
                        },
                    )
                }
                composable(
                    route = "characters/detail/{characterId}",
                    arguments = listOf(
                        navArgument("characterId") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val characterId = backStackEntry.arguments?.getString("characterId").orEmpty()
                    LaunchedEffect(characterId) {
                        if (characterId.isNotBlank()) charactersViewModel.selectCharacter(characterId)
                    }
                    CharacterDetailScreen(
                        viewModel = charactersViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // World tab with sub-navigation
            navigation(
                startDestination = "world/list",
                route = TellevTab.World.route,
            ) {
                composable("world/list") {
                    WorldBooksListScreen(
                        viewModel = worldViewModel,
                        onBookClick = { bookId ->
                            worldViewModel.selectBook(bookId)
                            navController.navigate("world/book/$bookId")
                        },
                    )
                }
                composable(
                    route = "world/book/{bookId}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                    LaunchedEffect(bookId) {
                        if (bookId.isNotBlank()) worldViewModel.selectBook(bookId)
                    }
                    WorldBookDetailScreen(
                        viewModel = worldViewModel,
                        onBack = { navController.popBackStack() },
                        onEditEntry = { entryId ->
                            navController.navigate("world/book/$bookId/entry/$entryId")
                        },
                    )
                }
                composable(
                    route = "world/book/{bookId}/entry/{entryId}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("entryId") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                    val entryId = backStackEntry.arguments?.getString("entryId").orEmpty()
                    LaunchedEffect(bookId, entryId) {
                        if (bookId.isNotBlank() && entryId.isNotBlank()) {
                            worldViewModel.openEntry(bookId, entryId)
                        }
                    }
                    WorldBookEntryEditScreen(
                        viewModel = worldViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // Extensions tab - single screen
            composable(TellevTab.Extensions.route) {
                ExtensionsScreen(viewModel = extensionsViewModel)
            }

            // Settings tab - single screen
            composable(TellevTab.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    presetFocusRequest = presetFocusRequest,
                    onOpenProviderSettings = {
                        navController.navigate("settings/providers")
                    },
                )
            }
            composable("settings/providers") {
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

    if (showPresetLimitUpgradeNotice) {
        fun closeNotice() {
            graph.appPreferences.markPresetLimitUpgradeNoticeHandled()
            showPresetLimitUpgradeNotice = false
        }
        AlertDialog(
            onDismissRequest = ::closeNotice,
            title = { Text("请检查当前生成预设") },
            text = {
                Text(
                    "旧版默认预设的上下文上限仅 4096、输出上限仅 300，可能造成世界书和回复被严重截断。" +
                        "新版内置默认值已调整为 1,000,000 / 131,072；导入或自行修改过的预设不会被强制覆盖，" +
                        "请前往“设置 → 生成预设”切换或检查当前预设。",
                )
            },
            dismissButton = {
                TextButton(onClick = ::closeNotice) { Text("稍后") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeNotice()
                        presetFocusRequest += 1
                        navController.navigate(TellevTab.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                ) { Text("去设置预设") }
            },
        )
    }

    // Queued after the beta-relay and preset-limit notices so at most one
    // dialog is up at a time.
    if (showQqGroupNotice && !showPresetLimitUpgradeNotice) {
        val clipboard = LocalClipboardManager.current
        fun closeNotice() {
            graph.appPreferences.markQqGroupNoticeHandled()
            showQqGroupNotice = false
        }
        AlertDialog(
            onDismissRequest = ::closeNotice,
            title = { Text("加入 QQ 交流群") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.qq_group_qrcode),
                        contentDescription = "QQ 群二维码",
                        modifier = Modifier
                            .size(216.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "tellev酒馆交流群",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "群号：754350480",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "扫码或搜索群号加入，反馈问题、交流玩法。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = ::closeNotice) { Text("我知道了") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString("754350480"))
                        Toast.makeText(activityContext, "已复制群号", Toast.LENGTH_SHORT).show()
                        closeNotice()
                    },
                ) { Text("复制群号") }
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
        !showPresetLimitUpgradeNotice && !showQqGroupNotice
    ) {
        AlertDialog(
            onDismissRequest = { dismissedUpdateVersion = pendingUpdate.version },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text(
                        text = "新版本 ${pendingUpdate.tagName} 已发布，当前版本 v$currentVersion。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pendingUpdate.apkSize > 0) {
                        Text(
                            text = "安装包约 ${"%.1f".format(pendingUpdate.apkSize / 1024f / 1024f)} MB",
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
                    Text("稍后")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismissedUpdateVersion = pendingUpdate.version
                        updateViewModel.downloadAndInstall()
                        Toast.makeText(activityContext, "开始下载新版本…", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("立即更新") }
            },
        )
    }
}
