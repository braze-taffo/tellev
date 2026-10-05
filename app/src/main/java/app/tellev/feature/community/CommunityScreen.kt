package app.tellev.feature.community

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.R
import app.tellev.core.model.CharacterSummary
import app.tellev.core.storage.CharacterImporter
import app.tellev.core.storage.StDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 类脑社区主入口（公共邀请页，会跳转到 Discord 页面）。 */
private const val COMMUNITY_URL = "https://discord.gg/odysseia"

/** 社区的新手入门指南页。 */
private const val COMMUNITY_GUIDE_URL =
    "https://blog.mini-tavern.com/zh/blog/leina-odysseia-sillytavern-discord-community-guide-2026-f7a8b9c0"

/** 可自动导入 tellev 的角色卡扩展名。 */
private val CARD_EXTENSIONS = setOf("png", "json", "webp", "charx")

/** 与 MainActivity 的 intent 导入保持同一条上限：正文卡含 4MB 封面约 6MB。 */
private const val DOWNLOAD_MAX_BYTES = 100L * 1024 * 1024

private data class PendingCardDownload(
    val fileName: String,
    val bytes: ByteArray,
) {
    // ByteArray 的数据类相等语义没有意义，只按内容字段保留默认结构相等即可。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 类脑（角色扮演酒馆社区）套壳界面。
 *
 * 社区本体是 Discord 服务器，这里用 WebView 承载它的公开页面：浏览、找卡、
 * 点下载链接。拦截下载回调，把 PNG / JSON / WebP 角色卡直接读进内存并弹导入
 * 确认框，导入路径与外部文件导入完全一致（CharacterImporter + DataStore）。
 * 打不开 Discord 的网络环境下显示降级面板，引导重试或用客户端打开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CommunityScreen(
    dataStore: StDataStore,
    onImported: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val importer = remember { CharacterImporter() }

    var pageFailed by remember { mutableStateOf(false) }
    var pageProgress by remember { mutableIntStateOf(0) }
    var pendingImport by remember { mutableStateOf<PendingCardDownload?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.comm_external_link),
                )
            }
        }
    }

    fun importDownload(download: PendingCardDownload) {
        scope.launch {
            busy = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val card = importer.importFromBytes(download.bytes, download.fileName)
                    // 与外部导入相同的 id 处理：空 id 或撞名时换一个新 id，绝不覆盖旧卡。
                    val existingIds = dataStore.listCharacters().map(CharacterSummary::id).toSet()
                    val imported = when {
                        card.id.isBlank() || card.id == "imported_character" ->
                            card.copy(id = "char_${UUID.randomUUID()}")
                        card.id in existingIds ->
                            card.copy(id = "${card.id}_${UUID.randomUUID().toString().take(8)}")
                        else -> card
                    }
                    dataStore.importCharacter(imported, download.bytes, download.fileName)
                    imported.name
                }
            }
            busy = false
            pendingImport = null
            result
                .onSuccess { name ->
                    onImported()
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.comm_import_success, name),
                    )
                }
                .onFailure { error ->
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.comm_import_failed,
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
        }
    }

    // 「主入口」按钮按下后在这里重载；首次进入由 factory 的 loadUrl 负责。
    LaunchedEffect(reloadToken) {
        if (reloadToken > 0) {
            pageFailed = false
            webViewRef.value?.loadUrl(COMMUNITY_URL)
        }
    }

    pendingImport?.let { download ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.comm_import_title)) },
            text = { Text(stringResource(R.string.comm_import_body, download.fileName)) },
            confirmButton = {
                TextButton(onClick = { importDownload(download) }) {
                    Text(stringResource(R.string.comm_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.chars_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.comm_title))
                        Text(
                            text = stringResource(R.string.comm_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef.value?.reload() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.comm_reload),
                        )
                    }
                    IconButton(onClick = { openExternally(COMMUNITY_URL) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = stringResource(R.string.comm_open_external),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                CommunityLinkBar(
                    onOpenMain = {
                        pageFailed = false
                        reloadToken++
                    },
                    onOpenGuide = { openExternally(COMMUNITY_GUIDE_URL) },
                )
                if (pageProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (pageFailed) {
                    CommunityFallback(
                        onRetry = {
                            pageFailed = false
                            webViewRef.value?.reload()
                        },
                        onOpenExternally = { openExternally(COMMUNITY_URL) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef.value = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportMultipleWindows(false)
                                android.webkit.CookieManager.getInstance()
                                    .setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(
                                        view: WebView?,
                                        newProgress: Int,
                                    ) {
                                        pageProgress = newProgress
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        // Discord 深链、telegram、mailto 等交给外部应用；
                                        // http/https 留在套壳里继续浏览。
                                        val scheme = request.url.scheme
                                        if (scheme == "http" || scheme == "https") return false
                                        openExternally(request.url.toString())
                                        return true
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) pageFailed = true
                                    }
                                }
                                setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                                    val fileName = URLUtil.guessFileName(
                                        url,
                                        contentDisposition,
                                        mimetype,
                                    )
                                    val ext = fileName.substringAfterLast('.', "").lowercase()
                                    if (ext in CARD_EXTENSIONS) {
                                        scope.launch {
                                            busy = true
                                            val downloaded = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    downloadBounded(url, userAgent)
                                                }
                                            }
                                            busy = false
                                            downloaded
                                                .onSuccess { bytes ->
                                                    if (bytes.isEmpty()) {
                                                        snackbarHostState.showSnackbar(
                                                            context.getString(
                                                                R.string.comm_import_failed,
                                                                fileName,
                                                            ),
                                                        )
                                                    } else {
                                                        pendingImport = PendingCardDownload(
                                                            fileName,
                                                            bytes,
                                                        )
                                                    }
                                                }
                                                .onFailure { error ->
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(
                                                            R.string.comm_import_failed,
                                                            error.message
                                                                ?: error.javaClass.simpleName,
                                                        ),
                                                    )
                                                }
                                        }
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.comm_unsupported, fileName),
                                            )
                                        }
                                    }
                                }
                                loadUrl(COMMUNITY_URL)
                            }
                        },
                        onRelease = { view ->
                            webViewRef.value = null
                            view.stopLoading()
                            view.destroy()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (busy) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityLinkBar(
    onOpenMain: () -> Unit,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.comm_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenMain) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.comm_main_entry),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onOpenGuide) {
                    Text(
                        text = stringResource(R.string.comm_guide),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.comm_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.comm_hint_network),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommunityFallback(
    onRetry: () -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.comm_fallback_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.comm_fallback_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.comm_retry)) }
            OutlinedButton(onClick = onOpenExternally) {
                Text(stringResource(R.string.comm_open_external))
            }
        }
    }
}

/**
 * 限量读取下载体：WebView 给的下载地址可能是任意大小的文件，读满上限或流结束即停。
 * 连接失败/非 2xx 抛异常，由调用方转成可读错误。
 */
private fun downloadBounded(url: String, userAgent: String): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("User-Agent", userAgent)
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }
    connection.connect()
    val code = connection.responseCode
    if (code !in 200..299) {
        connection.disconnect()
        error("HTTP $code")
    }
    val out = ByteArrayOutputStream(1 shl 20)
    var total = 0
    val stream: InputStream = connection.inputStream
    try {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > DOWNLOAD_MAX_BYTES) error("file too large")
            out.write(buffer, 0, read)
        }
    } finally {
        runCatching { stream.close() }
        connection.disconnect()
    }
    return out.toByteArray()
}
