package app.imagetosvg.image

import kotlin.math.max
import kotlin.math.roundToInt

/** Plain ARGB pixel helpers (not premultiplied), kept free of Android so they unit-test on the JVM. */
object Pixels {

    /** Size that fits inside [maxSide]×[maxSide] keeping the aspect ratio; never upscales. */
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Box-filter downscale. Colours are weighted by alpha so transparent pixels don't darken edges. */
    fun scale(pixels: IntArray, width: Int, height: Int, toWidth: Int, toHeight: Int): IntArray {
        if (toWidth == width && toHeight == height) return pixels.copyOf()
        val out = IntArray(toWidth * toHeight)
        for (ty in 0 until toHeight) {
            val y0 = (ty.toLong() * height / toHeight).toInt()
            val y1 = max(y0 + 1, ((ty + 1L) * height / toHeight).toInt())
            for (tx in 0 until toWidth) {
                val x0 = (tx.toLong() * width / toWidth).toInt()
                val x1 = max(x0 + 1, ((tx + 1L) * width / toWidth).toInt())
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (y in y0 until y1) {
                    val row = y * width
                    for (x in x0 until x1) {
                        val c = pixels[row + x]
                        val ca = c ushr 24
                        a += ca
                        r += ((c ushr 16) and 0xFF) * ca
                        g += ((c ushr 8) and 0xFF) * ca
                        b += (c and 0xFF) * ca
                    }
                }
                val n = (y1 - y0) * (x1 - x0)
                out[ty * toWidth + tx] = if (a == 0L) 0 else {
                    val oa = ((a + n / 2) / n).toInt()
                    if (oa == 0) 0 else (oa shl 24) or
                        (((r + a / 2) / a).toInt() shl 16) or
                        (((g + a / 2) / a).toInt() shl 8) or
                        ((b + a / 2) / a).toInt()
                }
            }
        }
        return out
    }

    /** Opaque RGB (0xRRGGBB) of the pixels as seen on a white background. */
    fun onWhite(pixels: IntArray): IntArray = IntArray(pixels.size) { i ->
        val c = pixels[i]
        val a = c ushr 24
        fun ch(shift: Int) = (((c ushr shift) and 0xFF) * a + 255 * (255 - a) + 127) / 255
        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
