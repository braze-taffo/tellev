package app.tellev.core.extension

import android.content.Context

/**
 * Loads Markdown-related JS scripts bundled under `assets/`. Sources are read once and
 * cached for the process lifetime; they're injected into the extension sandbox WebView
 * (see `WebViewJsExtensionHost.buildExtensionHtml`) so `TavernHelper.builtin.renderMarkdown`
 * can use showdown.js, matching the output of the upstream TavernHelper reference.
 */
object MarkdownScripts {
    @Volatile private var showdownSource: String? = null

    fun showdownSource(context: Context): String = showdownSource ?: synchronized(this) {
        showdownSource ?: context.assets.open("showdown/showdown.min.js")
            .bufferedReader()
            .use { it.readText() }
            .also { showdownSource = it }
    }
}
