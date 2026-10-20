package app.imagetosvg.shapes

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Random
import kotlin.math.sqrt

/**
 * The brute-force approach: keep throwing random semi-transparent shapes at the picture,
 * nudge the most promising one around (hill climbing) while that brings the drawing closer
 * to the image, then keep it and repeat. Each step picks the colour that fits best.
 *
 * It gets recognisable within a few dozen shapes and very close after a few hundred, but it
 * almost never becomes exact: every shape changes a whole patch of pixels at once, so
 * fixing the last few levels of one pixel without disturbing its neighbours takes about one
 * shape per pixel, found by luck.
 *
 * [target] is opaque RGB (0xRRGGBB) of a [width]×[height] working copy.
 */
class ShapeSearch(
    private val target: IntArray,
    val width: Int,
    val height: Int,
    seed: Long = System.nanoTime(),
    private val workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
) {
    class Placed(val shape: Shape, val color: Int)

    private val current = IntArray(width * height)
    private val pixels = width * height
    private val placed = ArrayList<Placed>()
    private val seeds = Random(seed)

    /** Typical mutation distance: 16px at the default 256px working size. */
    private val step = maxOf(width, height) / 16f

    val background: Int
    val shapes: List<Placed> get() = placed

    /** Sum of squared channel differences between the drawing and the target. */
    var error = 0L
        private set

    /** Working pixels whose colour is exactly right. */
    var exactPixels = 0
        private set

    /** 100% = identical. Based on the root-mean-square difference per channel. */
    val similarity: Double get() = 1 - sqrt(error.toDouble() / (pixels * 3.0)) / 255

    val isExact: Boolean get() = exactPixels == pixels

    init {
        require(target.size == pixels && pixels > 0)
        var r = 0L; var g = 0L; var b = 0L
        for (c in target) {
            r += (c ushr 16) and 0xFF
            g += (c ushr 8) and 0xFF
            b += c and 0xFF
        }
        background = (((r / pixels).toInt()) shl 16) or (((g / pixels).toInt()) shl 8) or (b / pixels).toInt()
        current.fill(background)
        for (i in 0 until pixels) {
            error += distance(target[i], background)
            if (target[i] == background) exactPixels++
        }
    }

    /**
     * Finds and adds the next shape. Workers search independently in parallel (each tries
     * [tries] random shapes, then climbs from the best until [patience] mutations in a row
     * fail to improve it); the best result wins. Returns null if nothing improved the picture.
     */
    suspend fun step(kind: ShapeKind, tries: Int = 64, patience: Int = 64): Placed? {
        if (isExact) return null
        val seedsForStep = LongArray(workers) { seeds.nextLong() }
        val results = coroutineScope {
            (0 until workers).map { i ->
                async { Climber(Random(seedsForStep[i])).climb(kind, tries, patience) }
            }.awaitAll()
        }
        val best = results.filterNotNull().minByOrNull { it.second } ?: return null
        if (best.second >= error) return null
        val shape = best.first
        val lines = Lines()
        shape.rasterize(width, height, lines)
        val color = bestColor(lines)
        draw(lines, color)
        return Placed(shape, color).also { placed += it }
    }

    /** The drawing as an SVG, scaled to [outWidth]×[outHeight] pixels. */
    fun svg(outWidth: Int, outHeight: Int, count: Int = placed.size): String {
        val sb = StringBuilder(128 + count * 64)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(outWidth)
            .append("\" height=\"").append(outHeight)
            .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
            .append("\" preserveAspectRatio=\"none\">\n")
        sb.append("<rect width=\"").append(width).append("\" height=\"").append(height)
            .append("\" fill=\"").append(hex(background)).append("\"/>\n")
        sb.append("<g fill-opacity=\"").append(OPACITY).append("\">\n")
        for (i in 0 until count) placed[i].shape.svg(sb, hex(placed[i].color))
        sb.append("</g>\n</svg>\n")
        return sb.toString()
    }

    /** Average colour that, painted at [ALPHA] over the current pixels, lands closest to the target. */
    private fun bestColor(lines: Lines): Int {
        var tr = 0L; var tg = 0L; var tb = 0L
        var cr = 0L; var cg = 0L; var cb = 0L
        var count = 0
        for (l in 0 until lines.size) {
            val base = lines.ys[l] * width
            for (i in base + lines.x1s[l]..base + lines.x2s[l]) {
                val t = target[i]
                val c = current[i]
                tr += (t ushr 16) and 0xFF; tg += (t ushr 8) and 0xFF; tb += t and 0xFF
                cr += (c ushr 16) and 0xFF; cg += (c ushr 8) and 0xFF; cb += c and 0xFF
                count++
            }
        }
        if (count == 0) return 0
        // paint·a + current·(1−a) = target  ⇒  paint = current + (target − current) / a
        fun channel(t: Long, c: Long) = ((c + (t - c) * 255 / ALPHA) / count).toInt().coerceIn(0, 255)
        return (channel(tr, cr) shl 16) or (channel(tg, cg) shl 8) or channel(tb, cb)
    }

    /** Change in [error] if [lines] were painted with [color]. */
    private fun delta(lines: Lines, color: Int): Long {
        val pr = (color ushr 16) and 0xFF
        val pg = (color ushr 8) and 0xFF
        val pb = color and 0xFF
        var d = 0L
        for (l in 0 until lines.size) {
            val base = lines.ys[l] * width
            for (i in base + lines.x1s[l]..base + lines.x2s[l]) {
                val t = target[i]
                val c = current[i]
                val tr = (t ushr 16) and 0xFF; val tg = (t ushr 8) and 0xFF; val tb = t and 0xFF
                val cr = (c ushr 16) and 0xFF; val cg = (c ushr 8) and 0xFF; val cb = c and 0xFF
                val nr = tr - blend(cr, pr); val ng = tg - blend(cg, pg); val nb = tb - blend(cb, pb)
                val er = tr - cr; val eg = tg - cg; val eb = tb - cb
                d += (nr * nr + ng * ng + nb * nb - er * er - eg * eg - eb * eb).toLong()
            }
        }
        return d
    }

    private fun draw(lines: Lines, color: Int) {
        val pr = (color ushr 16) and 0xFF
        val pg = (color ushr 8) and 0xFF
        val pb = color and 0xFF
        for (l in 0 until lines.size) {
            val base = lines.ys[l] * width
            for (i in base + lines.x1s[l]..base + lines.x2s[l]) {
                val t = target[i]
                val c = current[i]
                val n = (blend((c ushr 16) and 0xFF, pr) shl 16) or
                    (blend((c ushr 8) and 0xFF, pg) shl 8) or
                    blend(c and 0xFF, pb)
                error += distance(t, n) - distance(t, c)
                if (t == c) exactPixels--
                if (t == n) exactPixels++
                current[i] = n
            }
        }
    }

    private inner class Climber(private val rnd: Random) {
        private val lines = Lines()

        private fun energy(shape: Shape): Long {
            shape.rasterize(width, height, lines)
            if (lines.size == 0) return Long.MAX_VALUE
            return error + delta(lines, bestColor(lines))
        }

        fun climb(kind: ShapeKind, tries: Int, patience: Int): Pair<Shape, Long>? {
            var best: Shape? = null
            var bestEnergy = Long.MAX_VALUE
            repeat(tries) {
                val s = Shape.random(kind, rnd, width, height, step)
                val e = energy(s)
                if (e < bestEnergy) {
                    best = s
                    bestEnergy = e
                }
            }
            var shape = best ?: return null
            var age = 0
            while (age < patience) {
                val s = shape.mutate(rnd, width, height, step)
                val e = energy(s)
                if (e < bestEnergy) {
                    shape = s
                    bestEnergy = e
                    age = 0
                } else {
                    age++
                }
            }
            return shape to bestEnergy
        }
    }

    companion object {
        /** Shapes are painted at this alpha (≈ 50%). */
        const val ALPHA = 128
        const val OPACITY = "0.502"

        /** Default working size: the longest side of the copy the search runs on. */
        const val WORKING_SIZE = 256

        fun blend(c: Int, paint: Int) = (paint * ALPHA + c * (255 - ALPHA) + 127) / 255

        private fun distance(a: Int, b: Int): Long {
            val dr = ((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)
            val dg = ((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)
            val db = (a and 0xFF) - (b and 0xFF)
            return (dr * dr + dg * dg + db * db).toLong()
        }

        fun hex(rgb: Int) = "#" + (0x1000000 or (rgb and 0xFFFFFF)).toString(16).substring(1)
    }
}
