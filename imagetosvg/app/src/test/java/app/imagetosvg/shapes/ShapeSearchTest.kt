package app.imagetosvg.shapes

import app.imagetosvg.image.Pixels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Random
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.sin

class ShapeSearchTest {

    /** A soft picture: a dark disc on a gradient, with a little texture. */
    private fun picture(w: Int, h: Int) = IntArray(w * h) {
        val x = it % w
        val y = it / w
        val dx = x - w * 0.4
        val dy = y - h * 0.55
        val disc = dx * dx + dy * dy < (w * 0.25) * (w * 0.25)
        val r = if (disc) 40 else 120 + x * 120 / w
        val g = if (disc) 60 else 160 + (8 * sin(y * 0.3)).toInt()
        val b = if (disc) 90 else 220 - y * 100 / h
        (r shl 16) or (g shl 8) or b
    }

    @Test
    fun solidImageIsExactWithoutAnyShapes() = runBlocking {
        val search = ShapeSearch(IntArray(64 * 48) { 0x336699 }, 64, 48, seed = 1)
        assertTrue(search.isExact)
        assertEquals(1.0, search.similarity, 0.0)
        assertNull(search.step(ShapeKind.TRIANGLES))
    }

    @Test
    fun everyShapeKindKeepsImproving() = runBlocking {
        for (kind in ShapeKind.entries) {
            val search = ShapeSearch(picture(96, 72), 96, 72, seed = 7, workers = 2)
            val before = search.similarity
            var last = search.error
            repeat(40) {
                search.step(kind) ?: return@repeat
                assertTrue("$kind error must never go up", search.error <= last)
                last = search.error
            }
            assertTrue("$kind placed shapes", search.shapes.size > 30)
            assertTrue("$kind: ${search.similarity} vs $before", search.similarity > before + 0.03)
            assertFalse("$kind should not be exact", search.isExact)
        }
    }

    @Test
    fun runningTotalsMatchARecount() = runBlocking {
        val target = picture(50, 40)
        val search = ShapeSearch(target, 50, 40, seed = 3, workers = 1)
        repeat(25) { search.step(ShapeKind.MIXED) }
        // Replay the shapes from scratch and compare with the incrementally kept error.
        val canvas = IntArray(target.size) { search.background }
        val lines = Lines()
        for (p in search.shapes) {
            p.shape.rasterize(50, 40, lines)
            for (l in 0 until lines.size) for (x in lines.x1s[l]..lines.x2s[l]) {
                val i = lines.ys[l] * 50 + x
                val c = canvas[i]
                canvas[i] = (ShapeSearch.blend((c ushr 16) and 0xFF, (p.color ushr 16) and 0xFF) shl 16) or
                    (ShapeSearch.blend((c ushr 8) and 0xFF, (p.color ushr 8) and 0xFF) shl 8) or
                    ShapeSearch.blend(c and 0xFF, p.color and 0xFF)
            }
        }
        var error = 0L
        var exact = 0
        for (i in target.indices) {
            val a = target[i]
            val b = canvas[i]
            for (s in intArrayOf(16, 8, 0)) {
                val d = ((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)
                error += d * d
            }
            if (a == b) exact++
        }
        assertEquals(error, search.error)
        assertEquals(exact, search.exactPixels)
    }

    @Test
    fun svgIsWellFormedAndHasEveryShape() = runBlocking {
        val search = ShapeSearch(picture(80, 60), 80, 60, seed = 5, workers = 2)
        repeat(30) { search.step(ShapeKind.MIXED) }
        val svg = search.svg(800, 600)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(svg.toByteArray()))
        val root = doc.documentElement
        assertEquals("svg", root.tagName)
        assertEquals("800", root.getAttribute("width"))
        assertEquals("0 0 80 60", root.getAttribute("viewBox"))
        val group = root.getElementsByTagName("g").item(0) as Element
        assertEquals(search.shapes.size, group.getElementsByTagName("*").length)
        assertEquals(1, root.getElementsByTagName("rect").length)
    }

    @Test
    fun shapesStayInsideTheImage() {
        val rnd = Random(9)
        val lines = Lines()
        repeat(2000) {
            var s = Shape.random(ShapeKind.MIXED, rnd, 40, 30, 4f)
            repeat(20) { s = s.mutate(rnd, 40, 30, 4f) }
            s.rasterize(40, 30, lines)
            for (l in 0 until lines.size) {
                assertTrue(lines.ys[l] in 0 until 30)
                assertTrue(lines.x1s[l] in 0 until 40)
                assertTrue(lines.x2s[l] in lines.x1s[l] until 40)
            }
        }
    }

    @Test
    fun ellipseCoversAboutItsArea() {
        val lines = Lines()
        Oval(50f, 50f, 30f, 10f, 30f).rasterize(100, 100, lines)
        var area = 0
        for (l in 0 until lines.size) area += lines.x2s[l] - lines.x1s[l] + 1
        assertEquals(Math.PI * 30 * 10, area.toDouble(), 20.0)
    }

    @Test
    fun workingCopyIsFlattenedOnWhite() {
        val pixels = intArrayOf(0x00000000, 0xFF000000.toInt(), 0x80FF0000.toInt(), 0xFF0000FF.toInt())
        val flat = Pixels.onWhite(pixels)
        assertEquals(0xFFFFFF, flat[0])
        assertEquals(0x000000, flat[1])
        assertEquals(0xFF7F7F, flat[2])
        assertEquals(0x0000FF, flat[3])
    }

    @Test
    fun scalingAveragesBlocks() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = intArrayOf(black, white, white, white, black, white, white, white)
        assertEquals(2 to 1, Pixels.fit(4, 2, 2))
        val small = Pixels.scale(pixels, 4, 2, 2, 1)
        assertEquals(0xFF808080.toInt(), small[0])
        assertEquals(white, small[1])
        // Transparent pixels don't darken their neighbours.
        val half = Pixels.scale(intArrayOf(0, white), 2, 1, 1, 1)
        assertEquals(0x80FFFFFF.toInt(), half[0])
    }
}
