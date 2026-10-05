package app.tellev.core.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The channel/asset rules under test:
 *
 *  - the official channel accepts only a plain `vX.Y.Z`-style tag whose APK asset
 *    is exactly `tellev-<same version>.apk`;
 *  - the MNN channel accepts only a `vX.Y.Z-mnn` tag whose APK asset is exactly
 *    `tellev-<same version>-mnn.apk`;
 *  - `app-release.apk`, an asset from the other channel, an asset whose version
 *    differs from the tag and non-`.apk` suffixes are all rejected — on the list
 *    path ([UpdateChecker.parseLatestChannelRelease]) and on the single-release
 *    path ([UpdateChecker.parseReleaseJson]) alike.
 */
class UpdateCheckerTest {

    private val checker = UpdateChecker(OkHttpClient())

    /** Single-release bodies carry no channel marker of their own, so those
     *  fixtures are parsed against an explicitly chosen channel. */
    private val officialChecker = UpdateChecker(OkHttpClient(), channel = UpdateChannel.Official)
    private val mnnChecker = UpdateChecker(OkHttpClient(), channel = UpdateChannel.Mnn)

    @Test
    fun `newer patch version is an update`() {
        assertTrue(checker.isUpdateAvailable("1.4.0", info("1.4.1")))
    }

    @Test
    fun `same version is not an update`() {
        assertFalse(checker.isUpdateAvailable("1.4.0", info("1.4.0")))
    }

    @Test
    fun `older version is not an update`() {
        assertFalse(checker.isUpdateAvailable("1.4.0", info("1.3.3")))
    }

    @Test
    fun `double-digit patch sorts numerically`() {
        // 1.4.10 must be newer than 1.4.9, not lexically smaller.
        assertTrue(checker.isUpdateAvailable("1.4.9", info("1.4.10")))
        assertFalse(checker.isUpdateAvailable("1.4.10", info("1.4.9")))
    }

    @Test
    fun `leading v prefix is stripped`() {
        assertTrue(checker.isUpdateAvailable("1.4.0", info("v1.4.1")))
        assertTrue(checker.isUpdateAvailable("v1.4.0", info("1.4.1")))
    }

    @Test
    fun `missing segments are treated as zero`() {
        assertEquals(0, checker.compareVersions("1.4", "1.4.0"))
        assertEquals(0, checker.compareVersions("1.4.0.0", "1.4"))
        assertTrue(checker.isUpdateAvailable("1.4", info("1.4.1")))
        assertTrue(checker.isUpdateAvailable("1.5.5", info("1.5.5.1")))
    }

    // ── accepted shapes ──────────────────────────────────────────────

    @Test
    fun `parse picks the apk asset and strips v`() {
        val json = """
            {
              "tag_name": "v1.4.1",
              "name": "v1.4.1 - 修复",
              "body": "修复了一些问题",
              "html_url": "https://github.com/braze-taffo/tellev/releases/tag/v1.4.1",
              "published_at": "2026-07-28T00:00:00Z",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://github.com/braze-taffo/tellev/releases/download/v1.4.1/checksums.txt",
                  "size": 128,
                  "content_type": "text/plain"
                },
                {
                  "name": "tellev-1.4.1.apk",
                  "browser_download_url": "https://github.com/braze-taffo/tellev/releases/download/v1.4.1/tellev-1.4.1.apk",
                  "size": 4400000,
                  "content_type": "application/vnd.android.package-archive",
                  "digest": "sha256:abc123"
                }
              ]
            }
        """.trimIndent()

        val info = officialChecker.parseReleaseJson(json)
        assertEquals("v1.4.1", info.tagName)
        assertEquals("1.4.1", info.version)
        assertEquals("v1.4.1 - 修复", info.title)
        assertEquals("修复了一些问题", info.releaseNotes)
        assertTrue(info.apkUrl.endsWith("tellev-1.4.1.apk"))
        assertEquals(4_400_000L, info.apkSize)
        assertEquals("abc123", info.sha256)
    }

