package app.tellev.feature.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tellev.R

internal data class TavernMessageRuntime(
    val token: app.tellev.core.extension.RuntimeToken?,
    val messageIndex: Int,
    val variablesJson: () -> String,
    val contextJson: () -> String,
    val request: (String, String, (Boolean, String) -> Unit) -> Unit,
    val currentInput: () -> String = { "" },
    val onScrollStart: () -> Unit = {},
    val onBoundaryFling: (Float) -> Unit = {},
    val allowContentUpdates: Boolean = true,
    val messageId: String = "message-$messageIndex",
    val segmentIndex: Int = 0,
    val readVariables: ((String) -> String)? = null,
    val sizes: ChatPanelSizes? = null,
    val onScrollEnd: () -> Unit = {},
)

/** How long the message WebView's JS thread waits for a main-thread draft read. */
private const val INPUT_READ_TIMEOUT_MILLIS = 500L

internal class TavernMessageBridge(
    private val onHeightChanged: (Int) -> Unit,
    private val onBoundaryDrag: (Float) -> Unit,
    runtime: TavernMessageRuntime,
    val documentId: String = "",
    private val onDocumentReady: () -> Unit = {},
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
    fun close() {
        if (closed) return
        closed = true
        endNativeTouchGesture()
        mainHandler.removeCallbacksAndMessages(null)
        webView.clear()
    }

    fun isClosed(): Boolean = closed

    @JavascriptInterface
    fun documentReady() {
        if (closed) return
        mainHandler.post { if (!closed) onDocumentReady() }
    }

    private var lastVariablesJson: String? = null
    fun updateRuntime(value: TavernMessageRuntime) {
        if (closed) return
        check(runtime.token == value.token && runtime.messageId == value.messageId && runtime.segmentIndex == value.segmentIndex) {
            "Frontend runtime ownership changed without replacing document"
        }
        runtime = value
        val variables = runCatching { value.variablesJson() }.getOrNull() ?: return
        if (variables != lastVariablesJson) {
            lastVariablesJson = variables
            webView.get()?.evaluateJavascript("window.__tellevStateChanged?.()", null)
        }
    }

    fun shouldLoad(html: String): Boolean = loadTracker.shouldLoad(html, runtime.allowContentUpdates)

    /** 新 HTML 即将加载时调用：旧高度门限不能带到新页面。 */
    fun resetDeliveredHeight() {
        lastDeliveredHeight = 0
    }

    fun beginNativeTouchGesture() {
        if (closed) return
        nestedScrollGesture = false
        nativeTouchActive = true
        runtime.onScrollStart()
    }

    private var nativeTouchActive = false
    fun endNativeTouchGesture() {
        if (!nativeTouchActive) return
        nativeTouchActive = false
        runtime.onScrollEnd()
    }

    fun hasNestedScrollGesture(): Boolean = nestedScrollGesture

    @JavascriptInterface
    fun setNestedScrollGesture(active: Boolean) {
        if (!closed) nestedScrollGesture = active
    }

    @JavascriptInterface
    fun forwardBoundaryDrag(chatScrollDelta: Double) {
        if (closed || !chatScrollDelta.isFinite() || chatScrollDelta == 0.0) return
        mainHandler.post { if (!closed) onBoundaryDrag(chatScrollDelta.toFloat()) }
    }

    fun dispatchDocumentBoundaryDrag(chatScrollDelta: Float) {
        if (!closed && chatScrollDelta != 0f) onBoundaryDrag(chatScrollDelta)
    }

    @JavascriptInterface
    fun forwardBoundaryFling(velocity: Double) {
        if (closed || !velocity.isFinite()) return
        mainHandler.post { if (!closed) runtime.onBoundaryFling(velocity.toFloat()) }
    }

    fun dispatchBoundaryFling(velocity: Float) {
        if (!closed) runtime.onBoundaryFling(velocity)
    }

    @JavascriptInterface
    fun resize(height: Int) {
        if (closed || height <= 0) return
        // JS filters physical-pixel jitter; here only suppress exact duplicates.
        if (height == lastDeliveredHeight) return
        lastDeliveredHeight = height
        mainHandler.post { if (!closed) onHeightChanged(height) }
    }

    @JavascriptInterface
    fun getAllVariables(): String =
        if (closed) "{}" else runtime.variablesJson()

    @JavascriptInterface
    fun readVariables(payload: String): String = if (closed) "{\"ok\":false,\"error\":\"Page retired\"}"
        else runtime.readVariables?.invoke(payload) ?: "null"

    @JavascriptInterface
    fun getCurrentMessageId(): Int = if (closed) -1 else runtime.messageIndex

    /**
     * Composer draft, read synchronously by the message document's
     * `#send_textarea` shim: frontends that fill the input read `value` back to
     * append to it (思客大调查 answers pile up in one draft).
     *
     * The draft is Compose state owned by the chat screen, so the read has to
     * happen on the main thread. The WebView's JS thread waits for that hop —
     * nothing on the main thread ever waits for JS on this WebView, so the two
     * cannot deadlock — and falls back to an empty draft if the main thread is
     * wedged past the timeout (the shim then keeps appending its own shadow).
     */
    @JavascriptInterface
    fun getInput(): String {
        if (closed) return ""
        if (Looper.myLooper() == Looper.getMainLooper()) return runtime.currentInput()
        var draft = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = mainHandler.post {
            if (!closed) draft = runtime.currentInput()
            latch.countDown()
        }
        if (posted) {
            runCatching { latch.await(INPUT_READ_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS) }
        }
        return draft
    }

    @JavascriptInterface
    fun getContext(): String = if (closed) "{}" else runtime.contextJson()

    @JavascriptInterface
    fun request(requestId: String, operation: String, payloadJson: String) {
        if (closed) return
        val origin = runtime
        mainHandler.post {
            if (closed) return@post
            origin.request(operation, payloadJson) { ok, responseJson ->
                mainHandler.post {
                    if (closed) return@post
                    val view = webView.get() ?: return@post
                    val idLiteral = org.json.JSONObject.quote(requestId)
                    val payloadLiteral = org.json.JSONObject.quote(responseJson)
                    view.evaluateJavascript(
                        "if(window.__tellevDocumentId===${org.json.JSONObject.quote(documentId)} && window.__tellevMessageResolve){" +
                            "window.__tellevMessageResolve($idLiteral,${if (ok) "true" else "false"},$payloadLiteral);}",
                        null,
                    )
                }
            }
        }
    }
}

