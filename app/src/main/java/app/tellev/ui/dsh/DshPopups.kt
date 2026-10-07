package app.tellev.ui.dsh

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.tellev.R
import app.tellev.core.model.ReasoningEffort
import app.tellev.feature.chat.ModelOption
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * dsh 弹层官方复刻（ModelSelect.module.css / ContextMeter.module.css /
 * dshmob agent-preset sheet 规格）。共同形制：radius-lg=16、padding 4、
 * menu-surface 底、elevation-prominent；行 min-height 34、radius-md、13sp。
 * 弹层一律 Popup 锚定在 composer 上方。
 */

// ── 模型/思考档位组合弹层（图一+图二：root=滑杆+模型行，drill=搜索分组列表） ──

/**
 * composer 模型键弹层（harness ModelSelect 复刻）：
 * root = 两行（模型行「名称 · 当前值 ›」+ 思考强度行「档位 · 当前值 ›」）；
 * 点模型行钻入分组模型列表（搜索 + 自定义分组 + 目录 + 最近），
 * 点档位行钻入档位单选列表。选择即提交并关闭，钻入面板可返回。
 */
@Composable
internal fun DshModelEffortMenu(
    options: List<ModelOption>,
    groups: List<app.tellev.feature.chat.DshModelGroup>,
    catalog: app.tellev.feature.chat.ModelCatalogState,
    currentModel: String?,
    currentEffort: ReasoningEffort,
    effortLevels: List<ReasoningEffort>,
    currentEffortLabel: String,
    modelLabel: String?,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (ReasoningEffort) -> Unit,
    onRefreshCatalog: () -> Unit,
    onAssignGroup: (modelId: String, groupId: String, member: Boolean) -> Unit,
    onCreateGroup: (label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pane by remember { mutableStateOf("root") }
    var query by remember { mutableStateOf("") }
    var assignFor by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val cardWidth = minOf(280.dp, configuration.screenWidthDp.dp - 32.dp).coerceAtLeast(220.dp)
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(Dsh.RADIUS_LG.dp),
            color = Dsh.menuSurface,
            shadowElevation = 4.dp,
            modifier = Modifier.width(cardWidth),
        ) {
            if (pane == "root") {
                // root：两行（官方 Model/Effort 行对），行高 45、右侧当前值 + ›。
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 45.dp)
                            .clickable { pane = "models" }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.model_picker_title),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Dsh.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            modelLabel.orEmpty(),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFF4D70FF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 130.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                        Text(
                            "›",
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                            color = Dsh.textTertiary.copy(alpha = 0.42f),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    HorizontalDivider(color = Color(0x29797E91))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 45.dp)
                            .clickable { pane = "effort" }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.dsh_model_levels),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = Dsh.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(currentEffortLabel, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF4D70FF))
                        Text(
                            "›",
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                            color = Dsh.textTertiary.copy(alpha = 0.42f),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            } else if (pane == "effort") {
                Column {
                    DshDrillHeader(onBack = { pane = "root" }, title = stringResource(R.string.dsh_model_levels))
                    val autoLabel = stringResource(R.string.chat_reasoning_effort_auto)
                    val effortLabels = effortLevels.filter { it != ReasoningEffort.Auto }.associateWith { dshEffortLabel(it) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        val rows = buildList {
                            add(null to autoLabel)
                            effortLabels.forEach { (lvl, lbl) -> add(lvl to lbl) }
                        }
                        items(rows, key = { it.second }) { (level, label) ->
                            val selected = level == currentEffort || (level == null && currentEffort == ReasoningEffort.Auto)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                                    .background(if (selected) Dsh.hover else Color.Transparent)
                                    .clickable {
                                        onSelectEffort(level ?: ReasoningEffort.Auto)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    color = Dsh.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Dsh.textPrimary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Column {
                    DshDrillHeader(onBack = { pane = "root" }, title = stringResource(R.string.model_picker_search))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Dsh.textTertiary, modifier = Modifier.size(14.dp)) },
                            placeholder = { Text(stringResource(R.string.model_picker_search), fontSize = 14.sp, color = Dsh.textTertiary) },
                            shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Dsh.bgLayer1,
                                unfocusedContainerColor = Dsh.bgLayer1,
                                unfocusedBorderColor = Dsh.borderL2,
                                focusedBorderColor = Dsh.borderL3,
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Dsh.textPrimary),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 分区：自定义供应商分组 → 提供者目录（真实 listModels）→
                    // 未入目录的当前/最近兜底。目录分组失败时在组头下标注并可重试。
                    val catalogModels = remember(catalog) { catalog.groups.flatMap { it.models }.toSet() }
                    val inGroupIds = groups.flatMap { it.models }.toSet()
                    val listed = remember(groups, options, catalogModels) {
                        options.filter { it.id !in inGroupIds && it.id !in catalogModels }
                    }
                    val visibleGroups = remember(groups, query) {
                        if (query.isBlank()) groups
                        else groups.mapNotNull { group ->
                            val hit = group.models.filter { it.contains(query.trim(), ignoreCase = true) }
                            if (hit.isEmpty()) null else group.copy(models = hit)
                        }
                    }
                    val visibleCatalog = remember(catalog, query) {
                        if (query.isBlank()) catalog.groups
                        else catalog.groups.mapNotNull { group ->
                            val hit = group.models.filter { it.contains(query.trim(), ignoreCase = true) }
                            if (hit.isEmpty() && group.error == null) null else group.copy(models = hit)
                        }
                    }
                    val visibleListed = remember(listed, query) {
                        if (query.isBlank()) listed
                        else listed.filter { it.id.contains(query.trim(), ignoreCase = true) }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    ) {
                        if (catalog.isLoading) {
                            item(key = "catalog-loading") {
                                Text(
                                    stringResource(R.string.model_catalog_loading),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = Dsh.textTertiary,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        visibleGroups.forEach { group ->
                            item(key = "h-${group.id}") {
                                Text(
                                    group.label,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = Dsh.textTertiary,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(group.models, key = { "g-${group.id}/${it}" }) { modelId ->
                                DshModelRow(
                                    modelId = modelId,
                                    isCurrent = modelId == currentModel,
                                    onSelect = { onSelectModel(modelId) },
                                    onLongPress = { assignFor = modelId },
                                )
                            }
                        }
                        visibleCatalog.forEach { group ->
                            item(key = "h-cat-${group.id}") {
                                Column {
                                    Text(
                                        group.label,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        color = Dsh.textTertiary,
                                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                    )
                                    if (group.isFailed) {
                                        Row(
                                            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                stringResource(R.string.model_catalog_failed, group.error.orEmpty()),
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                color = Dsh.errorPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            Text(
                                                stringResource(R.string.model_catalog_retry),
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                color = Dsh.blue,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                                                    .clickable(onClick = onRefreshCatalog)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            if (!group.isFailed) {
                                items(group.models, key = { "c-${group.id}/${it}" }) { modelId ->
                                    DshModelRow(
                                        modelId = modelId,
                                        isCurrent = modelId == currentModel,
                                        onSelect = { onSelectModel(modelId) },
                                        onLongPress = { assignFor = modelId },
                                    )
                                }
                            }
                        }
                        if (visibleListed.isNotEmpty()) {
                            item(key = "h-recent") {
                                Text(
                                    stringResource(R.string.model_group_recent),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = Dsh.textTertiary,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(visibleListed, key = { "r-${it.id}" }) { option ->
                                DshModelRow(
                                    modelId = option.id,
                                    isCurrent = option.isCurrent,
                                    onSelect = { onSelectModel(option.id) },
                                    onLongPress = { assignFor = option.id },
                                )
                            }
                        }
                        if (!catalog.isLoading && visibleGroups.isEmpty() && visibleListed.isEmpty() &&
                            visibleCatalog.all { it.isFailed || it.models.isEmpty() }
                        ) {
                            item(key = "empty") {
                                Text(
                                    stringResource(R.string.model_catalog_empty),
                                    fontSize = 13.sp,
                                    color = Dsh.textTertiary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    assignFor?.let { modelId ->
        DshModelGroupDialog(
            modelId = modelId,
            groups = groups,
            onToggle = { groupId, member -> onAssignGroup(modelId, groupId, member) },
            onCreate = { label -> onCreateGroup(label) },
            onDismiss = { assignFor = null },
        )
    }
}

/** 钻入面板头部：‹ 返回 + 标题。 */
@Composable
private fun DshDrillHeader(onBack: () -> Unit, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹",
            fontSize = 20.sp,
            color = Dsh.textSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Dsh.textPrimary,
        )
    }
}

/** 模型行：单击选择，长按指派供应商分组（图一密度、缩窄版）。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DshModelRow(
    modelId: String,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .background(if (isCurrent) Dsh.hover else Color.Transparent)
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modelId,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isCurrent) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Dsh.textPrimary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** 供应商分组指派对话框：勾选成员关系 + 新建分组（自定义 id/名）。 */
@Composable
internal fun DshModelGroupDialog(
    modelId: String,
    groups: List<app.tellev.feature.chat.DshModelGroup>,
    onToggle: (groupId: String, member: Boolean) -> Unit,
    onCreate: (label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newLabel by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.model_group_assign_title, modelId),
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp)) {
                if (groups.isEmpty()) {
                    Text(
                        stringResource(R.string.model_group_none_hint),
                        fontSize = 13.sp,
                        color = Dsh.textTertiary,
                    )
                }
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                            .clickable { onToggle(group.id, modelId !in group.models) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = modelId in group.models,
                            onCheckedChange = { onToggle(group.id, it) },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(group.label, fontSize = 14.sp, color = Dsh.textPrimary)
                    }
                }
                HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.model_group_new_hint), fontSize = 13.sp, color = Dsh.textTertiary) },
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Dsh.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = {
                            if (newLabel.isNotBlank()) {
                                onCreate(newLabel.trim())
                                newLabel = ""
                            }
                        },
                        enabled = newLabel.isNotBlank(),
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                    ) {
                        Text(
                            stringResource(R.string.model_group_create),
                            fontSize = 13.sp,
                            color = Dsh.blue,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_cancel))
            }
        },
    )
}

/** 滑杆上的档位短标（上游 off/minimal/…/max 风格，用 tellev 档位资源）。 */
@Composable
internal fun dshEffortLabel(effort: ReasoningEffort): String = stringResource(
    when (effort) {
        ReasoningEffort.Auto -> R.string.chat_reasoning_effort_auto
        ReasoningEffort.Off -> R.string.chat_reasoning_effort_off
        ReasoningEffort.Minimal -> R.string.chat_reasoning_effort_minimal
        ReasoningEffort.Low -> R.string.chat_reasoning_effort_low
        ReasoningEffort.Medium -> R.string.chat_reasoning_effort_medium
        ReasoningEffort.High -> R.string.chat_reasoning_effort_high
        ReasoningEffort.XHigh -> R.string.chat_reasoning_effort_xhigh
        ReasoningEffort.Max -> R.string.chat_reasoning_effort_max
    },
)

// ── 上下文分段弹层（官方 ContextMeter 面板：264 宽、12sp、4px 分段条） ──

@Composable
internal fun DshContextPopover(
    usedPercent: Int,
    usedLabel: String,
    limitLabel: String,
    systemTokens: Long,
    worldTokens: Long,
    messageTokens: Long,
    systemLabel: String,
    worldLabel: String,
    messagesLabel: String,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomEnd,
        offset = androidx.compose.ui.unit.IntOffset(-with(density) { 12.dp.roundToPx() }, -with(density) { 120.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(264.dp)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ctx_usage_title, "$usedPercent%"),
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.ctx_usage_total, usedLabel, limitLabel),
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = Dsh.textTertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            val total = (systemTokens + worldTokens + messageTokens).coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                SegmentBar(Dsh.segSystem, systemTokens.toFloat() / total)
                SegmentBar(Dsh.segWorld, worldTokens.toFloat() / total)
                SegmentBar(Dsh.segMessages, messageTokens.toFloat() / total)
            }
            Spacer(Modifier.height(12.dp))
            LegendRow(Dsh.segSystem, systemLabel, systemTokens)
            LegendRow(Dsh.segWorld, worldLabel, worldTokens)
            LegendRow(Dsh.segMessages, messagesLabel, messageTokens)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SegmentBar(color: Color, fraction: Float) {
    Box(
        modifier = Modifier
            .weight(kotlin.math.max(0.02f, fraction))
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color),
    )
}

@Composable
private fun LegendRow(color: Color, label: String, tokens: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, lineHeight = 20.sp, color = Dsh.textSecondary, modifier = Modifier.weight(1f))
        Text("~" + dshCompactTokens(tokens), fontSize = 12.sp, lineHeight = 20.sp, color = Dsh.textSecondary)
    }
}

// ── 集合键弹层（预设 / 用户设定 / 世界书，dshmob agent-preset sheet 规格） ──

/** 弹层里的一行：id 是回调载荷，selected 画对勾。 */
internal data class DshCollectionItem(
    val id: String,
    val label: String,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit = {},
)

@Composable
internal fun DshCollectionSheet(
    title: String,
    items: List<DshCollectionItem>,
    emptyLabel: String? = null,
    footer: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxHeight = minOf(
        with(density) { (configuration.screenHeightDp * 0.55f).dp },
        440.dp,
    )
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            // 拖拽把手（36×4dp 圆条）。
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (items.isEmpty() && emptyLabel != null) {
                Text(
                    emptyLabel,
                    fontSize = 13.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                )
            } else {
                // 条目少时按内容收缩，避免 LazyColumn 占满上限留出大片空白。
                val listHeight = minOf(items.size * 35 + 8, maxHeight.value.roundToInt()).dp
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(listHeight),
                ) {
                    items(items, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                                .background(if (item.selected) Dsh.hover else Color.Transparent)
                                .clickable { item.onClick(); onDismiss() }
                                .heightIn(min = 34.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.label,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = if (item.danger) Dsh.errorPrimary else Dsh.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (item.selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Dsh.textPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            footer?.invoke(this)
        }
    }
}

/** ~9K / ~1.2M / ~536。 */
internal fun dshCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000L -> (tokens / 100_000L / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}M" else "%.1fM".format(it) }
    tokens >= 1_000L -> (tokens / 100L / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}K" else "%.1fK".format(it) }
    else -> tokens.toString()
}

// ── 顶栏合并键：集合 sheet（预设点选 / 用户设定 / 世界书入口） ─────────

@Composable
internal fun DshCollectionsSheet(
    title: String,
    presetsLabel: String,
    personaLabel: String,
    worldLabel: String,
    presetItems: List<DshCollectionItem>,
    personaName: String,
    worldName: String?,
    onOpenPersona: () -> Unit,
    onOpenWorld: () -> Unit,
    onOpenPresetEditor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxHeight = minOf(
        with(density) { (configuration.screenHeightDp * 0.55f).dp },
        440.dp,
    )
    val listHeight = minOf(presetItems.size * 35 + 8, maxHeight.value.roundToInt()).dp
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textPrimary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // 预设列表（点选即提交，对勾标当前）。
            Text(
                presetsLabel,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (presetItems.isEmpty()) {
                Text(
                    stringResource(R.string.chat_empty_presets),
                    fontSize = 13.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(listHeight),
                ) {
                    items(presetItems, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                                .background(if (item.selected) Dsh.hover else Color.Transparent)
                                .clickable { item.onClick(); onDismiss() }
                                .heightIn(min = 34.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.label,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Dsh.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (item.selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Dsh.textPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 6.dp))
            // 调整预设 / 用户设定（填写设定）/ 世界书（ST 布局）三个入口行。
            DshCollectionNavRow(
                stringResource(R.string.preset_adjust_entry),
                stringResource(R.string.preset_adjust_hint),
                onOpenPresetEditor,
            )
            DshCollectionNavRow(personaLabel, personaName, onOpenPersona)
            DshCollectionNavRow(worldLabel, worldName ?: stringResource(R.string.chat_panel_world_unbound), onOpenWorld)
        }
    }
}

@Composable
private fun DshCollectionNavRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, color = Dsh.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = Dsh.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Text(
            "›",
            fontSize = 18.sp,
            lineHeight = 18.sp,
            color = Dsh.textTertiary.copy(alpha = 0.42f),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// ── 世界书 sheet（SillyTavern 条目布局 + DSH 弹层外壳） ────────────────

/** ST 世界书条目行：启用圆点 / comment 标题 / keys·顺序·位置 副行 / 常驻锁。 */
@Composable
internal fun DshWorldBookSheet(
    books: List<app.tellev.core.model.WorldBook>,
    boundName: String?,
    onSelectBook: (String?) -> Unit,
    onToggleEntry: (bookId: String, entryId: String) -> Unit,
    onSaveEntry: (bookId: String, entry: app.tellev.core.model.WorldBookEntry) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedBook = books.firstOrNull { it.name == boundName } ?: books.firstOrNull()
    var bookMenu by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            // 书选择器（ST：选书即绑定；未绑定恢复直通）。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.world_select_book),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Surface(
                        onClick = { bookMenu = true },
                        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                        color = Dsh.hover,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                boundName ?: selectedBook?.name ?: stringResource(R.string.chat_panel_world_unbound),
                                fontSize = 12.sp,
                                color = Dsh.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp),
                            )
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = Dsh.textTertiary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                    androidx.compose.material3.DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_panel_world_unbound), fontSize = 14.sp) },
                            onClick = { bookMenu = false; onSelectBook(null); onDismiss() },
                        )
                        books.forEach { book ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(book.name, fontSize = 14.sp) },
                                trailingIcon = {
                                    if (book.name == boundName) {
                                        Icon(Icons.Default.Check, null, tint = Dsh.textPrimary, modifier = Modifier.size(14.dp))
                                    }
                                },
                                onClick = { bookMenu = false; onSelectBook(book.name) },
                            )
                        }
                    }
                }
            }
            // 条目列表（ST 布局：启用圆点 + 标题 + 顺序/位置副行 + 常驻锁）。
            val entries = selectedBook?.entries.orEmpty()
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.world_no_entries),
                    fontSize = 13.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        DshWorldEntryRow(
                            entry = entry,
                            positionLabel = worldPositionLabel(entry.position),
                            onToggle = { selectedBook?.let { onToggleEntry(it.id, entry.id) } },
                            onSave = { updated -> selectedBook?.let { onSaveEntry(it.id, updated) } },
                        )
                    }
                }
            }
            HorizontalDivider(color = Dsh.borderL1, modifier = Modifier.padding(vertical = 6.dp))
            // 管理（跳既有世界书编辑器）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                    .clickable { onManage(); onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = Dsh.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.chat_manage_world_books), fontSize = 13.sp, color = Dsh.textSecondary)
            }
        }
    }
}

