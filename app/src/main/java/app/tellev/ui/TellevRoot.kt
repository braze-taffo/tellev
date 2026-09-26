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
import androidx.compose.ui.res.stringResource
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
import app.tellev.feature.creation.CreationEditorScreen
import app.tellev.feature.creation.CreationHomeScreen
import app.tellev.feature.creation.CreationViewModel
import app.tellev.feature.creation.CreationViewModelFactory
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
            externalChatWritePort = graph.apiRouter.externalChatWrites,
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

    val creationViewModel: CreationViewModel = viewModel(
        factory = CreationViewModelFactory(
            root = LocalContext.current.filesDir.resolve("ai-creation"),
            store = graph.dataStore,
            secrets = graph.secretStore,
            providers = graph.providerRegistry,
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
                        val tabLabelRes = when (tab) {
                            TellevTab.Chat -> R.string.nav_tab_chat
                            TellevTab.Characters -> R.string.nav_tab_characters
                            TellevTab.World -> R.string.nav_tab_world
                            TellevTab.Extensions -> R.string.nav_tab_extensions
                            TellevTab.Settings -> R.string.nav_tab_settings
                        }
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
                            icon = { Icon(tab.icon, contentDescription = stringResource(tabLabelRes)) },
                            label = {
                                Text(
                                    text = stringResource(tabLabelRes),
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
                val bubbleAlpha by graph.chatBubbleAlphaFlow.collectAsState()
                val chatFontSizeSp by graph.chatFontSizeSpFlow.collectAsState()
                ChatScreen(
                    viewModel = chatViewModel,
                    bottomBarReserve = innerPadding.calculateBottomPadding(),
                    bubbleAlpha = bubbleAlpha,
                    chatFontSizeSp = chatFontSizeSp,
                )
            }

            // Characters tab with sub-navigation
            navigation(
                startDestination = "characters/list",
                route = TellevTab.Characters.route,
            ) {
                composable("characters/list") {
                    CharactersListScreen(
                        viewModel = charactersViewModel,
                        onCreateWithAi = { navController.navigate("creation/home") },
                        onCreateClick = { navController.navigate("characters/create") },
                        onEditWithAi = { characterId ->
                            navController.navigate("creation/edit/character/$characterId")
                        },
                        onCreateWorldBookWithAi = { characterId ->
                            navController.navigate("creation/from-character/world/$characterId")
                        },
                        onCharacterClick = { characterId ->
                            charactersViewModel.selectCharacter(characterId)
                            navController.navigate("characters/detail/$characterId")
                        },
                    )
                }
                composable("characters/create") {
                    CharacterDetailScreen(
                        viewModel = charactersViewModel,
                        onBack = { navController.popBackStack() },
                        isCreating = true,
                        onCreated = { characterId ->
                            navController.navigate("characters/detail/$characterId") {
                                popUpTo("characters/create") { inclusive = true }
                            }
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
                        onCreateWithAi = { navController.navigate("creation/home") },
                        onEditWithAi = { bookId ->
                            navController.navigate("creation/edit/world/$bookId")
                        },
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
            composable("creation/home") {
                CreationHomeScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenEditor = { navController.navigate("creation/editor") },
                )
            }
            composable(
                route = "creation/edit/character/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
                LaunchedEffect(cardId) {
                    if (cardId.isNotBlank()) creationViewModel.startFromCharacter(cardId)
                }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) {
                            charactersViewModel.loadCharacters()
                        } else {
                            worldViewModel.loadBookSummaries()
                        }
                    },
                )
            }
            composable(
                route = "creation/edit/world/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
                LaunchedEffect(bookId) {
                    if (bookId.isNotBlank()) creationViewModel.startFromWorldBook(bookId)
                }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) {
                            charactersViewModel.loadCharacters()
                        } else {
                            worldViewModel.loadBookSummaries()
                        }
                    },
                )
            }
            composable(
                route = "creation/from-character/world/{cardId}",
                arguments = listOf(navArgument("cardId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val cardId = backStackEntry.arguments?.getString("cardId").orEmpty()
                LaunchedEffect(cardId) {
                    if (cardId.isNotBlank()) creationViewModel.startWorldBookFromCharacter(cardId)
                }
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.WorldBook) {
                            worldViewModel.loadBookSummaries()
                        }
                    },
                )
            }
            composable("creation/editor") {
                CreationEditorScreen(
                    viewModel = creationViewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { kind, _ ->
                        if (kind == app.tellev.feature.creation.CreationKind.Character) {
                            charactersViewModel.loadCharacters()
                        } else {
                            worldViewModel.loadBookSummaries()
                        }
                    },
                )
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
                    onOpenImageGenSettings = {
                        navController.navigate("settings/imagegen")
                    },
                )
            }
            composable("settings/imagegen") {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    updateViewModel = updateViewModel,
                    onOpenProviderSettings = {},
                    imageGenDetailsOnly = true,
                    onBack = { navController.popBackStack() },
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
                        navController.navigate(TellevTab.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                ) { Text(stringResource(R.string.nav_go_to_preset_settings)) }
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
        !showPresetLimitUpgradeNotice && !showQqGroupNotice
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