internal object TavernPanelStats {
    @Volatile var enabled = false
    val creates = java.util.concurrent.atomic.AtomicInteger()
    val loads = java.util.concurrent.atomic.AtomicInteger()
    val releases = java.util.concurrent.atomic.AtomicInteger()
}

/** A reusable shell; every navigation gets a new, permanently revocable bridge. */
internal class TavernPanelController(private val view: WebView) {
    var bridge: TavernMessageBridge? = null
        private set
    private var runtime: TavernMessageRuntime? = null
    private var loadedHtml: String? = null
    private var interfaceName: String? = null
    private var url: String? = null
    private var heightChanged: (Int) -> Unit = {}
    private var boundaryDrag: (Float) -> Unit = {}
    private var viewportHeight = 0

    fun bind(value: TavernMessageRuntime, html: String, maxHeight: Int,
             onHeight: (Int) -> Unit, onDrag: (Float) -> Unit) {
        val ownerChanged = runtime?.let {
            it.token != value.token || it.messageId != value.messageId || it.segmentIndex != value.segmentIndex || it.messageIndex != value.messageIndex
        } ?: true
        if (ownerChanged || (loadedHtml != html && value.allowContentUpdates)) {
            retire()
            runtime = value
            heightChanged = onHeight
            boundaryDrag = onDrag
            viewportHeight = maxHeight
            val id = nextDocument.incrementAndGet().toString()
            val name = "__tellevNative_$id"
            val next = TavernMessageBridge({ heightChanged(it) }, { boundaryDrag(it) }, value, id) {
                if (bridge?.documentId == id) installLayout()
            }
            bridge = next
            interfaceName = name
            loadedHtml = html
            url = "https://message.tellev.local/?document=$id"
            next.attach(view)
            view.addJavascriptInterface(next, name)
            val aliases = "<script>window.__tellevDocumentId='$id';window.TellevMessage=window.$name;window.TellevBridge=window.$name;" +
                "document.addEventListener('DOMContentLoaded',function(){window.$name.documentReady();},{once:true});</script>"
            val head = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(html)
            val document = if (head != null) html.replaceRange(head.range, head.value + aliases) else aliases + html
            if (TavernPanelStats.enabled) TavernPanelStats.loads.incrementAndGet()
            view.loadDataWithBaseURL(url, document, "text/html", "UTF-8", null)
        } else {
            runtime = value
            heightChanged = onHeight
            boundaryDrag = onDrag
            if (viewportHeight != maxHeight) {
                viewportHeight = maxHeight
                installLayout()
            }
        }
        bridge?.updateRuntime(value)
    }

