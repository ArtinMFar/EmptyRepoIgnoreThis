package app.imagetosvg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.imagetosvg.shapes.ShapeKind
import app.imagetosvg.ui.AppViewModel
import app.imagetosvg.ui.ExactState
import app.imagetosvg.ui.Mode
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screens to app/build/screenshots/ for eyeballing UI changes
 * without a device. Skip with -Dscreenshots=false.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("screenshots") != "false")
        vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[AppViewModel::class.java] }
    }

    @Test
    fun emptyHome() {
        rule.waitForIdle()
        capture("home_empty")
    }

    @Test
    fun lightScreens() = screens(suffix = "light")

    @Test
    @Config(qualifiers = "+night")
    fun darkScreens() = screens(suffix = "dark")

    private fun screens(suffix: String) {
        val image = TestImages.logo(512)
        rule.runOnUiThread {
            vm.selectMode(Mode.EXACT)
            vm.viewModelScope.launch { vm.show(image) }
        }
        rule.waitFor(30_000, { "exact SVG, now ${vm.exact}" }) { vm.exact is ExactState.Done }
        capture("exact_$suffix")

        rule.runOnUiThread {
            vm.selectMode(Mode.BRUTE_FORCE)
            vm.selectKind(ShapeKind.TRIANGLES)
            vm.selectLimit(250)
            vm.startBruteForce()
        }
        rule.waitFor(180_000, { "250 shapes, now ${vm.shapeCount}" }) { !vm.running && vm.shapeCount > 0 }
        capture("brute_$suffix")
        rule.onNodeWithText("Start over").performScrollTo()
        rule.waitForIdle()
        capture("brute_stats_$suffix")
    }

    /** Draws every window into one image, like the real screen. */
    private fun capture(name: String) = rule.runOnUiThread {
        val windows = windowRoots()
        val screen = windows.first()
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windows.forEach { it.draw(canvas) }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }
        return (views.get(instance) as List<View>).filter { it.isShown }
    }
}