    @Test
    fun `mnn channel accepts only its own tag and apk`() {
        val json = release(
            tag = "v1.6.3-mnn",
            assets = arrayOf("tellev-1.6.3-mnn.apk"),
        )

        val info = mnnChecker.parseReleaseJson(json)
        assertEquals("v1.6.3-mnn", info.tagName)
        // version drops the channel suffix so it compares against versionName.
        assertEquals("1.6.3", info.version)
        assertTrue(info.apkUrl.endsWith("tellev-1.6.3-mnn.apk"))
        assertTrue(mnnChecker.isUpdateAvailable("1.6.2", info))
    }

    @Test
    fun `four part release versions keep their exact pairing`() {
        // Published releases like v1.5.5.1 / v1.6.0.1 ship tellev-1.6.0.1.apk.
        val accepted = officialChecker.parseReleaseJson(
            release(tag = "v1.6.0.1", assets = arrayOf("tellev-1.6.0.1.apk")),
        )
        assertEquals("1.6.0.1", accepted.version)
        assertTrue(accepted.apkUrl.endsWith("tellev-1.6.0.1.apk"))
    }

    @Test
    fun `parse handles missing digest gracefully`() {
        // The only APK a release may be updated from is its own
        // tellev-<version>.apk, so the no-digest fixture uses that name.
        val json = release(
            tag = "1.5.0",
            assets = arrayOf("tellev-1.5.0.apk"),
        )

        val info = officialChecker.parseReleaseJson(json)
        assertEquals("1.5.0", info.version)
        assertTrue(info.apkUrl.endsWith("tellev-1.5.0.apk"))
        assertNull(info.sha256)
    }

    // ── tag rules ────────────────────────────────────────────────────

    @Test
    fun `plain version tags are channel-scoped`() {
        assertTrue(UpdateChannel.Official.matchesTag("v1.6.3"))
        // Published official tags also cover four-part versions.
        assertTrue(UpdateChannel.Official.matchesTag("1.5.5.1"))
        assertFalse(UpdateChannel.Official.matchesTag("v1.6.3-mnn"))
        assertFalse(UpdateChannel.Official.matchesTag("nightly"))
        assertFalse(UpdateChannel.Official.matchesTag("v1.6.3-beta"))
        assertTrue(UpdateChannel.Mnn.matchesTag("v1.6.3-mnn"))
        assertTrue(UpdateChannel.Mnn.matchesTag("1.6.0.1-mnn"))
        assertFalse(UpdateChannel.Mnn.matchesTag("v1.6.3"))
        assertFalse(UpdateChannel.Mnn.matchesTag("nightly"))
        // A doubled suffix strips once and must not look like a version.
        assertFalse(UpdateChannel.Mnn.matchesTag("v1.6.3-mnn-mnn"))
    }

    @Test
    fun `expected apk names are built from the tag version`() {
        assertEquals("tellev-1.6.3.apk", UpdateChannel.Official.expectedApkName("1.6.3"))
        assertEquals(
            "tellev-1.6.3-mnn.apk",
            UpdateChannel.Mnn.expectedApkName("1.6.3"),
        )
    }

