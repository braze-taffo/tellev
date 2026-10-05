package app.tellev.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** Helpers for working with content:// URIs (image picking, file import). */
object UriUtils {

    /** Resolve a human-readable display name for [uri], or null if it can't be determined. */
    fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    /** Resolve the MIME type for [uri], or null. */
    fun resolveMimeType(context: Context, uri: Uri): String? =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()

    /**
     * Read [uri] fully into memory with a hard [maxBytes] cap. Mirrors the
     * external VIEW/SEND import path: a picked document can still be huge,
     * and reading it unbounded turns "import" into an OOM kill switch.
     */
    fun readBounded(context: Context, uri: Uri, maxBytes: Long): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("cannot open $uri")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                check(total <= maxBytes) { "file exceeds ${maxBytes / (1024 * 1024)}MB limit" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /**
     * Read the image at [uri], downsample so the longest edge is <= [maxDim] px, and
     * re-encode as JPEG at [quality]. Returns null if the image cannot be decoded.
     *
     * Vision attachments are sent as base64 in the request body, so keeping the byte size
     * bounded (a 1280px JPEG@85 is ~150-250KB) matters for both token cost and JSONL size.
     */
    fun readAndDownsample(
        resolver: ContentResolver,
        uri: Uri,
        maxDim: Int = 1280,
        quality: Int = 85,
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth.takeIf { it > 0 } ?: return null
        val h = bounds.outHeight.takeIf { it > 0 } ?: return null

        // Power-of-two downsample to get close to maxDim, then a precise scale for the remainder.
        var sample = 1
        while (maxOf(w, h) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val scaled = if (maxOf(decoded.width, decoded.height) > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        } finally {
            decoded.recycle()
            if (decoded !== scaled) scaled.recycle()
        }
    }
}
