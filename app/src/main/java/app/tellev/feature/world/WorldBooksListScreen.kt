package app.tellev.feature.world

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.WorldBookSummary
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBooksListScreen(
    viewModel: WorldViewModel,
    onBookClick: (String) -> Unit,
    onCreateWithAi: () -> Unit = {},
    onEditWithAi: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var newBookName by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(selectedUri)?.use { it.readBytes() }
                    } ?: error(context.getString(R.string.wblist_read_file_failed))
                    val fileName = UriUtils.resolveDisplayName(context, selectedUri)
                        ?: selectedUri.lastPathSegment
                        ?: "imported_world_book.json"
                    viewModel.importBook(bytes, fileName)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.wblist_import_failed, e.message))
                }
            }
        }
    }

    // Re-read the activation file every time this screen enters composition.
    // ChatViewModel.selectCharacter can write a new exclusive world-book
    // activation set while this (app-scoped) ViewModel retains its earlier
    // snapshot, so without this refresh the switches shown here would lag the
    // file by one selection.
    LaunchedEffect(Unit) {
        viewModel.loadBookSummaries()
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(state.info) {
        state.info?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wblist_title)) },
                actions = {
                    TextButton(onClick = onCreateWithAi) { Text(stringResource(R.string.wblist_ai_create)) }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.wblist_settings_cd))
                    }
                    // Use */* so .json files stay selectable (SAF MIME filtering
                    // greys them out in many file managers).
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.wblist_import_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newBookName = ""
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wblist_new)) },
                text = { Text(stringResource(R.string.wblist_new)) },
            )
        },
        modifier = modifier,
    ) { padding ->
        Crossfade(
            targetState = state.isLoading && state.worldBookSummaries.isEmpty(),
            label = "world_loading",
        ) { isLoading ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.worldBookSummaries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.wblist_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.wblist_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(state.worldBookSummaries, key = { it.id }) { book ->
                        WorldBookListItem(
                            book = book,
                            activated = book.id !in state.disabledWorldIds,
                            onClick = { onBookClick(book.id) },
                            onEditWithAi = { onEditWithAi(book.id) },
                            onToggleActivation = { viewModel.toggleWorldActivation(book.id) },
                            onDelete = { viewModel.deleteBook(book.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.wblist_create_title)) },
            text = {
                OutlinedTextField(
                    value = newBookName,
                    onValueChange = { newBookName = it },
                    label = { Text(stringResource(R.string.wblist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newBookName.isNotBlank()) {
                            viewModel.createBook(newBookName.trim())
                            showCreateDialog = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.wblist_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.wblist_cancel))
                }
            },
        )
    }

    if (showSettingsDialog) {
        val current = state.worldInfoSettings
        val currentPrompt = state.promptSettings
        var recursive by remember(current) { mutableStateOf(current.recursive) }
        var maxRecursionSteps by remember(current) { mutableStateOf(current.maxRecursionSteps.toString()) }
        var scanDepth by remember(current) { mutableStateOf(current.scanDepth.toString()) }
        var preferCharPrompt by remember(currentPrompt) { mutableStateOf(currentPrompt.preferCharacterPrompt) }
        var preferCharJailbreak by remember(currentPrompt) { mutableStateOf(currentPrompt.preferCharacterJailbreak) }
        var instructEnabled by remember(currentPrompt) { mutableStateOf(currentPrompt.instructEnabled) }
        var instructPresetName by remember(currentPrompt) { mutableStateOf(currentPrompt.instructPresetName) }
        val instructPresetOptions = remember(state.instructPresets) { state.instructPresets }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(stringResource(R.string.wblist_settings_title)) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wblist_recursive_scan), modifier = Modifier.weight(1f))
                        Switch(checked = recursive, onCheckedChange = { recursive = it })
                    }
                    Text(
                        stringResource(R.string.wblist_recursive_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = maxRecursionSteps,
                        onValueChange = { maxRecursionSteps = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.wblist_max_recursion_steps)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scanDepth,
                        onValueChange = { scanDepth = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.wblist_scan_depth)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.wblist_char_prompt_section), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wblist_prefer_char_prompt), modifier = Modifier.weight(1f))
                        Switch(checked = preferCharPrompt, onCheckedChange = { preferCharPrompt = it })
                    }
                    Text(
                        stringResource(R.string.wblist_prefer_char_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wblist_prefer_char_jailbreak), modifier = Modifier.weight(1f))
                        Switch(checked = preferCharJailbreak, onCheckedChange = { preferCharJailbreak = it })
                    }
                    Text(
                        stringResource(R.string.wblist_prefer_char_jailbreak_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.wblist_instruct_section), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.wblist_instruct_enabled), modifier = Modifier.weight(1f))
                        Switch(checked = instructEnabled, onCheckedChange = { instructEnabled = it })
                    }
                    Text(
                        stringResource(R.string.wblist_instruct_enabled_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.wblist_instruct_preset), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (instructPresetName.isBlank()) stringResource(R.string.wblist_instruct_preset_default)
                        else stringResource(R.string.wblist_instruct_preset_current, instructPresetName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (instructPresetOptions.isNotEmpty()) {
                        instructPresetOptions.forEach { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = instructPresetName == name,
                                    onClick = { instructPresetName = name },
                                )
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = instructPresetName.isBlank(),
                            onClick = { instructPresetName = "" },
                        )
                        Text(stringResource(R.string.wblist_instruct_preset_builtin), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveWorldInfoSettings(
                            current.copy(
                                recursive = recursive,
                                maxRecursionSteps = maxRecursionSteps.toIntOrNull() ?: 0,
                                scanDepth = (scanDepth.toIntOrNull() ?: current.scanDepth).coerceAtLeast(1),
                            ),
                        )
                        viewModel.savePromptSettings(
                            currentPrompt.copy(
                                preferCharacterPrompt = preferCharPrompt,
                                preferCharacterJailbreak = preferCharJailbreak,
                                instructEnabled = instructEnabled,
                                instructPresetName = instructPresetName,
                            ),
                        )
                        showSettingsDialog = false
                    },
                ) {
                    Text(stringResource(R.string.wblist_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(stringResource(R.string.wblist_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorldBookListItem(
    book: WorldBookSummary,
    activated: Boolean,
    onClick: () -> Unit,
    onEditWithAi: () -> Unit,
    onToggleActivation: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (activated) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.wblist_entry_count, book.entryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = activated,
                onCheckedChange = { onToggleActivation() },
            )

            Spacer(modifier = Modifier.padding(horizontal = 4.dp))

            IconButton(onClick = onEditWithAi) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.wblist_ai_edit_cd),
                )
            }

            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.wblist_delete_cd),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.wblist_delete_title)) },
            text = { Text(stringResource(R.string.wblist_delete_confirm, book.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.wblist_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.wblist_cancel))
                }
            },
        )
    }
}