@Composable
private fun DshWorldEntryRow(
    entry: app.tellev.core.model.WorldBookEntry,
    positionLabel: String,
    onToggle: () -> Unit,
    onSave: (app.tellev.core.model.WorldBookEntry) -> Unit,
) {
    val title = entry.comment.ifBlank {
        entry.keys.firstOrNull()?.take(24) ?: stringResource(R.string.world_no_entries)
    }
    val subtitle = entry.keys.drop(1).joinToString(", ").take(40).ifBlank { null }
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ST 启用圆点：绿实心=启用，灰描边=停用。
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (entry.enabled) Dsh.statusGreen else Color.Transparent)
                .border(1.5.dp, if (entry.enabled) Dsh.statusGreen else Dsh.borderL3, CircleShape)
                .clickable(onClick = onToggle),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (entry.enabled) Dsh.textPrimary else Dsh.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    subtitle,
                    stringResource(R.string.world_entry_order, entry.insertionOrder),
                    positionLabel,
                ).joinToString(" · "),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Dsh.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.constant) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.world_entry_constant),
                tint = Dsh.statusAmber,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            if (expanded) "⌃" else "⌄",
            fontSize = 14.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
    if (expanded) {
        DshWorldEntryEditor(
            entry = entry,
            onSave = onSave,
            onCollapse = { expanded = false },
        )
    }
}


