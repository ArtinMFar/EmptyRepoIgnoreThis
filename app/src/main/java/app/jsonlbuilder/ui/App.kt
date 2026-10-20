package app.jsonlbuilder.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        for (message in vm.messages) snackbar.showSnackbar(message)
    }

    // The open dataset was deleted (or never existed after a restore): go home.
    LaunchedEffect(vm.loaded, openId, vm.datasets.size) {
        val id = openId
        if (vm.loaded && id != null && vm.find(id) == null) openId = null
    }

    AnimatedContent(
        targetState = openId,
        transitionSpec = {
            val forward = targetState != null
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(280)) { w -> dir * w / 4 } + fadeIn(tween(220)))
                .togetherWith(slideOutHorizontally(tween(280)) { w -> -dir * w / 4 } + fadeOut(tween(180)))
        },
        label = "screen",
    ) { id ->
        val ds = id?.let { vm.find(it) }
        when {
            id == null -> HomeScreen(vm = vm, snackbar = snackbar, onOpen = { openId = it.id })
            ds != null -> EditorScreen(vm = vm, ds = ds, snackbar = snackbar, onBack = { openId = null })
            else -> Box(Modifier.fillMaxSize())
        }
    }
}
