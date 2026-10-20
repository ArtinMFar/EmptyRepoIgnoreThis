package app.jsonlbuilder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import app.jsonlbuilder.data.Example
import app.jsonlbuilder.ui.AppViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

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

    @Before
    fun enabled() = assumeTrue(System.getProperty("screenshots") != "false")

    @Test
    fun emptyHome() {
        val vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        rule.waitUntil(5_000) { vm.loaded }
        rule.runOnUiThread { vm.datasets.toList().forEach(vm::delete) }
        rule.waitForIdle()
        capture("home_empty")
    }

    @Test
    fun lightScreens() = screens(suffix = "light")

    @Test
    @Config(qualifiers = "+night")
    fun darkScreens() = screens(suffix = "dark")

    private fun screens(suffix: String) {
        val vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        rule.waitUntil(5_000) { vm.loaded }
        rule.runOnUiThread { seed(vm) }
        rule.waitForIdle()
        capture("home_$suffix")

        rule.onAllNodesWithText("Customer support")[0].performClick()
        rule.waitForIdle()
        capture("editor_$suffix")

        // Dialog windows never report idle under native graphics, so pump frames by hand.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Export").performClick()
        pumpFrames()
        capture("export_$suffix")
    }

    private fun seed(vm: AppViewModel) {
        vm.datasets.toList().forEach(vm::delete)
        vm.createDataset("Poems").apply {
            examples[0].input = "Write a haiku about rain"
            examples[0].output = "Soft drums on the roof\nthe gutters hum their one song\npuddles hold the sky"
        }
        vm.createDataset("SQL helper").examples.addAll(List(11) { Example(input = "q$it", output = "a$it") })
        vm.createDataset("Customer support").apply {
            systemPrompt = "You are a friendly support agent for Acme."
            examples[0].input = "How do I reset my password?"
            examples[0].output = "Go to Settings → Account → Reset password. You'll get an email with a link " +
                "that's valid for 30 minutes."
            examples += Example(input = "Can I get a refund after 30 days?")
            examples += Example()
            vm.changed(this)
        }
    }

    private fun pumpFrames(count: Int = 60) = repeat(count) {
        rule.mainClock.advanceTimeByFrame()
        ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
    }

    /** Draws every window (activity, dialogs, sheets) into one image, like the real screen. */
    private fun capture(name: String) = rule.runOnUiThread {
        val windows = windowRoots()
        val screen = windows.first()
        val bitmap = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        windows.forEachIndexed { i, view ->
            canvas.save()
            if (i > 0 && (view.width < screen.width || view.height < screen.height)) {
                canvas.drawColor(Color.argb(82, 0, 0, 0))
                canvas.translate((screen.width - view.width) / 2f, (screen.height - view.height) / 2f)
            }
            view.draw(canvas)
            canvas.restore()
        }
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