/** ST world_info_position 的短标。 */
@Composable
internal fun worldPositionLabel(position: Int): String = stringResource(
    when (position) {
        0 -> R.string.world_pos_before
        1 -> R.string.world_pos_after
        2 -> R.string.world_pos_an_top
        3 -> R.string.world_pos_an_bottom
        4 -> R.string.world_pos_at_depth
        5 -> R.string.world_pos_em_top
        6 -> R.string.world_pos_em_bottom
        else -> R.string.world_pos_before
    },
)

/**
 * ST 世界书条目编辑器（world-info drawer 字段子集）：memo 标题、主关键词、
 * 内容、插入顺序、位置、深度、概率、常驻。改动本地暂存，保存写回整本书。
 */
@Composable
private fun DshWorldEntryEditor(
    entry: app.tellev.core.model.WorldBookEntry,
    onSave: (app.tellev.core.model.WorldBookEntry) -> Unit,
    onCollapse: () -> Unit,
) {
    var memo by remember(entry.id) { mutableStateOf(entry.comment) }
    var keys by remember(entry.id) { mutableStateOf(entry.keys.joinToString(", ")) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var order by remember(entry.id) { mutableStateOf(entry.insertionOrder.toString()) }
    var depth by remember(entry.id) { mutableStateOf(entry.depth.toString()) }
    var probability by remember(entry.id) { mutableStateOf(entry.probability.toString()) }
    var position by remember(entry.id) { mutableStateOf(entry.position) }
    var constant by remember(entry.id) { mutableStateOf(entry.constant) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        DshSheetField(memo, { memo = it }, stringResource(R.string.world_edit_title), singleLine = true)
        Spacer(Modifier.height(6.dp))
        DshSheetField(keys, { keys = it }, stringResource(R.string.world_edit_keys), singleLine = true)
        Spacer(Modifier.height(6.dp))
        DshSheetField(content, { content = it }, stringResource(R.string.world_edit_content), singleLine = false)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (0..6).forEach { pos ->
                Surface(
                    onClick = { position = pos },
                    shape = RoundedCornerShape(999.dp),
                    color = if (position == pos) Dsh.hover else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                ) {
                    Text(
                        worldPositionLabel(pos),
                        fontSize = 11.sp,
                        color = if (position == pos) Dsh.textPrimary else Dsh.textTertiary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshNumberField(order, { order = it }, stringResource(R.string.world_edit_order), Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            DshNumberField(depth, { depth = it }, stringResource(R.string.world_edit_depth), Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            DshNumberField(probability, { probability = it }, stringResource(R.string.world_edit_prob), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable { constant = !constant },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = if (constant) Dsh.statusAmber else Dsh.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.world_edit_constant), fontSize = 12.sp, color = Dsh.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            onClick = {
                onSave(
                    entry.copy(
                        comment = memo.trim(),
                        keys = keys.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                        content = content,
                        insertionOrder = order.toIntOrNull() ?: entry.insertionOrder,
                        depth = depth.toIntOrNull() ?: entry.depth,
                        probability = (probability.toIntOrNull() ?: entry.probability).coerceIn(0, 100),
                        position = position,
                        constant = constant,
                    ),
                )
                onCollapse()
            },
            shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
            color = Dsh.hover,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.world_edit_save),
                fontSize = 13.sp,
                color = Dsh.blue,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DshSheetField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    singleLine: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            unfocusedBorderColor = Dsh.borderL2,
            focusedBorderColor = Dsh.borderL3,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Dsh.textPrimary),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DshNumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(6)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            unfocusedBorderColor = Dsh.borderL2,
            focusedBorderColor = Dsh.borderL3,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Dsh.textPrimary),
        modifier = modifier,
    )
}

/**
 * 预设完整调节 sheet（harness PresetSection 语义 + tellev 全字段）：
 * 采样族（温度/Top P/Top K/Top A/Min P）、惩罚族（重复/重复窗口/存在/频率）、
 * 长度族（回复上限/上下文上限）、种子、思考强度。留空=保持当前值。
 */
@Composable
internal fun DshPresetEditorSheet(
    preset: app.tellev.core.model.GenerationPreset,
    onSave: (
        temperature: Double?, topP: Double?, topK: Int?, topA: Double?, minP: Double?,
        repetitionPenalty: Double?, repetitionPenaltyRange: Int?,
        maxTokens: Int?, maxContextTokens: Int?,
        presencePenalty: Double?, frequencyPenalty: Double?,
        seed: Long?, reasoningEffort: ReasoningEffort?,
    ) -> Unit,
    onSaveRaw: (String, (String) -> Unit) -> Unit,
    onSavePrompts: (active: List<app.tellev.core.model.PresetPrompt>, unused: List<app.tellev.core.model.PresetPrompt>) -> Unit,
    onSaveKey: (path: String, value: kotlinx.serialization.json.JsonElement?) -> Unit,
    onDismiss: () -> Unit,
) {
    fun init(v: Double?) = v?.toString().orEmpty()
    fun initI(v: Int?) = v?.toString().orEmpty()
    var temperature by remember(preset.id) { mutableStateOf(init(preset.temperature)) }
    var topP by remember(preset.id) { mutableStateOf(init(preset.topP)) }
    var topK by remember(preset.id) { mutableStateOf(initI(preset.topK)) }
    var topA by remember(preset.id) { mutableStateOf(init(preset.topA)) }
    var minP by remember(preset.id) { mutableStateOf(init(preset.minP)) }
    var repPen by remember(preset.id) { mutableStateOf(init(preset.repetitionPenalty)) }
    var repPenRange by remember(preset.id) { mutableStateOf(initI(preset.repetitionPenaltyRange)) }
    var maxTokens by remember(preset.id) { mutableStateOf(initI(preset.maxCompletionTokens ?: preset.maxTokens)) }
    var maxContext by remember(preset.id) { mutableStateOf(initI(preset.maxContextTokens)) }
    var presencePen by remember(preset.id) { mutableStateOf(init(preset.presencePenalty)) }
    var freqPen by remember(preset.id) { mutableStateOf(init(preset.frequencyPenalty)) }
    var seed by remember(preset.id) { mutableStateOf(preset.seed?.toString().orEmpty()) }
    var effort by remember(preset.id) { mutableStateOf(preset.reasoningEffort) }
    // 两个编辑页：参数（表单字段）/ JSON（整份 raw，覆盖 ST 兼容的所有键）。
    var editPage by remember(preset.id) { mutableStateOf("params") }
    var rawJson by remember(preset.id) {
        mutableStateOf(kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), preset.raw))
    }
    var rawError by remember(preset.id) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = minOf((configuration.screenHeightDp * 0.72f).dp, 560.dp)
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                stringResource(R.string.dsh_preset_full_title, preset.name),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.dsh_preset_full_desc),
                fontSize = 11.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            // 页切换（参数 / JSON）：单选胶囊行。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "params" to stringResource(R.string.dsh_preset_page_params),
                    "prompts" to stringResource(R.string.dsh_preset_page_prompts),
                    "json" to stringResource(R.string.dsh_preset_page_json),
                ).forEach { (key, label) ->
                    Surface(
                        onClick = { editPage = key },
                        shape = RoundedCornerShape(999.dp),
                        color = if (editPage == key) Dsh.hover else Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                    ) {
                        Text(
                            label,
                            fontSize = 11.sp,
                            color = if (editPage == key) Dsh.textPrimary else Dsh.textTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (editPage == "json") {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight)) {
                    OutlinedTextField(
                        value = rawJson,
                        onValueChange = { rawJson = it; rawError = null },
                        label = { Text(stringResource(R.string.dsh_preset_page_json), fontSize = 10.sp) },
                        isError = rawError != null,
                        minLines = 8,
                        maxLines = 16,
                        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            unfocusedBorderColor = Dsh.borderL2,
                            focusedBorderColor = Dsh.borderL3,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Dsh.textPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    rawError?.let {
                        Text(it, fontSize = 11.sp, color = Dsh.errorPrimary, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = { onSaveRaw(rawJson) { rawError = it } },
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.world_edit_save),
                            fontSize = 14.sp,
                            color = Dsh.blue,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                        )
                    }
                }
            } else if (editPage == "prompts") {
                DshPresetPromptsPage(
                    preset = preset,
                    sheetMaxHeight = sheetMaxHeight,
                    onSave = onSavePrompts,
                    onDismiss = onDismiss,
                )
            } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 采样族
                DshFieldLabel(stringResource(R.string.preset_editor_temperature))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshDecimalField(temperature, { temperature = it }, stringResource(R.string.preset_editor_temperature), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshDecimalField(topP, { topP = it }, stringResource(R.string.preset_editor_top_p), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshNumberField(topK, { topK = it }, stringResource(R.string.preset_editor_top_k), Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshDecimalField(topA, { topA = it }, "Top A", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshDecimalField(minP, { minP = it }, "Min P", Modifier.weight(1f))
                }
                // 惩罚族
                DshFieldLabel(stringResource(R.string.preset_editor_rep_pen))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshDecimalField(repPen, { repPen = it }, stringResource(R.string.preset_editor_rep_pen), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshNumberField(repPenRange, { repPenRange = it }, "Range", Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshDecimalField(presencePen, { presencePen = it }, stringResource(R.string.preset_editor_presence_penalty), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshDecimalField(freqPen, { freqPen = it }, stringResource(R.string.preset_editor_frequency_penalty), Modifier.weight(1f))
                }
                // 长度族
                DshFieldLabel(stringResource(R.string.preset_editor_max_tokens))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshNumberField(maxTokens, { maxTokens = it }, stringResource(R.string.preset_editor_max_tokens), Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DshNumberField(maxContext, { maxContext = it }, stringResource(R.string.preset_editor_max_context), Modifier.weight(1f))
                }
                // 种子 + 思考强度
                DshFieldLabel(stringResource(R.string.preset_editor_seed))
                DshSeedField(seed, { seed = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.dsh_model_levels),
                    fontSize = 11.sp,
                    color = Dsh.textTertiary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val efforts = listOf<ReasoningEffort?>(null, ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.Max)
                    efforts.forEach { level ->
                        Surface(
                            onClick = { effort = level },
                            shape = RoundedCornerShape(999.dp),
                            color = if (effort == level) Dsh.hover else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                        ) {
                            Text(
                                level?.let { dshEffortLabel(it) } ?: stringResource(R.string.chat_reasoning_effort_auto),
                                fontSize = 11.sp,
                                color = if (effort == level) Dsh.textPrimary else Dsh.textTertiary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
                // 更多预设键（raw 拍平全量编辑：n/stream/continue*/names_behavior/
                // wrap_in_quotes/formats/inlining… 不在类型化字段里的键都在这里）。
                DshPresetRawKeysSection(
                    preset = preset,
                    onSaveKey = onSaveKey,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = {
                        onSave(
                            temperature.toDoubleOrNull(),
                            topP.toDoubleOrNull(),
                            topK.toIntOrNull(),
                            topA.toDoubleOrNull(),
                            minP.toDoubleOrNull(),
                            repPen.toDoubleOrNull(),
                            repPenRange.toIntOrNull(),
                            maxTokens.toIntOrNull(),
                            maxContext.toIntOrNull(),
                            presencePen.toDoubleOrNull(),
                            freqPen.toDoubleOrNull(),
                            seed.toLongOrNull(),
                            effort,
                        )
                        onDismiss()
                    },
                    shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                    color = Dsh.hover,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.world_edit_save),
                        fontSize = 14.sp,
                        color = Dsh.blue,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun DshFieldLabel(text: String) {
    Text(text, fontSize = 11.sp, lineHeight = 16.sp, color = Dsh.textTertiary)
}

@Composable
private fun DshSeedField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(19)) },
        label = { Text(stringResource(R.string.preset_editor_seed), fontSize = 10.sp) },
        singleLine = true,
        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            unfocusedBorderColor = Dsh.borderL2,
            focusedBorderColor = Dsh.borderL3,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Dsh.textPrimary),
        modifier = modifier,
    )
}

@Composable
private fun DshDecimalField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '.' || it == '-' }.take(9)) },
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            unfocusedBorderColor = Dsh.borderL2,
            focusedBorderColor = Dsh.borderL3,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Dsh.textPrimary),
        modifier = modifier,
    )
}

