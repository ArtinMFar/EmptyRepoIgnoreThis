package app.imagetosvg.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import kotlin.random.Random

class ExactSvgTest {

    private fun svgOf(pixels: IntArray, w: Int, h: Int): String =
        StringWriter().also { ExactSvg.write(pixels.copyOf(), w, h, it) }.toString()

    private fun normalized(pixels: IntArray) = pixels.copyOf().also(ExactSvg::normalize)

    private fun assertExact(pixels: IntArray, w: Int, h: Int, label: String = "") {
        val svg = svgOf(pixels, w, h)
        val expected = normalized(pixels)
        val result = SvgCheck.verify(StringReader(svg), expected, w, h)
        assertEquals("SvgCheck mismatches $label\n$svg", 0, result.mismatched)
        // A second, deliberately naive renderer: winding number at every pixel centre.
        assertTrue("naive render differs $label\n$svg", NaiveRender.render(svg, w, h).contentEquals(expected))
    }

    @Test
    fun twoPixels() {
        val svg = svgOf(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 2, 1)
        assertEquals(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2\" height=\"1\" viewBox=\"0 0 2 1\" shape-rendering=\"crispEdges\">\n" +
                "<path fill=\"#f00\" d=\"M0 0h1v1h-1z\"/>\n" +
                "<path fill=\"#00f\" d=\"M1 0h1v1h-1z\"/>\n" +
                "</svg>\n",
            svg,
        )
    }

    @Test
    fun solidImageIsOneRectangle() {
        val svg = svgOf(IntArray(640 * 480) { 0xFF123456.toInt() }, 640, 480)
        assertTrue(svg, svg.contains("<path fill=\"#123456\" d=\"M0 0h640v480h-640z\"/>"))
    }

    @Test
    fun holeIsCutOutOfItsRegion() {
        val o = 0xFF000000.toInt()
        val x = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(
            o, o, o, o,
            o, x, x, o,
            o, x, o, o,
            o, o, o, o,
        )
        val svg = svgOf(pixels, 4, 4)
        // Black: the outer outline (clockwise) plus the L-shaped hole (counter-clockwise).
        assertTrue(svg, svg.contains("<path fill=\"#000\" d=\"M0 0h4v4h-4zM3 1h-2v2h1v-1h1z\"/>"))
        assertExact(pixels, 4, 4)
    }

    @Test
    fun checkerboardCornersTouchingDiagonally() {
        val a = 0xFF202020.toInt()
        val b = 0xFFE0E0E0.toInt()
        val w = 7
        val h = 5
        assertExact(IntArray(w * h) { if ((it % w + it / w) % 2 == 0) a else b }, w, h)
    }

    @Test
    fun transparencyIsKept() {
        val pixels = intArrayOf(
            0x00000000, 0x00FFFFFF, 0x80FF0000.toInt(),
            0x01000000, 0xFF00FF00.toInt(), 0x7F00FF00,
        )
        val svg = svgOf(pixels, 3, 2)
        assertFalse("fully transparent pixels are left out", svg.contains("#fff"))
        assertTrue(svg, svg.contains("fill=\"#f00\" fill-opacity=\"0.502\""))
        assertExact(pixels, 3, 2)
    }

    @Test
    fun fullyTransparentImageHasNoPaths() {
        val svg = svgOf(IntArray(9), 3, 3)
        assertFalse(svg.contains("<path"))
        assertExact(IntArray(9), 3, 3)
    }

    @Test
    fun everyAlphaSurvivesTheRoundTrip() {
        for (alpha in 1..254) {
            val back = Math.round(ExactSvg.opacity(alpha).toFloat() * 255)
            assertEquals("alpha $alpha → ${ExactSvg.opacity(alpha)}", alpha, back)
        }
    }

    @Test
    fun hexColors() {
        assertEquals("#abc", ExactSvg.hexColor(0xFFAABBCC.toInt()))
        assertEquals("#abcdef", ExactSvg.hexColor(0xFFABCDEF.toInt()))
        assertEquals("#000", ExactSvg.hexColor(0x12000000))
        assertEquals("#0a0b0c", ExactSvg.hexColor(0xFF0A0B0C.toInt()))
    }