    fun pageFinished() {
        val page = bridge ?: return
        view.post {
            if (page !== bridge || page.isClosed()) return@post
            installLayout()
        }
    }

    private fun installLayout() {
        val page = bridge ?: return
        val script = tavernMessageLayoutScript(viewportHeight) + ";" + tavernResizeScript()
        // loadDataWithBaseURL callbacks need not preserve the base URL. The document
        // itself supplies the identity; late blank/old callbacks cannot reset it.
        view.evaluateJavascript(tavernPanelInstallScript(page.documentId, viewportHeight, script), null)
    }

    fun retire() {
        val old = bridge
        old?.close()
        if (old != null) runtime?.sizes?.remove(old)
        bridge = null
        runtime = null
        heightChanged = {}
        boundaryDrag = {}
        loadedHtml = null
        url = null
        view.stopLoading()
        interfaceName?.let(view::removeJavascriptInterface)
        interfaceName = null
        view.scrollTo(0, 0)
        view.loadUrl("about:blank")
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    companion object { private val nextDocument = java.util.concurrent.atomic.AtomicLong() }
}

@Composable
internal fun PendingMessagePanel(maxHeight: Dp, runtime: TavernMessageRuntime) {
    val density = LocalDensity.current
    val key = PanelSizeKey("bubble:${runtime.messageId}", "estimate", 0, density.density, density.fontScale, "")
    val cached = runtime.sizes?.get(key)
    Box(Modifier.fillMaxWidth().height(if (cached == null) maxHeight else with(density) { cached.toDp() })) {
        Text(stringResource(R.string.chat_render_pending), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TavernHtmlPanel(
    html: String,
    availableMaxHeight: Dp,
    dialogueQuoteColor: String? = null,
    baseFontSizePx: Int? = null,
    tavernRuntime: TavernMessageRuntime,
    onBoundaryDrag: (Float) -> Unit,
) {
    if (tavernRuntime.token == null) return
    androidx.compose.runtime.key(tavernRuntime.token) {
        val themeOnSurface = MaterialTheme.colorScheme.onSurface.toCssHex()
        val themeColors = mapOf(
            "SmartThemeBlurTintColor" to MaterialTheme.colorScheme.surface.toCssHex(),
            "SmartThemeChatTintColor" to MaterialTheme.colorScheme.surface.toCssHex(),
            "SmartThemeBorderColor" to MaterialTheme.colorScheme.outlineVariant.toCssHex(),
            "SmartThemeEmColor" to MaterialTheme.colorScheme.primary.toCssHex(),
            "SmartThemeQuoteColor" to (dialogueQuoteColor ?: MaterialTheme.colorScheme.primary.toCssHex()),
            "SmartThemeShadowColor" to "rgba(0, 0, 0, 0.2)",
        )
        val latestHtml = remember(html, themeOnSurface, dialogueQuoteColor, baseFontSizePx, themeColors) {
            wrapTavernHtml(html, themeOnSurface, dialogueQuoteColor, baseFontSizePx, themeColors)
        }
        var lastDisplayedHtml by remember(tavernRuntime.messageId, tavernRuntime.segmentIndex) { mutableStateOf(latestHtml) }
        val wrappedHtml = if (tavernRuntime.allowContentUpdates) latestHtml else lastDisplayedHtml
        SideEffect { lastDisplayedHtml = wrappedHtml }
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val maxPanelHeight = remember(configuration.screenHeightDp, availableMaxHeight) {
            val screenBound = (configuration.screenHeightDp.dp * 0.92f)
                .coerceAtLeast(420.dp)
                .coerceAtMost(1120.dp)
            val viewportBound = if (availableMaxHeight > 0.dp) availableMaxHeight else screenBound
            minOf(screenBound, viewportBound).coerceAtLeast(180.dp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val contentVersion = remember(wrappedHtml) {
            java.security.MessageDigest.getInstance("SHA-256").digest(wrappedHtml.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val sizeKey = PanelSizeKey("${tavernRuntime.messageId}:${tavernRuntime.segmentIndex}", contentVersion,
            constraints.maxWidth, density.density, density.fontScale, themeOnSurface)
        val capPx = with(density) { maxPanelHeight.roundToPx() }
        var displayedHeightPx by remember(sizeKey, capPx) {
            mutableIntStateOf((tavernRuntime.sizes?.get(sizeKey) ?: capPx).coerceIn(1, capPx))
        }
        val panelHeight = with(density) { displayedHeightPx.toDp() }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelHeight),
            factory = { context ->
                WebView(context).apply {
                    if (TavernPanelStats.enabled) TavernPanelStats.creates.incrementAndGet()
                    val controller = TavernPanelController(this)
                    tag = controller
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = true
                    overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
                    var lastTouchY = 0f
                    var velocityTracker: VelocityTracker? = null
                    var forwardedLastMove = false
                    val touchConfig = ViewConfiguration.get(context)
                    setOnTouchListener { view, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            velocityTracker?.recycle()
                            velocityTracker = VelocityTracker.obtain()
                            forwardedLastMove = false
                        }
                        // Velocity must use the same screen coordinates as boundary deltas.
                        val sample = MotionEvent.obtain(event)
                        sample.offsetLocation(event.rawX - event.x, event.rawY - event.y)
                        velocityTracker?.addMovement(sample)
                        sample.recycle()
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastTouchY = event.rawY
                                (view.tag as? TavernPanelController)?.bridge?.beginNativeTouchGesture()
                                // 先假定由 WebView 接管手势；MOVE 时若 WebView 在该方向上
                                // 没有可滚动内容，再把拦截权交还给外层聊天列表。
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                forwardedLastMove = false
                                // The list moves this WebView during a boundary drag.
                                // Local y would count that movement again on the next event.
                                val dy = event.rawY - lastTouchY
                                lastTouchY = event.rawY
                                val bridge = (view.tag as? TavernPanelController)?.bridge
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
                                    forwardedLastMove = chatScrollDelta != 0f
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val bridge = (view.tag as? TavernPanelController)?.bridge
                                if (event.actionMasked == MotionEvent.ACTION_UP && forwardedLastMove &&
                                    bridge?.hasNestedScrollGesture() != true) {
                                    velocityTracker?.computeCurrentVelocity(1000, touchConfig.scaledMaximumFlingVelocity.toFloat())
                                    val velocity = -(velocityTracker?.yVelocity ?: 0f)
                                    if (kotlin.math.abs(velocity) >= touchConfig.scaledMinimumFlingVelocity) {
                                        bridge?.dispatchBoundaryFling(velocity)
                                    }
                                }
                                velocityTracker?.recycle()
                                velocityTracker = null
                                bridge?.endNativeTouchGesture()
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
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                            app.tellev.core.extension.CompatAssets.intercept(context, request.url.toString())
                        override fun onPageFinished(view: WebView, url: String?) {
                            (view.tag as? TavernPanelController)?.pageFinished()
                        }
                    }
                }
            },
            onReset = { webView -> (webView.tag as? TavernPanelController)?.retire() },
            onRelease = { webView ->
                (webView.tag as? TavernPanelController)?.retire()
                if (TavernPanelStats.enabled) TavernPanelStats.releases.incrementAndGet()
                webView.destroy()
            },
            update = { webView ->
                val controller = webView.tag as TavernPanelController
                controller.bind(tavernRuntime, wrappedHtml, with(density) { maxPanelHeight.toPx() / density.density }.toInt(),
                    onHeight = { height ->
                        val rawHeight = (height * density.density).toInt().coerceAtLeast(1)
                        tavernRuntime.sizes?.record(sizeKey, rawHeight)
                        val effective = rawHeight.coerceAtMost(capPx)
                        val apply = { if (kotlin.math.abs(effective - displayedHeightPx) >= 2) displayedHeightPx = effective }
                        val binding = controller.bridge
                        if (binding != null && tavernRuntime.sizes != null) tavernRuntime.sizes.deliver(binding, apply) else apply()
                    }, onDrag = onBoundaryDrag)
            },
        )
        }
    }
}

internal fun tavernPanelInstallScript(documentId: String, viewportHeight: Int, script: String): String {
    val quotedId = org.json.JSONObject.quote(documentId)
    return """
        if (window.__tellevDocumentId === $quotedId && document.readyState !== 'loading' &&
            window.__tellevLayoutInstalledHeight !== $viewportHeight) {
            window.__tellevLayoutInstalledHeight = $viewportHeight;
            $script
        }
    """.trimIndent()
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
    baseFontSizePx: Int? = null,
    themeColors: Map<String, String> = emptyMap(),
): String {
    // Tavern fragments inherit these from the host page. Keep authored styles
    // after our defaults so explicit card themes can still override them.
    val themeVariables = (mapOf("SmartThemeBodyColor" to themeOnSurface) + themeColors)
        .entries.joinToString("\n") { (name, value) -> "--$name: $value;" }
    val dialogueQuoteCss = dialogueQuoteColor?.let { color ->
        "q { color: $color; } q::before, q::after { content: none; }"
    }.orEmpty()
    // Only Markdown message bodies pass a size. Authored frontend cards keep their own CSS.
    val fontSizeCss = baseFontSizePx?.let { "body { font-size: ${it.coerceIn(14, 20)}px; }" }.orEmpty()
    val hostHead = """
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <script src="https://extensions.tellev.local/compat/globals.js"></script>
        <script src="https://extensions.tellev.local/compat/message-host.js"></script>
        <script src="https://extensions.tellev.local/compat/chat.js"></script>
        <script src="https://extensions.tellev.local/compat/message.js"></script>
        <style id="tellev-host-style">
            :root {
                $themeVariables
            }
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
                /* 正文里单个超长未断词（URL、base64）也只在这一层折行，
                   否则它会撑破气泡后被根级 overflow-x:hidden 整段裁掉。 */
                overflow-wrap: break-word;
            }
            /* 代码块按原样保留缩进与不换行，长行改成块内横向滚动。根级的
               overflow-x:hidden 会裁掉逃出视口的内容，只有把 pre 自身做成
               滚动容器，右侧那截字才划得回来。刻意不用 pre-wrap：
               card 输出的对齐排版（状态栏、群聊记录这类）一换行就散架，
               那类兼容问题比这次的裁切更难收场。 */
            pre {
                max-width: 100%;
                overflow-x: auto;
                overflow-wrap: normal;
                white-space: pre;
            }
            $dialogueQuoteCss
            $fontSizeCss
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
            var body = document.body;
            if (!body) return 1;
            // Root scrollHeight is at least the viewport height, even after collapse.
            // Measure rendered content boxes instead, so a panel can shrink again.
            var bounds = body.getBoundingClientRect();
            var height = body.offsetHeight || bounds.height || 0;
            Array.prototype.forEach.call(body.children, function(child) {
                var style = window.getComputedStyle(child);
                if (style.display === 'none' || style.position === 'fixed') return;
                var rect = child.getBoundingClientRect();
                height = Math.max(height, rect.bottom - bounds.top + (parseFloat(style.marginBottom) || 0));
            });
            return Math.max(1, Math.ceil(height));
        }
        function postHeightNow() {
            scheduled = false;
            var h = pageHeight();
            if (Math.abs(h - lastPostedHeight) * (window.devicePixelRatio || 1) < 2) return;
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
                contentDescription = stringResource(R.string.chat_previous_page),
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
                contentDescription = stringResource(R.string.chat_next_page),
            )
        }
    }
}
