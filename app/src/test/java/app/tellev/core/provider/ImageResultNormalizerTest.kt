package app.tellev.core.provider

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageResultNormalizerTest {

    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(), 1, 2, 3,
    )
    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9)
    private val htmlBytes = "<html><body>error</body></html>".toByteArray()

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    // ── classification ────────────────────────────────────────────────────

    @Test
    fun bareBase64PayloadIsDecodedAndSniffed() {
        val classified = ImageResultNormalizer.classify(b64(pngBytes))
        assertTrue(classified is ImageResultNormalizer.Classified.Inline)
        assertEquals("image/png", (classified as ImageResultNormalizer.Classified.Inline).mimeType)
        assertTrue(pngBytes.contentEquals(classified.bytes))
    }

    @Test
    fun dataUriWithImageMimeIsDecoded() {
        val text = "data:image/png;base64,${b64(pngBytes)}"
        val classified = ImageResultNormalizer.classify(text)
        assertTrue(classified is ImageResultNormalizer.Classified.Inline)
        assertEquals("image/png", (classified as ImageResultNormalizer.Classified.Inline).mimeType)
    }

    @Test
    fun dataUriWithNonImageMimeIsRejected() {
        assertNull(ImageResultNormalizer.classify("data:text/html;base64,${b64(pngBytes)}"))
    }

    @Test
    fun httpsUrlIsRemoteAndOtherSchemesAreRejected() {
        assertTrue(ImageResultNormalizer.classify("https://cdn.example.com/img.png")
            is ImageResultNormalizer.Classified.Remote)
        assertNull(ImageResultNormalizer.classify("ftp://cdn.example.com/img.png"))
        assertNull(ImageResultNormalizer.classify("file:///etc/passwd"))
        // URLs never contain whitespace: a wrapped/garbled one is unusable.
        assertNull(ImageResultNormalizer.classify("https://cdn.example.com/a b.png"))
    }

    @Test
    fun whitespaceAndUrlSafeAlphabetsAreToleratedInBase64() {
        val wrapped = b64(pngBytes).chunked(4).joinToString("\n")
        assertTrue(ImageResultNormalizer.classify(wrapped) is ImageResultNormalizer.Classified.Inline)
        // URL-safe alphabet produced by some relays.
        val urlSafe = b64(pngBytes).replace('+', '-').replace('/', '_')
        assertTrue(ImageResultNormalizer.classify(urlSafe) is ImageResultNormalizer.Classified.Inline)
    }

    @Test
    fun garbagePayloadsAreRejected() {
        assertNull(ImageResultNormalizer.classify(""))
        assertNull(ImageResultNormalizer.classify("not base64 !!!"))
        assertNull(ImageResultNormalizer.classify(b64(htmlBytes))) // valid b64, wrong magic
        assertNull(ImageResultNormalizer.classify("data:image/png;base64,"))
    }

    @Test
    fun oversizedPayloadsAreRejected() {
        val big = ByteArray(ImageResultNormalizer.MAX_BYTES + 1) { 0x89.toByte() }
        assertNull(ImageResultNormalizer.classify(b64(big)))
    }

    // ── toBytes (URL download path) ──────────────────────────────────────

    @Test
    fun remoteUrlDownloadsAndSniffs() = runBlocking {
        val result = ImageResultNormalizer.toBytes(
            "https://cdn.example.com/img.png",
            downloadUrl = { url ->
                assertEquals("https://cdn.example.com/img.png", url)
                jpegBytes
            },
        )
        assertEquals("image/jpeg", result!!.mimeType)
        assertTrue(jpegBytes.contentEquals(result.bytes))
    }

    @Test
    fun remoteUrlWithoutDownloaderIsNull() = runBlocking {
        assertNull(ImageResultNormalizer.toBytes("https://cdn.example.com/img.png"))
    }

    @Test
    fun downloadedNonImageIsRejected() = runBlocking {
        assertNull(ImageResultNormalizer.toBytes(
            "https://cdn.example.com/img.png",
            downloadUrl = { htmlBytes },
        ))
        assertNull(ImageResultNormalizer.toBytes(
            "https://cdn.example.com/img.png",
            downloadUrl = { throw java.io.IOException("boom") },
        ))
    }

    @Test
    fun inlinePathNeverTouchesTheDownloader() = runBlocking {
        var called = false
        val result = ImageResultNormalizer.toBytes(
            b64(pngBytes),
            downloadUrl = { called = true; ByteArray(0) },
        )
        assertEquals("image/png", result!!.mimeType)
        assertTrue(!called)
    }
}
