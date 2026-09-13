package app.tellev.core.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val checker = UpdateChecker(OkHttpClient())

    /** Single-release bodies carry no channel marker of their own, so those
     *  fixtures are parsed against an explicitly chosen channel. */
    private val officialChecker = UpdateChecker(OkHttpClient(), channel = UpdateChannel.Official)

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
    fun `parse handles missing digest gracefully`() {
        val json = """
            {
              "tag_name": "1.5.0",
              "name": "1.5.0",
              "body": "",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/braze-taffo/tellev/releases/download/1.5.0/app-release.apk",
                  "size": 4500000,
                  "content_type": "application/vnd.android.package-archive"
                }
              ]
            }
        """.trimIndent()

        val info = officialChecker.parseReleaseJson(json)
        assertEquals("1.5.0", info.version)
        assertTrue(info.apkUrl.endsWith("app-release.apk"))
        assertNull(info.sha256)
    }

    @Test
    fun `mnn list picks the newest -mnn release and skips official ones`() {
        val json = """
            [
              {
                "tag_name": "v1.6.0-mnn",
                "name": "v1.6.0-mnn - 生图版",
                "assets": [
                  {"name": "tellev-1.6.0-mnn.apk", "browser_download_url": "https://x/tellev-1.6.0-mnn.apk", "size": 1}
                ]
              },
              {
                "tag_name": "v1.6.0",
                "name": "v1.6.0 - 正式版",
                "assets": [
                  {"name": "tellev-1.6.0.apk", "browser_download_url": "https://x/tellev-1.6.0.apk", "size": 1}
                ]
              },
              {
                "tag_name": "v1.5.5.1",
                "name": "v1.5.5.1",
                "assets": [
                  {"name": "tellev-1.5.5.1.apk", "browser_download_url": "https://x/tellev-1.5.5.1.apk", "size": 1}
                ]
              }
            ]
        """.trimIndent()

        val info = checker.parseLatestChannelRelease(json)
        assertEquals("v1.6.0-mnn", info.tagName)
        // 版本比较只取前导数字段：1.6.0-mnn 与 1.6.0 同版本号。
        assertEquals("1.6.0-mnn", info.version)
        assertTrue(info.apkUrl.endsWith("tellev-1.6.0-mnn.apk"))
        assertEquals(0, checker.compareVersions("1.6.0", info.version))
        assertTrue(checker.isUpdateAvailable("1.5.5.1", info))
    }

    @Test
    fun `plain version tags match only the official channel`() {
        assertTrue(UpdateChannel.Official.matchesTag("v1.6.3"))
        assertTrue(UpdateChannel.Official.matchesTag("1.5.5.1"))
        assertFalse(UpdateChannel.Official.matchesTag("v1.6.3-mnn"))
        assertTrue(UpdateChannel.Mnn.matchesTag("v1.6.3-mnn"))
        assertFalse(UpdateChannel.Mnn.matchesTag("v1.6.3"))
        assertFalse(UpdateChannel.Mnn.matchesTag("nightly"))
    }

    @Test
    fun `apk asset names are channel-scoped`() {
        assertTrue(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3.apk"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("tellev-1.6.3-mnn.apk"))
        assertFalse(UpdateChannel.Official.matchesApkAsset("checksums.txt"))
        assertTrue(UpdateChannel.Mnn.matchesApkAsset("tellev-1.6.3-mnn.apk"))
        assertFalse(UpdateChannel.Mnn.matchesApkAsset("tellev-1.6.3.apk"))
        assertFalse(UpdateChannel.Mnn.matchesApkAsset("checksums.txt"))
    }

    @Test(expected = IllegalStateException::class)
    fun `mnn list without any -mnn tag throws`() {
        checker.parseLatestChannelRelease("""[{"tag_name": "v1.6.0", "assets": []}]""")
    }

    @Test(expected = IllegalStateException::class)
    fun `mnn release carrying only an official apk is skipped`() {
        checker.parseLatestChannelRelease(
            """[{"tag_name": "v1.7.0-mnn", "assets": [{"name": "tellev-1.7.0.apk"}]}]""",
        )
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