    @Test
    fun randomBlobImagesAreExact() {
        val rnd = Random(1)
        repeat(400) { n ->
            val w = rnd.nextInt(1, 24)
            val h = rnd.nextInt(1, 24)
            val palette = IntArray(rnd.nextInt(1, 5)) {
                when (rnd.nextInt(4)) {
                    0 -> 0 // transparent
                    1 -> (rnd.nextInt(1, 255) shl 24) or rnd.nextInt(0x1000000)
                    else -> 0xFF000000.toInt() or rnd.nextInt(0x1000000)
                }
            }
            // Blobby: mostly copy a neighbour, so regions get holes, islands and diagonal touches.
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val x = i % w
                pixels[i] = when {
                    x > 0 && rnd.nextInt(3) == 0 -> pixels[i - 1]
                    i >= w && rnd.nextInt(2) == 0 -> pixels[i - w]
                    else -> palette[rnd.nextInt(palette.size)]
                }
            }
            assertExact(pixels, w, h, "case $n (${w}x$h)")
        }
    }

    @Test
    fun noisyPhotoLikeImageIsExact() {
        val rnd = Random(2)
        val w = 300
        val h = 200
        val pixels = IntArray(w * h) {
            val x = it % w
            val y = it / w
            val r = (x * 255 / w + rnd.nextInt(-6, 7)).coerceIn(0, 255)
            val g = (y * 255 / h + rnd.nextInt(-6, 7)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or 0x40
        }
        val svg = svgOf(pixels, w, h)
        assertEquals(0, SvgCheck.verify(StringReader(svg), normalized(pixels), w, h).mismatched)
    }

    @Test
    fun analysisCountsExactlyTheEdgesWritten() {
        val rnd = Random(3)
        repeat(100) {
            val w = rnd.nextInt(1, 30)
            val h = rnd.nextInt(1, 30)
            val pixels = IntArray(w * h) {
                if (rnd.nextInt(4) == 0) 0 else 0xFF000000.toInt() or rnd.nextInt(3) * 0x404040
            }
            val svg = svgOf(pixels, w, h)
            // Each h/v is one edge, and each z draws the closing edge.
            val data = Regex("d=\"([^\"]*)\"").findAll(svg).joinToString("") { it.groupValues[1] }
            val written = data.count { it == 'h' || it == 'v' || it == 'z' }
            val analysis = ExactSvg.analyze(pixels.copyOf(), w, h)
            assertEquals(written.toLong(), analysis.corners)
            assertEquals(Regex("<path").findAll(svg).count(), analysis.colors)
        }
    }

    @Test
    fun checkerCatchesAWrongPixel() {
        val pixels = IntArray(16) { if (it == 5) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val svg = svgOf(pixels, 4, 4).replace("#f00", "#f01")
        assertEquals(1, SvgCheck.verify(StringReader(svg), normalized(pixels), 4, 4).mismatched)
    }

    @Test
    fun checkerCatchesAMissingRegion() {
        val pixels = IntArray(16) { if (it == 5) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val svg = svgOf(pixels, 4, 4).lines().filterNot { it.contains("#f00") }.joinToString("\n")
        assertEquals(1, SvgCheck.verify(StringReader(svg), normalized(pixels), 4, 4).mismatched)
    }

    @Test(expected = SvgCheck.FormatException::class)
    fun checkerRejectsCurves() {
        val svg = "<svg width=\"2\" height=\"2\"><path fill=\"#000\" d=\"M0 0C1 1 2 2 0 2z\"/></svg>"
        SvgCheck.verify(StringReader(svg), IntArray(4), 2, 2)
    }
}

/** Point-in-polygon at each pixel centre, straight from the path text. Slow but obviously right. */
private object NaiveRender {
    fun render(svg: String, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val path = Regex("<path fill=\"#([0-9a-f]+)\"(?: fill-opacity=\"([0-9.]+)\")? d=\"([^\"]*)\"/>")
        for (m in path.findAll(svg)) {
            val hex = m.groupValues[1].let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
            val alpha = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { Math.round(it.toFloat() * 255) } ?: 255
            val color = (alpha shl 24) or hex.toInt(16)
            val loops = polygons(m.groupValues[3])
            for (y in 0 until h) for (x in 0 until w) {
                val winding = loops.sumOf { windingAt(it, x + 0.5, y + 0.5) }
                if (winding != 0) {
                    assertEquals("pixel ($x,$y) painted twice", 0, out[y * w + x])
                    out[y * w + x] = color
                }
            }
        }
        return out
    }

    private fun polygons(d: String): List<List<Pair<Int, Int>>> {
        val result = ArrayList<List<Pair<Int, Int>>>()
        var current = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        for (cmd in Regex("([MhvzZ])\\s*(-?\\d+)?\\s*(-?\\d+)?").findAll(d)) {
            when (cmd.groupValues[1]) {
                "M" -> {
                    x = cmd.groupValues[2].toInt(); y = cmd.groupValues[3].toInt()
                    current = arrayListOf(x to y)
                }
                "h" -> { x += cmd.groupValues[2].toInt(); current += x to y }
                "v" -> { y += cmd.groupValues[2].toInt(); current += x to y }
                else -> result += current
            }
        }
        return result
    }

    private fun windingAt(poly: List<Pair<Int, Int>>, px: Double, py: Double): Int {
        var wn = 0
        for (i in poly.indices) {
            val (x0, y0) = poly[i]
            val (x1, y1) = poly[(i + 1) % poly.size]
            if (y0 <= py && y1 > py && cross(x0, y0, x1, y1, px, py) > 0) wn++
            else if (y0 > py && y1 <= py && cross(x0, y0, x1, y1, px, py) < 0) wn--
        }
        return wn
    }

    private fun cross(x0: Int, y0: Int, x1: Int, y1: Int, px: Double, py: Double) =
        (x1 - x0) * (py - y0) - (px - x0) * (y1 - y0)
}
