package app.tellev.core.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.tellev.core.i18n.S
import app.tellev.core.i18n.UiStrings

/** Official NovelAI requests through a user-selected relay. */
internal object NovelAiImageRelay {
    fun generatePath(settings: NovelAiImageSettings): String = settings.relayGeneratePath.trim().ifBlank {
        "/ai/generate-image"
    }

    fun statusPath(settings: NovelAiImageSettings): String = settings.relayStatusPath.trim().ifBlank {
        "/user/subscription"
    }

    fun upscalePath(settings: NovelAiImageSettings): String = settings.relayUpscalePath.trim().ifBlank { "/ai/upscale" }

    fun validate(settings: NovelAiImageSettings) {
        if (!settings.useRelay) return
        endpoint(settings, generatePath(settings), generation = true)
        endpoint(settings, statusPath(settings))
        endpoint(settings, upscalePath(settings))
    }

    fun endpoint(settings: NovelAiImageSettings, path: String, generation: Boolean = false): String {
        val base = settings.relayBaseUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException(UiStrings.get(S.novai_relay_invalid_url))
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
            UiStrings.get(S.novai_relay_invalid_url)
        }
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('#') && !path.contains('?')) {
            UiStrings.get(S.novai_relay_invalid_path)
        }
        val generationPath = generatePath(settings).substringBefore('?').trimEnd('/')
        val fullGenerate = base.encodedPath.endsWith(generationPath)
        if (generation && fullGenerate) return base.toString()
        val root = if (fullGenerate) {
            base.newBuilder().encodedPath(base.encodedPath.removeSuffix(generationPath).ifBlank { "/" }).build()
        } else base
        val prefix = root.toString().trimEnd('/')
        return (prefix + path).toHttpUrlOrNull()?.toString()
            ?: throw IllegalArgumentException(UiStrings.get(S.novai_relay_invalid_path))
    }

    fun model(settings: NovelAiImageSettings): String =
        if (settings.useRelay) settings.relayModel.trim().ifBlank { settings.model } else settings.model

    /** Official ZIP response, or an already extracted PNG returned by a relay. */
    fun decode(bytes: ByteArray): ByteArray? {
        if (isPng(bytes)) return bytes
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return NovelAiImageProtocol.extractFirstPng(bytes)
        }
        return null
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 &&
        bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
}
