package app.tellev.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * A GitHub mirror. [prefix] is prepended to the full GitHub URL; an empty
 * prefix means "direct". The prefix-style community mirrors
 * (gh-proxy.com, ghfast.top, ...) accept the full URL right after the slash,
 * e.g. `https://gh-proxy.com/https://api.github.com/...`.
 */
data class UpdateMirror(val id: String, val name: String, val prefix: String) {
    fun rewrite(url: String): String = if (prefix.isEmpty()) url else "$prefix$url"
}

/**
 * Parsed information about the latest GitHub release that carries an APK.
 */
data class UpdateInfo(
    val tagName: String,
    /** [tagName] with a leading `v` stripped, for display and comparison. */
    val version: String,
    val title: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    /** SHA-256 of the APK asset if GitHub reported a `digest`, else null. */
    val sha256: String?,
)

/**
 * Checks GitHub for a newer tellev release and downloads its APK.
 *
 * Works behind the GFW by trying a direct request first and falling back
 * through [DEFAULT_MIRRORS]; the first mirror that returns a usable response
 * wins. All network calls use the [OkHttpClient] supplied at construction,
 * which should have short timeouts so a dead mirror is abandoned quickly.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Fetches the latest non-prerelease release. Returns null only if every
     * mirror was unreachable; throws the last error otherwise. A release with
     * no APK asset is treated as "no info" (returns null) so callers can show
     * "up to date" rather than a spurious error.
     */
    suspend fun fetchLatest(mirrors: List<UpdateMirror>): UpdateInfo? = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/braze-taffo/tellev/releases/latest"
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            try {
                val request = Request.Builder()
                    .url(mirror.rewrite(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "tellev-update-checker")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${response.code}")
                        return@use
                    }
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching { parseReleaseJson(body) }.getOrNull()
                    if (parsed != null) return@withContext parsed
                    lastError = IllegalStateException("无法解析版本信息")
                }
            } catch (e: java.io.IOException) {
                // Network/timeout/protocol error: try the next mirror.
                lastError = e
            }
        }
        // lastError is a var captured by the request/catch closures, so it
        // can't be smart-cast to Throwable; let{} keeps the type sound.
        lastError?.let { throw it }
        null
    }

    /**
     * Parses a GitHub `releases/latest` JSON body into [UpdateInfo].
     * Throws if the body lacks a tag or an APK asset.
     */
    fun parseReleaseJson(body: String): UpdateInfo {
        val root = json.parseToJsonElement(body).jsonObject
        require(root["draft"]?.jsonPrimitive?.booleanOrNull != true) {
            "草稿版本不能用于应用更新"
        }
        require(root["prerelease"]?.jsonPrimitive?.booleanOrNull != true) {
            "预发布版本不能用于正式更新"
        }
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: error("缺少 tag_name")
        require(!PRE_RELEASE_TAG.containsMatchIn(tag)) {
            "预发布标签不能用于正式更新"
        }
        val assets = root["assets"]?.jsonArray
            ?: error("缺少 assets")
        val apk = assets.firstOrNull { entry ->
            val name = entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            name != null && name.endsWith(".apk", ignoreCase = true)
        } ?: error("未找到 APK 资产")
        val apkObj = apk.jsonObject
        return UpdateInfo(
            tagName = tag,
            version = tag.removePrefix("v").trim(),
            title = root["name"]?.jsonPrimitive?.contentOrNull ?: tag,
            releaseNotes = root["body"]?.jsonPrimitive?.contentOrNull ?: "",
            htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
                ?: "https://github.com/braze-taffo/tellev/releases",
            apkUrl = apkObj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?: error("缺少 APK 下载地址"),
            apkSize = apkObj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
            sha256 = apkObj["digest"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:"),
        )
    }

    /** True when [latest] is strictly newer than [currentVersion]. */
    fun isUpdateAvailable(currentVersion: String, latest: UpdateInfo): Boolean =
        compareVersions(currentVersion, latest.version) < 0

    /**
     * Compares two semver-ish strings. Leading `v` is stripped, leading
     * numeric dot-groups are compared, missing segments default to 0, and a
     * prerelease is older than the stable build with the same core version.
     * Thus `1.4` == `1.4.0`, `1.4.10` > `1.4.9`, and
     * `1.5.0-beta.1` < `1.5.0`.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseSemver(a)
        val pb = parseSemver(b)
        val len = maxOf(pa.numbers.size, pb.numbers.size)
        for (i in 0 until len) {
            val x = pa.numbers.getOrElse(i) { 0 }
            val y = pb.numbers.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return when {
            pa.isPrerelease && !pb.isPrerelease -> -1
            !pa.isPrerelease && pb.isPrerelease -> 1
            else -> 0
        }
    }

    private fun parseSemver(version: String): ParsedVersion {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val match = Regex("""^(\d+(\.\d+){0,3})""").find(cleaned)
        val core = match?.groupValues?.get(1) ?: cleaned
        val numbers = core.split('.').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
        val suffix = cleaned.removePrefix(match?.value.orEmpty())
        return ParsedVersion(numbers, isPrerelease = suffix.startsWith("-"))
    }

    private data class ParsedVersion(
        val numbers: List<Int>,
        val isPrerelease: Boolean,
    )

    /**
     * Streams the APK for [info] to [target], trying [mirrors] in order until
     * one delivers the full file. Reports download progress in `[0f, 1f]`.
     * If [UpdateInfo.sha256] is known, the downloaded file is verified and a
     * mismatch aborts (the same asset from any mirror would mismatch, so there
     * is no point retrying).
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        mirrors: List<UpdateMirror>,
        target: File,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        var lastError: Throwable? = null
        for (mirror in mirrors) {
            try {
                val request = Request.Builder()
                    .url(mirror.rewrite(info.apkUrl))
                    .header("User-Agent", "tellev-update-checker")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = IllegalStateException("HTTP ${response.code}")
                        return@use
                    }
                    val body = response.body ?: error("空响应体")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                    target.outputStream().use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                // Verify integrity only after a clean download; a mismatch is
                // not a transient mirror failure, so propagate it directly.
                if (info.sha256 != null) {
                    val actual = sha256(target)
                    if (!actual.equals(info.sha256, ignoreCase = true)) {
                        target.delete()
                        throw IllegalStateException("APK 校验失败")
                    }
                }
                onProgress(1f)
                return@withContext target
            } catch (e: java.io.IOException) {
                lastError = e
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val PRE_RELEASE_TAG = Regex(
            """(^|[-_.])(alpha|beta|rc)([-_.0-9]|$)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Ordered mirror list: direct first, then community proxies that also
         * front api.github.com and release-asset downloads for users in China.
         * These mirrors rotate and occasionally go down; the auto-fallback is
         * exactly what makes the list safe to keep long.
         */
        val DEFAULT_MIRRORS: List<UpdateMirror> = listOf(
            UpdateMirror("direct", "直连", ""),
            UpdateMirror("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/"),
            UpdateMirror("ghfast", "ghfast.top", "https://ghfast.top/"),
            UpdateMirror("moeyy", "github.moeyy.xyz", "https://github.moeyy.xyz/"),
            UpdateMirror("llkk", "gh.llkk.cc", "https://gh.llkk.cc/"),
        )
    }
}
