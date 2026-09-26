package app.tellev.feature.characters

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.CharacterCard
import app.tellev.core.model.CharacterWorldBinding
import app.tellev.core.model.WorldBook
import app.tellev.ui.CharacterAvatar
import app.tellev.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersListScreen(
    viewModel: CharactersViewModel,
    onCreateClick: () -> Unit,
    onCharacterClick: (String) -> Unit,
    onCreateWithAi: () -> Unit = {},
    onEditWithAi: (String) -> Unit = {},
    onCreateWorldBookWithAi: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        val requested = pendingExport
        pendingExport = null
        if (uri != null && requested != null) {
            scope.launch {
                try {
                    val json = viewModel.exportCharacterToJson(requested.first) ?: return@launch
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error(context.getString(R.string.chars_export_write_failed))
                    }
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_export_saved, requested.second))
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_export_failed, e.message))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.readBytes()
                    }
                    val fileName = UriUtils.resolveDisplayName(context, it)
                        ?: it.lastPathSegment
                        ?: "imported_character.json"
                    if (bytes != null) {
                        viewModel.importCharacter(bytes, fileName)
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_import_failed, e.message))
                }
            }
        }
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
                title = { Text(stringResource(R.string.chars_title)) },
                actions = {
                    TextButton(onClick = onCreateWithAi) { Text(stringResource(R.string.chars_ai_create)) }
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.chars_import_cd))
                    }
                    IconButton(onClick = { searchActive = !searchActive }) {
                        Icon(
                            if (searchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchActive) {
                                stringResource(R.string.chars_search_close_cd)
                            } else {
                                stringResource(R.string.chars_search_cd)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.chars_new_character)) },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .animateContentSize(),
        ) {
            Crossfade(targetState = searchActive, label = "search_bar") { active ->
                if (active) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.search(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.chars_search_placeholder)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.search("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chars_clear_cd))
                                }
                            }
                        },
                    )
                }
            }

            Crossfade(
                targetState = state.isLoading,
                label = "loading_state",
            ) { isLoading ->
                if (isLoading && state.filteredCharacters.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.filteredCharacters.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (searchQuery.isNotEmpty()) {
                                    stringResource(R.string.chars_no_match)
                                } else {
                                    stringResource(R.string.chars_empty_list)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchQuery.isEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.chars_empty_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        items(
                            state.filteredCharacters,
                            key = { it.id },
                        ) { character ->
                            CharacterListItem(
                                character = character,
                                avatarFile = state.avatarFiles[character.id],
                                onClick = { onCharacterClick(character.id) },
                                onEditWithAi = { onEditWithAi(character.id) },
                                onCreateWorldBookWithAi = { onCreateWorldBookWithAi(character.id) },
                                onDuplicate = { viewModel.duplicateCharacter(character.id) },
                                onDelete = { viewModel.deleteCharacter(character.id) },
                                onExport = {
                                    pendingExport = character.id to character.name
                                    val safeName = character.name.replace(Regex("""[\\/:*?"<>|]"""), "_")
                                        .ifBlank { "character" }
                                    exportLauncher.launch("$safeName.json")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CharacterListItem(
    character: app.tellev.core.model.CharacterSummary,
    avatarFile: java.io.File?,
    onClick: () -> Unit,
    onEditWithAi: () -> Unit,
    onCreateWorldBookWithAi: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showContextMenu = true },
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterAvatar(
                file = avatarFile,
                fallbackText = character.name,
                modifier = Modifier.size(48.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (character.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        character.tags.take(5).forEach { tag ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                        if (character.tags.size > 5) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        text = "+${character.tags.size - 5}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }
            }

            Box {
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chars_more_options_cd))
                }
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_ai_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            onEditWithAi()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_write_worldbook)) },
                        onClick = {
                            onCreateWorldBookWithAi()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_duplicate)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            onDuplicate()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_export_json)) },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            onExport()
                            showContextMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chars_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showDeleteDialog = true
                            showContextMenu = false
                        },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.chars_delete_title)) },
            text = { Text(stringResource(R.string.chars_delete_confirm, character.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    },
                ) {
                    Text(stringResource(R.string.chars_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CharacterDetailScreen(
    viewModel: CharactersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isCreating: Boolean = false,
    onCreated: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val draft = remember { CharacterCard(id = "char_${UUID.randomUUID()}", name = "") }
    val character = if (isCreating) draft else state.selectedCharacter

    LaunchedEffect(isCreating) {
        if (isCreating) viewModel.loadWorldBooks()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAvatarPng by remember { mutableStateOf<ByteArray?>(null) }
    val pendingAvatar = remember(pendingAvatarPng) {
        pendingAvatarPng?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.readBytes()
                    }
                    if (bytes != null) {
                        if (isCreating) {
                            pendingAvatarPng = withContext(Dispatchers.IO) {
                                app.tellev.util.decodeImageAsPng(bytes, maxEdge = 1024)
                            } ?: error(context.getString(R.string.chars_image_parse_failed))
                        } else {
                            viewModel.setCharacterAvatar(bytes)
                        }
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.chars_read_image_failed, e.message))
                }
            }
        }
    }

    var name by remember(character?.id) { mutableStateOf(character?.name ?: "") }
    var description by remember(character?.id) { mutableStateOf(character?.description ?: "") }
    var personality by remember(character?.id) { mutableStateOf(character?.personality ?: "") }
    var scenario by remember(character?.id) { mutableStateOf(character?.scenario ?: "") }
    var firstMessage by remember(character?.id) { mutableStateOf(character?.firstMessage ?: "") }
    var alternateGreetings by remember(character?.id) { mutableStateOf(character?.alternateGreetings ?: emptyList()) }
    var exampleMessages by remember(character?.id) { mutableStateOf(character?.exampleMessages ?: "") }
    var creatorNotes by remember(character?.id) { mutableStateOf(character?.creatorNotes ?: "") }
    var systemPrompt by remember(character?.id) { mutableStateOf(character?.systemPrompt ?: "") }
    var postHistoryInstructions by remember(character?.id) { mutableStateOf(character?.postHistoryInstructions ?: "") }
    var creator by remember(character?.id) { mutableStateOf(character?.creator ?: "") }
    var characterVersion by remember(character?.id) { mutableStateOf(character?.characterVersion ?: "") }
    var tags by remember(character?.id) { mutableStateOf(character?.tags ?: emptyList()) }
    var newTag by remember { mutableStateOf("") }
    var saveAttempted by remember { mutableStateOf(false) }
    // External world-book binding (data.extensions.world). Empty string = unbound.
    var linkedWorldName by remember(character?.id) {
        mutableStateOf(character?.let { CharacterWorldBinding.linkedWorldBookName(it) } ?: "")
    }
    var showWorldPicker by remember { mutableStateOf(false) }

    fun updatedCard(c: CharacterCard): CharacterCard {
        val rawData = c.raw["data"] as? JsonObject ?: JsonObject(emptyMap())
        val updatedData = JsonObject(rawData + mapOf(
            "system_prompt" to JsonPrimitive(systemPrompt),
            "post_history_instructions" to JsonPrimitive(postHistoryInstructions),
            "creator" to JsonPrimitive(creator),
            "character_version" to JsonPrimitive(characterVersion),
        ))
        return c.copy(
            name = name.trim(),
            description = description,
            personality = personality,
            scenario = scenario,
            firstMessage = firstMessage,
            alternateGreetings = alternateGreetings,
            exampleMessages = exampleMessages,
            creatorNotes = creatorNotes,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistoryInstructions,
            creator = creator,
            characterVersion = characterVersion,
            tags = tags,
            raw = JsonObject(c.raw + ("data" to updatedData)),
        )
    }

    fun saveCard() {
        saveAttempted = true
        if (name.isBlank() || state.isLoading) return
        val card = CharacterWorldBinding.withLinkedWorldBookName(
            updatedCard(character ?: return),
            linkedWorldName,
        )
        if (isCreating) {
            scope.launch {
                if (viewModel.createCharacter(card, pendingAvatarPng)) onCreated(card.id)
            }
        } else {
            viewModel.saveCharacter(card)
        }
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
                title = {
                    Text(
                        if (isCreating) {
                            stringResource(R.string.chars_new_character)
                        } else {
                            character?.name ?: stringResource(R.string.chars_detail_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.chars_back_cd))
                    }
                },
                actions = {
                    IconButton(
                        onClick = ::saveCard,
                        enabled = !state.isLoading,
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = if (isCreating) {
                                stringResource(R.string.chars_create_character)
                            } else {
                                stringResource(R.string.chars_save_cd)
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (character == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (state.selectionError == null) {
                    CircularProgressIndicator()
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.selectionError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onBack) { Text(stringResource(R.string.chars_back_to_list)) }
                    }
                }
            }
        } else {
            val worldBooks = state.worldBooks
            val boundBook = linkedWorldName.ifBlank { null }?.let { nm ->
                worldBooks.firstOrNull { it.name.equals(nm, ignoreCase = true) || it.id == nm }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A picked image stays in the draft until the character is created.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pendingAvatar != null && isCreating) {
                        Image(
                            bitmap = pendingAvatar,
                            contentDescription = stringResource(R.string.chars_avatar_preview_cd),
                            modifier = Modifier.size(72.dp).clip(CircleShape).clickable { avatarPicker.launch("image/*") },
                        )
                    } else {
                        CharacterAvatar(
                            file = if (isCreating) null else state.avatarFiles[character.id],
                            fallbackText = name,
                            modifier = Modifier
                                .size(72.dp)
                                .clickable { avatarPicker.launch("image/*") },
                            fallbackTextStyle = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.chars_card_section),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (isCreating) {
                                stringResource(R.string.chars_name_only_hint)
                            } else {
                                stringResource(R.string.chars_character_id, character.id)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        TextButton(onClick = { avatarPicker.launch("image/*") }) {
                            Text(
                                if (isCreating) {
                                    stringResource(R.string.chars_pick_avatar_optional)
                                } else {
                                    stringResource(R.string.chars_change_avatar)
                                },
                            )
                        }
                    }
                }

                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.chars_field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = saveAttempted && name.isBlank(),
                    supportingText = if (saveAttempted && name.isBlank()) {{ Text(stringResource(R.string.chars_name_required)) }} else null,
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.chars_field_description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Personality
                OutlinedTextField(
                    value = personality,
                    onValueChange = { personality = it },
                    label = { Text(stringResource(R.string.chars_field_personality)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // Scenario
                OutlinedTextField(
                    value = scenario,
                    onValueChange = { scenario = it },
                    label = { Text(stringResource(R.string.chars_field_scenario)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )

                // First Message
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    label = { Text(stringResource(R.string.chars_field_first_message)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.chars_alternate_greetings), style = MaterialTheme.typography.titleSmall)
                    alternateGreetings.forEachIndexed { index, greeting ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = greeting,
                                onValueChange = { value ->
                                    alternateGreetings = alternateGreetings.toMutableList().also { it[index] = value }
                                },
                                label = { Text(stringResource(R.string.chars_greeting_n, index + 2)) },
                                modifier = Modifier.weight(1f),
                                minLines = 2,
                                maxLines = 8,
                            )
                            IconButton(onClick = {
                                alternateGreetings = alternateGreetings.toMutableList().also { it.removeAt(index) }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chars_remove_greeting_cd))
                            }
                        }
                    }
                    OutlinedButton(onClick = { alternateGreetings = alternateGreetings + "" }) {
                        Text(stringResource(R.string.chars_add_greeting))
                    }
                }

                // Example Messages
                OutlinedTextField(
                    value = exampleMessages,
                    onValueChange = { exampleMessages = it },
                    label = { Text(stringResource(R.string.chars_field_example_messages)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )

                // Creator Notes
                OutlinedTextField(
                    value = creatorNotes,
                    onValueChange = { creatorNotes = it },
                    label = { Text(stringResource(R.string.chars_field_creator_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6,
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.chars_field_system_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                )

                OutlinedTextField(
                    value = postHistoryInstructions,
                    onValueChange = { postHistoryInstructions = it },
                    label = { Text(stringResource(R.string.chars_field_post_history)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 8,
                )

                OutlinedTextField(
                    value = creator,
                    onValueChange = { creator = it },
                    label = { Text(stringResource(R.string.chars_field_creator)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = characterVersion,
                    onValueChange = { characterVersion = it },
                    label = { Text(stringResource(R.string.chars_field_version)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Tags
                Column {
                    Text(
                        text = stringResource(R.string.chars_field_tags),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = true,
                                onClick = { tags = tags - tag },
                                label = { Text(tag) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.chars_remove_tag_cd),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text(stringResource(R.string.chars_add_tag)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        FilledTonalButton(
                            onClick = {
                                if (newTag.isNotBlank()) {
                                    tags = tags + newTag.trim()
                                    newTag = ""
                                }
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chars_add_cd))
                        }
                    }
                }

                // Character Book section
                if (character.characterBook != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.chars_character_book),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.chars_book_entries,
                                    character.characterBook.name,
                                    character.characterBook.entries.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // World book binding (data.extensions.world): the external
                // lorebook activated when chatting with this character, on top
                // of the embedded character_book above.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.chars_world_binding),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (linkedWorldName.isBlank()) {
                                stringResource(R.string.chars_world_unbound)
                            } else if (boundBook != null) {
                                stringResource(R.string.chars_book_entries, boundBook.name, boundBook.entries.size)
                            } else {
                                stringResource(R.string.chars_world_missing, linkedWorldName)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(onClick = { showWorldPicker = true }) {
                            Text(stringResource(R.string.chars_pick_worldbook))
                        }
                    }
                }

                // Save button
                FilledTonalButton(
                    onClick = ::saveCard,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text(
                        if (isCreating) {
                            stringResource(R.string.chars_create_character)
                        } else {
                            stringResource(R.string.chars_save_changes)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (showWorldPicker) {
                AlertDialog(
                    onDismissRequest = { showWorldPicker = false },
                    title = { Text(stringResource(R.string.chars_pick_worldbook)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            WorldPickerOption(
                                label = stringResource(R.string.chars_world_none),
                                selected = linkedWorldName.isBlank(),
                                onClick = {
                                    linkedWorldName = ""
                                    showWorldPicker = false
                                },
                            )
                            if (worldBooks.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chars_world_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                )
                            } else {
                                HorizontalDivider()
                                worldBooks.forEach { book ->
                                    WorldPickerOption(
                                        label = book.name.ifBlank { book.id },
                                        sublabel = stringResource(R.string.chars_entries_count, book.entries.size),
                                        selected = book.name.equals(linkedWorldName, ignoreCase = true) ||
                                            book.id == linkedWorldName,
                                        onClick = {
                                            linkedWorldName = book.name
                                            showWorldPicker = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showWorldPicker = false }) { Text(stringResource(R.string.chars_cancel)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun WorldPickerOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    sublabel: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