// ── Token 使用面板（harness TurnUsagePanel 布局：总量精确值 + 输入/缓存/输出分行） ──

/**
 * 当前会话的 token 使用统计。口径：三个分区（本会话/今日/累计）全部来自同一份
 * 明细记录（generation-metrics.jsonl 最近 100 条）按范围过滤——本会话再按
 * sessionId 过滤、今日按本地日期过滤、累计=全量。输入/缓存命中/输出三行与标题
 * 总量同源相加，杜绝「总量来自日汇总、分项来自明细」的双口径与重复计数。
 * 右上角删除图标清空全部统计。
 */
@Composable
internal fun DshUsagePanel(
    sessionLabel: String,
    sessionBuckets: app.tellev.feature.chat.ChatTokenUsageLedger.Buckets,
    sessionReasoningTokens: Long,
    sessionEstimated: Boolean,
    todayMetrics: List<app.tellev.core.metrics.GenerationMetrics>,
    allMetrics: List<app.tellev.core.metrics.GenerationMetrics>,
    detailBufferNote: String,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.dsh_usage_title),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // 清除统计（dsh_usage_clear）：trash 图标，点击弹确认。
                var confirmClear by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                        .clickable { confirmClear = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        DshIcons.Trash,
                        contentDescription = stringResource(R.string.dsh_usage_clear),
                        tint = Dsh.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (confirmClear) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text(stringResource(R.string.dsh_usage_clear)) },
                        text = { Text(stringResource(R.string.dsh_usage_clear_confirm)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmClear = false
                                onClear()
                            }) { Text(stringResource(R.string.chat_delete), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { confirmClear = false }) {
                                Text(stringResource(R.string.chat_cancel))
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // 本会话：账本桶（metadata 持久累计，无明细缓冲截断）。
            DshUsageLedgerSection(sessionLabel, sessionBuckets, sessionReasoningTokens, sessionEstimated)
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
            Spacer(Modifier.height(10.dp))
            DshUsageSection(stringResource(R.string.dsh_usage_today), todayMetrics)
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
            Spacer(Modifier.height(10.dp))
            DshUsageSection(stringResource(R.string.dsh_usage_total), allMetrics)
            Spacer(Modifier.height(8.dp))
            Text(
                detailBufferNote,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = Dsh.textTertiary,
            )
        }
    }
}

