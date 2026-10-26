package app.tellev.ui.dsh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellev.R
import app.tellev.core.model.ChatSessionSummary

/**
 * dsh 抽屉（图五）像素复刻：⊕ 新会话大按钮、✦ 插件行、右对齐图标行
 * （搜索）、会话行（标题+紧凑时间+删除）、
 * 「展开其余 N 个会话」与底部「设置」行。
 */
@Composable
fun DshDrawer(
    enabled: Boolean,
    sessions: List<ChatSessionSummary>,
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (ChatSessionSummary) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenSettings: () -> Unit,
    drawerState: androidx.compose.material3.DrawerState,
    chatState: app.tellev.feature.chat.ChatUiState,
    content: @Composable (app.tellev.feature.chat.ChatUiState) -> Unit,
) {
    if (!enabled) {
        content(chatState)
        return
    }
    var searchActive by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = enabled && drawerState.isOpen,
        drawerContent = {
            if (enabled) Surface(
                modifier = Modifier.fillMaxHeight().width(300.dp),
                color = Dsh.bgSurface,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    // ⊕ 新会话（上游 session-log 同款描边按钮形制：34dp 高、12dp 圆角）。
                    OutlinedButton(
                        onClick = onNewSession,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Dsh.textPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.chat_drawer_new_session),
                            fontSize = 15.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    // ✦ 插件行。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenPlugins)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Dsh.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.chat_drawer_plugins),
                            fontSize = 15.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Session search.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                .clickable { searchActive = !searchActive },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.chat_drawer_search_sessions), tint = Dsh.textSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    if (searchActive) {
                        Text(
                            stringResource(R.string.chat_drawer_search_sessions),
                            fontSize = 13.sp,
                            color = Dsh.textTertiary,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                color = Dsh.textPrimary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Dsh.bgBase)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    val visible = remember(sessions, query) {
                        if (query.isBlank()) sessions
                        else sessions.filter { it.title.contains(query.trim(), ignoreCase = true) }
                    }
                    if (visible.isEmpty()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.chat_drawer_no_sessions),
                                fontSize = 13.sp,
                                color = Dsh.textTertiary,
                            )
                        }
                    } else {
                        // Initially show the first five sessions.
                        val ordered = visible
                        val shown = if (expanded || query.isNotBlank()) ordered else ordered.take(5)
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(shown, key = { it.id }) { session ->
                                DshSessionRow(
                                    session = session,
                                    isCurrent = session.id == currentSessionId,
                                    onOpen = { onOpenSession(session.id) },
                                    onDelete = { onDeleteSession(session) },
                                )
                            }
                            if (!expanded && ordered.size > 5 && query.isBlank()) {
                                item(key = "expand") {
                                    Text(
                                        stringResource(R.string.chat_drawer_expand, ordered.size - 5),
                                        fontSize = 13.sp,
                                        color = Dsh.textTertiary,
                                        modifier = Modifier
                                            .clickable { expanded = true }
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Settings remains reachable from the drawer.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            tint = Dsh.textPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.chat_drawer_settings),
                            fontSize = 15.sp,
                            color = Dsh.textPrimary,
                        )
                    }
                }
            }
        },
        content = { content(chatState) },
    )
}

@Composable
private fun DshSessionRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) Dsh.hover else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.title,
            fontSize = 14.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = dshCompactTime(session.lastMessageAtMillis),
            fontSize = 12.sp,
            color = Dsh.textTertiary,
        )
        Spacer(Modifier.width(4.dp))
        // Deletion is confirmed by the caller.
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.chat_delete_session),
                tint = Dsh.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }

    }
}

/** dsh 紧凑相对时间：刚刚 / N小时 / N天 / yyyy/M/d。 */
@Composable
internal fun dshCompactTime(millis: Long): String {
    if (millis <= 0L) return ""
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 60 -> stringResource(R.string.time_now)
        hours < 24 -> stringResource(R.string.time_hours_short, hours)
        days < 30 -> stringResource(R.string.time_days_short, days)
        else -> {
            val date = java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            "${date.year}/${date.monthValue}/${date.dayOfMonth}"
        }
    }
}
