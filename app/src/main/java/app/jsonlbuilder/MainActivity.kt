package app.jsonlbuilder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.jsonlbuilder.ui.App
import app.jsonlbuilder.ui.AppViewModel
import app.jsonlbuilder.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                App(vm)
            }
        }
    }

    override fun onStop() {
        // Leaving the app (home button, app switcher, screen off): write any edit
        // still waiting on the save delay so nothing typed is ever lost.
        vm.flushAll()
        super.onStop()
    }
}