/** 一个统计区：标题行（请求 N · 估算标记）+ 输入/缓存命中/输出三行 + 思考小注。 */
@Composable
private fun DshUsageSection(label: String, metrics: List<app.tellev.core.metrics.GenerationMetrics>) {
    val requests = metrics.size
    val input = metrics.sumOf { it.promptTokens?.toLong() ?: 0L }
    val cacheRead = metrics.sumOf { (it.cacheReadTokens ?: it.cachedTokens)?.toLong() ?: 0L }
    val output = metrics.sumOf { it.completionTokens?.toLong() ?: 0L }
    val total = input + output
    val estimated = metrics.count { it.isEstimate }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                listOfNotNull(
                    stringResource(R.string.dsh_usage_requests, requests),
                    if (estimated > 0) stringResource(R.string.dsh_usage_estimated) else null,
                ).joinToString(" · "),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.textTertiary,
            )
        }
        Text(
            stringResource(R.string.dsh_usage_total) + " · " + java.text.NumberFormat.getIntegerInstance().format(total),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = Dsh.textPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
        DshUsageRow(stringResource(R.string.dsh_usage_input), input)
        DshUsageRow(stringResource(R.string.dsh_usage_cache_read), cacheRead)
        DshUsageRow(stringResource(R.string.dsh_usage_output), output)
    }
}

/** 本会话区（账本口径）：四互斥桶 + 思考子注 + 估算标记；缓存命中不显示假 100%。 */
@Composable
private fun DshUsageLedgerSection(
    label: String,
    buckets: app.tellev.feature.chat.ChatTokenUsageLedger.Buckets,
    reasoningTokens: Long,
    estimated: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (estimated) {
                Text(
                    stringResource(R.string.dsh_usage_estimated),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Dsh.textTertiary,
                )
            }
        }
        val total = buckets.total
        Text(
            java.text.NumberFormat.getIntegerInstance().format(total),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium,
            color = Dsh.textPrimary,
            modifier = Modifier.padding(top = 2.dp),
        )
        DshUsageRow(stringResource(R.string.dsh_usage_input), buckets.uncachedInput)
        DshUsageRow(stringResource(R.string.dsh_usage_cache_read), buckets.cacheRead)
        if (buckets.cacheWrite > 0L) {
            DshUsageRow(stringResource(R.string.dsh_usage_cache_write), buckets.cacheWrite)
        }
        // 思考是输出的子集（dsh 语义：已含在 output 内，只作小注不重复计）。
        if (reasoningTokens > 0L) {
            Text(
                stringResource(R.string.dsh_usage_reasoning, java.text.NumberFormat.getIntegerInstance().format(reasoningTokens)),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
        DshUsageRow(stringResource(R.string.dsh_usage_output), buckets.output)
        // 缓存命中率（诚实百分比：部分命中不四舍五入成 100%）。
        val billed = buckets.billedInput
        if (billed > 0L) {
            Text(
                stringResource(R.string.dsh_usage_cache_hit) + " " + dshCacheHitPercent(buckets.cacheRead, billed),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Dsh.textSecondary,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
    }
}

/**
 * dsh formatCacheHitPercent 移植：整数舍入仍低于 100 用整数；否则补小数位
 * 保持诚实（99.6% 永远不会显示成 100%）；全命中=100。
 */
internal fun dshCacheHitPercent(cacheRead: Long, promptTokens: Long): String {
    if (promptTokens <= 0L) return "-"
    val missed = promptTokens - cacheRead
    if (missed <= 0L) return "100"
    val units = (cacheRead * 1000L * 2L + promptTokens) / (promptTokens * 2L) // round-half-up to 0.1%
    return if (units < 1000L) {
        val whole = units / 10L
        val tenths = units % 10L
        if (tenths == 0L) whole.toString() else "$whole.$tenths"
    } else {
        // 已到 100.0% 但仍有 miss → 99.9…n
        var places = 1
        var gap = missed * 200L
        val tens = promptTokens / 10L
        while (gap <= tens) { gap *= 10L; places += 1 }
        "99." + "9".repeat(places - 1) + ((10L - (missed * 200L * 10L / (promptTokens * 2L) + 5L) / 10L).coerceIn(1L, 9L))
    }
}

@Composable
private fun DshUsageRow(label: String, tokens: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            java.text.NumberFormat.getIntegerInstance().format(tokens),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textPrimary,
        )
    }
}

// ── 模型配置弹层（dsh Models 页布局 + bre EffortEditor 档位网格） ─────────

/**
 * 模型配置（不再跳设置页）：服务商选择 + 接口地址 / API 密钥 / 模型名称 +
 * 默认思考强度（bre 档位胶囊网格）+ 高级 JSON。保存走 ChatViewModel.saveModelConfig。
 */
