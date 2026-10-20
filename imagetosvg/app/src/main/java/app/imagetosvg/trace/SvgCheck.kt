package app.imagetosvg.trace

import java.io.Reader
import kotlin.math.roundToInt

/**
 * Proves an exact SVG really is the image: reads the file back, rasterises it the way an
 * SVG renderer does at 1:1 (nonzero fill, source-over blending) and compares every pixel.
 *
 * It understands what [ExactSvg] writes: `<svg width height>` and `<path fill fill-opacity d>`
 * with straight horizontal/vertical path commands on whole-pixel coordinates. Anything else
 * is reported as an error rather than silently skipped.
 */
object SvgCheck {

    class Result(
        val width: Int,
        val height: Int,
        /** Pixels whose rendered colour differs from the image. 0 means identical. */
        val mismatched: Int,
        /** The rendered SVG, ARGB, same layout as the input pixels. */
        val rendered: IntArray,
    ) {
        val total get() = width * height
        val identical get() = mismatched == 0
    }

    class FormatException(message: String) : Exception(message)

    /** [pixels] must already be normalized (see [ExactSvg.normalize]). */
    fun verify(svg: Reader, pixels: IntArray, width: Int, height: Int): Result {
        val rendered = Renderer(width, height).also { Parser(svg, it).run() }.pixels
        var mismatched = 0
        for (i in pixels.indices) if (rendered[i] != pixels[i]) mismatched++
        return Result(width, height, mismatched, rendered)
    }

    private class Renderer(val width: Int, val height: Int) {
        val pixels = IntArray(width * height)
        var svgWidth = -1
        var svgHeight = -1

        // Vertical edges crossed by each pixel row, as (row, x, direction) sorted later.
        private var events = LongArray(1024)
        private var count = 0

        fun verticalEdge(x: Int, y0: Int, y1: Int) {
            if (y0 == y1) return
            val down = if (y1 > y0) 1L else 0L
            val top = minOf(y0, y1).coerceAtLeast(0)
            val bottom = maxOf(y0, y1).coerceAtMost(height)
            val col = x.coerceIn(0, width).toLong()
            for (row in top until bottom) {
                if (count == events.size) events = events.copyOf(count * 2)
                events[count++] = ((row * (width + 1L) + col) shl 1) or down
            }
        }

        /** Fills the path collected so far with [color] (nonzero rule) and starts a new one. */
        fun fill(color: Int) {
            java.util.Arrays.sort(events, 0, count)
            var i = 0
            while (i < count) {
                val row = ((events[i] ushr 1) / (width + 1)).toInt()
                var winding = 0
                var spanStart = 0
                while (i < count) {
                    val key = events[i] ushr 1
                    if ((key / (width + 1)).toInt() != row) break
                    val x = (key % (width + 1)).toInt()
                    val before = winding
                    winding += if (events[i] and 1L == 1L) 1 else -1
                    if (before == 0 && winding != 0) {
                        spanStart = x
                    } else if (before != 0 && winding == 0) {
                        val base = row * width
                        for (px in spanStart until x) pixels[base + px] = over(color, pixels[base + px])
                    }
                    i++
                }
            }
            count = 0
        }

