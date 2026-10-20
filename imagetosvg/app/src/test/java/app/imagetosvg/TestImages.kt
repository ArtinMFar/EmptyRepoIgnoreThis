package app.imagetosvg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import app.imagetosvg.image.LoadedImage
import app.imagetosvg.trace.ExactSvg
import kotlin.math.cos
import kotlin.math.sin

/** Pictures drawn with the real (native) Android graphics stack, antialiasing and all. */
object TestImages {

    /** A flat logo-style picture: what exact SVGs are best at. */
    fun logo(size: Int = 512): LoadedImage {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val s = size / 512f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 244, 222)
        c.drawRoundRect(16 * s, 16 * s, 496 * s, 496 * s, 96 * s, 96 * s, paint)
        paint.shader = LinearGradient(0f, 60 * s, 0f, 330 * s, Color.rgb(255, 120, 72), Color.rgb(255, 196, 64), Shader.TileMode.CLAMP)
        c.drawCircle(256 * s, 250 * s, 150 * s, paint)
        paint.shader = null
        paint.color = Color.rgb(0, 105, 110)
        val hills = Path().apply {
            moveTo(16 * s, 330 * s)
            cubicTo(140 * s, 250 * s, 220 * s, 300 * s, 300 * s, 340 * s)
            cubicTo(380 * s, 300 * s, 440 * s, 280 * s, 496 * s, 320 * s)
            lineTo(496 * s, 400 * s)
            lineTo(16 * s, 400 * s)
            close()
        }
        c.save()
        c.clipPath(Path().apply { addRoundRect(16 * s, 16 * s, 496 * s, 496 * s, 96 * s, 96 * s, Path.Direction.CW) })
        c.drawPath(hills, paint)
        paint.color = Color.rgb(0, 72, 76)
        c.drawRect(16 * s, 396 * s, 496 * s, 496 * s, paint)
        c.restore()
        paint.color = Color.WHITE
        for (i in 0 until 5) {
            val a = Math.toRadians(200.0 + i * 12)
            c.drawCircle((256 + 210 * cos(a)).toFloat() * s, (180 + 120 * sin(a)).toFloat() * s, 10 * s, paint)
        }
        return toImage("sunset-logo", bitmap)
    }

    /** Every pixel a slightly different colour, like a photo. */
    fun noise(w: Int, h: Int, seed: Int = 1): LoadedImage {
        val rnd = java.util.Random(seed.toLong())
        val pixels = IntArray(w * h) {
            0xFF000000.toInt() or (rnd.nextInt(256) shl 16) or (rnd.nextInt(256) shl 8) or rnd.nextInt(256)
        }
        return LoadedImage("noise", w, h, pixels, null)
    }

    fun toImage(name: String, bitmap: Bitmap): LoadedImage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ExactSvg.normalize(pixels)
        return LoadedImage(name, bitmap.width, bitmap.height, pixels, null)
    }
}