@Composable
internal fun DshModelConfigSheet(
    providerLabel: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    effortLevels: List<ReasoningEffort>,
    defaultEffort: ReasoningEffort?,
    profileSummary: String,
    onOpenProfile: () -> Unit,
    onTest: () -> Unit,
    onSave: (baseUrl: String, apiKey: String, model: String, defaultEffort: ReasoningEffort?) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(baseUrl) }
    var key by remember { mutableStateOf(apiKey) }
    var modelName by remember { mutableStateOf(model) }
    var effort by remember { mutableStateOf(defaultEffort) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = minOf((configuration.screenHeightDp * 0.72f).dp, 560.dp)
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            Text(
                stringResource(R.string.dsh_model_config_title),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Dsh.textPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            // 服务商（当前选中，只读展示——切换走模型菜单的目录）。
            DshModelConfigRow(stringResource(R.string.dsh_model_provider), providerLabel)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DshSheetField(url, { url = it }, stringResource(R.string.dsh_model_base_url), singleLine = true)
                DshSheetField(key, { key = it }, stringResource(R.string.dsh_model_api_key), singleLine = true)
                DshSheetField(modelName, { modelName = it }, stringResource(R.string.dsh_model_name), singleLine = true)
                // 每模型思考档案钻入行（bre EffortEditor 入口）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                        .clickable { onOpenProfile() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.dsh_profile_entry),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Dsh.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        profileSummary,
                        fontSize = 12.sp,
                        color = Dsh.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 130.dp),
                    )
                    Text(
                        "›",
                        fontSize = 18.sp,
                        color = Dsh.textTertiary.copy(alpha = 0.42f),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 默认思考强度（bre effort-grid：档位胶囊，未设=跟随预设）。
                Text(
                    stringResource(R.string.dsh_model_default_effort),
                    fontSize = 11.sp,
                    color = Dsh.textTertiary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val levels = listOf<ReasoningEffort?>(null) + effortLevels
                    levels.forEach { level ->
                        Surface(
                            onClick = { effort = level },
                            shape = RoundedCornerShape(999.dp),
                            color = if (effort == level) Dsh.hover else Color.Transparent,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Dsh.borderL2),
                        ) {
                            Text(
                                level?.let { dshEffortLabel(it) } ?: stringResource(R.string.chat_reasoning_effort_auto),
                                fontSize = 11.sp,
                                color = if (effort == level) Dsh.textPrimary else Dsh.textTertiary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = onTest,
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.dsh_model_test),
                            fontSize = 13.sp,
                            color = Dsh.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth(),
                        )
                    }
                    Surface(
                        onClick = {
                            onSave(url.trim(), key.trim(), modelName.trim(), effort)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.dsh_model_save),
                            fontSize = 13.sp,
                            color = Dsh.blue,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DshModelConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

// ── 模型思考档案编辑（bre EffortEditor 复刻：档位网格 + 线上拼写 + 默认档） ──

/**
 * 单模型的思考档案编辑页：每档一行（启用开关 + 线上拼写输入，空=该档不发
 * 字段）+ 默认档下拉 + 知识库建议（只读：置信度 + 容量 + 备注）+ 自动适配。
 * 清空全部档位保存 = unset（自动适配不再覆盖，bre UNSET_MARKER 语义）。
 */
@Composable
internal fun DshModelProfileEditor(
    modelId: String,
    initial: app.tellev.core.provider.ModelReasoningProfile?,
    suggestion: app.tellev.core.provider.ReasoningKnowledgeBase.Suggestion,
    onAutoAdapt: () -> Boolean,
    onSave: (app.tellev.core.provider.ModelReasoningProfile?) -> Unit,
    onDismiss: () -> Unit,
) {
    // 档位行草稿：tellev 档位词汇（off/low/medium/high/max；Auto 不入档案）。
    data class Row(val on: Boolean, val wire: String)
    val levels = listOf("off", "low", "medium", "high", "max")
    val initialMap = initial?.efforts.orEmpty()
    var rows by remember(modelId) {
        mutableStateOf(levels.associateWith { level ->
            val wire = initialMap[level]
            Row(on = wire != null, wire = wire.orEmpty())
        })
    }
    var defaultEffort by remember(modelId) { mutableStateOf(initial?.defaultEffort.orEmpty()) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val sheetMaxHeight = minOf((configuration.screenHeightDp * 0.72f).dp, 560.dp)
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 12.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Dsh.borderL2)
                    .align(Alignment.CenterHorizontally),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.dsh_profile_title, modelId),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 自动适配（bre auto-adapt）：套用知识库建议。
                Text(
                    stringResource(R.string.dsh_profile_auto_adapt),
                    fontSize = 12.sp,
                    color = Dsh.blue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
                        .clickable { onAutoAdapt() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                stringResource(R.string.dsh_profile_hint),
                fontSize = 11.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 建议只读区（bre suggestion）：来源 + 置信度 + 容量 + 备注。
                if (suggestion.efforts != null) {
                    Surface(
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(
                                        R.string.dsh_profile_suggestion,
                                        stringResource(
                                            if (suggestion.confidence == "high") R.string.dsh_profile_conf_high
                                            else R.string.dsh_profile_conf_low,
                                        ),
                                    ),
                                    fontSize = 11.sp,
                                    color = Dsh.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                listOfNotNull(
                                    suggestion.contextWindow,
                                    suggestion.maxTokens,
                                ).let { caps ->
                                    if (caps.isNotEmpty()) {
                                        Text(
                                            stringResource(
                                                R.string.dsh_profile_capacity,
                                                dshCompactTokens(caps[0]),
                                                dshCompactTokens(caps.getOrElse(1) { caps[0] }),
                                            ),
                                            fontSize = 11.sp,
                                            color = Dsh.textTertiary,
                                        )
                                    }
                                }
                            }
                            if (suggestion.note.isNotBlank()) {
                                Text(
                                    suggestion.note,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Dsh.textTertiary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                // 档位网格（bre effort-grid）：每行 勾选 + 档位名 + 拼写输入。
                levels.forEach { level ->
                    val row = rows.getValue(level)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = row.on,
                            onCheckedChange = { checked ->
                                rows = rows + (level to row.copy(on = checked))
                            },
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            level,
                            fontSize = 13.sp,
                            color = Dsh.textPrimary,
                            modifier = Modifier.width(58.dp),
                        )
                        OutlinedTextField(
                            value = row.wire,
                            onValueChange = { wire ->
                                rows = rows + (level to row.copy(wire = wire.take(24)))
                            },
                            enabled = row.on,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.dsh_profile_wire_placeholder), fontSize = 11.sp, color = Dsh.textTertiary) },
                            shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                unfocusedBorderColor = Dsh.borderL2,
                                focusedBorderColor = Dsh.borderL3,
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Dsh.textPrimary),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 默认档下拉（bre defaultEffort）：新会话起点。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.dsh_model_default_effort),
                        fontSize = 12.sp,
                        color = Dsh.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    DshEffortDropdown(
                        selected = defaultEffort,
                        options = listOf("") + levels.filter { rows.getValue(it).on },
                        onSelect = { defaultEffort = it },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        onClick = { onSave(null) },
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.dsh_profile_unset),
                            fontSize = 13.sp,
                            color = Dsh.errorPrimary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth(),
                        )
                    }
                    Surface(
                        onClick = {
                            val efforts = rows.filterValues { it.on }
                                .mapValues { (_, row) -> row.wire.trim().ifEmpty { null } }
                            if (efforts.isEmpty()) {
                                onSave(null)
                            } else {
                                onSave(
                                    app.tellev.core.provider.ModelReasoningProfile(
                                        efforts = efforts,
                                        defaultEffort = defaultEffort.ifBlank { null },
                                        contextWindow = initial?.contextWindow,
                                        maxTokens = initial?.maxTokens,
                                        source = "user",
                                    ),
                                )
                            }
                        },
                        shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                        color = Dsh.hover,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.world_edit_save),
                            fontSize = 13.sp,
                            color = Dsh.blue,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** 默认档小下拉（胶囊 + 菜单）。 */
@Composable
private fun DshEffortDropdown(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
            color = Dsh.hover,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected.ifBlank { stringResource(R.string.chat_reasoning_effort_auto) },
                    fontSize = 12.sp,
                    color = Dsh.textSecondary,
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Dsh.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            option.ifBlank { stringResource(R.string.chat_reasoning_effort_auto) },
                            fontSize = 13.sp,
                        )
                    },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