        private fun over(src: Int, dst: Int): Int {
            val sa = src ushr 24
            if (sa == 255 || dst == 0) return src
            if (sa == 0) return dst
            val as_ = sa / 255f
            val ad = (dst ushr 24) / 255f
            val ao = as_ + ad * (1 - as_)
            fun ch(shift: Int): Int {
                val cs = (src ushr shift) and 0xFF
                val cd = (dst ushr shift) and 0xFF
                return ((cs * as_ + cd * ad * (1 - as_)) / ao).roundToInt().coerceIn(0, 255)
            }
            return ((ao * 255).roundToInt() shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private class Parser(private val input: Reader, private val r: Renderer) {
        private var peeked = -2

        private fun read(): Int {
            if (peeked != -2) return peeked.also { peeked = -2 }
            return input.read()
        }

        private fun peek(): Int {
            if (peeked == -2) peeked = input.read()
            return peeked
        }

        fun run() {
            while (true) {
                var c = read()
                while (c != -1 && c != '<'.code) c = read()
                if (c == -1) break
                val name = StringBuilder()
                while (true) {
                    val p = peek()
                    if (p == -1 || p.toChar().isWhitespace() || p == '>'.code || p == '/'.code) break
                    name.append(read().toChar())
                }
                when (name.toString()) {
                    "svg" -> svgTag()
                    "path" -> pathTag()
                    else -> skipTag()
                }
            }
            if (r.svgWidth != r.width || r.svgHeight != r.height) {
                throw FormatException("SVG is ${r.svgWidth}×${r.svgHeight}, image is ${r.width}×${r.height}")
            }
        }

        private fun skipTag() {
            var c = read()
            while (c != -1 && c != '>'.code) c = read()
        }

        private fun svgTag() {
            attributes { name ->
                val value = value()
                when (name) {
                    "width" -> r.svgWidth = value.toIntOrNull() ?: throw FormatException("width=\"$value\"")
                    "height" -> r.svgHeight = value.toIntOrNull() ?: throw FormatException("height=\"$value\"")
                }
            }
        }

        private fun pathTag() {
            var rgb = -1
            var alpha = 255
            attributes { name ->
                when (name) {
                    "d" -> pathData()
                    "fill" -> rgb = parseColor(value())
                    "fill-opacity" -> {
                        val v = value()
                        val f = v.toFloatOrNull() ?: throw FormatException("fill-opacity=\"$v\"")
                        alpha = (f.coerceIn(0f, 1f) * 255).roundToInt()
                    }
                    else -> value()
                }
            }
            if (rgb == -1) throw FormatException("path without a fill colour")
            r.fill(if (alpha == 0) 0 else (alpha shl 24) or rgb)
        }

        /** Calls [onAttribute] with each attribute name; it must consume the value. */
        private inline fun attributes(onAttribute: (String) -> Unit) {
            while (true) {
                skipSpace()
                val p = peek()
                if (p == -1) throw FormatException("unexpected end of file")
                if (p == '>'.code) { read(); return }
                if (p == '/'.code) { read(); continue }
                val name = StringBuilder()
                while (peek() != '='.code) {
                    val c = read()
                    if (c == -1 || c == '>'.code) throw FormatException("bad attribute")
                    name.append(c.toChar())
                }
                read()
                skipSpace()
                quote = read()
                if (quote != '"'.code && quote != '\''.code) throw FormatException("unquoted attribute")
                onAttribute(name.toString().trim())
            }
        }

        private var quote = '"'.code

        private fun value(): String {
            val sb = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) throw FormatException("unexpected end of file")
                if (c == quote) return sb.toString()
                sb.append(c.toChar())
            }
        }

        private fun skipSpace() {
            while (peek() != -1 && peek().toChar().isWhitespace()) read()
        }

        private fun parseColor(v: String): Int {
            val hex = v.trim().removePrefix("#")
            if (!v.trim().startsWith("#")) throw FormatException("fill=\"$v\"")
            val full = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6 -> hex
                else -> throw FormatException("fill=\"$v\"")
            }
            return full.toIntOrNull(16) ?: throw FormatException("fill=\"$v\"")
        }

        /** Streams the `d` attribute straight into the renderer, without holding it in memory. */
        private fun pathData() {
            var x = 0
            var y = 0
            var startX = 0
            var startY = 0
            var open = false
            var cmd = ' '

            fun closePath() {
                if (open) {
                    if (x != startX && y != startY) throw FormatException("diagonal close")
                    r.verticalEdge(x, y, startY)
                }
                x = startX
                y = startY
                open = false
            }

            while (true) {
                skipSpaceAndCommas()
                val p = peek()
                if (p == -1) throw FormatException("unexpected end of file")
                if (p == quote) { read(); break }
                val ch = p.toChar()
                if (ch.isLetter()) {
                    read()
                    cmd = ch
                    if (cmd == 'Z' || cmd == 'z') {
                        closePath()
                        continue
                    }
                }
                when (cmd) {
                    'M', 'm' -> {
                        if (open) closePath()
                        val nx = number()
                        val ny = number()
                        x = if (cmd == 'm') x + nx else nx
                        y = if (cmd == 'm') y + ny else ny
                        startX = x
                        startY = y
                        open = true
                        cmd = if (cmd == 'm') 'l' else 'L' // further pairs are line-tos
                    }
                    'H', 'h' -> { val v = number(); x = if (cmd == 'h') x + v else v }
                    'V', 'v' -> {
                        val v = number()
                        val ny = if (cmd == 'v') y + v else v
                        r.verticalEdge(x, y, ny)
                        y = ny
                    }
                    'L', 'l' -> {
                        val nx = number().let { if (cmd == 'l') x + it else it }
                        val ny = number().let { if (cmd == 'l') y + it else it }
                        if (nx != x && ny != y) throw FormatException("diagonal line")
                        r.verticalEdge(x, y, ny)
                        x = nx
                        y = ny
                    }
                    else -> throw FormatException("unsupported path command '$cmd'")
                }
            }
            if (open) closePath()
        }

        private fun skipSpaceAndCommas() {
            while (true) {
                val p = peek()
                if (p == -1 || !(p.toChar().isWhitespace() || p == ','.code)) return
                read()
            }
        }

        private fun number(): Int {
            skipSpaceAndCommas()
            var negative = false
            if (peek() == '-'.code) { read(); negative = true } else if (peek() == '+'.code) read()
            var v = 0
            var digits = 0
            while (true) {
                val p = peek()
                if (p < '0'.code || p > '9'.code) break
                v = v * 10 + (read() - '0'.code)
                digits++
            }
            if (digits == 0 || peek() == '.'.code || peek() == 'e'.code || peek() == 'E'.code) {
                throw FormatException("expected a whole number in path data")
            }
            return if (negative) -v else v
        }
    }
}
