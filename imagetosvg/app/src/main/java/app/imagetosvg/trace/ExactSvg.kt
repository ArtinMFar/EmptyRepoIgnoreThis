package app.imagetosvg.trace

import java.io.Writer

/**
 * Lossless image → SVG.
 *
 * Pixels of one colour that touch along an edge form a region. Each region's outline (and
 * the outline of every hole in it) is traced along the pixel edges and written as a path,
 * with all regions of one colour sharing one `<path>`. The outlines run exactly along pixel
 * boundaries, so every pixel is covered by its own colour and nothing else: the SVG is the
 * image, pixel for pixel. [SvgCheck] re-renders the file to prove it.
 *
 * Pixels are ARGB ints, not premultiplied. Fully transparent pixels are left out.
 */
object ExactSvg {

    class Stats(
        val width: Int,
        val height: Int,
        /** Distinct visible colours, which is also the number of `<path>` elements. */
        val colors: Int,
        /** Closed outlines (regions plus holes). */
        val outlines: Int,
        val chars: Long,
    )

    class Analysis(
        val colors: Int,
        /** Corners of all outlines, i.e. the number of straight edges the SVG will contain. */
        val corners: Long,
    ) {
        /** Rough size of the SVG in bytes, good to ±20% or so. */
        val estimatedBytes: Long get() = 120 + colors * 30L + corners * 4
    }

    /** Sets every fully transparent pixel to 0 so they all compare equal. */
    fun normalize(pixels: IntArray) {
        for (i in pixels.indices) if (pixels[i] ushr 24 == 0) pixels[i] = 0
    }

    /** Counts what [write] would produce without writing anything (one quick pass). */
    fun analyze(pixels: IntArray, width: Int, height: Int): Analysis {
        normalize(pixels)
        val table = ColorTable()
        for (c in pixels) if (c != 0) table.add(c)

        // Every outline turns at a corner of the pixel grid. Look at the 2×2 pixels around
        // each grid point: a colour holding 1 or 3 of them turns there once, a colour on
        // the two diagonal cells turns twice (two outlines meet), anything else runs straight.
        fun px(x: Int, y: Int) = if (x < 0 || y < 0 || x >= width || y >= height) 0 else pixels[y * width + x]
        var corners = 0L
        for (y in 0..height) {
            for (x in 0..width) {
                val a = px(x - 1, y - 1)
                val b = px(x, y - 1)
                val c = px(x - 1, y)
                val d = px(x, y)
                if (a != 0) {
                    val n = 1 + (if (b == a) 1 else 0) + (if (c == a) 1 else 0) + (if (d == a) 1 else 0)
                    corners += when (n) {
                        1, 3 -> 1
                        2 -> if (d == a) 2 else 0
                        else -> 0
                    }
                }
                if (b != 0 && b != a) {
                    val n = 1 + (if (c == b) 1 else 0) + (if (d == b) 1 else 0)
                    corners += when (n) {
                        1, 3 -> 1
                        else -> if (c == b) 2 else 0
                    }
                }
                if (c != 0 && c != a && c != b && d != c) corners += 1
                if (d != 0 && d != a && d != b && d != c) corners += 1
            }
        }
        return Analysis(table.size, corners)
    }

