package app.imagetosvg.shapes

import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind(val label: String) {
    TRIANGLES("Triangles"),
    RECTANGLES("Rectangles"),
    ELLIPSES("Ellipses"),
    MIXED("Mixed"),
}

/** Horizontal pixel runs (y, x1..x2 inclusive) covered by a shape. Reused to avoid garbage. */
class Lines {
    var ys = IntArray(512); private set
    var x1s = IntArray(512); private set
    var x2s = IntArray(512); private set
    var size = 0; private set

    fun clear() {
        size = 0
    }

    fun add(y: Int, x1: Int, x2: Int) {
        if (size == ys.size) {
            ys = ys.copyOf(size * 2)
            x1s = x1s.copyOf(size * 2)
            x2s = x2s.copyOf(size * 2)
        }
        ys[size] = y
        x1s[size] = x1
        x2s[size] = x2
        size++
    }
}

/** A shape in working-image pixel coordinates. Immutable: [mutate] returns a new one. */
sealed class Shape {
    abstract fun rasterize(w: Int, h: Int, out: Lines)
    abstract fun mutate(rnd: Random, w: Int, h: Int, step: Float): Shape
    abstract fun svg(sb: StringBuilder, fill: String)

    companion object {
        fun random(kind: ShapeKind, rnd: Random, w: Int, h: Int, step: Float): Shape = when (kind) {
            ShapeKind.TRIANGLES -> Triangle.random(rnd, w, h, step)
            ShapeKind.RECTANGLES -> Box.random(rnd, w, h, step)
            ShapeKind.ELLIPSES -> Oval.random(rnd, w, h, step)
            ShapeKind.MIXED -> when (rnd.nextInt(3)) {
                0 -> Triangle.random(rnd, w, h, step)
                1 -> Box.random(rnd, w, h, step)
                else -> Oval.random(rnd, w, h, step)
            }
        }
    }
}

class Triangle(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val x3: Float, val y3: Float,
) : Shape() {

    override fun rasterize(w: Int, h: Int, out: Lines) =
        convex(floatArrayOf(x1, x2, x3), floatArrayOf(y1, y2, y3), w, h, out)

    override fun mutate(rnd: Random, w: Int, h: Int, step: Float): Shape {
        while (true) {
            val dx = (rnd.nextGaussian() * step).toFloat()
            val dy = (rnd.nextGaussian() * step).toFloat()
            val t = when (rnd.nextInt(3)) {
                0 -> Triangle(clampX(x1 + dx, w), clampY(y1 + dy, h), x2, y2, x3, y3)
                1 -> Triangle(x1, y1, clampX(x2 + dx, w), clampY(y2 + dy, h), x3, y3)
                else -> Triangle(x1, y1, x2, y2, clampX(x3 + dx, w), clampY(y3 + dy, h))
            }
            if (t.valid()) return t
        }
    }

    /** No slivers: every angle at least 15°, like the classic "primitive" algorithm. */
    fun valid(): Boolean {
        fun angle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Double {
            val ux = bx - ax; val uy = by - ay
            val vx = cx - ax; val vy = cy - ay
            val lu = sqrt(ux * ux + uy * uy); val lv = sqrt(vx * vx + vy * vy)
            if (lu == 0f || lv == 0f) return 0.0
            return Math.toDegrees(acos(((ux * vx + uy * vy) / (lu * lv)).toDouble().coerceIn(-1.0, 1.0)))
        }
        val min = 15.0
        return angle(x1, y1, x2, y2, x3, y3) > min &&
            angle(x2, y2, x1, y1, x3, y3) > min &&
            angle(x3, y3, x1, y1, x2, y2) > min
    }

    override fun svg(sb: StringBuilder, fill: String) {
        sb.append("<polygon fill=\"").append(fill).append("\" points=\"")
        num(sb, x1); sb.append(','); num(sb, y1); sb.append(' ')
        num(sb, x2); sb.append(','); num(sb, y2); sb.append(' ')
        num(sb, x3); sb.append(','); num(sb, y3)
        sb.append("\"/>\n")
    }

    companion object {
        fun random(rnd: Random, w: Int, h: Int, step: Float): Triangle {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val r = step * 2
            while (true) {
                val t = Triangle(
                    x, y,
                    x + (rnd.nextFloat() * 2 - 1) * r, y + (rnd.nextFloat() * 2 - 1) * r,
                    x + (rnd.nextFloat() * 2 - 1) * r, y + (rnd.nextFloat() * 2 - 1) * r,
                )
                if (t.valid()) return t
            }
        }
    }
}

/** Rectangle of half-size (hw, hh) centred on (cx, cy), rotated [angle] degrees clockwise. */
class Box(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val angle: Float) : Shape() {

