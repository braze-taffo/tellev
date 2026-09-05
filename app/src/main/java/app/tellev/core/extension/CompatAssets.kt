package app.tellev.core.extension

import android.content.Context
import android.webkit.WebResourceResponse

/** Exact URL aliases only: arbitrary card resources continue to use their own URL. */
object CompatAssets {
    private val aliases = buildMap {
        put("https://extensions.tellev.local/compat/globals.js", "globals.js")
        put("https://extensions.tellev.local/compat/message.js", "message.js")
        put("https://extensions.tellev.local/compat/chat.js", "chat.js")
        for (host in listOf("testingcf.jsdelivr.net", "cdn.jsdelivr.net", "fastly.jsdelivr.net")) {
            put("https://$host/gh/MagicalAstrogy/MagVarUpdate/artifact/bundle.js", "mvu.js")
            put("https://$host/gh/StageDog/tavern_resource/dist/util/mvu_zod.js", "mvu-zod.js")
        }
    }

    fun intercept(context: Context, url: String): WebResourceResponse? {
        val file = aliases[url] ?: return null
        return WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
            context.assets.open("compat/$file"))
    }

    fun source(context: Context, name: String): String =
        context.assets.open("compat/$name").bufferedReader().use { it.readText() }
}
