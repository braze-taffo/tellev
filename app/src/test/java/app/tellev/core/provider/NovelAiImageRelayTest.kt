package app.tellev.core.provider

import org.junit.Assert.*
import org.junit.Test

class NovelAiImageRelayTest {
    @Test fun `root prefix and complete generation endpoint produce consistent sibling paths`() {
        for (base in listOf("https://relay.example", "https://relay.example/", "https://relay.example/ai/generate-image")) {
            val settings = NovelAiImageSettings(useRelay = true, relayBaseUrl = base)
            assertEquals("https://relay.example/ai/generate-image", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.generatePath(settings), true))
            assertEquals("https://relay.example/user/subscription", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.statusPath(settings)))
        }
        for (base in listOf("https://relay.example/novel/v1/", "https://relay.example/novel/v1/ai/generate-image")) {
            val settings = NovelAiImageSettings(useRelay = true, relayBaseUrl = base)
            assertEquals("https://relay.example/novel/v1/ai/generate-image", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.generatePath(settings), true))
            assertEquals("https://relay.example/novel/v1/ai/upscale", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.upscalePath(settings)))
        }
    }

    @Test fun `custom generation endpoint can also be supplied in full`() {
        val settings = NovelAiImageSettings(useRelay = true, relayBaseUrl = "https://relay.example/proxy/generate", relayGeneratePath = "/generate")
        assertEquals("https://relay.example/proxy/generate", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.generatePath(settings), true))
        assertEquals("https://relay.example/proxy/user/subscription", NovelAiImageRelay.endpoint(settings, NovelAiImageRelay.statusPath(settings)))
    }

    @Test fun `absolute or credential carrying custom paths are rejected`() {
        for (path in listOf("https://official.example/ai/generate-image", "//official.example/path", "generate", "/generate?key=secret", "/generate#fragment")) {
            val settings = NovelAiImageSettings(useRelay = true, relayBaseUrl = "https://relay.example", relayGeneratePath = path)
            assertThrows(IllegalArgumentException::class.java) { NovelAiImageRelay.validate(settings) }
        }
        NovelAiImageRelay.validate(NovelAiImageSettings(relayBaseUrl = "ignored while official"))
    }
}
