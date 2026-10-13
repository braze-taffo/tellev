package app.tellev.feature.characters

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.tellev.R
import app.tellev.core.storage.BatchImportSource
import app.tellev.core.storage.CharacterBatchImporter
import app.tellev.util.UriUtils

/** 当前分组标签：全部 / 未分组 / 具体分组名（标题位分组键的显示文本）。 */
@Composable
internal fun currentGroupLabel(state: CharactersUiState): String = when (val id = state.activeGroupId) {
    null -> stringResource(R.string.chars_group_all)
    CharactersViewModel.UNGROUPED_GROUP_ID -> stringResource(R.string.chars_group_ungrouped)
    else -> state.groups.firstOrNull { it.id == id }?.label
        ?: stringResource(R.string.chars_group_all)
}

/**
 * 分组弹层：筛选列表（全部 / 未分组 / 各分组）+ 新建 / 重命名 / 删除。
 * dsh 弹层形制（圆角 16、surface 底、行高 45 上下）。
 */
@Composable
internal fun DshCharacterGroupSheet(
    groups: List<CharacterGroup>,
    activeGroupId: String?,
    allCount: Int,
    ungroupedCount: Int,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Boolean,
    onRename: (String, String) -> Boolean,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<CharacterGroup?>(null) }
    var renameLabel by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<CharacterGroup?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                Text(
                    stringResource(R.string.chars_group_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    GroupRow(
                        label = stringResource(R.string.chars_group_all),
                        count = allCount,
                        selected = activeGroupId == null,
                        onClick = { onSelect(null) },
                    )
                    GroupRow(
                        label = stringResource(R.string.chars_group_ungrouped),
                        count = ungroupedCount,
                        selected = activeGroupId == CharactersViewModel.UNGROUPED_GROUP_ID,
                        onClick = { onSelect(CharactersViewModel.UNGROUPED_GROUP_ID) },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    groups.forEach { group ->
                        GroupRow(
                            label = group.label,
                            count = group.members.size,
                            selected = activeGroupId == group.id,
                            onClick = { onSelect(group.id) },
                            onRename = {
                                renaming = group
                                renameLabel = group.label
                            },
                            onDelete = { deleting = group },
                        )
                    }
                }
                if (creating) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newLabel,
                            onValueChange = { newLabel = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.chars_group_new_hint)) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                if (onCreate(newLabel)) {
                                    newLabel = ""
                                    creating = false
                                }
                            },
                            enabled = newLabel.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.chars_group_create))
                        }
                    }
                } else {
                    TextButton(
                        onClick = { creating = true },
                        modifier = Modifier.padding(horizontal = 10.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.chars_group_new))
                    }
                }
            }
        }
    }

    renaming?.let { group ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.chars_group_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameLabel,
                    onValueChange = { renameLabel = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onRename(group.id, renameLabel)) renaming = null
                    },
                    enabled = renameLabel.isNotBlank(),
                ) { Text(stringResource(R.string.chars_group_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }

    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chars_group_delete_title)) },
            text = { Text(stringResource(R.string.chars_group_delete_confirm, group.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(group.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.chars_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }
}

/** 分组筛选行：名称 + 计数 + 选中勾；右侧菜单重命名/删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (onRename != null) ({ showMenu = true }) else null,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onRename != null || onDelete != null) {
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chars_more_options_cd),
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    onRename?.let { rename ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chars_group_rename)) },
                            onClick = {
                                rename()
                                showMenu = false
                            },
                        )
                    }
                    onDelete?.let { delete ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.chars_group_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                delete()
                                showMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 卡片指派到分组：多选勾选 + 就地新建分组。 */
@Composable
internal fun CharacterGroupAssignDialog(
    characterName: String,
    groups: List<CharacterGroup>,
    isMember: (CharacterGroup) -> Boolean,
    onToggle: (groupId: String, member: Boolean) -> Unit,
    onCreate: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.chars_assign_group_title, characterName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.chars_group_none_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggle(group.id, !isMember(group)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isMember(group),
                            onCheckedChange = { onToggle(group.id, it) },
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(group.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chars_group_new_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (onCreate(newLabel)) newLabel = ""
                        },
                        enabled = newLabel.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.chars_group_create))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.nav_got_it)) }
        },
    )
}

/**
 * 目录扫描：SAF 树递归找角色卡文件（png/webp/json），逐项读入为导入源。
 * 读失败的文件保留在批里（readError），让导入报告显示失败而不是静默丢弃。
 * 依赖 contentResolver 而非 java.io，兼容 Android 11+ 的存储访问框架。
 */
internal fun scanCharacterTree(context: Context, treeUri: Uri): List<BatchImportSource> {
    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri) ?: return emptyList()
    val results = mutableListOf<BatchImportSource>()
    val queue = ArrayDeque<String>()
    queue.add(docId)

    while (queue.isNotEmpty()) {
        val currentDocId = queue.removeFirst()
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            currentDocId,
        )
        val cursor = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )
        }.getOrNull() ?: continue

        cursor.use { c ->
            val idIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0) return@use
            while (c.moveToNext()) {
                val childId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx).orEmpty()
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    queue.add(childId)
                    continue
                }
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in setOf("png", "webp", "json")) continue
                val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                try {
                    results.add(
                        BatchImportSource(
                            fileName = name.ifBlank { "scanned_character" },
                            bytes = UriUtils.readBounded(
                                context,
                                fileUri,
                                CharacterBatchImporter.IMPORT_MAX_BYTES,
                            ),
                        ),
                    )
                } catch (e: Exception) {
                    results.add(
                        BatchImportSource(
                            name.ifBlank { "scanned_character" },
                            null,
                            readError = e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
        }
    }
    return results
}
