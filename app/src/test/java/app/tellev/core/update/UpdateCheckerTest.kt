package app.tellev.core.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val checker = UpdateChecker(OkHttpClient())

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

        val info = checker.parseReleaseJson(json)
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

        val info = checker.parseReleaseJson(json)
        assertEquals("1.5.0", info.version)
        assertTrue(info.apkUrl.endsWith("app-release.apk"))
        assertNull(info.sha256)
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