    @Test
    fun `apk asset names must be the exact channel name`() {
        assertTrue(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3.apk"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3-mnn.apk"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("app-release.apk"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("checksums.txt"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3.zip"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3.apk.txt"))
        assertTrue(UpdateChannel.Mnn.matchesApkAsset("tellev-1.6.3-mnn.apk"))
        assertFalse(UpdateChannel.Mnn.matchesApkAsset("tellev-1.6.3.apk"))
        assertFalse(UpdateChannel.Mnn.matchesApkAsset("tellev-1.6.3-mnn-mnn.apk"))
        assertFalse(UpdateChannel.Mnn.matchesApkAsset("checksums.txt"))
        // The embedded version is what the tag is paired against.
        assertEquals("1.6.3", UpdateChannel.Official.apkAssetVersion("tellev-1.6.3.apk"))
        assertEquals("1.6.3", UpdateChannel.Mnn.apkAssetVersion("tellev-1.6.3-mnn.apk"))
        assertNull(UpdateChannel.Official.apkAssetVersion("tellev-1.6.3-mnn.apk"))
        assertNull(UpdateChannel.Official.apkAssetVersion("app-release.apk"))
    }

    @Test
    fun `asset names must match entirely, no trailing whitespace`() {
        // A regex `$` anchor also matches before a final line terminator, so the
        // pairing uses matchEntire: nothing may follow the `.apk`.
        assertNull(UpdateChannel.Official.apkAssetVersion("tellev-1.6.3.apk\n"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3.apk\n"))
        assertNull(UpdateChannel.Official.apkAssetVersion("tellev-1.6.3.apk "))
        assertNull(UpdateChannel.Official.apkAssetVersion("\ntellev-1.6.3.apk"))
        // `\\n` is a literal backslash + n in Kotlin, i.e. a JSON escape that
        // decodes to a real newline inside the asset name.
        assertRejected(
            release(tag = "v1.6.3", assets = arrayOf("tellev-1.6.3.apk\\n")),
        ) { body -> officialChecker.parseReleaseJson(body) }
    }

    // ── rejected shapes ──────────────────────────────────────────────

    @Test
    fun `official rejects an app-release apk`() {
        assertRejected(
            release(tag = "v1.5.0", assets = arrayOf("app-release.apk")),
        ) { body -> officialChecker.parseReleaseJson(body) }

        // …and on the list path, a release with no acceptable APK is skipped,
        // so a single such release means "no release for this channel".
        assertListRejected(releases(release(tag = "v1.5.0", assets = arrayOf("app-release.apk"))))
    }

    @Test
    fun `official rejects an apk whose version differs from the tag`() {
        assertRejected(
            release(tag = "v1.4.1", assets = arrayOf("tellev-1.6.3.apk")),
        ) { body -> officialChecker.parseReleaseJson(body) }

        // Same rule on the list path: the mismatched release is skipped and the
        // next release whose asset matches its own tag wins.
        val list = releases(
            release(tag = "v1.7.0", assets = arrayOf("tellev-1.6.3.apk")),
            release(tag = "v1.6.3", assets = arrayOf("tellev-1.6.3.apk")),
        )
        val info = checker.parseLatestChannelRelease(list)
        assertEquals("v1.6.3", info.tagName)
        assertTrue(info.apkUrl.endsWith("tellev-1.6.3.apk"))
    }

    @Test
    fun `official rejects a cross-channel apk even with a matching tag`() {
        assertRejected(
            release(tag = "v1.7.0", assets = arrayOf("tellev-1.7.0-mnn.apk")),
        ) { body -> officialChecker.parseReleaseJson(body) }

        assertRejected(
            release(tag = "v1.7.0-mnn", assets = arrayOf("tellev-1.7.0-mnn.apk")),
        ) { body -> officialChecker.parseReleaseJson(body) }
    }

    @Test
    fun `mnn rejects an official tag and asset`() {
        assertRejected(
            release(tag = "v1.7.0", assets = arrayOf("tellev-1.7.0.apk")),
        ) { body -> mnnChecker.parseReleaseJson(body) }

        assertRejected(
            release(tag = "v1.7.0-mnn", assets = arrayOf("tellev-1.7.0.apk")),
        ) { body -> mnnChecker.parseReleaseJson(body) }
    }

    @Test
    fun `wrong apk suffixes are rejected`() {
        assertRejected(
            release(tag = "v1.4.1", assets = arrayOf("tellev-1.4.1.zip")),
        ) { body -> officialChecker.parseReleaseJson(body) }
        assertRejected(
            release(tag = "v1.4.1", assets = arrayOf("tellev-1.4.1.apk.txt")),
        ) { body -> officialChecker.parseReleaseJson(body) }
        assertRejected(
            release(tag = "v1.4.1", assets = arrayOf("tellev-1.4.1")),
        ) { body -> officialChecker.parseReleaseJson(body) }
        assertRejected(
            release(tag = "v1.4.1-mnn", assets = arrayOf("tellev-1.4.1-mnn.zip")),
        ) { body -> mnnChecker.parseReleaseJson(body) }
    }

    @Test(expected = IllegalStateException::class)
    fun `a release without any asset throws`() {
        officialChecker.parseReleaseJson(release(tag = "v1.5.0", assets = emptyArray()))
    }

    // ── list path ────────────────────────────────────────────────────

    @Test
    fun `release carrying both channels picks the asset matching its tag`() {
        val info = officialChecker.parseReleaseJson(
            release(
                tag = "v1.6.3",
                assets = arrayOf(
                    "tellev-1.6.3-mnn.apk",
                    "app-release.apk",
                    "tellev-1.6.3.apk",
                ),
            ),
        )
        assertTrue(info.apkUrl.endsWith("tellev-1.6.3.apk"))
        assertEquals("1.6.3", info.version)
    }

    @Test
    fun `official list skips -mnn releases even when they are newer`() {
        val json = releases(
            release(tag = "v1.7.0-mnn", assets = arrayOf("tellev-1.7.0-mnn.apk")),
            release(tag = "v1.6.3", assets = arrayOf("tellev-1.6.3.apk")),
        )

        val info = checker.parseLatestChannelRelease(json)
        assertEquals("v1.6.3", info.tagName)
        assertTrue(info.apkUrl.endsWith("tellev-1.6.3.apk"))
        assertTrue(checker.isUpdateAvailable("1.6.2", info))
        assertFalse(checker.isUpdateAvailable("1.6.3", info))
    }

    @Test(expected = IllegalStateException::class)
    fun `official list without an official release throws`() {
        checker.parseLatestChannelRelease(
            """[{"tag_name": "v1.7.0-mnn", "assets": [{"name": "tellev-1.7.0-mnn.apk"}]}]""",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `official release carrying only an mnn apk is skipped`() {
        checker.parseLatestChannelRelease(
            """[{"tag_name": "v1.7.0", "assets": [{"name": "tellev-1.7.0-mnn.apk"}]}]""",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `official release whose apk version differs from its tag is skipped`() {
        checker.parseLatestChannelRelease(
            """[{"tag_name": "v1.7.0", "assets": [{"name": "tellev-1.6.3.apk"}]}]""",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * A GitHub `releases` list body. The newest entry comes first, which is the
     * order the API itself uses.
     */
    private fun releases(vararg bodies: String): String =
        bodies.joinToString(prefix = "[", postfix = "]") { it }

    private fun release(tag: String, assets: Array<String>): String {
        val assetsJson = assets.joinToString(", ") { asset(it) }
        return """{"tag_name":"$tag","name":"$tag","body":"","assets":[$assetsJson]}"""
    }

    private fun asset(name: String): String =
        """{"name":"$name","browser_download_url":"https://github.com/braze-taffo/tellev/releases/download/$name","size":4400000}"""

    private fun assertRejected(body: String, parse: (String) -> UpdateInfo) {
        try {
            val parsed = parse(body)
            fail("expected a rejected release, got $parsed")
        } catch (expected: IllegalStateException) {
            // Channel / tag / asset validation rejected it, as intended.
        }
    }

    private fun assertListRejected(body: String) {
        try {
            val parsed = checker.parseLatestChannelRelease(body)
            fail("expected a rejected release list, got $parsed")
        } catch (expected: IllegalStateException) {
            // No release on this build's channel, as intended.
        }
    }

    private fun info(version: String) = UpdateInfo(
        tagName = version,
        version = version,
        title = version,
        releaseNotes = "",
        htmlUrl = "",
        apkUrl = "",
        apkSize = 0L,
        publishedAt = "",
        sha256 = null,
    )
}
