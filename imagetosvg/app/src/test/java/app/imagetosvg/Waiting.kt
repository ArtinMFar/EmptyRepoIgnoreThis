package app.imagetosvg

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import org.robolectric.shadows.ShadowLooper

/**
 * Like waitUntil, but also runs the main looper: work that hops back to the main thread
 * after a background step (withContext(Dispatchers.Default) etc.) only lands when the
 * looper is pumped, and Compose's own waitUntil doesn't always do that under Robolectric.
 */
fun AndroidComposeTestRule<*, *>.waitFor(timeoutMs: Long, what: () -> String = { "condition" }, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        runOnUiThread { ShadowLooper.idleMainLooper() }
        if (runOnUiThread(condition)) break
        check(System.currentTimeMillis() < end) { "Timed out after $timeoutMs ms waiting for ${what()}" }
        Thread.sleep(10)
    }
    waitForIdle()
}
