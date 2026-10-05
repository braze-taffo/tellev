package app.tellev.core.update

import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import java.io.IOException
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
     * The version this channel accepts for [tag]: the channel [tagSuffix] is
     * stripped off and what remains must be a plain `vX.Y.Z`-style version
     * (optional `v`, 2-4 numeric dot-groups, nothing else). Returns null for
     * tags from the other channel (`v1.6.3-mnn` on the official stream) and for
     * unrelated tags such as `nightly` or `v1.6.3-beta`.
     */
    fun versionOf(tag: String): String? {
        val trimmed = tag.trim()
        val core = when {
            tagSuffix.isEmpty() -> trimmed
            trimmed.endsWith(tagSuffix) -> trimmed.dropLast(tagSuffix.length)
            else -> return null
        }
        if (!PLAIN_VERSION_TAG.matches(core)) return null
        return core.removePrefix("v")
    }

    /** True when version [tag] belongs to this channel. */
    fun matchesTag(tag: String): Boolean = versionOf(tag) != null

    /**
     * The exact APK asset name this channel accepts for [version]:
     * `tellev-X.Y.Z.apk` for the official channel,
     * `tellev-X.Y.Z-mnn.apk` for MNN. Everything else — `app-release.apk`,
     * another channel's asset, another version's asset, a non-APK suffix —
     * is rejected, so a release can only be updated from the asset that was
     * built for its own tag and channel.
     */
    fun expectedApkName(version: String): String = "tellev-$version$tagSuffix.apk"

    /**
     * True when APK asset [name] belongs to this channel *and* is the one
     * canonical name shape (`tellev-<version>[-mnn].apk`). The `-mnn` marker
     * decides an asset's channel, so an official build never picks up an MNN
     * APK even if both were attached to one release, and a hand-named
     * `app-release.apk` never counts as a channel asset at all.
     */
    fun matchesApkAsset(name: String): Boolean = apkAssetVersion(name) != null

    /**
     * The version embedded in a channel-correct APK asset [name], or null when
     * the name is not exactly `tellev-<version><channel suffix>.apk`. Parsing
     * compares this against the release tag's version, which is what enforces
     * the exact tag ↔ asset pairing.
     *
     * [matchEntire] (not [Regex.find]) so a trailing newline — a `$` anchor
     * matches before a final line terminator in Java regexes — cannot make a
     * crafted name such as `tellev-1.4.1.apk\n` count as the real asset.
     */
    fun apkAssetVersion(name: String): String? =
        APK_NAME.matchEntire(name)?.groupValues?.get(1)

    /** `tellev-<version><channel suffix>.apk`, built from this channel's suffix. */
    private val APK_NAME: Regex by lazy {
        Regex("""^tellev-(\d+(\.\d+){1,3})""" + Regex.escape(tagSuffix) + """\.apk$""")
    }

    private companion object {
        /** `v` optional, 2-4 numeric dot-groups, no channel suffix. */
        val PLAIN_VERSION_TAG = Regex("""^v?\d+(\.\d+){1,3}$""")
    }
}

/**
 * [UpdateChannel.displayName] stays a plain enum field (it is data, not UI text);
 * user-facing strings resolve through resources so both channels localize.
 */
private fun UpdateChannel.localizedDisplayName(): String = when (this) {
    UpdateChannel.Official -> UiStrings.get(S.updchk_channel_official)
    UpdateChannel.Mnn -> UiStrings.get(S.updchk_channel_mnn)
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
                    lastError = IllegalStateException(UiStrings.get(S.updchk_channel_release_missing, channel.localizedDisplayName()))
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
            ?: error(UiStrings.get(S.updchk_channel_release_missing, channel.localizedDisplayName()))
        return parseReleaseObject(release.jsonObject)
    }

    /**
     * True when [entry] is a release on [channel] carrying a [channel] APK whose
     * name carries exactly the tag's version (`tellev-<version><suffix>.apk`).
     * All three halves are required: a tag from the other channel, a matching
     * tag with only the other channel's APK, and a matching tag whose asset
     * version differs from the tag are all non-publishable, so none of them may
     * be offered as evidence that this channel has a build.
     */
    private fun isChannelRelease(entry: JsonObject?): Boolean {
        if (entry == null) return false
        val tag = entry["tag_name"]?.jsonPrimitive?.contentOrNull ?: return false
        val version = channel.versionOf(tag) ?: return false
        return entry["assets"]?.jsonArray?.any { asset ->
            val name = asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            name != null && channel.apkAssetVersion(name) == version
        } == true
    }

    /** Parses a single-release JSON body (e.g. a `releases/tags/<tag>` response). */
    fun parseReleaseJson(body: String): UpdateInfo =
        parseReleaseObject(json.parseToJsonElement(body).jsonObject)

    /**
     * Parses a single GitHub release JSON object. Throws when the tag does not
     * belong to this build's [channel] or when the release carries no APK asset
     * named `tellev-<tag version><channel suffix>.apk`. The tag/asset pairing is
     * checked here as well as in [parseLatestChannelRelease], so
     * [parseReleaseJson] cannot be used to smuggle in the other channel's APK,
     * an `app-release.apk`, an asset whose version differs from the tag, or a
     * non-`.apk` suffix.
     */
    fun parseReleaseObject(root: JsonObject): UpdateInfo {
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
            ?: error("缺少 tag_name")
        val version = channel.versionOf(tag)
            ?: error(UiStrings.get(S.updchk_channel_release_missing, channel.localizedDisplayName()))
        val assets = root["assets"]?.jsonArray
            ?: error("缺少 assets")
        val apk = assets.firstOrNull { entry ->
            val name = entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            name != null && channel.apkAssetVersion(name) == version
        } ?: error(UiStrings.get(S.updchk_channel_apk_missing, channel.localizedDisplayName()))
        val apkObj = apk.jsonObject
        return UpdateInfo(
            tagName = tag,
            version = version,
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
     * Compares two semver-ish strings; see [compareSemverVersions] for the rules.
     */
    fun compareVersions(a: String, b: String): Int = compareSemverVersions(a, b)

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
                        throw IOException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException(UiStrings.get(S.updchk_empty_response_body))
                    val contentLength = body.contentLength()
                    val total = contentLength.takeIf { it > 0 } ?: info.apkSize
                    var read = 0L
                    target.outputStream().use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    if (read == 0L || (contentLength >= 0 && read != contentLength) ||
                        (info.apkSize > 0 && read != info.apkSize)) {
                        throw IOException("Incomplete APK download: received $read bytes, expected $total")
                    }
                }
                // Verify integrity only after a clean download; a mismatch is
                // not a transient mirror failure, so propagate it directly.
                if (info.sha256 != null) {
                    val actual = sha256(target)
                    if (!actual.equals(info.sha256, ignoreCase = true)) {
                        target.delete()
                        throw IllegalStateException(UiStrings.get(S.updchk_apk_checksum_failed))
                    }
                }
                onProgress(1f)
                return@withContext target
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: IOException) {
                lastError = e
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException(UiStrings.get(S.updchk_download_failed))
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
