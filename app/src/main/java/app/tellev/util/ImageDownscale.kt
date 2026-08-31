package app.tellev.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Decodes arbitrary image bytes (JPEG/PNG/WebP/...) and re-encodes them as a
 * PNG whose longest edge is at most [maxEdge] pixels. Used when copying
 * user-picked images into app storage so a single 50MP photo can't blow the
 * heap or bloat the character card / chat background on disk.
 *
 * Returns null when the bytes are not a decodable image.
 */
fun decodeImageAsPng(imageBytes: ByteArray, maxEdge: Int = 1024): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxEdge || bounds.outHeight / sampleSize > maxEdge) {
        sampleSize *= 2
    }

    val bitmap = BitmapFactory.decodeByteArray(
        imageBytes,
        0,
        imageBytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null

    val output = ByteArrayOutputStream()
    try {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
    } finally {
        bitmap.recycle()
    }
    return output.toByteArray()
}
