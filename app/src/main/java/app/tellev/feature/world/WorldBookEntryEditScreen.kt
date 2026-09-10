package app.tellev.feature.world

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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
                title = { Text(if (entry?.content?.isBlank() == true || entry?.keys?.isEmpty() == true) "新建条目" else "编辑条目") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (entry != null && bookId != null) {
                                if (viewModel.saveEntry(bookId, buildUpdatedEntry(entry))) onBack()
                            }
                        },
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "保存条目")
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
                        TextButton(onClick = onBack) { Text("返回世界书") }
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
                // Keys
                OutlinedTextField(
                    value = keys,
                    onValueChange = { keys = it },
                    label = { Text("关键词（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Secondary Keys
                OutlinedTextField(
                    value = secondaryKeys,
                    onValueChange = { secondaryKeys = it },
                    label = { Text("次级关键词（逗号分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                // Content
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
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
                    Text("启用", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择性触发", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = selective, onCheckedChange = { selective = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("常驻注入", style = MaterialTheme.typography.bodyLarge)
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
                        label = { Text("优先级") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = insertionOrder,
                        onValueChange = { insertionOrder = it },
                        label = { Text("插入顺序") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = depth,
                        onValueChange = { depth = it },
                        label = { Text("深度") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }

                // 注入位置（ST 8 种 position）
                DropdownSelector(
                    label = "注入位置",
                    selected = position,
                    options = listOf(
                        0 to "角色前",
                        1 to "角色后",
                        2 to "作者注前",
                        3 to "作者注后",
                        4 to "@深度",
                        5 to "示例对话前",
                        6 to "示例对话后",
                        7 to "插槽",
                    ),
                    onSelected = { position = it },
                )

                // 选择性逻辑（仅 selective 时有意义）
                DropdownSelector(
                    label = "选择性逻辑",
                    selected = selectiveLogic,
                    enabled = selective,
                    options = listOf(
                        0 to "AND 任一匹配",
                        1 to "NOT 任一不匹配",
                        2 to "NOT 全不匹配",
                        3 to "AND 全部匹配",
                    ),
                    onSelected = { selectiveLogic = it },
                )

                // 角色（仅 @深度 时有意义）
                DropdownSelector(
                    label = "角色",
                    selected = role,
                    enabled = position == 4,
                    options = listOf(
                        0 to "System",
                        1 to "User",
                        2 to "Assistant",
                    ),
                    onSelected = { role = it },
                )

                // 概率触发
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("概率触发", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = useProbability, onCheckedChange = { useProbability = it })
                }
                if (useProbability) {
                    OutlinedTextField(
                        value = probability,
                        onValueChange = { probability = it.filter { c -> c.isDigit() } },
                        label = { Text("概率 (0-100)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // 匹配选项
                ToggleRow("全词匹配", matchWholeWords) { matchWholeWords = it }
                ToggleRow("正则匹配", useRegex) { useRegex = it }
                ToggleRow("区分大小写", caseSensitive) { caseSensitive = it }

                // 递归选项
                ToggleRow("排除递归（不喂入递归文本）", excludeRecursion) { excludeRecursion = it }
                ToggleRow("阻止递归（不触发后续激活）", preventRecursion) { preventRecursion = it }
                ToggleRow("延迟到递归轮", delayUntilRecursion) { delayUntilRecursion = it }
                if (delayUntilRecursion) {
                    OutlinedTextField(
                        value = delayUntilRecursionLevel,
                        onValueChange = { delayUntilRecursionLevel = it.filter(Char::isDigit) },
                        label = { Text("递归层级（第几轮递归开始可激活，需开启递归扫描）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 备注
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // Save and Cancel buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                    FilledTonalButton(
                        onClick = {
                            if (entry != null && bookId != null) {
                                viewModel.saveEntry(bookId, buildUpdatedEntry(entry))
                                onBack()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存条目")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
