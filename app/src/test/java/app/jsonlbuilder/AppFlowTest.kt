package app.jsonlbuilder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import app.jsonlbuilder.data.DatasetStore
import app.jsonlbuilder.ui.AppViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real UI: create a dataset, fill in examples, add another one,
 * then leave the app and check everything was written to disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun fields() = rule.onAllNodes(hasSetTextAction())

    @Before
    fun startEmpty() {
        val vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[AppViewModel::class.java] }
        rule.waitUntil(5_000) { vm.loaded }
        rule.runOnUiThread { vm.datasets.toList().forEach(vm::delete) }
    }

    @Test
    fun createFillAndPersistOnLeavingTheApp() {
        rule.onNodeWithText("Create a dataset").performClick()
        fields()[0].performTextInput("Customer support")
        rule.onNodeWithText("Create").performClick()

        fields()[0].performTextInput("How do I reset my password?")
        fields()[1].performTextInput("Go to Settings → Account → Reset password.")
        // The floating button hides while the keyboard is up; the one at the end of the list stays.
        rule.onNode(hasScrollAction()).performScrollToKey("add")
        rule.onNodeWithText("Add another example").performClick()
        fields()[2].performTextInput("Can I get a refund after 30 days?")
        rule.onNode(hasScrollAction()).performScrollToKey("system")
        rule.onNodeWithText("System prompt").performClick()
        fields()[0].performTextInput("You are a friendly support agent.")

        rule.onNodeWithText("2 examples · 1 ready").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithText("Customer support")[0].assertExists()

        // Leaving the app must flush edits that are still waiting on the save delay.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Read back through a fresh store, as a relaunched app would. (Robolectric can hand the
        // ViewModel an Application from an earlier test, so use the one it actually writes with.)
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        val stored = DatasetStore(vm.getApplication()).loadAll().single()
        assertEquals("Customer support", stored.name)
        assertEquals("You are a friendly support agent.", stored.systemPrompt)
        assertEquals(
            listOf(
                "How do I reset my password?" to "Go to Settings → Account → Reset password.",
                "Can I get a refund after 30 days?" to "",
            ),
            stored.pairs(),
        )
    }

    @Test
    fun deletingAnExampleCanBeUndone() {
        rule.onNodeWithText("Create a dataset").performClick()
        rule.onNodeWithText("Create").performClick()
        fields()[0].performTextInput("keep me")
        rule.onNodeWithContentDescription("Delete example 1").performClick()
        rule.onAllNodesWithText("keep me").assertCountEquals(0)
        rule.onNodeWithText("Undo").performClick()
        rule.onNodeWithText("keep me").assertExists()
    }
}
