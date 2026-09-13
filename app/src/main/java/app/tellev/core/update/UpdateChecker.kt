package app.tellev.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
 * A tellev release channel. Every version tag and APK asset name on a channel
 * carries its [tagSuffix] (`""` for the official app, `"-mnn"` for the MNN
 * image-gen build), so the two update streams can be told apart from the tag
 * itself. Matching on the suffix rather than on GitHub's pre-release flag means
 * a release that was published with the wrong flag still cannot leak across
 * channels.
 */
enum class UpdateChannel(val tagSuffix: String, val displayName: String) {
    Official("", "正式版"),
    Mnn("-mnn", "生图版"),
    ;

    /**
     * True when version [tag] belongs to this channel: the channel suffix is
     * stripped off and what remains must be a plain `vX.Y.Z`-style version.
     * The suffix requirement also rejects unrelated tags such as `nightly`.
     */
    fun matchesTag(tag: String): Boolean {
        val trimmed = tag.trim()
        val core = when {
            tagSuffix.isEmpty() -> trimmed
            trimmed.endsWith(tagSuffix) -> trimmed.dropLast(tagSuffix.length)
            else -> return false
        }
        return PLAIN_VERSION_TAG.matches(core)
    }

    /**
     * True when APK asset [name] belongs to this channel. The `-mnn` marker
     * decides an asset's channel, so an official build never picks up an MNN
     * APK even if both were attached to one release.
     */
    fun matchesApkAsset(name: String): Boolean {
        if (!name.endsWith(".apk", ignoreCase = true)) return false
        val assetIsMnn = name.contains(MNN_MARKER, ignoreCase = true)
        return assetIsMnn == (this == Mnn)
    }

    private companion object {
        /** `v` optional, 2-4 numeric dot-groups, no channel suffix. */
        val PLAIN_VERSION_TAG = Regex("""^v?\d+(\.\d+){1,3}$""")

        /** Marks the MNN image-gen channel in release asset names. */
        const val MNN_MARKER = "-mnn"
    }
}

/**
 * Checks GitHub for a newer tellev release and downloads its APK.
 *
 * Works behind the GFW by trying a direct request first and falling back
 * through [DEFAULT_MIRRORS]; the first mirror that returns a usable response
 * wins. All network calls use the [OkHttpClient] supplied at construction,
 * which should have short timeouts so a dead mirror is abandoned quickly.
 *
 * Only releases on [channel] are ever surfaced, so one build can never offer
 * another channel's APK.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val channel: UpdateChannel = CHANNEL,
) {
    /**
     * Fetches the newest release on this build's [channel]. Returns null only
     * if every mirror was unreachable; throws the last error otherwise. The
     * release list is filtered by tag suffix and APK asset name, so the
     * official app never offers an MNN build and vice versa — regardless of how
     * a release's GitHub pre-release flag happens to be set.
     */
    suspend fun fetchLatest(mirrors: List<UpdateMirror>): UpdateInfo? = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/braze-taffo/tellev/releases?per_page=100"
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
                    val parsed = runCatching { parseLatestChannelRelease(body) }.getOrNull()
                    if (parsed != null) return@withContext parsed
                    lastError = IllegalStateException("未找到 ${channel.displayName} 渠道的发行")
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
     * Parses a GitHub `releases` list body and returns the newest entry whose
     * tag and APK asset both belong to this build's [channel]. Releases from the
     * other channel are skipped even when they are newer. Throws when the list
     * holds no release for this channel.
     */
    fun parseLatestChannelRelease(body: String): UpdateInfo {
        val root = json.parseToJsonElement(body).jsonArray
        val release = root.firstOrNull { isChannelRelease(it as? JsonObject) }
            ?: error("未找到 ${channel.displayName} 渠道的发行")
        return parseReleaseObject(release.jsonObject)
    }

    /**
     * True when [entry] is a release on [channel] carrying a [channel] APK. Both
     * halves are required: a matching tag with only the other channel's APK is
     * not publishable evidence that this channel has a build.
     */
    private fun isChannelRelease(entry: JsonObject?): Boolean {
        if (entry == null) return false
        val tag = entry["tag_name"]?.jsonPrimitive?.contentOrNull ?: return false
        if (!channel.matchesTag(tag)) return false
        return entry["assets"]?.jsonArray?.any { asset ->
            val name = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            name != null && channel.matchesApkAsset(name)
        } == true
    }

    /** Parses a single-release JSON body (e.g. a `releases/tags/<tag>` response). */
    fun parseReleaseJson(body: String): UpdateInfo =
        parseReleaseObject(json.parseToJsonElement(body).jsonObject)

    /**
     * Parses a single GitHub release JSON object. Throws if it lacks a tag or an
     * APK asset belonging to this build's [channel].
     */
    fun parseReleaseObject(root: JsonObject): UpdateInfo {
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: error("缺少 tag_name")
        val assets = root["assets"]?.jsonArray
            ?: error("缺少 assets")
        val apk = assets.firstOrNull { entry ->
            val name = entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            name != null && channel.matchesApkAsset(name)
        } ?: error("未找到 ${channel.displayName} 渠道的 APK 资产")
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
     * Compares two semver-ish strings. Leading `v` is stripped, only leading
     * numeric dot-groups are considered, and missing segments default to 0,
     * so `1.4` == `1.4.0` and `1.4.10` > `1.4.9`.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseSemver(a)
        val pb = parseSemver(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseSemver(version: String): List<Int> {
        val cleaned = version.trim().removePrefix("v").removePrefix("V")
        val match = Regex("""^(\d+(\.\d+){0,3})""").find(cleaned)
        val core = match?.groupValues?.get(1) ?: cleaned
        return core.split('.').mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
    }

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
        /**
         * This build's release channel — the one line that differs between the
         * official (`master`) and MNN (`mnn-image-gen`) worktrees.
         */
        val CHANNEL: UpdateChannel = UpdateChannel.Official

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