// ── 轮级 token 用量 pill（harness TurnUsagePanel 复刻：读消息自己的账本桶） ──

/**
 * 一轮回复的 token 用量：pill 显示「数据库图标 + 紧凑总量」，点击弹出
 * 详情（精确总量 + 输入/缓存读/缓存写/输出 + 思考小注 + 缓存命中%）。
 * 数据源 = 该消息 metadata.tellev_turn_usage（dsh deriveTurnTokenUsage 语义：
 * 轮级只报 provider 上报的精确值，无样本则整个 pill 不显示）。
 */
@Composable
internal fun DshTurnUsagePill(
    buckets: app.tellev.feature.chat.ChatTokenUsageLedger.Buckets?,
    reasoningTokens: Long,
    onClick: () -> Unit,
) {
    if (buckets == null || buckets.isZero()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(Dsh.RADIUS_SM.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            DshIcons.Database,
            contentDescription = stringResource(R.string.dsh_usage_title),
            tint = Dsh.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            dshCompactTokens(buckets.total),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Dsh.textTertiary,
        )
    }
}

/** 轮级详情弹层（dsh turn-usage dialog：标题=精确总量 + dl 分桶行）。 */
@Composable
internal fun DshTurnUsageDialog(
    buckets: app.tellev.feature.chat.ChatTokenUsageLedger.Buckets,
    reasoningTokens: Long,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.BottomCenter,
        offset = androidx.compose.ui.unit.IntOffset(0, -with(density) { 96.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(0.96f)
                .clip(RoundedCornerShape(Dsh.RADIUS_LG.dp))
                .background(Dsh.menuSurface)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    DshIcons.Database,
                    contentDescription = null,
                    tint = Dsh.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.dsh_usage_title),
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Dsh.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    java.text.NumberFormat.getIntegerInstance().format(buckets.total),
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    color = Dsh.textPrimary,
                )
            }
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(color = Dsh.borderL1)
            Spacer(Modifier.height(6.dp))
            DshUsageRow(stringResource(R.string.dsh_usage_input), buckets.uncachedInput)
            DshUsageRow(stringResource(R.string.dsh_usage_cache_read), buckets.cacheRead)
            if (buckets.cacheWrite > 0L) {
                DshUsageRow(stringResource(R.string.dsh_usage_cache_write), buckets.cacheWrite)
            }
            if (reasoningTokens > 0L) {
                Text(
                    stringResource(R.string.dsh_usage_reasoning, java.text.NumberFormat.getIntegerInstance().format(reasoningTokens)),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                )
            }
            DshUsageRow(stringResource(R.string.dsh_usage_output), buckets.output)
            val billed = buckets.billedInput
            if (billed > 0L) {
                Text(
                    stringResource(R.string.dsh_usage_cache_hit) + " " + dshCacheHitPercent(buckets.cacheRead, billed) + "%",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Dsh.textSecondary,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * 提示词栈编辑页（ST prompt_order 语义）：启用列表可上移/下移/停用/逐条编辑；
 * 停用与未使用条目在底部列表，可一键启用。保存一次写回
 * prompts + prompts_unused + prompt_order。
 */
@Composable
private fun DshPresetPromptsPage(
    preset: app.tellev.core.model.GenerationPreset,
    sheetMaxHeight: androidx.compose.ui.unit.Dp,
    onSave: (active: List<app.tellev.core.model.PresetPrompt>, unused: List<app.tellev.core.model.PresetPrompt>) -> Unit,
    onDismiss: () -> Unit,
) {
    var active by remember(preset.id) { mutableStateOf(preset.prompts) }
    var unused by remember(preset.id) { mutableStateOf(preset.promptsUnused) }
    var editingIndex by remember(preset.id) { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.dsh_preset_prompts_hint),
            fontSize = 11.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (active.isEmpty()) {
                Text(
                    stringResource(R.string.dsh_preset_prompts_empty),
                    fontSize = 12.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                )
            }
            active.forEachIndexed { index, prompt ->
                val body = prompt.content.ifBlank { prompt.name }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                        .background(Dsh.hover.copy(alpha = 0.35f))
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            prompt.name.ifBlank { prompt.identifier },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Dsh.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 角色徽标（system/user/assistant）。
                        Text(
                            prompt.role,
                            fontSize = 10.sp,
                            color = Dsh.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Dsh.blue.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        body.take(120),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = Dsh.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = stringResource(R.string.dsh_prompt_move_up),
                            tint = Dsh.textTertiary,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                .clickable(enabled = index > 0) {
                                    val copy = active.toMutableList()
                                    val t = copy.removeAt(index)
                                    copy.add(index - 1, t)
                                    active = copy
                                }
                                .padding(4.dp),
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.dsh_prompt_move_down),
                            tint = Dsh.textTertiary,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                .clickable(enabled = index < active.lastIndex) {
                                    val copy = active.toMutableList()
                                    val t = copy.removeAt(index)
                                    copy.add(index + 1, t)
                                    active = copy
                                }
                                .padding(4.dp),
                        )
                        Text(
                            stringResource(R.string.dsh_prompt_edit),
                            fontSize = 11.sp,
                            color = Dsh.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                .clickable { editingIndex = if (editingIndex == index) null else index }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.dsh_prompt_disable),
                            fontSize = 11.sp,
                            color = Dsh.errorPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                .clickable {
                                    active = active.filterIndexed { i, _ -> i != index }
                                    unused = unused + prompt
                                    if (editingIndex == index) editingIndex = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    if (editingIndex == index) {
                        Spacer(Modifier.height(6.dp))
                        DshPromptEditorFields(
                            prompt = prompt,
                            onChange = { updated ->
                                active = active.mapIndexed { i, p -> if (i == index) updated else p }
                            },
                        )
                    }
                }
            }
            if (unused.isNotEmpty()) {
                Text(
                    stringResource(R.string.dsh_prompt_unused),
                    fontSize = 11.sp,
                    color = Dsh.textTertiary,
                    modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
                )
                unused.forEachIndexed { index, prompt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Dsh.RADIUS_MD.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            prompt.name.ifBlank { prompt.identifier },
                            fontSize = 12.sp,
                            color = Dsh.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.dsh_prompt_enable),
                            fontSize = 11.sp,
                            color = Dsh.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                                .clickable {
                                    unused = unused.filterIndexed { i, _ -> i != index }
                                    active = active + prompt.copy(enabled = true)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                onClick = {
                    // 未参与编辑的 un 项保留；active 顺序即 prompt_order。
                    onSave(active, unused)
                    onDismiss()
                },
                shape = RoundedCornerShape(Dsh.RADIUS_MD.dp),
                color = Dsh.hover,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.world_edit_save),
                    fontSize = 14.sp,
                    color = Dsh.blue,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/** 单条提示词编辑：名称/角色/内容/深度/注入序/相对位置/禁用覆盖。 */
@Composable
private fun DshPromptEditorFields(
    prompt: app.tellev.core.model.PresetPrompt,
    onChange: (app.tellev.core.model.PresetPrompt) -> Unit,
) {
    var roleOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DshSheetField(prompt.name, { value -> onChange(prompt.copy(name = value)) }, stringResource(R.string.dsh_prompt_name), singleLine = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.dsh_prompt_role),
                fontSize = 11.sp,
                color = Dsh.textTertiary,
                modifier = Modifier.width(72.dp),
            )
            Box {
                Surface(
                    onClick = { roleOpen = true },
                    shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                    color = Dsh.hover,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(prompt.role, fontSize = 12.sp, color = Dsh.textSecondary)
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Dsh.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                androidx.compose.material3.DropdownMenu(expanded = roleOpen, onDismissRequest = { roleOpen = false }) {
                    listOf("system", "user", "assistant").forEach { role ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(role, fontSize = 13.sp) },
                            onClick = { roleOpen = false; onChange(prompt.copy(role = role)) },
                        )
                    }
                }
            }
        }
        DshSheetField(prompt.content, { value -> onChange(prompt.copy(content = value)) }, stringResource(R.string.dsh_prompt_content), singleLine = false)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DshNumberField(prompt.depth.toString(), { value -> onChange(prompt.copy(depth = value.toIntOrNull() ?: prompt.depth)) }, stringResource(R.string.dsh_prompt_depth), Modifier.weight(1f))
            DshNumberField(prompt.injectionOrder.toString(), { value -> onChange(prompt.copy(injectionOrder = value.toIntOrNull() ?: prompt.injectionOrder)) }, stringResource(R.string.dsh_prompt_injection_order), Modifier.weight(1f))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onChange(prompt.copy(relative = !prompt.relative)) },
            ) {
                androidx.compose.material3.Checkbox(
                    checked = prompt.relative,
                    onCheckedChange = { value -> onChange(prompt.copy(relative = value)) },
                    modifier = Modifier.size(20.dp),
                )
                Text(stringResource(R.string.dsh_prompt_relative), fontSize = 11.sp, color = Dsh.textSecondary)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onChange(prompt.copy(forbidOverrides = !prompt.forbidOverrides)) },
            ) {
                androidx.compose.material3.Checkbox(
                    checked = prompt.forbidOverrides,
                    onCheckedChange = { value -> onChange(prompt.copy(forbidOverrides = value)) },
                    modifier = Modifier.size(20.dp),
                )
                Text(stringResource(R.string.dsh_prompt_forbid_overrides), fontSize = 11.sp, color = Dsh.textSecondary)
            }
        }
    }
}

