package app.tellev.ui.dsh

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.R
import app.tellev.core.model.ChatSessionSummary
import app.tellev.feature.chat.CharacterSessionGroup

/**
 * dsh 抽屉官方复刻（SidebarRoot.module.css + Rows.module.css + dshmob 实测）：
 * 宽 min(88vw, 280)（SIDEBAR_DEFAULT=280）、底 specific-sidebar-fill、
 * 内边距 6/12；新会话按钮 38 高、0.5px border-l3、radius-md、button-elevated 底；
 * 分组头 34 高、会话行 32 高 radius-md（标题 14/20、时间 10sp/16 tertiary）、
 * 行距 2px；底部设置入口 42 高。树语义（forkAt）：子会话缩进 16dp。
 */
@Composable
fun DshDrawer(
    groups: List<CharacterSessionGroup>,
    pinnedIds: Set<String>,
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (ChatSessionSummary) -> Unit,
    onRenameSession: (ChatSessionSummary) -> Unit,
    onTogglePinned: (String) -> Unit,
    onOpenPlugins: () -> Unit,
    onExportLog: () -> Unit,
    onOpenSettings: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState,
    content: @Composable () -> Unit,
) {
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 展开状态：分组（角色卡）默认全收起、当前角色组自动展开；父会话默认收起、
    // 当前会话的父链自动展开。首次数据到达时各补一次种子。
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var expandedSessions by remember { mutableStateOf(setOf<String>()) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(groups, currentSessionId) {
        if (!seeded && groups.isNotEmpty()) {
            seeded = true
            groups.firstOrNull { it.isCurrentCharacter }?.let { expandedGroups = setOf(it.characterId) }
        }
        if (currentSessionId != null) {
            groups.forEach { group ->
                group.sessions.firstOrNull { it.id == currentSessionId }?.parentId?.let { parentId ->
                    expandedGroups = expandedGroups + group.characterId
                    expandedSessions = expandedSessions + parentId
                }
            }
        }
    }
    val drawerWidth = minOf(LocalConfiguration.current.screenWidthDp.dp * 0.88f, 280.dp)
    val sessionShape = RoundedCornerShape(Dsh.RADIUS_MD.dp)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            Surface(
                modifier = Modifier.fillMaxHeight().width(drawerWidth),
                color = Dsh.sidebarFill,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    // ⊕ 新会话（官方 .newSession：38 高、border 0.5 l3、radius-md、elevated 底）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(38.dp)
                            .clip(sessionShape)
                            .background(if (Dsh.palette.isDark) Color(0xFF43454A) else Color.White)
                            .border(0.5.dp, Dsh.borderL3, sessionShape)
                            .clickable(onClick = onNewSession)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Dsh.textPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.chat_drawer_new_session),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = Dsh.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // ✦ 插件行（面板行：36 高、radius-md、hover 面）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(sessionShape)
                            .clickable(onClick = onOpenPlugins)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Dsh.textSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.chat_drawer_plugins),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // 区块头 36 高：左侧留白，右侧搜索 / 导出（28×28 图标钮）。
                    Row(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(sessionShape)
                                .clickable { searchActive = !searchActive },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.chat_drawer_search_sessions),
                                tint = Dsh.textTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(28.dp).clip(sessionShape)
                                .clickable(onClick = onExportLog),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_drawer_export_log),
                                tint = Dsh.textTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (searchActive) {
                        // 官方展开态搜索框：30 高、border 0.5 l4、radius-md、13sp。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .height(30.dp)
                                .clip(sessionShape)
                                .border(0.5.dp, Dsh.borderL4, sessionShape),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search, null,
                                tint = Dsh.textTertiary,
                                modifier = Modifier.padding(start = 8.dp).size(12.dp),
                            )
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,

                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = Dsh.textPrimary,
                                ),
                                decorationBox = { inner ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f).padding(start = 6.dp)) {
                                            if (query.isEmpty()) {
                                                Text(
                                                    stringResource(R.string.chat_drawer_search_sessions),
                                                    fontSize = 13.sp,
                                                    color = Dsh.textTertiary,
                                                    maxLines = 1,
                                                )
                                            }
                                            inner()
                                        }
                                        if (query.isNotEmpty()) {
                                            Icon(
                                                DshIcons.Close,
                                                contentDescription = stringResource(R.string.dsh_drawer_clear_search),
                                                tint = Dsh.textTertiary,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                                    .clickable { query = "" }
                                                    .padding(4.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))

                    val trimmedQuery = query.trim()
                    val visibleGroups = remember(groups, trimmedQuery) {
                        if (trimmedQuery.isBlank()) groups
                        else groups.mapNotNull { group ->
                            val hit = group.sessions.filter { it.title.contains(trimmedQuery, ignoreCase = true) }
                            if (hit.isEmpty()) null else group.copy(sessions = hit)
                        }
                    }
                    if (visibleGroups.isEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.chat_drawer_no_sessions),
                                fontSize = 13.sp,
                                color = Dsh.textTertiary,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            visibleGroups.forEach { group ->
                                item(key = "g-${group.characterId}") {
                                    DshGroupHeader(
                                        title = group.characterName,
                                        count = group.sessions.size,
                                        highlighted = group.isCurrentCharacter,
                                        expanded = group.characterId in expandedGroups || trimmedQuery.isNotBlank(),
                                        onToggle = {
                                            expandedGroups = if (group.characterId in expandedGroups) {
                                                expandedGroups - group.characterId
                                            } else {
                                                expandedGroups + group.characterId
                                            }
                                        },
                                    )
                                }
                                if (group.characterId in expandedGroups || trimmedQuery.isNotBlank()) {
                                    treeRows(
                                        sessions = orderedSessions(group.sessions, pinnedIds),
                                        childrenOf = { parentId -> group.sessions.filter { it.parentId == parentId } },
                                        expandedSessions = expandedSessions,
                                    ) { session, depth ->
                                        DshSessionRow(
                                            session = session,
                                            isCurrent = session.id == currentSessionId,
                                            isPinned = session.id in pinnedIds,
                                            depth = depth,
                                            hasChildren = group.sessions.any { it.parentId == session.id },
                                            expanded = session.id in expandedSessions,
                                            onOpen = { onOpenSession(session.id) },
                                            onDelete = { onDeleteSession(session) },
                                            onRename = { onRenameSession(session) },
                                            onTogglePinned = { onTogglePinned(session.id) },
                                            onToggleExpanded = {
                                                expandedSessions = if (session.id in expandedSessions) {
                                                    expandedSessions - session.id
                                                } else {
                                                    expandedSessions + session.id
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 底部：设置行（官方 .trigger：42 高）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(sessionShape)
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = Dsh.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.chat_drawer_settings),
                            fontSize = 14.sp,
                            lineHeight = 22.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                }
            }
        },
        content = content,
    )
}

/**
 * 树形行展开：父会话先输出自身行，若展开则缩进输出其子会话。
 * 放在 LazyListScope 上直接组合 item。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.treeRows(
    sessions: List<ChatSessionSummary>,
    childrenOf: (String) -> List<ChatSessionSummary>,
    expandedSessions: Set<String>,
    row: @Composable (ChatSessionSummary, Int) -> Unit,
) {
    fun emitTree(parentId: String?, depth: Int) {
        sessions.filter { it.parentId == parentId }.forEach { node ->
            item(key = node.id) { row(node, depth) }
            if (node.id in expandedSessions) emitTree(node.id, depth + 1)
        }
    }
    emitTree(null, 0)
    // 悬挂子会话（父会话被删/损坏）：按普通行平铺，避免丢数据。
    val orphans = sessions.filter { it.parentId != null && sessions.none { s -> s.id == it.parentId } }
    orphans.forEach { orphan ->
        item(key = "orphan-${orphan.id}") { row(orphan, 0) }
    }
}

/** 组内排序：置顶优先 → 最近更新优先。 */
private fun orderedSessions(sessions: List<ChatSessionSummary>, pinned: Set<String> = emptySet()): List<ChatSessionSummary> =
    sessions.sortedWith(compareByDescending<ChatSessionSummary> { it.id in pinned }.thenByDescending { it.lastMessageAtMillis })

/** 分组头（官方 projectRow：34 高、radius-md、标题 14/20 tertiary）。 */
@Composable
private fun DshGroupHeader(
    title: String,
    count: Int,
    highlighted: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.ifBlank { stringResource(R.string.chat_drawer_ungrouped) },
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
            color = if (highlighted) Dsh.textPrimary else Dsh.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$count", fontSize = 10.sp, lineHeight = 16.sp, color = Dsh.textTertiary)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = Dsh.textTertiary,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** 会话行（官方 .sessionRow：32 高、radius-md、选中=hover 面、标题 14/20、时间 10/16；长按=重命名）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DshSessionRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    isPinned: Boolean,
    depth: Int,
    hasChildren: Boolean,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .padding(start = (8 + depth * 16).dp)
            .height(32.dp)
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .background(if (isCurrent) Dsh.hover else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onRename)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                    .clickable(onClick = onToggleExpanded),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Dsh.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        } else {
            Spacer(Modifier.size(16.dp))
        }
        Text(
            text = session.title,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 6.dp),
        )
        Text(
            text = dshCompactTime(session.lastMessageAtMillis),
            fontSize = 10.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
        )
        Spacer(Modifier.width(2.dp))
        // 功能键（⋮）：重命名 / 置顶切换 / 删除——长按重命名不可发现，
        // 可见入口必须常驻；行上不再铺两个小图标。
        var rowMenu by remember { mutableStateOf(false) }
        Box {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.chat_more_options),
                tint = Dsh.textTertiary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                    .clickable { rowMenu = true }
                    .padding(4.dp),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = rowMenu,
                onDismissRequest = { rowMenu = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_rename_session), fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = Dsh.textSecondary, modifier = Modifier.size(15.dp)) },
                    onClick = { rowMenu = false; onRename() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.chat_unpin else R.string.chat_pin), fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            DshIcons.Pin, null,
                            tint = if (isPinned) Dsh.blue else Dsh.textSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                    },
                    onClick = { rowMenu = false; onTogglePinned() },
                )
                androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_delete_session), fontSize = 14.sp, color = Dsh.errorPrimary) },
                    leadingIcon = { Icon(DshIcons.Trash, null, tint = Dsh.errorPrimary, modifier = Modifier.size(15.dp)) },
                    onClick = { rowMenu = false; onDelete() },
                )
            }
        }
    }
}

/** dsh 紧凑相对时间：刚刚 / N小时 / N天 / yyyy/M/d。 */
internal fun dshCompactTime(millis: Long): String {
    if (millis <= 0L) return ""
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 60 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_now)
        hours < 24 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_hours_short, hours)
        days < 30 -> app.tellev.core.i18n.UiStrings.get(app.tellev.core.i18n.S.time_days_short, days)
        else -> {
            val date = java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        }
    }
}
