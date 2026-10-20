package app.imagetosvg

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.imagetosvg.image.ImageLoader
import app.imagetosvg.image.LoadedImage
import app.imagetosvg.trace.SvgCheck
import app.imagetosvg.ui.AppViewModel
import app.imagetosvg.ui.ExactState
import app.imagetosvg.ui.FileType
import app.imagetosvg.ui.Mode
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Drives the real UI with images decoded and drawn by the native Android graphics stack. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AppFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        rule.runOnUiThread { vm.selectMode(Mode.EXACT) }
    }

    private fun show(image: LoadedImage) {
        rule.runOnUiThread { vm.viewModelScope.launch { vm.show(image) } }
        rule.waitFor(10_000) { vm.current?.image === image }
    }

    @Test
    fun exactSvgIsVerifiedPixelForPixel() {
        val image = TestImages.logo(256)
        show(image)
        rule.waitFor(30_000, { "exact SVG, now ${vm.exact}" }) { vm.exact is ExactState.Done }
        rule.onNodeWithText("100% match").assertExists()
        rule.onNodeWithText("Save SVG").performScrollTo().assertIsEnabled()

        // Check the file on disk independently of what the screen says.
        val done = vm.exact as ExactState.Done
        val check = done.file.bufferedReader().use { SvgCheck.verify(it, image.pixels, image.width, image.height) }
        assertEquals(0, check.mismatched)
        assertTrue("antialiased edges give many colours", done.stats.colors > 50)
        assertEquals("sunset-logo.svg", vm.fileName(Mode.EXACT))
    }

    @Test
    fun savingAsTextWritesTheSameSvg() {
        show(TestImages.logo(128))
        rule.waitFor(30_000, { "exact SVG, now ${vm.exact}" }) { vm.exact is ExactState.Done }
        rule.onNodeWithText("Save as .txt").performScrollTo().assertIsEnabled()
        assertEquals("sunset-logo.txt", vm.fileName(Mode.EXACT, FileType.TEXT))

        val out = File(rule.activity.cacheDir, "saved.txt")
        assertTrue(runBlocking { vm.saveTo(Uri.fromFile(out), Mode.EXACT) })
        val svg = (vm.exact as ExactState.Done).file
        assertArrayEquals(svg.readBytes(), out.readBytes())
        assertTrue(out.readText().startsWith("<svg xmlns="))
    }

    @Test
    fun hugeExactSvgWaitsForAGoAhead() {
        show(TestImages.noise(900, 900))
        rule.waitFor(10_000, { "warning, now ${vm.exact}" }) { vm.exact is ExactState.TooBig }
        rule.onNodeWithText("Big file ahead").assertExists()
        rule.onNodeWithText("Try brute force instead").performScrollTo().performClick()
        rule.waitFor(5_000) { vm.mode == Mode.BRUTE_FORCE }
    }

    @Test
    fun bruteForceAddsShapesUntilTheLimit() {
        show(TestImages.logo(256))
        rule.onNodeWithText("Brute force").performClick()
        rule.onNodeWithText("100").performScrollTo().performClick()
        rule.onNodeWithText("Start").performScrollTo().performClick()
        rule.waitFor(120_000, { "100 shapes, now ${vm.shapeCount}" }) { !vm.running && vm.shapeCount > 0 }
        assertEquals(100, vm.shapeCount)
        assertTrue("similarity ${vm.similarity}", vm.similarity > 0.85)
        rule.onNodeWithText("Start over").performScrollTo().assertExists()

        val svg = runBlocking { vm.svgFile(Mode.BRUTE_FORCE) }!!.readText()
        assertTrue(svg.startsWith("<svg"))
        assertEquals(100, Regex("<(polygon|ellipse) ").findAll(svg).count())
        assertTrue(svg.contains("width=\"256\" height=\"256\" viewBox=\"0 0 256 256\""))
    }

    // Robolectric can't run ImageDecoder on a content/file Uri, so this covers the
    // BitmapFactory path the app uses before Android 9.
    @Test
    @Config(sdk = [27])
    fun loaderKeepsExactColoursIncludingTransparency() {
        val w = 7
        val h = 5
        val colors = IntArray(w * h) { i ->
            when (i % 5) {
                0 -> 0x00000000
                1 -> 0x80FF0000.toInt()
                2 -> 0x0A123456
                3 -> 0xFFABCDEF.toInt()
                else -> 0xC8000000.toInt() or (i * 0x030507)
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            isPremultiplied = false
            setPixels(colors, 0, w, 0, 0, w, h)
        }
        val file = File(rule.activity.cacheDir, "My picture (1).png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val loaded = ImageLoader.load(rule.activity, Uri.fromFile(file))
        assertEquals(w, loaded.width)
        assertEquals(h, loaded.height)
        assertEquals("My_picture_1", loaded.name)
        assertArrayEquals(colors, loaded.pixels)
    }
}