// ── 预设 raw 键值编辑（「全部可调」：拍平 raw 每个叶子，按类型给控件） ──

/**
 * 预设参数页的「更多预设键」区：把 preset.raw 里参数页未覆盖的每个叶子键
 * 自动生成控件行——布尔→开关、数字→数字框、文本→文本框、对象/数组→JSON
 * 编辑对话框。行末「⋯」进单键编辑/删除。保存只动该键自身。
 */
@Composable
internal fun DshPresetRawKeysSection(
    preset: app.tellev.core.model.GenerationPreset,
    onSaveKey: (path: String, value: kotlinx.serialization.json.JsonElement?) -> Unit,
) {
    val rows = remember(preset.raw) { app.tellev.feature.chat.PresetRawKeys.rows(preset.raw) }
    var editing by remember(preset.id) { mutableStateOf<String?>(null) }
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.dsh_preset_raw_empty),
            fontSize = 11.sp,
            color = Dsh.textTertiary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
        return
    }
    Text(
        stringResource(R.string.dsh_preset_raw_hint),
        fontSize = 11.sp,
        color = Dsh.textTertiary,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    )
    rows.forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (row.kind) {
                app.tellev.feature.chat.PresetRawKeys.Kind.Switch -> {
                    val checked = app.tellev.feature.chat.PresetRawKeys.boolOrNull(row.value) ?: false
                    Text(
                        row.path,
                        fontSize = 12.sp,
                        color = Dsh.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = checked,
                        onCheckedChange = { value ->
                            onSaveKey(row.path, kotlinx.serialization.json.JsonPrimitive(value))
                        },
                        modifier = Modifier.height(24.dp),
                    )
                }
                app.tellev.feature.chat.PresetRawKeys.Kind.Number -> {
                    val value = app.tellev.feature.chat.PresetRawKeys.intOrNull(row.value)
                    OutlinedTextField(
                        value = value?.toString() ?: "",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(row.path, fontSize = 10.sp, maxLines = 1) },
                        singleLine = true,
                        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            unfocusedBorderColor = Dsh.borderL2,
                            focusedBorderColor = Dsh.borderL3,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Dsh.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    DshRawRowMenu(onEdit = { editing = row.path })
                }
                app.tellev.feature.chat.PresetRawKeys.Kind.Text -> {
                    val text = app.tellev.feature.chat.PresetRawKeys.display(row.value)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(row.path, fontSize = 10.sp, maxLines = 1) },
                        singleLine = true,
                        shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            unfocusedBorderColor = Dsh.borderL2,
                            focusedBorderColor = Dsh.borderL3,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Dsh.textPrimary),
                        modifier = Modifier.weight(1f),
                    )
                    DshRawRowMenu(onEdit = { editing = row.path })
                }
                app.tellev.feature.chat.PresetRawKeys.Kind.Json -> {
                    Text(
                        row.path,
                        fontSize = 12.sp,
                        color = Dsh.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.dsh_preset_raw_json_kind),
                        fontSize = 10.sp,
                        color = Dsh.textTertiary,
                    )
                    DshRawRowMenu(onEdit = { editing = row.path })
                }
            }
        }
    }
    editing?.let { path ->
        val current = rows.firstOrNull { it.path == path }
        DshRawKeyEditorDialog(
            path = path,
            value = current?.value,
            onSave = { text ->
                onSaveKey(path, app.tellev.feature.chat.PresetRawKeys.parseUserValue(text))
                editing = null
            },
            onDelete = {
                onSaveKey(path, null)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** 行末「⋯」：编辑单键。 */
@Composable
private fun DshRawRowMenu(onEdit: () -> Unit) {
    Box {
        Text(
            "⋯",
            fontSize = 14.sp,
            color = Dsh.textTertiary,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(Dsh.RADIUS_XS.dp))
                .clickable(onClick = onEdit),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** 单键编辑对话框：值文本框（类型提示）+ 保存 + 删除键。 */
@Composable
private fun DshRawKeyEditorDialog(
    path: String,
    value: kotlinx.serialization.json.JsonElement?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(path) {
        mutableStateOf(value?.let { app.tellev.feature.chat.PresetRawKeys.display(it) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(path) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            R.string.dsh_preset_raw_current_type,
                            when (value) {
                                is kotlinx.serialization.json.JsonPrimitive -> {
                                    val row = app.tellev.feature.chat.PresetRawKeys.Row(path, value)
                                    when (row.kind) {
                                        app.tellev.feature.chat.PresetRawKeys.Kind.Switch -> stringResource(R.string.dsh_preset_raw_type_switch)
                                        app.tellev.feature.chat.PresetRawKeys.Kind.Number -> stringResource(R.string.dsh_preset_raw_type_number)
                                        app.tellev.feature.chat.PresetRawKeys.Kind.Text -> stringResource(R.string.dsh_preset_raw_type_text)
                                        app.tellev.feature.chat.PresetRawKeys.Kind.Json -> stringResource(R.string.dsh_preset_raw_type_json)
                                    }
                                }
                                else -> stringResource(R.string.dsh_preset_raw_type_json)
                            },
                        ),
                        fontSize = 11.sp,
                        color = Dsh.textTertiary,
                        modifier = Modifier.weight(1f),
                    )
                    if (value != null) {
                        Text(
                            stringResource(R.string.dsh_preset_raw_delete_key),
                            fontSize = 11.sp,
                            color = Dsh.errorPrimary,
                            modifier = Modifier
                                .clickable(onClick = onDelete)
                                .padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dsh_preset_raw_value_label), fontSize = 11.sp) },
                    minLines = 2,
                    maxLines = 8,
                    shape = RoundedCornerShape(Dsh.RADIUS_SM.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = Dsh.borderL2,
                        focusedBorderColor = Dsh.borderL3,
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Dsh.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.world_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}
