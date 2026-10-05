package app.tellev.ui.dsh

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
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

private enum class DshTab(val route: String, val labelRes: Int, val icon: ImageVector) {
    Workshop("creation/home", R.string.nav_tab_workshop, Icons.Default.AutoFixHigh),
    Community("community", R.string.nav_tab_community, Icons.Default.Forum),
    Characters("characters", R.string.nav_tab_characters, Icons.Default.PlayCircle),
    Settings("settings", R.string.nav_tab_settings, Icons.Default.Settings),
}

/** 顶层路由（保底提交 c9f4ad1 的语义：这些叶子保留底栏）。 */
internal fun isDshTopLevel(route: String?): Boolean = route in setOf(
    "characters/list", "creation/home", "community", "settings",
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
                composable(route, arguments) { block(it) }
            }

            page("chat") {
                DshChatScreen(
                    viewModel = chatViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenWorldBooks = { navController.navigate("world/list") },
                    onOpenPlugins = { navController.navigate("extensions") },
                    onOpenImageGenSettings = { navController.navigate("settings/imagegen") },
                    onOpenUsageStats = { navController.navigate("settings/usage") },
                )
            }

            navigation(startDestination = "characters/list", route = "characters") {
                page("characters/list") {
                    CharactersListScreen(
                        viewModel = charactersViewModel,
                        onCreateClick = { navController.navigate("characters/create") },
                        onCharacterClick = { id ->
                            charactersViewModel.selectCharacter(id)
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
                        onCreateWithAi = { navController.navigate("creation/home") },
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
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    onOpenProviderSettings = { navController.navigate("settings/providers") },
                    onOpenImageGenSettings = { navController.navigate("settings/imagegen") },
                    onOpenUsageStats = { navController.navigate("settings/usage") },
                    onOpenExtensions = { navController.navigate("extensions") },
                )
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
}
