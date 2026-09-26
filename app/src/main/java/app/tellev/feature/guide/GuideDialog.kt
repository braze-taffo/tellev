package app.tellev.feature.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellev.R
import app.tellev.core.guide.GuideDocument
import app.tellev.core.guide.GuideKind
import app.tellev.core.guide.GuidePage
import app.tellev.core.guide.loadGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 指引内容的三种状态；把「加载中」和「加载失败」分开，失败才谈得上如实提示。 */
private sealed interface GuideState {
    data object Loading : GuideState
    data class Ready(val document: GuideDocument) : GuideState
    data object Failed : GuideState
}

/**
 * 全屏分页指引：一页一个功能（新手引导里一页一步），底部「上一步 / 下一步」，
 * 最后一页换成收尾按钮。
 *
 * 正文来自 `assets/guide` 下的当前语言资产（回退链见 [loadGuide]），读盘与解析
 * 放在 IO 线程——这个弹窗会在冷启动路径上出现，不该在主线程读文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GuideDialog(kind: GuideKind, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // resources 已被 AppLocale 包装过，所以跟随系统时这里拿到的就是系统语言。
    val languageTag = remember(context) { context.resources.configuration.locales[0].toLanguageTag() }
    val state by produceState<GuideState>(GuideState.Loading, kind, languageTag) {
        value = withContext(Dispatchers.IO) {
            loadGuide(context.assets, kind, languageTag)
                ?.let { GuideState.Ready(it) }
                ?: GuideState.Failed
        }
    }
    val titleRes = when (kind) {
        GuideKind.Onboarding -> R.string.guide_title_onboarding
        GuideKind.Update -> R.string.guide_title_update
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(titleRes)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.guide_close))
                        }
                    },
                )
                when (val current = state) {
                    GuideState.Loading -> Centered { CircularProgressIndicator() }
                    GuideState.Failed -> Centered {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.guide_load_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.nav_got_it)) }
                        }
                    }
                    is GuideState.Ready -> GuidePager(
                        kind = kind,
                        document = current.document,
                        onFinish = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidePager(kind: GuideKind, document: GuideDocument, onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { document.pages.size })
    val scope = rememberCoroutineScope()
    val lastIndex = document.pages.lastIndex

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
            GuidePageView(document.pages[index])
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.guide_page_indicator, pagerState.currentPage + 1, document.pages.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                    ) { Text(stringResource(R.string.guide_action_prev)) }
                }
                if (pagerState.currentPage < lastIndex) {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    ) { Text(stringResource(R.string.guide_action_next)) }
                } else {
                    Button(onClick = onFinish) {
                        Text(
                            stringResource(
                                if (kind == GuideKind.Onboarding) R.string.guide_action_start else R.string.nav_got_it,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePageView(page: GuidePage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = page.title, style = MaterialTheme.typography.headlineSmall)
        Text(text = page.summary, style = MaterialTheme.typography.bodyLarge)
        LabeledList(label = stringResource(R.string.guide_label_where), items = page.where)
        LabeledList(label = stringResource(R.string.guide_label_how), items = page.steps, numbered = true)
    }
}

@Composable
private fun LabeledList(label: String, items: List<String>, numbered: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (numbered) "${index + 1}." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )
                Text(text = item, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
