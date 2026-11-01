package dev.foldman.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Decodes cover art small enough for a cassette label, from bytes, content URIs or the web. */
object Artwork {
    private const val MAX_EDGE = 640
    internal const val MAX_BYTES = 8 * 1024 * 1024
    private val thumbs = android.util.LruCache<Uri, Bitmap>(96)

    /** Like [load], but remembers recent results for list thumbnails. */
    suspend fun thumbnail(context: Context, uri: Uri): Bitmap? =
        thumbs.get(uri) ?: load(context, uri)?.also { thumbs.put(uri, it) }

    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 200_000_000L) {
            null
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds) })
        }
    }.getOrNull()

    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // Only pictures from the web (https) or from content providers; never a file path.
            val bytes = when (uri.scheme) {
                "https" -> (URL(uri.toString()).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000
                    readTimeout = 5000
                    try {
                        if (responseCode != HttpURLConnection.HTTP_OK || contentLengthLong > MAX_BYTES) null
                        else inputStream.use { readLimited(it) }
                    } finally {
                        disconnect()
                    }
                }
                "content" -> context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                else -> null
            }
            bytes?.let(::decode)
        }.getOrNull()
    }

    /** Reads at most [MAX_BYTES]; anything longer is not a cover and is refused. */
    internal fun readLimited(input: InputStream, limit: Int = MAX_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun sampleSize(bounds: BitmapFactory.Options): Int {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }
}
