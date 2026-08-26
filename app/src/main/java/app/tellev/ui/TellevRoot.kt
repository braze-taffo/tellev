package app.tellev.ui

import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import app.tellev.BuildConfig
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
    var showBetaRelayNotice by remember {
        mutableStateOf(
            BuildConfig.TELLEV_BETA_RELAY_ENABLED && !graph.appPreferences.betaRelayNoticeAccepted,
        )
    }
    val currentVersion = remember(activityContext) {
        activityContext.packageManager
            .getPackageInfo(activityContext.packageName, 0).versionName ?: "0.0.0"
    }
    val updateViewModel: UpdateViewModel = viewModel(
        factory = UpdateViewModelFactory(
            appContext = activityContext.applicationContext,
            checker = graph.updateChecker,
            preferences = graph.appPreferences,
            currentVersion = currentVersion,
        ),
    )

    // Silently check GitHub for a newer release on launch, rate-limited to
    // once per day. The result surfaces in the 关于 card on the Settings tab.
    LaunchedEffect(Unit) {
        updateViewModel.maybeAutoCheck()
    }

    if (showBetaRelayNotice) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("测试通道安全说明") },
            text = {
                Text(
                    "本测试版提供免费的 DeepSeek V4 Pro 通道。连接使用明文 HTTP，传输内容可能被网络中的第三方读取或篡改。请勿输入账号、密码、真实身份等敏感信息。不同意请退出测试版。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        graph.appPreferences.betaRelayNoticeAccepted = true
                        showBetaRelayNotice = false
                    },
                ) {
                    Text("同意并继续")
                }
            },
            dismissButton = {
                TextButton(onClick = { (activityContext as? Activity)?.finish() }) {
                    Text("退出")
                }
            },
        )
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
}
