package app.tellev.feature.world

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellev.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldBookEntryEditScreen(
    viewModel: WorldViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val entry = state.selectedEntry
    val bookId = state.selectedBook?.id
    var editorActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        editorActive = true
        onDispose { editorActive = false }
    }
    fun leaveEditor() {
        editorActive = false
        onBack()
    }
    BackHandler { leaveEditor() }

    var keys by remember(entry?.id) { mutableStateOf(entry?.keys?.joinToString(", ") ?: "") }
    var secondaryKeys by remember(entry?.id) { mutableStateOf(entry?.secondaryKeys?.joinToString(", ") ?: "") }
    var content by remember(entry?.id) { mutableStateOf(entry?.content ?: "") }
    var enabled by remember(entry?.id) { mutableStateOf(entry?.enabled ?: true) }
    var selective by remember(entry?.id) { mutableStateOf(entry?.selective ?: false) }
    var constant by remember(entry?.id) { mutableStateOf(entry?.constant ?: false) }
    var priority by remember(entry?.id) { mutableStateOf(entry?.priority?.toString() ?: "0") }
    var insertionOrder by remember(entry?.id) { mutableStateOf(entry?.insertionOrder?.toString() ?: "100") }
    var depth by remember(entry?.id) { mutableStateOf(entry?.depth?.toString() ?: "4") }
    var position by remember(entry?.id) { mutableStateOf(entry?.position ?: 0) }
    var selectiveLogic by remember(entry?.id) { mutableStateOf(entry?.selectiveLogic ?: 0) }
    var useProbability by remember(entry?.id) { mutableStateOf(entry?.useProbability ?: false) }
    var probability by remember(entry?.id) { mutableStateOf(entry?.probability?.toString() ?: "100") }
    var role by remember(entry?.id) { mutableStateOf(entry?.role ?: 0) }
    var matchWholeWords by remember(entry?.id) { mutableStateOf(entry?.matchWholeWords ?: false) }
    var useRegex by remember(entry?.id) { mutableStateOf(entry?.useRegex ?: false) }
    var caseSensitive by remember(entry?.id) { mutableStateOf(entry?.caseSensitive ?: false) }
    var excludeRecursion by remember(entry?.id) { mutableStateOf(entry?.excludeRecursion ?: false) }
    var preventRecursion by remember(entry?.id) { mutableStateOf(entry?.preventRecursion ?: false) }
    var delayUntilRecursion by remember(entry?.id) { mutableStateOf((entry?.delayUntilRecursion ?: 0) > 0) }
    var delayUntilRecursionLevel by remember(entry?.id) { mutableStateOf((entry?.delayUntilRecursion ?: 1).coerceAtLeast(1).toString()) }
    var comment by remember(entry?.id) { mutableStateOf(entry?.comment ?: "") }

    fun buildUpdatedEntry(base: app.tellev.core.model.WorldBookEntry): app.tellev.core.model.WorldBookEntry {
        val parsedKeys = keys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val parsedSecondaryKeys = secondaryKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return base.copy(
            keys = parsedKeys,
            secondaryKeys = parsedSecondaryKeys,
            content = content,
            enabled = enabled,
            selective = selective,
            constant = constant,
            priority = priority.toIntOrNull() ?: 0,
            insertionOrder = insertionOrder.toIntOrNull() ?: 100,
            depth = depth.toIntOrNull() ?: 4,
            position = position,
            selectiveLogic = selectiveLogic,
            useProbability = useProbability,
            probability = probability.toIntOrNull()?.coerceIn(0, 100) ?: 100,
            role = role,
            matchWholeWords = matchWholeWords,
            useRegex = useRegex,
            caseSensitive = caseSensitive,
            excludeRecursion = excludeRecursion,
            preventRecursion = preventRecursion,
            delayUntilRecursion = if (delayUntilRecursion) {
                (delayUntilRecursionLevel.toIntOrNull() ?: 1).coerceAtLeast(1)
            } else {
                0
            },
            comment = comment,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry?.content?.isBlank() == true || entry?.keys?.isEmpty() == true) stringResource(R.string.wbedit_new_entry) else stringResource(R.string.wbedit_edit_entry)) },
                navigationIcon = {
                    IconButton(onClick = { leaveEditor() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wbedit_back_cd))
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.isSaving,
                        onClick = {
                            if (entry != null && bookId != null) {
                                viewModel.saveEntry(bookId, buildUpdatedEntry(entry), onSaved = { if (editorActive) leaveEditor() })
                            }
                        },
                    ) {
                        Icon(Icons.Default.Save, contentDescription = stringResource(R.string.wbedit_save_entry_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { padding ->
        if (entry == null || bookId == null) {
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
                        TextButton(onClick = { leaveEditor() }) { Text(stringResource(R.string.wbedit_back_to_book)) }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                // Keys
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text(stringResource(R.string.wbedit_field_keys)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Secondary Keys
                OutlinedTextField(
                    value = secondaryKeys,
                    onValueChange = { secondaryKeys = it },
                    label = { Text(stringResource(R.string.wbedit_field_secondary_keys)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Content
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.wbedit_field_content)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 20,
                )

                // Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.wbedit_enabled), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.wbedit_selective), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = selective, onCheckedChange = { selective = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.wbedit_constant), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = constant, onCheckedChange = { constant = it })
                }

                // Number fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = priority,
                        onValueChange = { priority = it },
                        label = { Text(stringResource(R.string.wbedit_priority)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = insertionOrder,
                        onValueChange = { insertionOrder = it },
                        label = { Text(stringResource(R.string.wbedit_insertion_order)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = depth,
                        onValueChange = { depth = it },
                        label = { Text(stringResource(R.string.wbedit_depth)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                // 注入位置（ST 8 种 position）
                DropdownSelector(
                    label = stringResource(R.string.wbedit_position),
                    selected = position,
                    options = listOf(
                        0 to stringResource(R.string.wbedit_position_before_char),
                        1 to stringResource(R.string.wbedit_position_after_char),
                        2 to stringResource(R.string.wbedit_position_before_an),
                        3 to stringResource(R.string.wbedit_position_after_an),
                        4 to stringResource(R.string.wbedit_position_at_depth),
                        5 to stringResource(R.string.wbedit_position_before_example),
                        6 to stringResource(R.string.wbedit_position_after_example),
                        7 to stringResource(R.string.wbedit_position_slot),
                    ),
                    onSelected = { position = it },
                )

                // 选择性逻辑（仅 selective 时有意义）
                DropdownSelector(
                    label = stringResource(R.string.wbedit_selective_logic),
                    selected = selectiveLogic,
                    enabled = selective,
                    options = listOf(
                        0 to stringResource(R.string.wbedit_logic_and_any),
                        1 to stringResource(R.string.wbedit_logic_not_any),
                        2 to stringResource(R.string.wbedit_logic_not_all),
                        3 to stringResource(R.string.wbedit_logic_and_all),
                    ),
                    onSelected = { selectiveLogic = it },
                )

                // 角色（仅 @深度 时有意义）
                DropdownSelector(
                    label = stringResource(R.string.wbedit_role),
                    selected = role,
                    enabled = position == 4,
                    options = listOf(
                        0 to stringResource(R.string.wbedit_role_system),
                        1 to stringResource(R.string.wbedit_role_user),
                        2 to stringResource(R.string.wbedit_role_assistant),
                    ),
                    onSelected = { role = it },
                )

                // 概率触发
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.wbedit_use_probability), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = useProbability, onCheckedChange = { useProbability = it })
                }
                if (useProbability) {
                    OutlinedTextField(
                        value = probability,
                        onValueChange = { probability = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.wbedit_probability)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // 匹配选项
                ToggleRow(stringResource(R.string.wbedit_match_whole_words), matchWholeWords) { matchWholeWords = it }
                ToggleRow(stringResource(R.string.wbedit_use_regex), useRegex) { useRegex = it }
                ToggleRow(stringResource(R.string.wbedit_case_sensitive), caseSensitive) { caseSensitive = it }

                // 递归选项
                ToggleRow(stringResource(R.string.wbedit_exclude_recursion), excludeRecursion) { excludeRecursion = it }
                ToggleRow(stringResource(R.string.wbedit_prevent_recursion), preventRecursion) { preventRecursion = it }
                ToggleRow(stringResource(R.string.wbedit_delay_recursion), delayUntilRecursion) { delayUntilRecursion = it }
                if (delayUntilRecursion) {
                    OutlinedTextField(
                        value = delayUntilRecursionLevel,
                        onValueChange = { delayUntilRecursionLevel = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.wbedit_delay_recursion_level)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 备注
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.wbedit_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Save and Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { leaveEditor() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.wbedit_cancel))
                    }
                    FilledTonalButton(
                        enabled = !state.isSaving,
                        onClick = {
                            if (entry != null && bookId != null) {
                                viewModel.saveEntry(bookId, buildUpdatedEntry(entry), onSaved = { if (editorActive) leaveEditor() })
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.wbedit_save_entry))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
