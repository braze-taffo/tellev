package app.tellev.core.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TELLER-3-5 / TELLER-2-8：自更新下载的完整性判定不能再静默降级。
 *
 * 覆盖回归点：
 *  - 旧实现在 `if (info.sha256 != null)` 守卫里做哈希，发行没带 digest 时整段内容
 *    校验被无声跳过（TELLER-3-5）；
 *  - 旧截断检查在「响应没有 Content-Length、发行元数据也没有 size」时失去全部长度
 *    锚点，只剩 `read == 0L` 兜底，截断的 APK 会被当成完整文件交给安装器（TELLER-2-8）。
 *
 * 长度判定保持 OpenIssueRegressionTest 已固化的语义：两个锚点都在时必须都相符，
 * 否则 `emptyAndTruncatedDownloadsRetryAndDiscardPartialBytes` 的 3 字节断流会
 * 被当成完整下载。
 */
class T12NetUpdateIntegrityTest {

    // ── 长度锚点：截断检查 ───────────────────────────────────────────

    @Test
    fun `byte count agreeing with both anchors means a whole transfer`() {
        assertTrue(UpdateChecker.isWholeDownload(received = 9L, contentLength = 9L, apkSize = 9L))
    }

    @Test
    fun `body shorter than the release reported size is truncation`() {
        // 响应给了 Content-Length（字节数对得上），但发行元数据里的 size 还是完整值：
        // 镜像在 30% 处断流，Content-Length 也会跟着变小，此时只有 size 能识破。
        assertFalse(UpdateChecker.isWholeDownload(received = 3L, contentLength = 3L, apkSize = 9L))
    }

    @Test
    fun `body shorter than the reported content length is truncation`() {
        assertFalse(UpdateChecker.isWholeDownload(received = 3L, contentLength = 9L, apkSize = 0L))
    }

    @Test
    fun `empty body is never a whole download`() {
        assertFalse(UpdateChecker.isWholeDownload(received = 0L, contentLength = 0L, apkSize = 0L))
        assertFalse(UpdateChecker.isWholeDownload(received = 0L, contentLength = -1L, apkSize = 0L))
    }

    @Test
    fun `either anchor alone is enough to compare against`() {
        assertTrue(UpdateChecker.isWholeDownload(received = 9L, contentLength = 9L, apkSize = 0L))
        assertTrue(UpdateChecker.isWholeDownload(received = 9L, contentLength = -1L, apkSize = 9L))
        assertFalse(UpdateChecker.isWholeDownload(received = 8L, contentLength = -1L, apkSize = 9L))
    }

    @Test
    fun `no anchor at all leaves only the digest to decide`() {
        assertFalse(UpdateChecker.hasLengthAnchor(contentLength = -1L, apkSize = 0L))
        assertTrue(UpdateChecker.hasLengthAnchor(contentLength = 0L, apkSize = 0L))
        assertTrue(UpdateChecker.hasLengthAnchor(contentLength = -1L, apkSize = 9L))
    }

    // ── digest：缺值时不静默跳过 ─────────────────────────────────────

    @Test
    fun `release without a digest keeps a null sha256 for the caller to surface`() {
        // parseReleaseObject 的既有契约：没有 digest 的发行仍可解析，sha256 为 null。
        // downloadApk 不再在这一点上无声跳过，而是把「未校验哈希」显式交给
        // onHashUnverified（默认出口见 logUnverifiedHash）。
        val info = UpdateChecker(OkHttpClient()).parseReleaseJson(releaseWithoutDigest)
        assertEquals(null, info.sha256)
        assertEquals("v1.6.3", info.tagName)
        assertEquals(60_000_000L, info.apkSize)
    }

    private val releaseWithoutDigest = """
        {
          "tag_name": "v1.6.3",
          "name": "v1.6.3",
          "body": "",
          "html_url": "https://github.com/braze-taffo/tellev/releases/tag/v1.6.3",
          "published_at": "2026-07-28T00:00:00Z",
          "assets": [
            {
              "name": "tellev-1.6.3.apk",
              "size": 60000000,
              "browser_download_url": "https://github.com/braze-taffo/tellev/releases/download/v1.6.3/tellev-1.6.3.apk"
            }
          ]
        }
    """.trimIndent()
}
