package app.imagetosvg.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.imagetosvg.trace.ExactSvg
import java.io.IOException
import kotlin.math.sqrt

/** An image decoded to plain ARGB pixels (not premultiplied, fully transparent pixels = 0). */
class LoadedImage(
    /** File name without its extension, for naming the SVG. */
    val name: String,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Set when the file was bigger than [ImageLoader.MAX_PIXELS] and had to be shrunk to open. */
    val originalSize: Pair<Int, Int>?,
)

object ImageLoader {

    /** Beyond this the pixels alone would crowd out everything else in memory. */
    const val MAX_PIXELS = 36_000_000

    fun load(context: Context, uri: Uri): LoadedImage {
        val name = displayName(context, uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "image"
        var original: Pair<Int, Int>? = null
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decode(context, uri, unpremultiplied = true) { original = it }
                ?: decode(context, uri, unpremultiplied = false) { original = it }
                ?: throw IOException("Couldn't decode that image")
        } else {
            decodeLegacy(context, uri) { original = it }
        }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        ExactSvg.normalize(pixels)
        val shrunk = original?.takeIf { it.first != w || it.second != h }
        return LoadedImage(fileSafe(name), w, h, pixels, shrunk)
    }

    /** Keeps the exact stored values: unpremultiplied, sRGB, software memory, EXIF rotation applied. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decode(
        context: Context,
        uri: Uri,
        unpremultiplied: Boolean,
        onSize: (Pair<Int, Int>) -> Unit,
    ): Bitmap? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            onSize(w to h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isUnpremultipliedRequired = unpremultiplied
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            if (w.toLong() * h > MAX_PIXELS) {
                val s = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
        }
    } catch (e: IOException) {
        throw e
    } catch (_: RuntimeException) {
        // Some decoders can't hand out unpremultiplied pixels; the caller retries without.
        null
    }

    private fun decodeLegacy(context: Context, uri: Uri, onSize: (Pair<Int, Int>) -> Unit): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        onSize(bounds.outWidth to bounds.outHeight)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Couldn't decode that image")
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun fileSafe(name: String): String = name.trim()
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.', '-')
        .take(60)
        .ifEmpty { "image" }

    /** A premultiplied bitmap for showing [pixels] on screen, at most [maxSide] px on its longest side. */
    fun displayBitmap(pixels: IntArray, width: Int, height: Int, maxSide: Int): Bitmap {
        val (w, h) = Pixels.fit(width, height, maxSide)
        val scaled = if (w == width && h == height) pixels else Pixels.scale(pixels, width, height, w, h)
        return Bitmap.createBitmap(scaled, w, h, Bitmap.Config.ARGB_8888)
    }
}