    fun corners(): Pair<FloatArray, FloatArray> {
        val a = Math.toRadians(angle.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val ux = floatArrayOf(-hw, hw, hw, -hw)
        val uy = floatArrayOf(-hh, -hh, hh, hh)
        for (i in 0 until 4) {
            xs[i] = cx + ux[i] * c - uy[i] * s
            ys[i] = cy + ux[i] * s + uy[i] * c
        }
        return xs to ys
    }

    override fun rasterize(w: Int, h: Int, out: Lines) {
        val (xs, ys) = corners()
        convex(xs, ys, w, h, out)
    }

    override fun mutate(rnd: Random, w: Int, h: Int, step: Float): Shape = when (rnd.nextInt(3)) {
        0 -> Box(
            clampX(cx + (rnd.nextGaussian() * step).toFloat(), w),
            clampY(cy + (rnd.nextGaussian() * step).toFloat(), h), hw, hh, angle,
        )
        1 -> Box(
            cx, cy,
            size(hw + (rnd.nextGaussian() * step).toFloat(), w),
            size(hh + (rnd.nextGaussian() * step).toFloat(), h), angle,
        )
        else -> Box(cx, cy, hw, hh, angle + (rnd.nextGaussian() * 32).toFloat())
    }

    override fun svg(sb: StringBuilder, fill: String) {
        val (xs, ys) = corners()
        sb.append("<polygon fill=\"").append(fill).append("\" points=\"")
        for (i in 0 until 4) {
            if (i > 0) sb.append(' ')
            num(sb, xs[i]); sb.append(','); num(sb, ys[i])
        }
        sb.append("\"/>\n")
    }

    companion object {
        fun random(rnd: Random, w: Int, h: Int, step: Float) = Box(
            rnd.nextFloat() * w, rnd.nextFloat() * h,
            1 + rnd.nextFloat() * step * 2, 1 + rnd.nextFloat() * step * 2,
            rnd.nextFloat() * 360,
        )
    }
}

/** Ellipse with radii (rx, ry) centred on (cx, cy), rotated [angle] degrees clockwise. */
class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float) : Shape() {

    override fun rasterize(w: Int, h: Int, out: Lines) {
        out.clear()
        val a = Math.toRadians(angle.toDouble())
        val c = cos(a)
        val s = sin(a)
        val irx2 = 1.0 / (rx * rx)
        val iry2 = 1.0 / (ry * ry)
        // Point (dx, dy) from the centre is inside when A·dx² + B·dx + C ≤ 0.
        val qa = c * c * irx2 + s * s * iry2
        val halfHeight = sqrt(rx * rx * s * s + ry * ry * c * c)
        val y0 = max(0, ceil(cy - halfHeight - 0.5).toInt())
        val y1 = min(h - 1, floor(cy + halfHeight - 0.5).toInt())
        for (y in y0..y1) {
            val dy = y + 0.5 - cy
            val qb = 2 * dy * c * s * (irx2 - iry2)
            val qc = dy * dy * (s * s * irx2 + c * c * iry2) - 1
            val disc = qb * qb - 4 * qa * qc
            if (disc < 0) continue
            val root = sqrt(disc)
            val lo = cx + (-qb - root) / (2 * qa)
            val hi = cx + (-qb + root) / (2 * qa)
            val a1 = max(0, ceil(lo - 0.5).toInt())
            val a2 = min(w - 1, floor(hi - 0.5).toInt())
            if (a1 <= a2) out.add(y, a1, a2)
        }
    }

    override fun mutate(rnd: Random, w: Int, h: Int, step: Float): Shape = when (rnd.nextInt(3)) {
        0 -> Oval(
            clampX(cx + (rnd.nextGaussian() * step).toFloat(), w),
            clampY(cy + (rnd.nextGaussian() * step).toFloat(), h), rx, ry, angle,
        )
        1 -> Oval(
            cx, cy,
            size(rx + (rnd.nextGaussian() * step).toFloat(), w),
            size(ry + (rnd.nextGaussian() * step).toFloat(), h), angle,
        )
        else -> Oval(cx, cy, rx, ry, angle + (rnd.nextGaussian() * 32).toFloat())
    }

    override fun svg(sb: StringBuilder, fill: String) {
        sb.append("<ellipse fill=\"").append(fill).append("\" cx=\"")
        num(sb, cx); sb.append("\" cy=\""); num(sb, cy)
        sb.append("\" rx=\""); num(sb, rx); sb.append("\" ry=\""); num(sb, ry)
        val turn = ((angle % 180) + 180) % 180
        if (abs(turn) >= 0.05f) {
            sb.append("\" transform=\"rotate(")
            num(sb, turn); sb.append(' '); num(sb, cx); sb.append(' '); num(sb, cy); sb.append(')')
        }
        sb.append("\"/>\n")
    }

    companion object {
        fun random(rnd: Random, w: Int, h: Int, step: Float) = Oval(
            rnd.nextFloat() * w, rnd.nextFloat() * h,
            1 + rnd.nextFloat() * step * 2, 1 + rnd.nextFloat() * step * 2,
            rnd.nextFloat() * 360,
        )
    }
}

/** Pixels whose centres fall inside a convex polygon. */
private fun convex(xs: FloatArray, ys: FloatArray, w: Int, h: Int, out: Lines) {
    out.clear()
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (y in ys) {
        minY = min(minY, y)
        maxY = max(maxY, y)
    }
    val y0 = max(0, ceil(minY - 0.5f).toInt())
    val y1 = min(h - 1, floor(maxY - 0.5f).toInt())
    val n = xs.size
    for (y in y0..y1) {
        val yc = y + 0.5f
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val ya = ys[i]
            val yb = ys[j]
            if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                val x = xs[i] + (yc - ya) / (yb - ya) * (xs[j] - xs[i])
                lo = min(lo, x)
                hi = max(hi, x)
            }
        }
        if (lo > hi) continue
        val a = max(0, ceil(lo - 0.5f).toInt())
        val b = min(w - 1, floor(hi - 0.5f).toInt())
        if (a <= b) out.add(y, a, b)
    }
}

private fun clampX(v: Float, w: Int) = v.coerceIn(-w * 0.1f, w * 1.1f)
private fun clampY(v: Float, h: Int) = v.coerceIn(-h * 0.1f, h * 1.1f)
private fun size(v: Float, limit: Int) = v.coerceIn(1f, limit.toFloat())

/** One decimal place, without a trailing ".0". */
internal fun num(sb: StringBuilder, v: Float) {
    var tenths = (v * 10).roundToInt()
    if (tenths < 0) {
        sb.append('-')
        tenths = -tenths
    }
    sb.append(tenths / 10)
    if (tenths % 10 != 0) sb.append('.').append(tenths % 10)
}
