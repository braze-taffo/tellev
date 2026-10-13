package app.tellev.feature.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URL

/**
 * TELLER-2-3：社区卡片下载的协议/主机策略必须逐跳复核。
 *
 * 覆盖回归点：旧实现 `URL(url).openConnection()` + `instanceFollowRedirects = true`，
 * 入口与每一跳都不校验协议与主机，一次 https → http 的重定向就能把明文请求送出
 * CleartextGuard 与用户明文开关的覆盖范围。本组用例守住：只认 https、主机名必须
 * 存在、相对 Location 按基线解析、用户信息段不能伪装主机、3xx 分类不含 304。
 */
class T12NetCommunityDownloadTest {

    @Test
    fun `https download url is accepted`() {
        val parsed = parseDownloadUrl("https://cdn.discordapp.com/attachments/1/2/card.png")
        assertEquals("cdn.discordapp.com", parsed.host)
        assertEquals("https", parsed.protocol)
    }

    @Test
    fun `http download url is rejected before connecting`() {
        assertRejected("http://cdn.discordapp.com/attachments/1/2/card.png")
    }

    @Test
    fun `uppercase https scheme is still https`() {
        assertAccepted("HTTPS://CDN.DISCORDAPP.COM/card.png")
    }

    @Test
    fun `non http schemes are rejected`() {
        assertRejected("ftp://cdn.discordapp.com/card.png")
        assertRejected("file:///sdcard/card.png")
        assertRejected("content://downloads/card.png")
    }

    @Test
    fun `url without a host is rejected`() {
        assertRejected("https:///card.png")
    }

    @Test
    fun `redirect location resolves against the current hop`() {
        val base = parseDownloadUrl("https://cdn.discordapp.com/attachments/1/2/card.png")
        assertEquals(
            "https://cdn.discordapp.com/x/card.png",
            parseDownloadUrl("/x/card.png", base = base).toString(),
        )
        assertEquals(
            "https://cdn.discordapp.com/attachments/1/2/deeper/card.png",
            parseDownloadUrl("deeper/card.png", base = base).toString(),
        )
    }

    @Test
    fun `protocol relative location keeps https`() {
        val base = parseDownloadUrl("https://discord.gg/odysseia")
        assertEquals(
            "https://media.discordapp.net/attachments/1/2/card.png",
            parseDownloadUrl("//media.discordapp.net/attachments/1/2/card.png", base = base).toString(),
        )
    }

    @Test
    fun `redirect may not downgrade to cleartext`() {
        val base = parseDownloadUrl("https://cdn.discordapp.com/attachments/1/2/card.png")
        assertRejected("http://cdn.discordapp.com/attachments/1/2/card.png", base = base)
    }

    @Test
    fun `userinfo in the authority cannot disguise the real host`() {
        // 显示给用户的主机必须是 URL 真正会连上的那个，不能是 @ 前面伪装的那个。
        assertEquals("evil.example", parseDownloadUrl("https://cdn.discordapp.com@evil.example/x.png").host)
    }

    @Test
    fun `redirect codes exclude 304`() {
        assertTrue(isDownloadRedirect(301))
        assertTrue(isDownloadRedirect(302))
        assertTrue(isDownloadRedirect(303))
        assertTrue(isDownloadRedirect(307))
        assertTrue(isDownloadRedirect(308))
        assertFalse(isDownloadRedirect(304))
        assertFalse(isDownloadRedirect(200))
        assertFalse(isDownloadRedirect(404))
    }

    private fun assertAccepted(raw: String) {
        parseDownloadUrl(raw)
    }

    private fun assertRejected(raw: String, base: URL? = null) {
        try {
            parseDownloadUrl(raw, base = base)
            fail("应被拒绝：$raw")
        } catch (expected: IOException) {
            // 解析失败与协议/主机不合规都收敛为 IOException，调用方统一转成可读错误。
        }
    }
}
