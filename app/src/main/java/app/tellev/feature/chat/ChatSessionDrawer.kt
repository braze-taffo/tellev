package app.tellev.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellev.R
import app.tellev.core.model.ChatSessionSummary

/**
 * dsh 复刻（参考图五）的会话抽屉：顶部「⊕ 新会话」大按钮、「✦ 插件」行、
 * 右对齐的 搜索/筛选/新文件夹 图标行，中间是会话列表（当前会话高亮胶囊、
 * 行尾相对时间 + 归档 + 图钉），「展开其余 N 个会话」，底部「导出会话日志」
 * 全宽描边按钮与「设置」行。
 */
@Composable
fun ChatSessionDrawer(
    sessionGroups: List<CharacterSessionGroup>,
    pinnedSessionIds: Set<String> = emptySet(),
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onTogglePinned: (String) -> Unit = {},
    onOpenPlugins: () -> Unit,
    onExportLog: () -> Unit,
    onOpenSettings: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                DrawerContent(
                    sessionGroups = sessionGroups,
                    pinnedSessionIds = pinnedSessionIds,
                    currentSessionId = currentSessionId,
                    onNewSession = onNewSession,
                    onOpenSession = onOpenSession,
                    onDeleteSession = onDeleteSession,
                    onTogglePinned = onTogglePinned,
                    onOpenPlugins = onOpenPlugins,
                    onExportLog = onExportLog,
                    onOpenSettings = onOpenSettings,
                )
            }
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerContent(
    sessionGroups: List<CharacterSessionGroup>,
    pinnedSessionIds: Set<String>,
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onOpenPlugins: () -> Unit,
    onExportLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var pinnedOnly by remember { mutableStateOf(false) }

    val visibleGroups = remember(sessionGroups, query, pinnedOnly) {
        val filtered = if (pinnedOnly) {
            sessionGroups.mapNotNull { group ->
                group.copy(sessions = group.sessions.filter { it.id in pinnedSessionIds })
                    .takeIf { it.sessions.isNotEmpty() }
            }
        } else {
            sessionGroups
        }
        if (query.isBlank()) {
            filtered
        } else {
            filtered.mapNotNull { group ->
                val matched = group.sessions.filter { it.title.contains(query.trim(), ignoreCase = true) }
                if (matched.isEmpty()) null else group.copy(sessions = matched)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        // ⊕ 新会话：全宽大按钮（参考图五顶部）。
        OutlinedButton(
            onClick = onNewSession,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                Icons.Default.AddCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_drawer_new_session),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // ✦ 插件行。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlugins)
                .padding(vertical = 14.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = stringResource(R.string.drawer_plugins_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.chat_drawer_plugins), style = MaterialTheme.typography.bodyLarge)
        }

        // 右对齐图标行：搜索 / 只看置顶 / 新会话（文件夹+ 位）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) query = ""
            }) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.chat_drawer_search_sessions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { pinnedOnly = !pinnedOnly }) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = stringResource(R.string.drawer_filter_pinned),
                    tint = if (pinnedOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onNewSession) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = stringResource(R.string.chat_drawer_new_session),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showSearch || query.isNotBlank()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chat_drawer_search_sessions)) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }

        if (visibleGroups.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_drawer_no_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                visibleGroups.forEach { group ->
                    item(key = "group_${group.characterId}") {
                        Text(
                            text = group.characterName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (group.isCurrentCharacter) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        )
                    }
                    val expanded = expandedGroup == group.characterId || query.isNotBlank() ||
                        group.sessions.size <= 4
                    // 置顶会话排组内最前（列表本身仍按时间倒序）。
                    val ordered = group.sessions.sortedByDescending { it.id in pinnedSessionIds }
                    val shown = if (expanded) ordered else ordered.take(4)
                    items(shown, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            isCurrent = session.id == currentSessionId,
                            isPinned = session.id in pinnedSessionIds,
                            onOpen = { onOpenSession(session.id) },
                            onDelete = { onDeleteSession(session.id) },
                            onTogglePinned = { onTogglePinned(session.id) },
                        )
                    }
                    if (!expanded && group.sessions.size > 4) {
                        item(key = "expand_${group.characterId}") {
                            Text(
                                text = stringResource(R.string.chat_drawer_expand, group.sessions.size - 4),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { expandedGroup = group.characterId }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // ⬇ 导出会话日志：全宽描边按钮（参考图五底部）。
        OutlinedButton(
            onClick = onExportLog,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_drawer_export_log))
        }

        // ⚙ 设置行（参考图五最底部）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 14.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.chat_drawer_settings), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SessionRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    isPinned: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onTogglePinned: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent || isPinned) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSessionTime(session.lastMessageAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " ···",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = stringResource(R.string.chat_delete_session),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePinned, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    contentDescription = stringResource(
                        if (isPinned) R.string.chat_unpin else R.string.chat_pin,
                    ),
                    modifier = Modifier.size(18.dp),
                    tint = if (isPinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 会话列表的相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期。 */
internal fun formatSessionTime(millis: Long): String {
    if (millis <= 0L) return ""
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_now)
        hours < 1 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_minutes_ago, minutes)
        days < 1 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_hours_ago, hours)
        days < 30 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_days_ago, days)
        else -> {
            val date = java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            "%d/%d/%d".format(date.year, date.monthValue, date.dayOfMonth)
        }
    }
}