    /**
     * Writes the SVG to [out]. [onProgress] gets 0..1 as colours are traced; throw from it
     * (e.g. a CancellationException) to stop.
     */
    fun write(
        pixels: IntArray,
        width: Int,
        height: Int,
        out: Writer,
        onProgress: (Float) -> Unit = {},
    ): Stats {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad image size" }
        normalize(pixels)

        // Group pixels by colour (counting sort), keeping row-major order inside each group.
        val table = ColorTable()
        var counts = IntArray(256)
        var visible = 0
        for (c in pixels) {
            if (c == 0) continue
            val k = table.add(c)
            if (k == counts.size) counts = counts.copyOf(counts.size * 2)
            counts[k]++
            visible++
        }
        val colors = table.size
        val start = IntArray(colors + 1)
        for (k in 0 until colors) start[k + 1] = start[k] + counts[k]
        val next = start.copyOf(colors)
        val order = IntArray(visible)
        for (p in pixels.indices) {
            val c = pixels[p]
            if (c != 0) order[next[table.indexOf(c)]++] = p
        }

        val sink = SvgOut(out)
        sink.str("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
        sink.int(width); sink.str("\" height=\""); sink.int(height)
        sink.str("\" viewBox=\"0 0 "); sink.int(width); sink.ch(' '); sink.int(height)
        sink.str("\" shape-rendering=\"crispEdges\">\n")

        val tracer = Tracer(pixels, width, height, sink)
        var done = 0
        var reported = 0
        for (k in 0 until colors) {
            val c = table.colorAt(k)
            sink.str("<path fill=\"")
            sink.str(hexColor(c))
            val alpha = c ushr 24
            if (alpha != 255) {
                sink.str("\" fill-opacity=\"")
                sink.str(opacity(alpha))
            }
            sink.str("\" d=\"")
            for (i in start[k] until start[k + 1]) tracer.outlinesOf(order[i], c)
            sink.str("\"/>\n")

            done += counts[k]
            if (done - reported >= 65_536) {
                reported = done
                onProgress(done.toFloat() / visible)
            }
        }
        sink.str("</svg>\n")
        sink.flush()
        return Stats(width, height, colors, tracer.outlines, sink.written)
    }

    /** `#rrggbb`, shortened to `#rgb` when that says the same thing. */
    fun hexColor(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        val short = rgb and 0x0F0F0F == (rgb ushr 4) and 0x0F0F0F
        return if (short) {
            "#" + HEX[(rgb ushr 16) and 0xF] + HEX[(rgb ushr 8) and 0xF] + HEX[rgb and 0xF]
        } else {
            "#" + (0x1000000 or rgb).toString(16).substring(1)
        }
    }

    /** alpha/255 with three decimals, which is enough to get the exact alpha back. */
    fun opacity(alpha: Int): String {
        val milli = (alpha * 1000 + 127) / 255
        if (milli >= 1000) return "1"
        return "0." + milli.toString().padStart(3, '0').trimEnd('0')
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Follows outlines along pixel edges. An edge is walked with its region on the right-hand
 * side (screen coordinates, y down), so outer outlines run clockwise and holes run
 * counter-clockwise; that makes the default nonzero fill rule cut the holes out.
 */
private class Tracer(
    private val px: IntArray,
    private val w: Int,
    private val h: Int,
    private val out: SvgOut,
) {
    /** Bit d set = the edge of this pixel walked in direction d (0 top, 1 right, 2 bottom, 3 left) is done. */
    private val visited = ByteArray(w * h)
    private var segDir = IntArray(64)
    private var segLen = IntArray(64)
    var outlines = 0
        private set

    fun outlinesOf(p: Int, c: Int) {
        val x = p % w
        val y = p / w
        if (visited[p].toInt() and 1 == 0 && (y == 0 || px[p - w] != c)) trace(x, y, 0, c)
        if (visited[p].toInt() and 2 == 0 && (x == w - 1 || px[p + 1] != c)) trace(x + 1, y, 1, c)
        if (visited[p].toInt() and 4 == 0 && (y == h - 1 || px[p + w] != c)) trace(x + 1, y + 1, 2, c)
        if (visited[p].toInt() and 8 == 0 && (x == 0 || px[p - 1] != c)) trace(x, y + 1, 3, c)
    }

    private fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) 0 else px[y * w + x]

    /** Walks one closed outline starting at grid point (sx, sy) heading [sd], and writes it. */
    private fun trace(sx: Int, sy: Int, sd: Int, c: Int) {
        var x = sx
        var y = sy
        var d = sd
        var n = 0
        var curDir = -1
        var curLen = 0
        do {
            val p = (y + RPY[d]) * w + (x + RPX[d])
            visited[p] = (visited[p].toInt() or (1 shl d)).toByte()
            if (d == curDir) {
                curLen++
            } else {
                if (curDir >= 0) n = push(n, curDir, curLen)
                curDir = d
                curLen = 1
            }
            x += DX[d]
            y += DY[d]
            // Turn right if the pixel ahead on the right isn't ours, go straight if the one
            // ahead on the left isn't, otherwise turn left. Turning right first keeps
            // diagonally touching pixels in separate outlines.
            val right = (d + 1) and 3
            val left = (d + 3) and 3
            d = when {
                at(x + RPX[d], y + RPY[d]) != c -> right
                at(x + RPX[left], y + RPY[left]) != c -> d
                else -> left
            }
        } while (x != sx || y != sy || d != sd)
        n = push(n, curDir, curLen)

        // If we started halfway along a straight edge, join its two halves.
        var mx = sx
        var my = sy
        if (segDir[0] == segDir[n - 1]) {
            n--
            mx -= DX[segDir[n]] * segLen[n]
            my -= DY[segDir[n]] * segLen[n]
            segLen[0] += segLen[n]
        }

        out.ch('M'); out.int(mx); out.ch(' '); out.int(my)
        // The last edge goes back to the start, which "z" draws for free.
        for (i in 0 until n - 1) {
            val dir = segDir[i]
            out.ch(if (dir and 1 == 0) 'h' else 'v')
            out.int(if (dir < 2) segLen[i] else -segLen[i])
        }
        out.ch('z')
        outlines++
    }

    private fun push(n: Int, dir: Int, len: Int): Int {
        if (n == segDir.size) {
            segDir = segDir.copyOf(n * 2)
            segLen = segLen.copyOf(n * 2)
        }
        segDir[n] = dir
        segLen[n] = len
        return n + 1
    }

    private companion object {
        // Directions: 0 right, 1 down, 2 left, 3 up.
        val DX = intArrayOf(1, 0, -1, 0)
        val DY = intArrayOf(0, 1, 0, -1)
        // Offset from an edge's start point to the pixel on its right-hand side.
        val RPX = intArrayOf(0, -1, -1, 0)
        val RPY = intArrayOf(0, 0, -1, -1)
    }
}

/** Buffered character output with allocation-free integers. */
private class SvgOut(private val out: Writer) {
    private val buf = CharArray(16_384)
    private var n = 0
    var written = 0L
        private set

    fun ch(c: Char) {
        if (n == buf.size) flush()
        buf[n++] = c
    }

    fun str(s: String) {
        for (c in s) ch(c)
    }

    fun int(value: Int) {
        if (n + 11 > buf.size) flush()
        var v = value
        if (v < 0) {
            buf[n++] = '-'
            v = -v
        }
        val first = n
        do {
            buf[n++] = '0' + v % 10
            v /= 10
        } while (v != 0)
        buf.reverse(first, n)
    }

    fun flush() {
        out.write(buf, 0, n)
        written += n
        n = 0
    }
}

/** Open-addressing map from colour to a dense index (in order of first appearance). 0 is never stored. */
internal class ColorTable {
    private var keys = IntArray(1 shl 10)
    private var slots = IntArray(1 shl 10)
    private var colors = IntArray(256)
    var size = 0
        private set

    fun colorAt(index: Int) = colors[index]

    fun indexOf(color: Int): Int {
        val mask = keys.size - 1
        var i = mix(color) and mask
        while (true) {
            val k = keys[i]
            if (k == color) return slots[i]
            if (k == 0) return -1
            i = (i + 1) and mask
        }
    }

    /** Index of [color], adding it if new. */
    fun add(color: Int): Int {
        val mask = keys.size - 1
        var i = mix(color) and mask
        while (true) {
            val k = keys[i]
            if (k == color) return slots[i]
            if (k == 0) break
            i = (i + 1) and mask
        }
        val index = size++
        keys[i] = color
        slots[i] = index
        if (index == colors.size) colors = colors.copyOf(index * 2)
        colors[index] = color
        if (size * 2 > keys.size) grow()
        return index
    }

    private fun grow() {
        val oldKeys = keys
        val oldSlots = slots
        keys = IntArray(oldKeys.size * 2)
        slots = IntArray(oldKeys.size * 2)
        val mask = keys.size - 1
        for (j in oldKeys.indices) {
            val k = oldKeys[j]
            if (k == 0) continue
            var i = mix(k) and mask
            while (keys[i] != 0) i = (i + 1) and mask
            keys[i] = k
            slots[i] = oldSlots[j]
        }
    }

    private fun mix(v: Int): Int {
        val h = v * -0x61c88647
        return h xor (h ushr 16)
    }
}
