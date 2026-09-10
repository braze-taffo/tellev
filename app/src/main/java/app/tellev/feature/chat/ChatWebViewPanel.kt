package app.tellev.feature.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal data class TavernMessageRuntime(
    val token: app.tellev.core.extension.RuntimeToken?,
    val messageIndex: Int,
    val variablesJson: () -> String,
    val contextJson: () -> String,
    val request: (String, String, (Boolean, String) -> Unit) -> Unit,
)

internal class TavernMessageBridge(
    private val onHeightChanged: (Int) -> Unit,
    private val onBoundaryDrag: (Float) -> Unit,
    runtime: TavernMessageRuntime,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView = java.lang.ref.WeakReference<WebView>(null)

    @Volatile
    private var runtime: TavernMessageRuntime = runtime

    private val loadTracker = TavernMessageLoadTracker()

    @Volatile
    private var nestedScrollGesture: Boolean = false

    /**
     * 已下发过的高度。ResizeObserver 是像素级触发的，卡片内图片渐进加载、
     * 字体就绪都会产生 1px 级抖动；没有这道门限，每次抖动都会经过
     * mainHandler.post → Compose 重组 → WebView 视口变化 → 再次触发
     * ResizeObserver 的完整循环，主线程被排版脉冲淹没（ANR）。
     */
    @Volatile
    private var lastDeliveredHeight: Int = 0

    fun attach(view: WebView) {
        webView = java.lang.ref.WeakReference(view)
    }

    @Volatile private var closed = false
    fun close() { closed = true; mainHandler.removeCallbacksAndMessages(null); webView.clear() }

    private var lastVariablesJson: String? = null
    fun updateRuntime(value: TavernMessageRuntime) {
        check(runtime.token == value.token) { "Frontend runtime ownership changed without replacing WebView" }
        runtime = value
        val variables = runCatching { value.variablesJson() }.getOrNull() ?: return
        if (variables != lastVariablesJson) {
            lastVariablesJson = variables
            webView.get()?.evaluateJavascript("window.__tellevStateChanged?.()", null)
        }
    }

    fun shouldLoad(html: String): Boolean = loadTracker.shouldLoad(html)

    /** 新 HTML 即将加载时调用：旧高度门限不能带到新页面。 */
    fun resetDeliveredHeight() {
        lastDeliveredHeight = 0
    }

    fun beginNativeTouchGesture() {
        nestedScrollGesture = false
    }

    fun hasNestedScrollGesture(): Boolean = nestedScrollGesture

    @JavascriptInterface
    fun setNestedScrollGesture(active: Boolean) {
        nestedScrollGesture = active
    }

    @JavascriptInterface
    fun forwardBoundaryDrag(chatScrollDelta: Double) {
        if (!chatScrollDelta.isFinite() || chatScrollDelta == 0.0) return
        mainHandler.post { onBoundaryDrag(chatScrollDelta.toFloat()) }
    }

    fun dispatchDocumentBoundaryDrag(chatScrollDelta: Float) {
        if (chatScrollDelta != 0f) onBoundaryDrag(chatScrollDelta)
    }

    @JavascriptInterface
    fun resize(height: Int) {
        if (height <= 0) return
        // 差值门限：1px 级抖动直接丢弃，不进主线程消息队列。
        if (kotlin.math.abs(height - lastDeliveredHeight) < 2) return
        lastDeliveredHeight = height
        mainHandler.post { onHeightChanged(height) }
    }

    @JavascriptInterface
    fun getAllVariables(): String =
        runtime.variablesJson()

    @JavascriptInterface
    fun getCurrentMessageId(): Int = runtime.messageIndex

    @JavascriptInterface
    fun getContext(): String = runtime.contextJson()

    @JavascriptInterface
    fun request(requestId: String, operation: String, payloadJson: String) {
        if (closed) return
        val origin = runtime
        mainHandler.post {
            if (closed) return@post
            origin.request(operation, payloadJson) { ok, responseJson ->
                mainHandler.post {
                    val view = webView.get() ?: return@post
                    val idLiteral = org.json.JSONObject.quote(requestId)
                    val payloadLiteral = org.json.JSONObject.quote(responseJson)
                    view.evaluateJavascript(
                        "if(window.__tellevMessageResolve){" +
                            "window.__tellevMessageResolve($idLiteral,${if (ok) "true" else "false"},$payloadLiteral);}",
                        null,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TavernHtmlPanel(
    html: String,
    availableMaxHeight: Dp,
    dialogueQuoteColor: String? = null,
    tavernRuntime: TavernMessageRuntime,
    onBoundaryDrag: (Float) -> Unit,
) {
    if (tavernRuntime.token == null) return
    androidx.compose.runtime.key(tavernRuntime.token) {
        val themeOnSurface = MaterialTheme.colorScheme.onSurface.toCssHex()
        val wrappedHtml = remember(html, themeOnSurface, dialogueQuoteColor) {
            wrapTavernHtml(html, themeOnSurface, dialogueQuoteColor)
        }
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val maxPanelHeight = remember(configuration.screenHeightDp, availableMaxHeight) {
            val screenBound = (configuration.screenHeightDp.dp * 0.92f)
                .coerceAtLeast(420.dp)
                .coerceAtMost(1120.dp)
            val viewportBound = if (availableMaxHeight > 0.dp) availableMaxHeight else screenBound
            minOf(screenBound, viewportBound).coerceAtLeast(180.dp)
        }
        val minPanelHeight = remember(html, maxPanelHeight) {
            if (html.isLargeTavernFrontend()) maxPanelHeight else minOf(240.dp, maxPanelHeight)
        }
        var contentHeightPx by remember(html) { mutableIntStateOf(0) }
        val panelHeight = remember(contentHeightPx, density, minPanelHeight, maxPanelHeight) {
            val measured = with(density) { contentHeightPx.toDp() }
            measured.coercePanelHeight(min = minPanelHeight, max = maxPanelHeight)
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelHeight),
            factory = { context ->
                val bridge = TavernMessageBridge(
                    onHeightChanged = { height ->
                        if (kotlin.math.abs(height - contentHeightPx) >= 2) {
                            contentHeightPx = height
                        }
                    },
                    onBoundaryDrag = onBoundaryDrag,
                    runtime = tavernRuntime,
                )
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = true
                    overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
                    var lastTouchY = 0f
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastTouchY = event.y
                                (view.tag as? TavernMessageBridge)?.beginNativeTouchGesture()
                                // 先假定由 WebView 接管手势；MOVE 时若 WebView 在该方向上
                                // 没有可滚动内容，再把拦截权交还给外层聊天列表。
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dy = event.y - lastTouchY
                                lastTouchY = event.y
                                val bridge = view.tag as? TavernMessageBridge
                                // Element-level scrollers (for example a card's
                                // `.screen.active`) are invisible to
                                // WebView.canScrollVertically(). JavaScript owns
                                // those gestures and forwards only edge deltas.
                                if (bridge?.hasNestedScrollGesture() == true) {
                                    return@setOnTouchListener false
                                }
                                val canScrollInDirection = when {
                                    dy > 0 -> view.canScrollVertically(-1)
                                    dy < 0 -> view.canScrollVertically(1)
                                    else -> true
                                }
                                if (shouldForwardWebViewDragToChat(
                                        canScrollInDirection = canScrollInDirection,
                                    )
                                ) {
                                    // Compose LazyColumn cannot reliably take over a
                                    // gesture after an Android WebView has started it.
                                    // Keep receiving MOVE events and forward every
                                    // boundary delta explicitly; release on UP/CANCEL.
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                    val chatScrollDelta = chatScrollDeltaAtWebViewEdge(
                                        canScrollInDirection = canScrollInDirection,
                                        fingerDeltaY = dy,
                                    )
                                    bridge?.dispatchDocumentBoundaryDrag(chatScrollDelta)
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = false
                    settings.textZoom = 100
                    tag = bridge
                    bridge.attach(this)
                    addJavascriptInterface(bridge, "TellevBridge")
                    addJavascriptInterface(bridge, "TellevMessage")
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                            app.tellev.core.extension.CompatAssets.intercept(context, request.url.toString())
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.post {
                                val density = view.resources.displayMetrics.density.coerceAtLeast(1f)
                                val viewportHeight = (view.height / density).toInt()
                                view.scrollTo(0, 0)
                                view.evaluateJavascript(tavernMessageLayoutScript(viewportHeight), null)
                                view.evaluateJavascript(tavernResizeScript(), null)
                            }
                        }
                    }
                }
            },
            onRelease = { webView ->
                (webView.tag as? TavernMessageBridge)?.close()
                webView.stopLoading()
                webView.removeJavascriptInterface("TellevBridge")
                webView.removeJavascriptInterface("TellevMessage")
                webView.destroy()
            },
            update = { webView ->
                val bridge = webView.tag as? TavernMessageBridge
                bridge?.updateRuntime(tavernRuntime)
                if (bridge?.shouldLoad(wrappedHtml) != false) {
                    bridge?.resetDeliveredHeight()
                    webView.stopLoading()
                    webView.scrollTo(0, 0)
                    webView.loadDataWithBaseURL(
                        "https://message.tellev.local/",
                        wrappedHtml,
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            },
        )
    }
}

internal fun Dp.coercePanelHeight(min: Dp, max: Dp): Dp =
    when {
        this < min -> min
        this > max -> max
        else -> this
    }

internal fun String.isLargeTavernFrontend(): Boolean =
    length > 1600 ||
        contains("Tavern", ignoreCase = true) ||
        contains("酒馆", ignoreCase = true) ||
        contains("swiper", ignoreCase = true) ||
        contains("carousel", ignoreCase = true)

internal fun androidx.compose.ui.graphics.Color.toCssHex(): String =
    "#%06X".format(0xFFFFFF and toArgb())

internal fun wrapTavernHtml(
    html: String,
    themeOnSurface: String,
    dialogueQuoteColor: String? = null,
): String {
    val dialogueQuoteCss = dialogueQuoteColor?.let { color ->
        "q { color: $color; } q::before, q::after { content: none; }"
    }.orEmpty()
    val hostHead = """
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <script src="https://extensions.tellev.local/compat/globals.js"></script>
        ${tavernMessageCompatScript()}
        <script src="https://extensions.tellev.local/compat/chat.js"></script>
        <script src="https://extensions.tellev.local/compat/message.js"></script>
        <style id="tellev-host-style">
            html, body {
                width: 100%;
                min-width: 0;
                margin: 0;
                padding: 0;
                background: transparent;
                color: $themeOnSurface;
                overflow-x: hidden;
            }
            * {
                box-sizing: border-box;
            }
            img, video, canvas, iframe, table { max-width: 100%; }
            body {
                overflow-y: auto !important;
                overflow-x: hidden !important;
            }
            $dialogueQuoteCss
        </style>
    """.trimIndent()

    if (html.contains("<html", ignoreCase = true)) {
        val head = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(html)
        if (head != null) {
            return html.replaceRange(head.range, "${head.value}\n$hostHead")
        }
        val match = Regex("""<html([^>]*)>""", RegexOption.IGNORE_CASE).find(html)
        return if (match == null) {
            "<html><head>$hostHead</head>$html</html>"
        } else {
            val replacement = "<html${match.groupValues[1]}>\n<head>\n$hostHead\n</head>"
            html.replaceRange(match.range, replacement)
        }
    }

    return """
        <!doctype html>
        <html>
        <head>
            $hostHead
        </head>
        $html
        </html>
    """.trimIndent()
}

internal fun tavernResizeScript(): String = """
    (function() {
        var lastPostedHeight = 0;
        var scheduled = false;
        var debounceTimer = 0;
        function pageHeight() {
            var body = document.body || {};
            var doc = document.documentElement || {};
            // NOTE: doc.clientHeight 故意不参与：它是 WebView 视口高度，
            // Compose 侧 panelHeight 变大 → 视口变大 → clientHeight 变大 →
            // 再次 post 更大的高度，正反馈一路顶到 maxPanelHeight。
            return Math.ceil(Math.max(
                body.scrollHeight || 0,
                body.offsetHeight || 0,
                doc.scrollHeight || 0,
                doc.offsetHeight || 0
            ));
        }
        function postHeightNow() {
            scheduled = false;
            var h = pageHeight();
            if (Math.abs(h - lastPostedHeight) < 2) return;
            lastPostedHeight = h;
            if (window.TellevBridge && window.TellevBridge.resize) {
                window.TellevBridge.resize(h);
            }
        }
        function postHeight() {
            if (scheduled) return;
            scheduled = true;
            var flush = function() {
                clearTimeout(debounceTimer);
                debounceTimer = setTimeout(postHeightNow, 150);
            };
            if (window.requestAnimationFrame) {
                window.requestAnimationFrame(flush);
            } else {
                flush();
            }
        }
        if (!window.__tellevResizeInstalled) {
            window.__tellevResizeInstalled = true;
            window.addEventListener('load', postHeight);
            window.addEventListener('resize', postHeight);
            document.addEventListener('toggle', function() {
                requestAnimationFrame(postHeight);
                setTimeout(postHeight, 80);
            }, true);
            if (window.ResizeObserver) {
                var observer = new ResizeObserver(postHeight);
                observer.observe(document.documentElement);
                if (document.body) observer.observe(document.body);
            }
            setTimeout(postHeight, 50);
            setTimeout(postHeight, 250);
            setTimeout(postHeight, 1000);
        }
        postHeightNow();
    })();
""".trimIndent()

@Composable
internal fun HtmlSwipeControls(
    currentIndex: Int,
    totalSwipes: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一页",
            )
        }
        Text(
            text = "${currentIndex + 1}/$totalSwipes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "下一页",
            )
        }
    }
}
